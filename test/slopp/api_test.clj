(ns slopp.api-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.api :as api])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(deftest create-ns-modes
  (let [sess (api/open!)]
    (try
      (testing ":source lands a whole namespace in one verified call (folded-in ingest)"
        (let [r (api/create-ns! sess 'cn.core
                                :source "(ns cn.core)\n(defn f [x] (* 2 x))\n(defn g [x] (+ 1 x))\n"
                                :agent "alice")]
          (is (nil? (:error r)))
          (is (= 3 (:forms r)))
          (is (re-find #"defn f" (api/query-source sess 'cn.core)))
          (is (= [10] (api/query-eval sess "(cn.core/f 5)")))))
      (testing ":source carries provenance via :agent"
        (is (some #(= "alice" (:agent %))
                  (api/query-lineage sess 'cn.core 'f))))
      (testing ":requires still scaffolds an empty namespace"
        (let [r (api/create-ns! sess 'cn.util :requires ["[clojure.string :as str]"])]
          (is (nil? (:error r)))
          (is (re-find #"clojure.string" (api/query-source sess 'cn.util)))))
      (testing ":source and :requires are mutually exclusive"
        (is (:error (api/create-ns! sess 'cn.bad
                                    :source "(ns cn.bad)\n"
                                    :requires ["[clojure.string]"]))))
      (finally (api/close! sess)))))

(deftest operation-surface
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'demo
                   (str "(ns demo)\n"
                        "(defn add [x y] (+ x y))\n"
                        "(defn tainted [a] (swap! a inc))\n"))
      (testing "query.source renders current source from the store (VFS read)"
        (is (re-find #"defn add" (api/query-source sess 'demo))))
      (testing "query.symbol reports effectfulness (D6)"
        (is (false? (:effectful? (api/query-symbol sess 'demo 'add))))
        (is (true? (:effectful? (api/query-symbol sess 'demo 'tainted)))))
      (testing "query.references finds callers"
        (api/edit-replace! sess 'demo 'add "(defn add [x y] (tainted (atom (+ x y))))"
                           :prompt "call tainted")
        (is (seq (api/query-references sess 'demo 'tainted))))
      (testing "query.eval asks the live image (the oracle)"
        (is (= [7] (api/query-eval sess "(+ 3 4)"))))
      (testing "edit.replace-form updates store + hot-reloads image"
        (let [r (api/edit-replace! sess 'demo 'tainted "(defn tainted [a] a)"
                                   :prompt "defang")]
          (is (nil? (:error r)))
          (is (= [42] (api/query-eval sess "(demo/tainted 42)")))))
      (testing "query.lineage shows provenance (ingest + replaces, with prompts)"
        (let [lin (api/query-lineage sess 'demo 'tainted)]
          (is (contains? (set (map :op lin)) :ingest))
          (is (contains? (set (map :op lin)) :replace))
          (is (some #(= "defang" (:prompt %)) lin))))
      (testing "build materializes .clj on demand (C1/C6 explicit build)"
        (let [dir (str (Files/createTempDirectory "slopp-build"
                                                  (make-array FileAttribute 0)))]
          (api/build! sess dir)
          (is (= (api/query-source sess 'demo) (slurp (str dir "/src/demo.clj"))))
          (is (.exists (clojure.java.io/file dir "deps.edn")))
          (testing "X4 guard: never into the running system, absolute only, no deps.edn clobber"
            (is (:error (api/build! sess ".")))
            (is (:error (api/build! sess (System/getProperty "user.dir"))))
            (spit (str dir "/deps.edn") "{:paths [\"src\"] :custom true}\n")
            (api/build! sess dir)
            (is (re-find #":custom" (slurp (str dir "/deps.edn")))))))
      (finally (api/close! sess)))))

(deftest parse-test-summary-reads-the-runner-line
  (testing "a green clojure.test summary"
    (is (= {:ran 46 :assertions 1200 :failures 0 :errors 0 :status :green}
           (api/parse-test-summary
            "Testing slopp.foo\n\nRan 46 tests containing 1200 assertions.\n0 failures, 0 errors.\n"))))
  (testing "a red summary (singular/plural both parse)"
    (let [r (api/parse-test-summary
             "Ran 5 tests containing 10 assertions.\n2 failures, 1 error.\n")]
      (is (= :red (:status r)))
      (is (= 2 (:failures r)))
      (is (= 1 (:errors r)))))
  (testing "no summary present -> nil"
    (is (nil? (api/parse-test-summary "boom — the JVM died before any test ran")))))

(deftest build-routes-test-namespaces-to-test-dir
  ;; a normal Clojure layout: production under src/, tests under test/, off the
  ;; default classpath (a :test alias makes them runnable).
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'proj.core "(ns proj.core)\n(defn f [x] (inc x))\n")
      (api/create-ns! sess 'proj.core-test
                      :requires ["[clojure.test :refer [deftest is]]"
                                 "[proj.core :as c]"])
      (api/add-form! sess 'proj.core-test "(deftest f-t (is (= 2 (c/f 1))))")
      (let [dir (str (Files/createTempDirectory "slopp-testdir"
                                                (make-array FileAttribute 0)))
            f   #(clojure.java.io/file dir %)]
        (api/build! sess dir)
        (testing "production ns under src/, test ns under test/ (not src/)"
          (is (.exists (f "src/proj/core.clj")))
          (is (.exists (f "test/proj/core_test.clj")))
          (is (not (.exists (f "src/proj/core_test.clj")))))
        (testing "deps.edn puts test/ on a runnable :test extra-path"
          (let [m (clojure.edn/read-string (slurp (f "deps.edn")))]
            (is (= ["src"] (:paths m)))
            (is (= ["test"] (get-in m [:aliases :test :extra-paths]))))))
      (finally (api/close! sess)))))
