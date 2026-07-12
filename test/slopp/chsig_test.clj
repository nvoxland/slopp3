(ns slopp.chsig-test
  "change_signature (P2): the defn and every call site rewritten as ONE
  atomic intent — call args rebuilt from a $1..$9 template, the callee kept
  as written (aliases survive), higher-order references surfaced as :manual.
  Plan tests are pure (ingest stores); the api op is exercised isolated."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.refactor :as refactor]
            [slopp.store :as store] [slopp.api :as api]))

(defn- st2 []
  (-> (store/empty-store)
      (store/ingest 'cs.core "(ns cs.core)\n(defn f [x y] (+ x y))\n(defn g [a] (f a 1))\n")
      (store/ingest 'cs.user "(ns cs.user\n  (:require [cs.core :as c]))\n(defn h [b] (c/f b 2))\n(defn hof [xs] (map c/f xs))\n")))

(deftest template-rewrites-call-sites
  (let [plan (refactor/change-signature-plan (st2) 'cs.core 'f "$1 $2 nil")]
    (is (nil? (:error plan)) (pr-str plan))
    (testing "same-ns caller: head as written"
      (let [g-step (first (filter #(= 'g (:name %)) (:caller-steps plan)))]
        (is (= 'cs.core (:ns g-step)))
        (is (= "(defn g [a] (f a 1 nil))" (:source g-step)))))
    (testing "cross-ns caller keeps its alias"
      (let [h-step (first (filter #(= 'h (:name %)) (:caller-steps plan)))]
        (is (= 'cs.user (:ns h-step)))
        (is (= "(defn h [b] (c/f b 2 nil))" (:source h-step)))))
    (testing "higher-order reference is NOT rewritten — surfaced as :manual"
      (is (empty? (filter #(= 'hof (:name %)) (:caller-steps plan))))
      (is (= 1 (count (:manual plan))))
      (is (= 'hof (:form (first (:manual plan))))))))

(deftest template-reorders-and-empties
  (testing "$2 $1 swaps args"
    (let [plan (refactor/change-signature-plan (st2) 'cs.core 'f "$2 $1")]
      (is (= "(defn g [a] (f 1 a))"
             (:source (first (filter #(= 'g (:name %)) (:caller-steps plan))))))))
  (testing "an empty template empties the arg list"
    (let [plan (refactor/change-signature-plan (st2) 'cs.core 'f "")]
      (is (= "(defn g [a] (f))"
             (:source (first (filter #(= 'g (:name %)) (:caller-steps plan)))))))))

(deftest template-guardrails
  (testing "a site with fewer args than the template needs is a hard error"
    (let [plan (refactor/change-signature-plan (st2) 'cs.core 'f "$1 $2 $3")]
      (is (:error plan))
      (is (re-find #"\$3" (str (:error plan))))))
  (testing "unknown fn is a clear error"
    (is (:error (refactor/change-signature-plan (st2) 'cs.core 'nope "$1")))))

(deftest nested-call-sites-are-refused
  (let [store (store/ingest (store/empty-store) 'cs.nest
                            "(ns cs.nest)\n(defn f [x] x)\n(defn g [a] (f (f a)))\n")
        plan  (refactor/change-signature-plan store 'cs.nest 'f "$1 0")]
    (is (:error plan))
    (is (re-find #"nested" (str (:error plan))))))

(deftest recursive-self-calls-belong-to-the-new-source
  (let [store (store/ingest (store/empty-store) 'cs.rec
                            "(ns cs.rec)\n(defn f [x] (if (pos? x) (f (dec x)) x))\n(defn g [a] (f a))\n")
        plan  (refactor/change-signature-plan store 'cs.rec 'f "$1")]
    (is (nil? (:error plan)) (pr-str plan))
    (is (= ['g] (mapv :name (:caller-steps plan))))))
(deftest ^:isolated change-signature-end-to-end
  (let [sess (api/open!)]
    (try
      (api/create-ns! sess 'cs.e2e
                      :source "(ns cs.e2e (:require [clojure.test :refer [deftest is]]))\n(defn f [x y] (+ x y))\n(defn g [a] (f a 1))\n(deftest g-t (is (= 3 (g 2))))\n")
      (testing "defn + callers move as one group; verification stays green"
        (let [r (api/change-signature! sess 'cs.e2e 'f
                                       "(defn f [x y z] (+ x y (or z 0)))"
                                       "$1 $2 nil")]
          (is (nil? (:error r)) (pr-str r))
          (is (= 1 (:rewrote r)))
          (is (zero? (get-in r [:test :fail] 0)) (pr-str (:test r)))))
      (testing "a template that breaks arity is refused by the lint gate, with the hint"
        (let [r (api/change-signature! sess 'cs.e2e 'f
                                       "(defn f [x y z] (+ x y (or z 0)))"
                                       "$1")]
          (is (:error r))
          (is (re-find #"invalid-arity" (str (:error r))))
          (is (re-find #"change_signature" (str (:error r))))))
      (finally (api/close! sess)))))
