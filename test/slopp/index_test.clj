(ns slopp.index-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.index :as index] [slopp.index.derive :as derive] [slopp.cache :as cache] [slopp.index.analyze :as analyze]))

(def src
  (str "(ns demo)\n"
       "(defn pure [x] (inc x))\n"
       "(defn tainted [a] (swap! a inc))\n"   ; effectful, mis-named (no !)
       "(defn caller [a] (tainted a))\n"      ; effectful via tainted, mis-named
       "(defn ok! [a] (reset! a 0))\n"))

; effectful, correctly named
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

(deftest lint-honours-the-platform-lang
  (let [src "(ns x)\n(defn f [] (js/alert 1) (.-value (js/document.getElementById \"q\")))\n"]
    (testing "default (:clj) lang flags js/* as an unresolved namespace"
      (is (some #(= :unresolved-namespace (:type %)) (index/lint src))
          "clj lint can't resolve js"))
    (testing ":cljs lang resolves js/* — no false unresolved-namespace finding"
      (is (not (some #(= :unresolved-namespace (:type %)) (index/lint src :cljs)))
          "cljs lint knows js"))))

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
  ;; neither pass may recompute for the same source. The lint memo keys on
  ;; [source lang] (D-web-cljs) so a clj-linted source is never reused for a
  ;; cljs form.
  (let [s "(ns kx.core)\n(defn f [x] (reduce + x))\n(defn g [] (f 1 2 3))\n"]
    (testing "lint keeps its cache-dir-backed pass, memoized as before"
      (is (seq (index/lint s)) "lint returns findings")
      (let [before (get @@#'index/kondo-cache [s :clj])]
        (is (some? before) "lint populates the kondo cache under [source lang]")
        (index/lint s)
        (is (identical? before (get @@#'index/kondo-cache [s :clj]))
            "same cached kondo result object — no recompute")))
    (testing "analysis runs its own pass and does NOT ride lint's cache"
      (let [s2 "(ns kx.other)\n\n(defn h \"D.\" [x] (inc x))\n"]
        (analyze/analyze s2)
        (is (not (contains? @@#'index/kondo-cache [s2 :clj]))
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
  ;; slopp.refactor, slopp.edit.modules and slopp.index.refs from layering.
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

(deftest var-quote-in-call-position-propagates-effects
  (testing "((#'save! 1)) is a CALL — the caller reaches the effect"
    (let [an (analyze/analyze
              (str "(ns vq)\n"
                   "(defn save! [x] (swap! x inc))\n"
                   "(defn sneak [x] (#'save! x))\n"))
          eff (derive/effectful-vars an)]
      (is (contains? eff 'vq/sneak)
          "a var-quote call let the effect escape into a would-be pure fn")))
  (testing "a var-quote HELD as data is still a carrier — it does not taint"
    (let [an (analyze/analyze
              (str "(ns vq2)\n"
                   "(defn save! [x] (swap! x inc))\n"
                   "(def registry {:fn #'save!})\n"
                   "(defn holder [] registry)\n"))
          eff (derive/effectful-vars an)]
      (is (not (contains? eff 'vq2/holder))
          "a #'var carried in data must not propagate the effect"))))

(deftest console-io-blocks-pure-without-demanding-a-bang
  ;; `!` means MUTATION by convention — idiomatic Clojure never bang-names a
  ;; print fn, and dialect.md carries that as a recorded decision. But console
  ;; IO still breaks referential transparency, so the :pure tier must see it.
  ;; Two axes, like nondeterminism: rand/slurp block :pure without demanding a
  ;; bang either.
  (let [an (analyze/analyze
            (str "(ns c)\n"
                 "(defn evaluate [s] (count s))\n"
                 "(defn run-cli [args] (println (evaluate args)))\n"))]
    (testing "printing does NOT demand a ! name"
      (is (not-any? #(= 'c/run-cli (:var %)) (derive/effect-violations an))
          (pr-str (mapv :var (derive/effect-violations an)))))
    (testing "but the :pure tier still sees it as console output"
      (is (contains? (derive/console-vars an) 'c/run-cli)))
    (testing "and a transitive caller of a printing fn is seen too"
      (is (contains? (derive/console-vars an) 'c/run-cli)))
    (testing "a real MUTATION still demands the !"
      (let [an2 (analyze/analyze "(ns c2)\n(defn bump [a] (swap! a inc))\n")]
        (is (some #(= 'c2/bump (:var %)) (derive/effect-violations an2)))))))

(deftest reset-kondo-cache-clears-stale-cross-ns-facts
  ;; A restarted server produced FOUR confident lint ERRORS — "slopp.index/lint
  ;; is called with 2 args but expects 1" — plus eleven unresolved-var warnings,
  ;; every one naming a var added the same day. All false: the code was correct
  ;; and the whole suite green. kondo reads cross-ns facts (arities, var
  ;; existence) from its DISK cache, "linting a namespace teaches it", and the
  ;; sweep runs alphabetically — so namespaces linted early were judged against
  ;; a cache predating those vars. Clearing the cache made it green, which is
  ;; the tell: a STALE entry lies confidently, an ABSENT one is benign. The
  ;; whole-store gate must therefore not inherit incremental cache state.
  (let [dir (java.nio.file.Files/createTempDirectory
             "slopp-kondo" (make-array java.nio.file.attribute.FileAttribute 0))
        cache (java.io.File. (.toFile dir) ".cache")
        _     (.mkdirs cache)
        stale (java.io.File. cache "stale-fact.edn")
        prev  @index/kondo-cache-dir]
    (try
      (spit stale "{:pretend :stale}")
      ;; a PATH STRING, which is what api/open! actually stores — the first
      ;; version of this test used a File and so passed against an
      ;; implementation that threw on every real call
      (reset! index/kondo-cache-dir (str (.toFile dir)))
      (is (.exists stale) "sentinel is in place before the reset")
      (index/reset-kondo-cache!)
      (is (not (.exists stale))
          "a stale cross-ns fact does not survive into a whole-store lint")
      (testing "resetting an unset cache dir is a no-op, not a throw"
        (reset! index/kondo-cache-dir nil)
        (is (nil? (index/reset-kondo-cache!))))
      (finally (reset! index/kondo-cache-dir prev)))))

(deftest externality-ORIGINATES-in-one-var-and-only-reaches-the-other
  ;; The adapter/reaches distinction (ideas/isolating-the-external-world.md §2):
  ;; a namespace is :external when it REACHES the world; it is an ADAPTER when
  ;; it IS the reaching. Only the second has a fake, so only the second can be
  ;; asked for one — a gate built on the fixpoint would fire on slopp.api,
  ;; which cannot be pushed down and so cannot discharge it.
  ;;
  ;; externally-effectful-vars answers the FIRST question and cannot answer the
  ;; second: its fixpoint holds the adapter and every caller alike. The SEED of
  ;; that same fixpoint — the vars calling an anchor directly, before any
  ;; propagation round — is the second answer, and it is already computed and
  ;; discarded.
  (let [an (analyze/analyze
            (str "(ns c)\n"
                 "(defn touches [p] (slurp p))\n"
                 "(defn reaches [p] (touches p))\n"))]
    (testing "the fixpoint holds BOTH — which is why it cannot answer the question"
      (is (= '#{c/touches c/reaches}
             (derive/externally-effectful-vars an nil nil))))
    (testing "the origin set holds only the var that reaches the world itself"
      (is (= '#{c/touches} (derive/external-origin-vars an nil nil))))))

(deftest interop-with-the-world-is-an-effect-even-with-no-var-to-see
  ;; The hole this closes: a namespace that binds a port and blocks on accept
  ;; was :pure by EVERY derivation slopp has, because interop produces no var
  ;; usage and so no call-graph edge. tier-refusal would have accepted
  ;; module_purity :pure on it, and the D6 !-naming rule does not catch it
  ;; either — that fires when a non-bang fn REACHES an effect, and nothing
  ;; here reached one as far as the analysis was concerned.
  ;;
  ;; The anchor set is ENUMERATED, not a package prefix, and that is measured
  ;; rather than cautious: across slopp's own production namespaces the java
  ;; class vocabulary is 128 java.lang.Exception, 41 Throwable, 31 String —
  ;; catch clauses and type hints. java.net. holds URI and URLDecoder (pure)
  ;; next to ServerSocket; java.io. holds ByteArrayOutputStream next to File.
  ;; A prefix match would move the whole codebase out of :pure.
  (let [ext (str "(ns sock (:import [java.net ServerSocket]))\n"
                 "(defn bind [p] (let [s (ServerSocket. (int p))] (.accept s)))\n")
        pure (str "(ns val)\n"
                  "(defn stringify [x] (String/valueOf x))\n")
        imp  (str "(ns imp (:import [java.net ServerSocket]))\n"
                  "(defn f [x] x)\n")]
    (testing "a socket opened through interop is external, and its own origin"
      (let [a (analyze/analyze ext)]
        (is (contains? (derive/external-origin-vars a nil nil) 'sock/bind))
        (is (contains? (derive/externally-effectful-vars a nil nil) 'sock/bind))
        (testing "and effectful, so :pure refuses it — the tier hole"
          (is (contains? (derive/effectful-vars a nil nil) 'sock/bind)))))
    (testing "interop with a class that touches nothing stays pure"
      (let [a (analyze/analyze pure)]
        (is (empty? (derive/external-origin-vars a nil nil)))
        (is (empty? (derive/effectful-vars a nil nil)))))
    (testing "IMPORTING a class is not USING it — kondo reports the :import
              itself as a class usage, and it belongs to no var"
      (let [a (analyze/analyze imp)]
        (is (empty? (derive/external-origin-vars a nil nil)))
        (is (empty? (derive/effectful-vars a nil nil)))))))
