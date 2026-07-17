(ns slopp.index-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.index :as index]))

(def src
  (str "(ns demo)\n"
       "(defn pure [x] (inc x))\n"
       "(defn tainted [a] (swap! a inc))\n"   ; effectful, mis-named (no !)
       "(defn caller [a] (tainted a))\n"      ; effectful via tainted, mis-named
       "(defn ok! [a] (reset! a 0))\n"))      ; effectful, correctly named

(deftest analyze-and-effects
  (let [an (index/analyze src)]
    (testing "effectful reachability propagates through the call graph (D6)"
      (let [eff (index/effectful-vars an)]
        (is (contains? eff 'demo/tainted))
        (is (contains? eff 'demo/caller))
        (is (contains? eff 'demo/ok!))
        (is (not (contains? eff 'demo/pure)))))
    (testing "`!` name must match computed effectfulness (D6)"
      (let [v (set (map :var (index/effect-violations an)))]
        (is (contains? v 'demo/tainted))   ; effectful but not !-named
        (is (contains? v 'demo/caller))    ; effectful (transitively) but not !-named
        (is (not (contains? v 'demo/ok!))) ; effectful and !-named — ok
        (is (not (contains? v 'demo/pure)))))
    (testing "references finds callers of a var"
      (let [refs (index/references an 'demo 'tainted)]
        (is (= 1 (count refs)))
        (is (= 'caller (:from-var (first refs))))))))

(deftest cross-ns-bang-callees-propagate-effects       ; N1
  (let [an (index/analyze
            (str "(ns w (:require [other.store :as st]))\n"
                 "(defn save-all [xs] (doseq [x xs] (st/put! x)))\n"
                 "(defn pure-view [xs] (map :id xs))\n"))]
    (is (contains? (index/effectful-vars an) 'w/save-all))
    (is (not (contains? (index/effectful-vars an) 'w/pure-view)))
    (is (some #(= 'w/save-all (:var %)) (index/effect-violations an)))))

(deftest external-purity-narrows-at-var-and-namespace-granularity   ; M3 coarser :pure
  (let [an   (index/analyze
              (str "(ns c (:require [ext.lib :as e]))\n"
                   "(defn f [x] (e/go x))\n"))
        ext? #{'ext.lib}]
    (testing "an external call is effectful by default"
      (is (contains? (index/effectful-vars an ext? #{}) 'c/f)))
    (testing "var-level :pure narrows it (existing granularity)"
      (is (not (contains? (index/effectful-vars an ext? #{'ext.lib/go}) 'c/f))))
    (testing "NAMESPACE-level :pure narrows every var in that namespace (new)"
      (is (not (contains? (index/effectful-vars an ext? #{'ext.lib}) 'c/f))))))

(deftest deftests-are-exempt-from-bang-rule            ; T1
  (let [an (index/analyze
            (str "(ns d (:require [clojure.test :refer [deftest is]]))\n"
                 "(defn go! [a] (swap! a inc))\n"
                 "(deftest go-test (is (= 1 (go! (atom 0)))))\n"))]
    (testing "a test exercising effectful code is NOT a naming violation"
      (is (not-any? #(= 'd/go-test (:var %)) (index/effect-violations an))))
    (testing "but real violations still surface"
      (let [an2 (index/analyze "(ns d)\n(defn go [a] (swap! a inc))\n")]
        (is (some #(= 'd/go (:var %)) (index/effect-violations an2)))))))

(deftest main-is-exempt-from-bang-rule                  ; entry-point convention
  ;; -main is an effectful entry point that is never bang-named (Clojure
  ;; convention), exactly like deftest — exempt it.
  (let [an (index/analyze "(ns app)\n(defn -main [& a] (spit \"f\" a))\n")]
    (is (not-any? #(= 'app/-main (:var %)) (index/effect-violations an)))))

(deftest a-bang-is-trusted-never-flagged-for-removal    ; interop effects
  ;; A `!` is a human assertion of effectfulness; when the analyzer computes a
  ;; banged fn as pure (an interop/opaque effect it can't see — .close, a socket
  ;; write), it must NOT demand the `!` be removed. Only the MISSING-`!`
  ;; direction (effectful but unlabeled) is a real signal.
  (let [an (index/analyze "(ns app)\n(defn shut! [x] (.close x))\n")]
    (testing "banged-but-analyzer-thinks-pure is NOT a violation"
      (is (not-any? #(= 'app/shut! (:var %)) (index/effect-violations an))))
    (testing "missing-bang (effectful, unlabeled) is STILL flagged"
      (let [an2 (index/analyze "(ns app)\n(defn go [a] (reset! a 1))\n")]
        (is (some #(= 'app/go (:var %)) (index/effect-violations an2)))))))
(deftest analyze-and-lint-share-one-memoized-kondo-pass
  ;; per-write kondo cost: analyze + lint used to be TWO passes over the same
  ;; rendered ns, and lint wasn't memoized (the unchanged base re-linted every
  ;; write). One cached pass now feeds both.
  (let [s "(ns kx.core)\n(defn f [x] (reduce + x))\n(defn g [] (f 1 2 3))\n"]
    (index/analyze s)
    (is (contains? @@#'index/kondo-cache s)
        "analyze populates the shared cache")
    (is (seq (index/lint s)) "lint returns findings")
    (testing "lint of the SAME content is a cache hit (no second kondo run)"
      (let [before (get @@#'index/kondo-cache s)]
        (index/lint s)
        (is (identical? before (get @@#'index/kondo-cache s))
            "same cached kondo result object — no recompute")))))
^:unsafe (deftest lint-findings-refresh-when-a-dependency-moves
  ;; The memo key must cover what the findings actually depend on. kondo reads
  ;; CROSS-NS facts (arities, var existence) from .clj-kondo/.cache, which other
  ;; lints rewrite — so findings are NOT a function of this source alone.
  ;; Measured 2026-07-16: :analysis IS cache-independent, only :findings aren't.
  ;;
  ;; This is the false-GREEN half and the reason it matters: a stale caller's
  ;; source is BY DEFINITION unchanged, so it is exactly the case the memo
  ;; blinds — and lint-refusals' :carried gate exists to catch stale callers.
  (.mkdirs (java.io.File. ".clj-kondo"))   ; kondo caches cross-ns facts only if it has somewhere to put them
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
