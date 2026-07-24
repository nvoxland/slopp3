(ns slopp.api.shape-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.api.shape :as shape]))

(deftest return-keys-enumerates-a-statically-known-result-map
  (testing "a bare map literal in return position"
    (is (= #{:a :b} (shape/return-keys '(defn f [] {:a 1 :b 2})))))
  (testing "cond-> threading — base keys plus every (assoc :k …) clause"
    (is (= #{:a :b :c}
           (shape/return-keys
            '(defn f [x y] (cond-> {:a 1} x (assoc :b 2) y (assoc :c 3 :a 9)))))))
  (testing "assoc onto a known base"
    (is (= #{:a :b} (shape/return-keys '(defn f [] (assoc {:a 1} :b 2))))))
  (testing "through a let / do to the tail"
    (is (= #{:a} (shape/return-keys '(defn f [] (let [x 1] (do :ignored {:a x}))))))
    (is (= #{:status} (shape/return-keys '(defn f [] (when-let [x 1] {:status x})))))))

(deftest return-keys-bails-when-the-shape-is-not-bounded
  (testing "a bare variable / opaque call — cannot be bounded"
    (is (nil? (shape/return-keys '(defn f [x] x))))
    (is (nil? (shape/return-keys '(defn f [] (build-it))))))
  (testing "merge adds unknown keys — bail, do not under-report"
    (is (nil? (shape/return-keys '(defn f [m] (merge {:a 1} m))))))
  (testing "a cond-> step that is not (assoc …) could add anything — bail"
    (is (nil? (shape/return-keys '(defn f [x] (cond-> {:a 1} x (into {:b 2})))))))
  (testing "non-keyword keys cannot be enumerated"
    (is (nil? (shape/return-keys '(defn f [k] {k 1})))))
  (testing "multi-arity bails if ANY arity is unbounded, unions otherwise"
    (is (= #{:a :b} (shape/return-keys '(defn f ([] {:a 1}) ([x] {:a 1 :b x})))))
    (is (nil? (shape/return-keys '(defn f ([] {:a 1}) ([x] x)))))))

(deftest key-not-returned-flags-a-read-of-an-unreturned-key
  (let [full-ck '#{:status :unused-public :stale-unused-ok :lint}
        resolver {'external/full-check! full-ck}]
    (testing "the vacuous assertions from the assertions-that-cannot-fail incident"
      (let [caller '(deftest t
                      (let [r (external/full-check! sess)]
                        (is (empty? (:unused r)))
                        (is (empty? (:stale r)))
                        (is (= :green (:status r)))))
            fs (shape/key-not-returned caller resolver)]
        (is (= #{:unused :stale} (set (map :key fs))) (pr-str fs))
        (is (every? #(= 'external/full-check! (:callee %)) fs))))
    (testing "a key the callee DOES return is not flagged"
      (is (empty? (shape/key-not-returned
                   '(deftest t (let [r (external/full-check! sess)] (:status r)))
                   resolver))))
    (testing "an unresolvable callee (unknown return shape) is never flagged"
      (is (empty? (shape/key-not-returned
                   '(deftest t (let [r (mystery-call x)] (:whatever r)))
                   resolver))))
    (testing "a local bound to something other than a resolvable call is ignored"
      (is (empty? (shape/key-not-returned
                   '(deftest t (let [r {:a 1}] (:zzz r)))
                   resolver))))))

(deftest key-not-returned-ignores-deliberate-and-loud-shapes
  (let [resolver {'external/full-check! '#{:status}}
        run (fn [caller] (shape/key-not-returned caller resolver))]
    (testing "(nil? (:k r)) is a DELIBERATE absence check — not flagged"
      (is (empty? (run '(deftest t (let [r (external/full-check! sess)]
                                     (is (nil? (:unused r)))))))))
    (testing "(= v (:k r)) fails loudly on nil (red-first catches it) — not flagged"
      (is (empty? (run '(deftest t (let [r (external/full-check! sess)]
                                     (is (= 3 (:unused r)))))))))
    (testing "a bare read is not an assertion — only the silently-vacuous (empty? …) shape"
      (is (empty? (run '(deftest t (let [r (external/full-check! sess)]
                                     (println (:unused r))))))))))
