(ns slopp.normalize-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.normalize :as norm]
            [slopp.store :as store]
            [slopp.api :as api]))

(defn- normed [src] (:src (norm/normalize-source src)))

(deftest rewrite-rules
  (testing "kibit-classics, conservative set"
    (is (= "(when x y)"          (normed "(if x y nil)")))
    (is (= "(when x y)"          (normed "(if x y)")))
    (is (= "(when-not x y)"      (normed "(if x nil y)")))
    (is (= "(boolean x)"         (normed "(if x true false)")))
    (is (= "(if-not x a b)"      (normed "(if (not x) a b)")))
    (is (= "(when-not x a b)"    (normed "(when (not x) a b)")))
    (is (= "(not= a b)"          (normed "(not (= a b))")))
    (is (= "x"                   (normed "(do x)"))))
  (testing "rewrites nest inside surrounding code without disturbing it"
    (is (= "(defn f [x]\n  (if-not x 1 2))"
           (normed "(defn f [x]\n  (if (not x) 1 2))"))))
  (testing "cascading rewrites reach a fixpoint"
    (is (= "(when x y)" (normed "(do (if x y nil))"))))
  (testing "already-idiomatic code is untouched"
    (doseq [src ["(if c a b)" "(when x y)" "(not= a b)" "(defn f [x] x)"
                 "(if x y (do a b))"]]
      (is (= src (normed src)) src))))

(deftest checkpoint-normalizes-the-working-set
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'cp.core
                   (str "(ns cp.core (:require [clojure.test :refer [deftest is]]))\n"
                        "(defn clean [x] (inc x))\n"))
      (api/checkpoint! sess :label "baseline")           ; everything-so-far boundary
      ;; agent writes working-but-clunky code
      (api/add-form! sess 'cp.core
                     "(defn classify [x] (if (not (neg? x)) (if (pos? x) :pos :zero) :neg))")
      (api/add-form! sess 'cp.core
                     (str "(deftest classify-t\n"
                          "  (is (= :pos (classify 2)))\n"
                          "  (is (= :zero (classify 0)))\n"
                          "  (is (= :neg (classify -2))))"))
      (let [r (api/checkpoint! sess :label "classification done")]
        (testing "changed-since-checkpoint forms are normalized, others untouched"
          (is (= 1 (:normalized r)))
          (is (= ['cp.core/classify] (mapv :form (:rewrites r))))
          (is (re-find #"if-not" (api/query-source sess 'cp.core)))
          (is (re-find #"\(defn clean \[x\] \(inc x\)\)"
                       (api/query-source sess 'cp.core))))
        (testing "behavior verified after normalization (affected tests green)"
          (is (zero? (+ (:fail (:test r)) (:error (:test r)))))
          (is (= [:pos] (api/query-eval sess "(cp.core/classify 5)"))))
        (testing "provenance: a :normalize delta + a :checkpoint boundary"
          (is (contains? (set (map :op (api/query-lineage sess 'cp.core 'classify)))
                         :normalize))
          (is (= :checkpoint (:op (last (store/deltas (:store @sess))))))))
      (testing "an immediate second checkpoint is a no-op"
        (let [r (api/checkpoint! sess)]
          (is (zero? (:normalized r)))
          (is (empty? (:lint r)))))
      (testing "checkpoint lints the changed namespaces (kondo findings)"
        (api/add-form! sess 'cp.core "(defn sloppy [x] (let [unused 1] x))")
        (let [r    (api/checkpoint! sess :label "lint probe")
              hits (filter #(= :unused-binding (:type %)) (:lint r))]
          (is (seq hits))
          (is (= 'cp.core/sloppy (:form (first hits))))
          (is (= :warning (:level (first hits))))))
      (finally (api/close! sess)))))

(deftest expanded-rules-are-strictly-value-preserving
  (doseq [[in out] {"(= x nil)"                  "(nil? x)"
                    "(= nil x)"                  "(nil? x)"
                    "(not (nil? x))"             "(some? x)"
                    "(into [] xs)"               "(vec xs)"
                    "(filter (complement p) xs)" "(remove p xs)"
                    "(cond t x)"                 "(when t x)"}]
    (is (= out (:src (norm/normalize-source in))) in))
  (testing "near-misses stay untouched"
    (doseq [src ["(= x y)" "(into [0] xs)" "(cond a b c d)"
                 "(filter pred xs)"]]
      (is (= src (:src (norm/normalize-source src))) src))))

(deftest checkpoint-runs-declare-hygiene
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'dh.core
                   (str "(ns dh.core (:require [clojure.test :refer [deftest is]]))\n"
                        "(deftest t (is true))\n"))
      (api/checkpoint! sess :label "base")
      ;; the compile gate's escape hatch, as agents actually use it
      (api/add-form! sess 'dh.core "(declare later)")
      (api/add-form! sess 'dh.core "(defn caller [x] (later x))")
      (api/add-form! sess 'dh.core "(defn later [x] (inc x))")
      (api/add-form! sess 'dh.core "(deftest caller-t (is (= 3 (caller 2))))")
      (let [r (api/checkpoint! sess :label "feature")]
        (is (seq (:declares-fixed r)) (pr-str (keys r)))
        (let [src (api/query-source sess 'dh.core)]
          (is (not (re-find #"declare" src)))
          (is (< (.indexOf src "defn later") (.indexOf src "defn caller")))))
      (finally (api/close! sess)))))
