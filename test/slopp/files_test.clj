(ns slopp.files-test
  "The files manifest: non-code files (README, CI workflows) tracked on the
  store, surviving pushes because they ride every projected tree. Same
  state-carrying-delta pattern as the deps manifest."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]))

(def wf ".github/workflows/test.yml")

(deftest file-put-lands-on-the-manifest
  (let [base (store/ingest (store/empty-store) 'fm.core "(ns fm.core)\n")
        [st d] (store/record-file-put base wf "name: test\n" :agent "t")]
    (is (= "name: test\n" (get-in st [:files wf])))
    (testing "the delta is state-carrying — foreign replay reconstructs"
      (is (= :file-put (:op d)))
      (is (= "name: test\n" (get-in (store/replay-delta base d) [:files wf]))))
    (testing "overwrite updates"
      (let [[st2 _] (store/record-file-put st wf "name: v2\n")]
        (is (= "name: v2\n" (get-in st2 [:files wf])))))
    (testing "remove drops it, replay converges"
      (let [[st3 d3] (store/record-file-remove st wf)]
        (is (nil? (get-in st3 [:files wf])))
        (is (nil? (get-in (store/replay-delta st d3) [:files wf])))))))
(deftest file-history-and-time-travel
  (let [base (store/ingest (store/empty-store) 'fm.core "(ns fm.core)\n")
        [s1 d1] (store/record-file-put base wf "v1\n" :agent "a" :prompt "first")
        [s2 d2] (store/record-file-put s1 wf "v2\n" :agent "b" :prompt "second")
        [s3 d3] (store/record-file-remove s2 wf :agent "c")]
    (testing "history: every version, oldest first, with provenance"
      (let [h (store/file-history s3 wf)]
        (is (= [:file-put :file-put :file-remove] (mapv :op h)))
        (is (= [(:id d1) (:id d2) (:id d3)] (mapv :delta h)))
        (is (= ["a" "b" "c"] (mapv :agent h)))
        (is (= [3 3 nil] (mapv :bytes h)))))
    (testing "content at a point in time (the query_form_at analog)"
      (is (= "v1\n" (store/file-at s3 wf (:id d1))))
      (is (= "v2\n" (store/file-at s3 wf (:id d2))))
      (is (nil? (store/file-at s3 wf (:id d3))))
      (is (nil? (store/file-at s3 wf "d0"))))))
