(ns slopp.trace-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.api :as api]))

(def catch-frames
  "(try (tr.core/outer) (catch Exception e (mapv str (take 4 (.getStackTrace e)))))")

(deftest ^:external stack-traces-map-to-vfs-lines                 ; F6
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'tr.core
                   (str "(ns tr.core)\n"
                        "\n"
                        "(defn boom [] (throw (ex-info \"kapow\" {})))\n" ; VFS line 3
                        "\n"
                        "(defn outer [] (boom))\n"))                      ; VFS line 5
      (testing "freshly loaded namespaces: frames carry the VFS file+line"
        (let [frames (first (api/query-eval sess catch-frames))]
          (is (some #(re-find #"core\.clj:3" %) frames) (pr-str frames))
          (is (some #(re-find #"core\.clj:5" %) frames) (pr-str frames))))
      (testing "hot-reloaded forms keep the mapping (padded to their VFS row)"
        (api/edit-replace! sess 'tr.core 'boom
                           "(defn boom [] (throw (ex-info \"pow2\" {})))"
                           :prompt "new message")
        (let [frames (first (api/query-eval sess catch-frames))]
          (is (some #(re-find #"core\.clj:3" %) frames) (pr-str frames))))
      (testing "F8: editing a form no test exercises is flagged"
        (api/add-form! sess 'tr.core "(defn quiet [x] x)")
        (api/module-dep! sess "tr.t" "tr.core" :prompt "fixture edge")
        (api/ingest! sess 'tr.t (str "(ns tr.t (:require [clojure.test :refer [deftest is]]\n"
                                     "                   [tr.core]))\n"
                                     "(deftest outer-t (is (thrown? Exception (tr.core/outer))))\n"))
        (api/test-run! sess 'tr.t)                     ; trace map now exists
        (let [r (api/edit-replace! sess 'tr.core 'quiet "(defn quiet [x] [x])"
                                   :prompt "wrap")]
          (is (true? (:untested r)))
          (is (= :all (:affected r))))
        (let [r (api/edit-replace! sess 'tr.core 'outer "(defn outer [] (boom))"
                                   :prompt "same")]
          (is (nil? (:untested r)))
          (is (= ['tr.t/outer-t] (:affected r)))))
      (finally (api/close! sess)))))
