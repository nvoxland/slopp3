(ns slopp.rename-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.api :as api])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def target
  (str "(ns rdemo\n  (:require [clojure.test :refer [deftest is]]))\n"
       "(defn helper [x] (* x 2))\n"
       "(defn caller [x] (+ 1 (helper x)))\n"
       "(defn trap [helper] (helper 9))\n"       ; local param SHADOWS the var
       "(deftest caller-t (is (= 5 (caller 2))))\n"))

(deftest ^:isolated rename-coordinates-all-references
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'rdemo target)
      (api/test-run! sess 'rdemo)                ; build the trace map
      (testing "validation errors"
        (is (:error (api/rename! sess 'rdemo 'nope 'x)))
        (is (:error (api/rename! sess 'rdemo 'helper 'caller))))
      (let [r (api/rename! sess 'rdemo 'helper 'doubler :prompt "clearer name")]
        (is (nil? (:error r)))
        (let [src (api/query-source sess 'rdemo)]
          (testing "def + true references renamed"
            (is (re-find #"\(defn doubler \[x\]" src))
            (is (re-find #"\(\+ 1 \(doubler x\)\)" src)))
          (testing "the shadowed local is UNTOUCHED (string-replace would corrupt it)"
            (is (re-find #"\(defn trap \[helper\] \(helper 9\)\)" src))))
        (testing "one delta covers the def and the caller"
          (is (= :rename (:op (:delta r))))
          (is (= 2 (count (:form-ids (:delta r))))))
        (testing "only the affected test re-ran, green"
          (is (= ['rdemo/caller-t] (:affected r)))
          (is (= 1 (:pass (:test r))))
          (is (zero? (+ (:fail (:test r)) (:error (:test r))))))
        (testing "the image reflects the rename; the old var is gone"
          (is (= [11] (api/query-eval sess "(rdemo/caller 5)")))
          (is (= [nil] (api/query-eval sess "(resolve 'rdemo/helper)")))
          (is (= [10] (api/query-eval sess "(rdemo/trap (fn [x] 10))"))))
        (testing "lineage of the new name includes the rename delta"
          (is (contains? (set (map :op (api/query-lineage sess 'rdemo 'doubler)))
                         :rename))))
      (finally (api/close! sess)))))

(deftest ^:isolated rename-across-namespaces-and-restart
  (let [dir  (str (Files/createTempDirectory "slopp-rename-test"
                                             (make-array FileAttribute 0)))
        sess (api/open! {:dir dir})]
    (try
      (api/ingest! sess 'liba "(ns liba)\n(defn helper [x] (* x 2))\n")
      (api/ingest! sess 'libb (str "(ns libb\n  (:require [liba :as la]))\n"
                                   "(defn use-it [x] (la/helper x))\n"))
      (let [r (api/rename! sess 'liba 'helper 'twice :prompt "cross-ns")]
        (is (nil? (:error r)))
        (testing "alias-qualified reference in the other namespace is rewritten"
          (is (re-find #"defn twice" (api/query-source sess 'liba)))
          (is (re-find #"la/twice" (api/query-source sess 'libb))))
        (testing "the live image works across the rename"
          (is (= [10] (api/query-eval sess "(libb/use-it 5)")))))
      (finally (api/close! sess)))
    ;; a fresh session over the same dir: the rename persisted in both nses
    (let [sess2 (api/open! {:dir dir})]
      (try
        (is (re-find #"defn twice" (api/query-source sess2 'liba)))
        (is (re-find #"la/twice" (api/query-source sess2 'libb)))
        (is (= [10] (api/query-eval sess2 "(libb/use-it 5)")))
        (is (= :rename (:op (last (filter #(= :rename (:op %))
                                          (store/deltas (:store @sess2)))))))
        (finally (api/close! sess2))))))
(deftest ^:isolated already-renamed-is-state-not-error
  (let [sess (api/open!)]
    (try
      (api/create-ns! sess 'ar.core :source "(ns ar.core)\n(defn old-name [] 1)\n")
      (is (nil? (:error (api/rename! sess 'ar.core 'old-name 'new-name :agent "t"))))
      (testing "the retried rename reports state instead of refusing"
        (let [r (api/rename! sess 'ar.core 'old-name 'new-name :agent "t")]
          (is (nil? (:error r)) (pr-str r))
          (is (:already (:renamed r)))))
      (finally (api/close! sess)))))
