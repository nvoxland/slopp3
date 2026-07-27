(ns slopp.api.artifacts-test
  "Tests for derived files — the half of the manifest that carries no bytes.

  Two properties are defended here and everything else is detail. First, the
  DELTA stays small: a 2 MB bundle must cost a few hundred bytes of journal,
  because the measurement that prompted this field was 30.47 MB of delta log
  spent on fifteen inline copies of one compiled file. Second, a miss is
  RECOVERABLE: a cleared cache, or one holding bytes that no longer match
  their sha, must report the recipe rather than fail, because deleting a
  cache is an ordinary thing to do and should never look like corruption.

  Lives under `slopp.api` so `slopp.api.artifacts` can stay package-private —
  widening a surface to make a test reachable is how visibility rules quietly
  stop meaning anything."
  (:require [clojure.test :refer [deftest testing is]]
            [slopp.api.artifacts :as artifacts]
            [slopp.store :as store]))

(deftest ^:external artifacts-keep-bytes-out-of-the-journal-and-stay-recoverable
  (let [dir    (str (java.nio.file.Files/createTempDirectory
                     "slopp-artifacts"
                     (make-array java.nio.file.attribute.FileAttribute 0)))
        bs     (.getBytes "console.log(1)\n" "UTF-8")
        recipe {:kind :build :tool "compile_client"}
        entry  (artifacts/put! dir bs recipe :content-type "application/javascript")
        [st d] (store/record-artifact (store/empty-store) "public/cljs/main.js" entry
                                      :prompt "the compiled bundle")]
    (testing "the cache is content-addressed, so identical bytes cost one file"
      (is (.exists (artifacts/cache-file dir (:sha entry))))
      (is (= (:sha entry) (:sha (artifacts/put! dir bs recipe)))
          "re-putting the same bytes returns the same sha"))
    (testing "THE POINT: the delta carries the sha and the recipe, never the bytes"
      (is (= :artifact-put (:op d)))
      (is (nil? (:content d)))
      (is (= (:sha entry) (get-in d [:entry :sha])))
      (is (= recipe (get-in d [:entry :recipe])))
      (is (< (count (pr-str d)) 400)
          "a delta for a 2MB bundle must stay this small — that is the whole change"))
    (testing "a cache hit returns verified bytes"
      (let [r (artifacts/fetch dir st "public/cljs/main.js")]
        (is (= "console.log(1)\n" (String. ^bytes (:bytes r) "UTF-8")))))
    (testing "a cache MISS reports the recipe rather than failing"
      (.delete (artifacts/cache-file dir (:sha entry)))
      (let [r (artifacts/fetch dir st "public/cljs/main.js")]
        (is (nil? (:bytes r)))
        (is (= "public/cljs/main.js" (:missing r)))
        (is (= recipe (:recipe r)) "and says how to get it back")))
    (testing "a CORRUPTED cache reports a miss too — a sha nothing checks is decoration"
      (spit (artifacts/cache-file dir (:sha entry)) "tampered")
      (let [r (artifacts/fetch dir st "public/cljs/main.js")]
        (is (nil? (:bytes r)))
        (is (re-find #"do not match" (:why r)))))))

(deftest a-session-with-no-project-dir-still-caches-under-slopp
  ;; In-memory sessions are ordinary and carry no :dir. Two wrong answers were
  ;; live here: (io/file nil …) resolves to the filesystem ROOT, which threw
  ;; FileNotFoundException in three tests; and the system temp dir, which puts
  ;; artifacts somewhere cleanup will never look.
  (let [p (str (artifacts/cache-file nil "abc123"))]
    (testing "it lands under a .slopp, where cleanup and an operator both look"
      (is (re-find #"\.slopp[/\\]artifacts[/\\]abc123$" p) p))
    (testing "and NOT at the filesystem root"
      (is (nil? (re-find #"^/\.slopp" p)) p))
    (testing "an explicit dir is still honoured"
      (is (re-find #"proj[/\\]\.slopp[/\\]artifacts[/\\]abc123$"
                   (str (artifacts/cache-file "proj" "abc123")))))))

(deftest ^:external the-cache-reports-what-is-live-and-what-is-merely-taking-space
  ;; Moving 30MB out of the journal only helps if something still counts it.
  ;; store_health exists because a tree snapshot reached 94% of a 344MB journal
  ;; unnoticed; putting those bytes in a directory nothing measures would be
  ;; the same mistake with a better hiding place.
  (let [dir     (str (java.nio.file.Files/createTempDirectory
                      "slopp-artifact-stats"
                      (make-array java.nio.file.attribute.FileAttribute 0)))
        live    (artifacts/put! dir (.getBytes "kept\n" "UTF-8") {:kind :build})
        orphan  (artifacts/put! dir (.getBytes "stranded bytes\n" "UTF-8") {:kind :build})
        [st _]  (store/record-artifact (store/empty-store) "public/a.js" live)
        stats   (artifacts/cache-stats dir st)]
    (testing "everything on disk is counted, referenced or not"
      (is (= 2 (:n stats)))
      (is (= (+ (:bytes live) (:bytes orphan)) (:bytes stats))))
    (testing "and split by whether the store still points at it"
      (is (= 1 (get-in stats [:live :n])))
      (is (= (:bytes live) (get-in stats [:live :bytes])))
      (is (= 1 (get-in stats [:orphaned :n])))
      (is (= (:bytes orphan) (get-in stats [:orphaned :bytes]))
          "the reclaimable number is the one worth reporting"))
    (testing "a cache that was never written reads as empty, not as an error"
      (is (= {:n 0 :bytes 0 :live {:n 0 :bytes 0} :orphaned {:n 0 :bytes 0}}
             (artifacts/cache-stats (str dir "/nope") st))))))

(deftest ^:external superseding-an-artifact-reclaims-the-bytes-it-replaced
  (let [dir    (str (java.nio.file.Files/createTempDirectory
                     "slopp-artifact-prune"
                     (make-array java.nio.file.attribute.FileAttribute 0)))
        v1     (artifacts/put! dir (.getBytes "bundle v1\n" "UTF-8") {:kind :build})
        v2     (artifacts/put! dir (.getBytes "bundle v2\n" "UTF-8") {:kind :build})
        [st _] (store/record-artifact (store/empty-store) "public/main.js" v1)]
    (testing "while the store still references it, pruning is a no-op"
      (is (= 0 (artifacts/prune-superseded! dir st (:sha v1))))
      (is (.exists (artifacts/cache-file dir (:sha v1)))
          "deleting bytes the manifest still points at would be corruption, not cleanup"))
    (let [[st' _] (store/record-artifact st "public/main.js" v2)]
      (testing "once superseded, the old bytes are reclaimed"
        (is (= (:bytes v1) (artifacts/prune-superseded! dir st' (:sha v1))))
        (is (not (.exists (artifacts/cache-file dir (:sha v1)))))
        (is (.exists (artifacts/cache-file dir (:sha v2))) "and the new bytes stay"))
      (testing "pruning a sha already gone reclaims nothing and does not throw"
        (is (= 0 (artifacts/prune-superseded! dir st' (:sha v1)))))
      (testing "a nil sha — nothing was superseded — must not aim at the cache DIRECTORY"
        (is (= 0 (artifacts/prune-superseded! dir st' nil)))
        (is (.isDirectory (.getParentFile (artifacts/cache-file dir (:sha v2))))
            "(str nil) is \"\", which resolves to the directory itself")
        (is (.exists (artifacts/cache-file dir (:sha v2)))))
      (testing "a sha ANOTHER path still references survives — the cache is content-addressed"
        (let [[st'' _] (store/record-artifact st' "public/copy.js" v2)]
          (is (= 0 (artifacts/prune-superseded! dir st'' (:sha v2))))
          (is (.exists (artifacts/cache-file dir (:sha v2)))
              "two paths, identical bytes, one file — dropping it for one drops it for both"))))))

(deftest a-miss-names-the-call-that-refills-it
  ;; A recipe is data about provenance; it is not an instruction. The gap
  ;; between "{:kind :download :npm \"roughjs@4.6.6\"}" and knowing to call
  ;; js_dep is small for whoever wrote the recipe and total for whoever hits
  ;; the miss six months later.
  (testing "a generated artifact names the tool that regenerates it"
    (let [i (artifacts/refill-instruction
             "public/cljs/main.js" {:kind :build :tool "compile_client"})]
      (is (re-find #"compile_client" i) i)))
  (testing "a downloaded one carries the registry coordinate AND the verb"
    (let [i (artifacts/refill-instruction
             "public/js/roughjs-4.6.6.js"
             {:kind :download :npm "roughjs@4.6.6" :npm-path "bundled/rough.js"
              :integrity "sha512-ZUz"})]
      (is (re-find #"js_dep" i) i)
      (is (re-find #"roughjs@4\.6\.6" i) i)
      (is (re-find #"bundled/rough\.js" i) i)))
  (testing "an unrecognised or absent recipe says so rather than inventing a call"
    (let [i (artifacts/refill-instruction "x" {:kind :sorcery})]
      (is (re-find #"sorcery" i) i))
    (is (string? (artifacts/refill-instruction "x" nil)))))
