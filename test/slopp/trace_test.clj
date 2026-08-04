(ns slopp.trace-test
  "Mapping a JVM stack trace back onto the STORE.

  The tree is fileless, so a frame's file and line name the VFS rendering
  rather than anything on disk, and these tests hold that correspondence
  (including through catch frames, where the interesting line is not the top
  one). It matters more than its size suggests: a trace pointing at the wrong
  line is the one diagnostic an agent cannot route around, because every other
  read it would reach for starts from the name the trace gave it."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.ops :as ops] [slopp.ops.external :as external]))

(def catch-frames
  "(try (tr.core/outer) (catch Exception e (mapv str (take 4 (.getStackTrace e)))))")

(deftest ^:external stack-traces-map-to-vfs-lines                 ; F6
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'tr.core
                   (str "(ns tr.core)\n"
                        "\n"
                        "(defn boom [] (throw (ex-info \"kapow\" {})))\n" ; VFS line 3
                        "\n"
                        "(defn outer [] (boom))\n"))                      ; VFS line 5
      (testing "freshly loaded namespaces: frames carry the VFS file+line"
        (let [frames (first (ops/query-eval sess catch-frames))]
          (is (some #(re-find #"core\.clj:3" %) frames) (pr-str frames))
          (is (some #(re-find #"core\.clj:5" %) frames) (pr-str frames))))
      (testing "hot-reloaded forms keep the mapping (padded to their VFS row)"
        (ops/edit-replace! sess 'tr.core 'boom
                           "(defn boom [] (throw (ex-info \"pow2\" {})))"
                           :prompt "new message")
        (let [frames (first (ops/query-eval sess catch-frames))]
          (is (some #(re-find #"core\.clj:3" %) frames) (pr-str frames))))
      (testing "F8: editing a form no test exercises is flagged"
        (ops/add-form! sess 'tr.core "(defn quiet [x] x)")
        (ops/module-dep! sess "tr.t" "tr.core" :prompt "fixture edge")
        (ops/ingest! sess 'tr.t (str "(ns tr.t (:require [clojure.test :refer [deftest is]]\n"
                                     "                   [tr.core]))\n"
                                     "(deftest outer-t (is (thrown? Exception (tr.core/outer))))\n"))
        (ops/test-run! sess 'tr.t)                     ; trace map now exists
        (let [r (ops/edit-replace! sess 'tr.core 'quiet "(defn quiet [x] [x])"
                                   :prompt "wrap")]
          (is (true? (:untested r)))
          (is (= :all (:affected r))))
        (let [r (ops/edit-replace! sess 'tr.core 'outer "(defn outer [] (boom))"
                                   :prompt "same")]
          (is (nil? (:untested r)))
          (is (= ['tr.t/outer-t] (:affected r)))))
      (finally (ops/close! sess)))))
