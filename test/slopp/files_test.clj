(ns slopp.files-test
  "The files manifest: non-code files (README, CI workflows) tracked on the
  store, surviving pushes because they ride every projected tree. Same
  state-carrying-delta pattern as the deps manifest."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store] [slopp.api :as api] [slopp.api.external :as external] [slopp.store.db :as db] [next.jdbc :as jdbc] [slopp.store.artifacts :as artifacts]))

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

(deftest ^:external file-api-round-trip-and-refusals
  ;; The store layer is covered above; the API layer was not — and it is the
  ;; layer the file_put/file_get/file_remove wire tools actually call, so its
  ;; validation and session plumbing had no test at all.
  (let [sess (external/open!)]
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

(deftest binary-files-are-content-addressed
  (let [base  (store/empty-store)
        png   (byte-array [(byte -119) 80 78 71 13 10 26 10 0 1 2 3])
        b64   (.encodeToString (java.util.Base64/getEncoder) png)
        [s1 d1] (store/record-file-put base "public/logo.png" b64
                                       :encoding "base64"
                                       :content-type "image/png"
                                       :prompt "an asset")]
    (testing "the manifest entry is content-addressed, not inline"
      (let [e (get-in s1 [:files "public/logo.png"])]
        (is (map? e))
        (is (string? (:sha e)))
        (is (= "image/png" (:content-type e)))
        (is (= (count png) (:bytes e)))))
    (testing "the delta carries the sha and size, never the payload"
      (is (= (get-in s1 [:files "public/logo.png" :sha]) (:sha d1)))
      (is (nil? (:content d1)))
      (is (= (count png) (:bytes d1))))
    (testing "the bytes live in the :blobs cache under their sha"
      (is (java.util.Arrays/equals png
                                   ^bytes (get-in s1 [:blobs (:sha d1)]))))
    (testing "replay reconstructs the entry from the delta"
      (let [e (get-in (store/replay-delta base d1) [:files "public/logo.png"])]
        (is (= (:sha d1) (:sha e)))
        (is (= "image/png" (:content-type e)))))
    (testing "identical content converges on ONE blob whatever the path"
      (let [[s2 d2] (store/record-file-put s1 "other/copy.png" b64
                                           :encoding "base64"
                                           :content-type "image/png")]
        (is (= (:sha d1) (:sha d2)))
        (is (= 1 (count (:blobs s2))))))
    (testing "file-content resolves text AND binary uniformly"
      (let [[s3 _] (store/record-file-put s1 "README.md" "hello\n")]
        (is (= "hello\n" (:content (store/file-content s3 "README.md"))))
        (let [{:keys [content content-type]} (store/file-content s3 "public/logo.png")]
          (is (= "image/png" content-type))
          (is (java.util.Arrays/equals png ^bytes content)))))))

(deftest ^:external blob-bytes-survive-the-db
  (let [dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-blobs" (make-array java.nio.file.attribute.FileAttribute 0)))
        conn (db/open! dir)
        png  (byte-array [(byte -119) 80 78 71 13 10 26 10 42 7])
        b64  (.encodeToString (java.util.Base64/getEncoder) png)
        [s1 d1] (store/record-file-put (store/empty-store) "public/a.png" b64
                                       :encoding "base64" :content-type "image/png")]
    (db/persist! conn s1 d1)
    (testing "load-store restores the ENTRY; the bytes stay in the table, not memory"
      (let [st (db/load-store conn)]
        (is (= (:sha d1) (get-in st [:files "public/a.png" :sha])))
        (let [{:keys [content content-type]} (store/file-content st "public/a.png")]
          (is (= "image/png" content-type))
          ;; :blobs is a PARTIAL cache — file-content documents :content nil on
          ;; a miss and defers to the db. Loading every blob's bytes at open
          ;; cost a compiled JS bundle (~1.8MB) on every session open, for
          ;; something no consumer needs until it asks.
          (is (nil? content) "no blob bytes are pulled into memory at open")
          (is (java.util.Arrays/equals png ^bytes (db/get-blob conn (:sha d1)))
              "and the bytes are right there, on demand"))))
    (testing "the journal payload is sha-only — no base64 in the deltas table"
      (let [payloads (map :deltas/payload
                          (jdbc/execute! conn ["SELECT payload FROM deltas"]))]
        (is (not-any? #(re-find #"iVBOR|AAEC" (str %)) payloads))))
    (testing "get-blob answers without a session (the projection's path)"
      (is (java.util.Arrays/equals png ^bytes (db/get-blob conn (:sha d1)))))))

(deftest ^:external binary-file-api-round-trip
  (let [sess (external/open!)
        png  (byte-array [(byte -119) 80 78 71 99 100 101])
        b64  (.encodeToString (java.util.Base64/getEncoder) png)]
    (try
      (testing "binary put reports the content address"
        (let [r (api/file-put! sess "public/i.png" b64
                               :encoding "base64" :content-type "image/png"
                               :prompt "asset")]
          (is (nil? (:error r)) (pr-str r))
          (is (= (count png) (:bytes r)))
          (is (string? (:sha r)))))
      (testing "binary get returns base64 + content-type"
        (let [r (api/file-get sess "public/i.png")]
          (is (= "base64" (:encoding r)))
          (is (= "image/png" (:content-type r)))
          (is (java.util.Arrays/equals
               png (.decode (java.util.Base64/getDecoder) (str (:content r)))))))
      (testing "text stays text"
        (api/file-put! sess "NOTES.md" "plain\n")
        (let [r (api/file-get sess "NOTES.md")]
          (is (= "plain\n" (:content r)))
          (is (nil? (:encoding r)))))
      (testing "build! materializes the asset as real bytes"
        (api/ingest! sess 'bf.core "(ns bf.core)\n(defn ^:unused-ok f [x] x)\n")
        (let [dir (str (java.nio.file.Files/createTempDirectory
                        "slopp-binbuild" (make-array java.nio.file.attribute.FileAttribute 0)))
              r   (external/build! sess dir)]
          (is (nil? (:error r)) (pr-str r))
          (is (java.util.Arrays/equals
               png (java.nio.file.Files/readAllBytes
                    (.toPath (java.io.File. dir "public/i.png")))))))
      (finally (api/close! sess)))))

(deftest a-vendored-js-declaration-can-be-checked-against-its-blob
  (let [b64      "Ly8gcm91Z2gK"                       ; "// rough\n"
        [s1 _]   (store/record-file-put (store/empty-store)
                                        "public/js/rough-4.6.6.js" b64
                                        :content-type "text/javascript")
        real-sha (get-in s1 ["public/js/rough-4.6.6.js" :sha])
        real-sha (or real-sha (get-in s1 [:files "public/js/rough-4.6.6.js" :sha]))
        [s2 d]   (store/record-js-dep s1 "roughjs"
                                      {:version "4.6.6" :format :iife
                                       :global "rough"
                                       :file "public/js/rough-4.6.6.js"
                                       :sha real-sha
                                       :source-url "https://cdn.jsdelivr.net/npm/roughjs@4.6.6/bundled/rough.js"
                                       :license "MIT"}
                                      :prompt "the sketch renderer")]
    (testing "the declaration lands in its own manifest, keyed by name"
      (is (= "4.6.6" (get-in s2 [:js-deps "roughjs" :version])))
      (is (= :iife (get-in s2 [:js-deps "roughjs" :format])))
      (is (= "rough" (get-in s2 [:js-deps "roughjs" :global]))))
    (testing "the why rides the delta, like every other declaration"
      (is (= "the sketch renderer" (:prompt d)))
      (is (= :js-dep (:op d))))
    (testing "and the recorded sha MATCHES the blob — provenance nothing checks is decoration"
      (is (= (get-in s2 [:files (get-in s2 [:js-deps "roughjs" :file]) :sha])
             (get-in s2 [:js-deps "roughjs" :sha]))))
    (testing "retraction removes the declaration and leaves the bytes alone"
      (let [[s3 _] (store/record-js-dep s2 "roughjs" nil :remove true)]
        (is (nil? (get-in s3 [:js-deps "roughjs"])))
        (is (some? (get-in s3 [:files "public/js/rough-4.6.6.js"])))))))

(deftest ^:external js-dep-vendors-and-declares-in-one-act
  ;; Each refusal here stands for a failure INVISIBLE to the compiler: the
  ;; bundle builds clean and the diagram is blank in a tab. That is the whole
  ;; argument for a verb rather than a bare store write.
  (let [sess (external/open!)
        tmp  (java.io.File/createTempFile "rough" ".js")]
    (try
      (spit tmp "var rough=1;\n")
      (testing "a format typo would be a silent no-op at compile time"
        (is (re-find #":iife, :umd or :esm"
                     (str (:error (api/js-dep! sess "roughjs"
                                               {:version "4.6.6" :format :iffe
                                                :global "rough"
                                                :file "public/js/rough.js"}
                                               :source (str tmp)))))))
      (testing "an :iife library with no :global has nothing to map a require onto"
        (is (re-find #":global"
                     (str (:error (api/js-dep! sess "roughjs"
                                               {:version "4.6.6" :format :iife
                                                :file "public/js/rough.js"}
                                               :source (str tmp)))))))
      (testing "no :source at all — declaring IS vendoring, so the bytes must exist"
        (is (re-find #":source"
                     (str (:error (api/js-dep! sess "roughjs"
                                               {:version "4.6.6" :format :iife
                                                :global "rough"
                                                :file "public/js/rough.js"}))))))
      (testing "a good declaration vendors the bytes and records the coordinate"
        (let [r (api/js-dep! sess "roughjs"
                             {:version "4.6.6" :format :iife :global "rough"
                              :file "public/js/rough.js"
                              :npm "roughjs@4.6.6" :npm-path "bundled/rough.js"
                              :integrity "sha512-abc" :license "MIT"}
                             :source (str tmp)
                             :prompt "the sketch renderer")
              st (:store @sess)]
          (is (= "roughjs" (:declared r)) (pr-str r))
          (testing "the library is a DERIVED artifact, not an authored file"
            (is (some? (get-in st [:artifacts "public/js/rough.js"])))
            (is (nil? (get-in st [:files "public/js/rough.js"]))
                "one path, one manifest"))
          (testing "and its recipe is the registry coordinate, which is re-fetchable"
            (let [recipe (get-in st [:artifacts "public/js/rough.js" :recipe])]
              (is (= :download (:kind recipe)))
              (is (= "roughjs@4.6.6" (:npm recipe)))
              (is (= "bundled/rough.js" (:npm-path recipe)))
              (is (= "sha512-abc" (:integrity recipe)))))
          (testing "the sha is computed from the bytes, and the cache holds them"
            (is (= 64 (count (:sha r))))
            (is (.exists (artifacts/cache-file (:dir @sess) (:sha r)))))))
      (testing "retraction drops the declaration"
        (is (= {:retracted "roughjs"} (api/js-dep! sess "roughjs" nil :remove true)))
        (is (nil? (get-in (:store @sess) [:js-deps "roughjs"]))))
      (finally (.delete tmp) (api/close! sess)))))
