(ns slopp.files-test
  "The files manifest: non-code files (README, CI workflows) tracked on the
  store, surviving pushes because they ride every projected tree. Same
  state-carrying-delta pattern as the deps manifest."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store] [slopp.api :as api]))

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
(deftest structured-config-is-semantic-with-per-key-history
  (let [base (store/ingest (store/empty-store) 'cf.core "(ns cf.core)\n")
        mf   "META-INF/MANIFEST.MF"
        [s1 d1] (store/record-config-put base mf :manifest
                                         "Main-Class" "slopp.launcher" :agent "a")
        [s2 d2] (store/record-config-put s1 mf :manifest
                                         "X-Slopp-Main" "slopp.boot/-main" :agent "a")]
    (testing "the store holds semantics, not text"
      (is (= {:format :manifest
              :values {"Main-Class" "slopp.launcher"
                       "X-Slopp-Main" "slopp.boot/-main"}}
             (get-in s2 [:config mf]))))
    (testing "rendering serializes to the format (sorted, deterministic)"
      (is (= "Main-Class: slopp.launcher\nX-Slopp-Main: slopp.boot/-main\n"
             (store/render-config (get-in s2 [:config mf])))))
    (testing "per-key deltas replay on foreign stores"
      (is (= (get-in s2 [:config mf])
             (get-in (store/replay-delta (store/replay-delta base d1) d2)
                     [:config mf]))))
    (testing "unset drops a key; the last key drops the entry"
      (let [[s3 d3] (store/record-config-unset s2 mf "X-Slopp-Main")]
        (is (= {"Main-Class" "slopp.launcher"} (get-in s3 [:config mf :values])))
        (is (= (get-in s3 [:config mf])
               (get-in (store/replay-delta s2 d3) [:config mf])))
        (let [[s4 _] (store/record-config-unset s3 mf "Main-Class")]
          (is (nil? (get-in s4 [:config mf]))))))
    (testing "unknown formats refuse to render"
      (is (thrown? Exception (store/render-config {:format :yaml :values {"a" "b"}}))))))

(deftest ^:isolated file-api-round-trip-and-refusals
  ;; The store layer is covered above; the API layer was not — and it is the
  ;; layer the file_put/file_get/file_remove wire tools actually call, so its
  ;; validation and session plumbing had no test at all.
  (let [sess (api/open!)]
    (try
      (testing "put lands and reports what it wrote"
        (let [r (api/file-put! sess "README.md" "# hello\n" :prompt "seed")]
          (is (nil? (:error r)) (pr-str r))
          (is (= {:path "README.md" :bytes 8} r))))
      (testing "get reads it back"
        (is (= {:path "README.md" :content "# hello\n"}
               (api/file-get sess "README.md"))))
      (testing "put overwrites, and :at still sees the old content"
        (let [before (:id (last (:deltas (:store @sess))))]
          (api/file-put! sess "README.md" "# v2\n" :prompt "revise")
          (is (= "# v2\n" (:content (api/file-get sess "README.md"))))
          (is (= "# hello\n" (:content (api/file-get sess "README.md" :at before)))
              "time travel through the files manifest")))
      (testing "remove drops it, and reading it back is an error not a nil"
        (is (= {:removed "README.md"} (api/file-remove! sess "README.md")))
        (is (re-find #"not on the files manifest"
                     (str (:error (api/file-get sess "README.md"))))))
      (testing "the refusals are data, not throws"
        (is (re-find #"needs a :path" (str (:error (api/file-put! sess "" "x")))))
        (is (re-find #"needs :content" (str (:error (api/file-put! sess "a.txt" nil)))))
        (is (re-find #"not on the files manifest"
                     (str (:error (api/file-remove! sess "nope.txt"))))))
      (finally (api/close! sess)))))
