(ns slopp.addelete-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.api :as api]))

(def target
  (str "(ns adm\n  (:require [clojure.test :refer [deftest is]]))\n"
       "(defn add [x y] (+ x y))\n"
       "(deftest add-t (is (= 5 (add 2 3))))\n"))

(deftest add-form-grows-the-namespace
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'adm target)
      (testing "validation"
        (is (:error (api/add-form! sess 'adm "(defn add [x] x)")))     ; name taken
        (is (:error (api/add-form! sess 'adm "(defmacro m [x] x)")))   ; dialect (D4)
        (is (:error (api/add-form! sess 'adm "(def a 1) (def b 2)"))) ; one form only
        (is (:error (api/add-form! sess 'nope "(def a 1)"))))          ; unknown ns
      (let [r (api/add-form! sess 'adm "(defn triple [x] (* 3 x))" :prompt "new helper")]
        (is (nil? (:error r)))
        (is (= :add (:op (:delta r))))
        (testing "rendered source contains the new form, tidily separated"
          (let [src (api/query-source sess 'adm)]
            (is (re-find #"\(defn triple \[x\] \(\* 3 x\)\)" src))
            (is (not (re-find #"\n\n\n" src)))))
        (testing "the new form is live in the image"
          (is (= [12] (api/query-eval sess "(adm/triple 4)"))))
        (testing "its lineage starts at the :add delta"
          (is (= [:add] (mapv :op (api/query-lineage sess 'adm 'triple)))))
        (testing "verification ran and was recorded"
          (is (zero? (+ (:fail (:test r)) (:error (:test r)))))))
      (testing "effect warnings surface at add time (D6)"
        (let [r (api/add-form! sess 'adm "(defn stash [a v] (reset! a v))")]
          (is (some #(= "stash!" (:suggest %)) (:warnings r)))))
      (finally (api/close! sess)))))

(deftest delete-form-removes-everywhere
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'adm target)
      (api/add-form! sess 'adm "(defn triple [x] (* 3 x))")
      (is (:error (api/delete-form! sess 'adm 'nope)))
      (let [r (api/delete-form! sess 'adm 'triple :prompt "unused")]
        (is (nil? (:error r)))
        (is (= :delete (:op (:delta r))))
        (testing "gone from the source and from the live image"
          (is (not (re-find #"triple" (api/query-source sess 'adm))))
          (is (= [nil] (api/query-eval sess "(resolve 'adm/triple)"))))
        (testing "remaining tests still verify green"
          (is (= 1 (:pass (:test r))))))
      (finally (api/close! sess)))))
