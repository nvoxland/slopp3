(ns slopp.index-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.index :as index] [slopp.index.derive :as derive] [slopp.cache :as cache] [slopp.index.analyze :as analyze]))

(def src
  (str "(ns demo)\n"
       "(defn pure [x] (inc x))\n"
       "(defn tainted [a] (swap! a inc))\n"   ; effectful, mis-named (no !)
       "(defn caller [a] (tainted a))\n"      ; effectful via tainted, mis-named
       "(defn ok! [a] (reset! a 0))\n"))      ; effectful, correctly named

(deftest analyze-and-effects
  (let [an (analyze/analyze src)]
    (testing "effectful reachability propagates through the call graph (D6)"
      (let [eff (derive/effectful-vars an)]
        (is (contains? eff 'demo/tainted))
        (is (contains? eff 'demo/caller))
        (is (contains? eff 'demo/ok!))
        (is (not (contains? eff 'demo/pure)))))
    (testing "`!` name must match computed effectfulness (D6)"
      (let [v (set (map :var (derive/effect-violations an)))]
        (is (contains? v 'demo/tainted))   ; effectful but not !-named
        (is (contains? v 'demo/caller))    ; effectful (transitively) but not !-named
        (is (not (contains? v 'demo/ok!))) ; effectful and !-named — ok
        (is (not (contains? v 'demo/pure)))))
    (testing "references finds callers of a var"
      (let [refs (derive/references an 'demo 'tainted)]
        (is (= 1 (count refs)))
        (is (= 'caller (:from-var (first refs))))))))

(deftest cross-ns-bang-callees-propagate-effects       ; N1
  (let [an (analyze/analyze
            (str "(ns w (:require [other.store :as st]))\n"
                 "(defn save-all [xs] (doseq [x xs] (st/put! x)))\n"
                 "(defn pure-view [xs] (map :id xs))\n"))]
    (is (contains? (derive/effectful-vars an) 'w/save-all))
    (is (not (contains? (derive/effectful-vars an) 'w/pure-view)))
    (is (some #(= 'w/save-all (:var %)) (derive/effect-violations an)))))

(deftest external-purity-narrows-at-var-and-namespace-granularity   ; M3 coarser :pure
  (let [an   (analyze/analyze
              (str "(ns c (:require [ext.lib :as e]))\n"
                   "(defn f [x] (e/go x))\n"))
        ext? #{'ext.lib}]
    (testing "an external call is effectful by default"
      (is (contains? (derive/effectful-vars an ext? #{}) 'c/f)))
    (testing "var-level :pure narrows it (existing granularity)"
      (is (not (contains? (derive/effectful-vars an ext? #{'ext.lib/go}) 'c/f))))
    (testing "NAMESPACE-level :pure narrows every var in that namespace (new)"
      (is (not (contains? (derive/effectful-vars an ext? #{'ext.lib}) 'c/f))))))

(deftest deftests-are-exempt-from-bang-rule            ; T1
  (let [an (analyze/analyze
            (str "(ns d (:require [clojure.test :refer [deftest is]]))\n"
                 "(defn go! [a] (swap! a inc))\n"
                 "(deftest go-test (is (= 1 (go! (atom 0)))))\n"))]
    (testing "a test exercising effectful code is NOT a naming violation"
      (is (not-any? #(= 'd/go-test (:var %)) (derive/effect-violations an))))
    (testing "but real violations still surface"
      (let [an2 (analyze/analyze "(ns d)\n(defn go [a] (swap! a inc))\n")]
        (is (some #(= 'd/go (:var %)) (derive/effect-violations an2)))))))

(deftest main-is-exempt-from-bang-rule                  ; entry-point convention
  ;; -main is an effectful entry point that is never bang-named (Clojure
  ;; convention), exactly like deftest — exempt it.
  (let [an (analyze/analyze "(ns app)\n(defn -main [& a] (spit \"f\" a))\n")]
    (is (not-any? #(= 'app/-main (:var %)) (derive/effect-violations an)))))

(deftest a-bang-is-trusted-never-flagged-for-removal    ; interop effects
  ;; A `!` is a human assertion of effectfulness; when the analyzer computes a
  ;; banged fn as pure (an interop/opaque effect it can't see — .close, a socket
  ;; write), it must NOT demand the `!` be removed. Only the MISSING-`!`
  ;; direction (effectful but unlabeled) is a real signal.
  (let [an (analyze/analyze "(ns app)\n(defn shut! [x] (.close x))\n")]
    (testing "banged-but-analyzer-thinks-pure is NOT a violation"
      (is (not-any? #(= 'app/shut! (:var %)) (derive/effect-violations an))))
    (testing "missing-bang (effectful, unlabeled) is STILL flagged"
      (let [an2 (analyze/analyze "(ns app)\n(defn go [a] (reset! a 1))\n")]
        (is (some #(= 'app/go (:var %)) (derive/effect-violations an2)))))))
(deftest analysis-and-lint-are-memoized-separately
  ;; These used to share ONE cached kondo pass, to hold per-write kondo cost
  ;; at a single run. That coupling is RETIRED: `:findings` depend on
  ;; cross-namespace cache state and `:analysis` does not, so sharing the pass
  ;; made analysis IO — which every caller of `analyze` inherited, and that is
  ;; most of the pure core. Measured before the split: warm-cache and
  ;; `:cache false` runs differ only in :fixed-arities on cross-ns var-usages,
  ;; which nothing reads. Measured after: no benchmark regression.
  ;;
  ;; The ORIGINAL concern still stands and is what this test now protects:
  ;; neither pass may recompute for the same source.
  (let [s "(ns kx.core)\n(defn f [x] (reduce + x))\n(defn g [] (f 1 2 3))\n"]
    (testing "lint keeps its cache-dir-backed pass, memoized as before"
      (is (seq (index/lint s)) "lint returns findings")
      (let [before (get @@#'index/kondo-cache s)]
        (is (some? before) "lint populates the kondo cache")
        (index/lint s)
        (is (identical? before (get @@#'index/kondo-cache s))
            "same cached kondo result object — no recompute")))
    (testing "analysis runs its own pass and does NOT ride lint's cache"
      (let [s2 "(ns kx.other)\n\n(defn h \"D.\" [x] (inc x))\n"]
        (analyze/analyze s2)
        (is (not (contains? @@#'index/kondo-cache s2))
            "analysis must not populate the cache-dir-backed pass")))
    (testing "and analysis is memoized on its own key"
      (let [s3 "(ns kx.memo)\n\n(defn k \"D.\" [x] (inc x))\n"
            _  (analyze/analyze s3)
            n1 (get (cache/registry) :slopp.index.analyze/analysis 0)]
        (analyze/analyze s3)
        (is (= n1 (get (cache/registry) :slopp.index.analyze/analysis 0))
            "a second analysis of the same source adds no entry — memo hit")))))
^:unsafe (deftest lint-findings-refresh-when-a-dependency-moves
  ;; The memo key must cover what the findings actually depend on. kondo reads
  ;; CROSS-NS facts (arities, var existence) from .clj-kondo/.cache, which other
  ;; lints rewrite — so findings are NOT a function of this source alone.
  ;; Measured 2026-07-16: :analysis IS cache-independent, only :findings aren't.
  ;;
  ;; This is the false-GREEN half and the reason it matters: a stale caller's
  ;; source is BY DEFINITION unchanged, so it is exactly the case the memo
  ;; blinds — and lint-refusals' :carried gate exists to catch stale callers.
  ;; NOTE (#134): this line used to read (.mkdirs (java.io.File. ".clj-kondo"))
  ;; — "kondo caches cross-ns facts only if it has somewhere to put them". That
  ;; workaround WAS the bug's fingerprint: kondo resolved its cache from the
  ;; process cwd, so this test had to manufacture one. slopp now names its own
  ;; cache dir and the crutch is gone.
  (reset! index/kondo-cache-dir
          (str (java.nio.file.Files/createTempDirectory
                "kondo-memo" (make-array java.nio.file.attribute.FileAttribute 0))))   ; kondo caches cross-ns facts only if it has somewhere to put them
  (let [dep1 "(ns memo.dep)\n(defn f [x] x)\n"
        dep2 "(ns memo.dep)\n(defn f ([x] x) ([x y] x))\n"
        use  "(ns memo.use (:require [memo.dep :as d]))\n(defn g [] (d/f 1 2))\n"]
    (index/lint dep1)
    (testing "a real cross-ns arity error is found (this is the gate working)"
      (is (= [:invalid-arity] (mapv :type (index/lint use)))))
    (index/lint dep2)
    (testing "the dependency grew the arity — the SAME caller source is now fine"
      (is (= [] (mapv :type (index/lint use)))
          "stale replay: the memo answered from before the callee moved"))
    (testing "and it flips back — this is not a one-way latch"
      (index/lint dep1)
      (is (= [:invalid-arity] (mapv :type (index/lint use)))))))
^:unsafe (deftest cross-ns-lint-uses-the-cache-dir-slopp-owns
  ;; kondo resolves its cache from the PROCESS CWD unless told otherwise, so
  ;; every cross-ns finding (arity, var existence) worked only where a
  ;; .clj-kondo/ happened to sit next to the process. Probed 2026-07-17 from an
  ;; image whose cwd is a temp dir — exactly a user project's situation:
  ;;   default cwd cache  -> []                 (no finding at all)
  ;;   explicit cache-dir -> [:invalid-arity]
  ;; So a user project's :carried stale-caller gate silently did NOTHING, and
  ;; it failed toward "no findings" — the direction that never announces itself.
  ;; The fingerprint was already in this file: the test below had to
  ;; (.mkdirs ".clj-kondo") to work at all.
  (let [fresh (fn [] (str (java.nio.file.Files/createTempDirectory
                           "kondo-owned"
                           (make-array java.nio.file.attribute.FileAttribute 0))))
        a     (fresh)
        b     (fresh)
        prev  @index/kondo-cache-dir
        use   "(ns owned.use (:require [owned.dep :as d]))\n(defn g \"G.\" [] (d/f 1 2 3))\n"]
    (try
      (reset! index/kondo-cache-dir a)
      (index/lint "(ns owned.dep)\n(defn f \"F.\" [x] x)\n")
      (testing "kondo wrote its cross-ns facts into the dir WE named"
        (is (seq (.list (java.io.File. a)))
            "empty — the cache-dir was ignored and the facts went to the cwd"))
      (testing "…and the caller's lint reads them back, with no .clj-kondo anywhere"
        (is (= [:invalid-arity] (mapv :type (index/lint use)))))
      (testing "pointing at a DIFFERENT cache re-passes — the memo must not
                answer with findings computed against another world, which is
                the same key-omits-an-input bug the fingerprint fixed"
        (reset! index/kondo-cache-dir b)
        (is (= [] (mapv :type (index/lint use)))
            "stale replay: answered from the cache dir we just left"))
      (finally (reset! index/kondo-cache-dir prev)))))

(deftest carrier-refs-do-not-propagate-effects
  (let [src (str "(ns app.core)\n"
                 "(defn leaf! [a] (swap! a inc))\n"
                 "(def registry [#'leaf!])\n"
                 "(def aliased leaf!)\n"
                 "(defn caller [a] (leaf! a))\n")
        an  (analyze/analyze src)
        eff (derive/effectful-vars an)]
    (testing "a fn that CALLS an effect is effectful"
      (is (contains? eff 'app.core/caller)))
    (testing "a #'var CARRIER held in data is NOT effectful (it's not invoked)"
      (is (not (contains? eff 'app.core/registry))))
    (testing "but a BARE value alias (def aliased leaf!) IS — it is callable-as-leaf!"
      (is (contains? eff 'app.core/aliased)))))

(deftest ^:external analysis-does-not-touch-the-kondo-cache
  ;; analyze's VALUE is a function of source alone — measured: a warm-cache
  ;; run and a `:cache false` run differ only in :fixed-arities on cross-ns
  ;; var-USAGES, which nothing in slopp reads (every reader takes arities from
  ;; var-definitions, which are same-source). The cache exists for `lint`'s
  ;; cross-ns findings, not for analysis.
  ;;
  ;; While analysis runs against the cache dir it is IO, so every namespace
  ;; calling analyze inherits an :external dependency — which is what kept
  ;; slopp.refactor, slopp.edit.modules and slopp.edit.refs from layering.
  (let [dir  (java.nio.file.Files/createTempDirectory
              "kondo-probe" (make-array java.nio.file.attribute.FileAttribute 0))
        f    (.toFile dir)
        prev @index/kondo-cache-dir]
    (try
      (reset! index/kondo-cache-dir (str f))
      (cache/without-caching!
       (fn []
         (analyze/analyze "(ns probe.a)\n\n(defn f \"D.\" [x] (inc x))\n")))
      (is (empty? (seq (.listFiles f)))
          "analysis must leave kondo's cache dir untouched")
      (finally (reset! index/kondo-cache-dir prev)))))
