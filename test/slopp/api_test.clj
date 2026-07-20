(ns slopp.api-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.api :as api] [slopp.api.testrun :as testrun] [clojure.java.io :as io] [clojure.edn :as edn] [slopp.api.query :as query] [slopp.api.external :as external])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(deftest ^:external create-ns-modes
  (let [sess (api/open!)]
    (try
      (testing ":source lands a whole namespace in one verified call (folded-in ingest)"
        (let [r (api/create-ns! sess 'cn.core
                                :source "(ns cn.core)\n(defn f [x] (* 2 x))\n(defn g [x] (+ 1 x))\n"
                                :agent "alice")]
          (is (nil? (:error r)))
          (is (= 3 (:forms r)))
          (is (re-find #"defn f" (query/query-source sess 'cn.core)))
          (is (= [10] (api/query-eval sess "(cn.core/f 5)")))))
      (testing ":source carries provenance via :agent"
        (is (some #(= "alice" (:agent %))
                  (query/query-lineage sess 'cn.core 'f))))
      (testing ":requires still scaffolds an empty namespace"
        (let [r (api/create-ns! sess 'cn.util :requires ["[clojure.string :as str]"])]
          (is (nil? (:error r)))
          (is (re-find #"clojure.string" (query/query-source sess 'cn.util)))))
      (testing ":source and :requires are mutually exclusive"
        (is (:error (api/create-ns! sess 'cn.bad
                                    :source "(ns cn.bad)\n"
                                    :requires ["[clojure.string]"]))))
      (finally (api/close! sess)))))

(deftest ^:external operation-surface
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'demo
                   (str "(ns demo)\n"
                        "(defn add [x y] (+ x y))\n"
                        "(defn tainted [a] (swap! a inc))\n"))
      (testing "query.source renders current source from the store (VFS read)"
        (is (re-find #"defn add" (query/query-source sess 'demo))))
      (testing "query.symbol reports effectfulness (D6)"
        (is (false? (:effectful? (query/query-symbol sess 'demo 'add))))
        (is (true? (:effectful? (query/query-symbol sess 'demo 'tainted)))))
      (testing "query.references finds callers"
        ;; tainted is defined AFTER add — the caller must be the later form,
        ;; or the write is (correctly) refused by the cold-load gate (S1b)
        (let [r (api/edit-replace! sess 'demo 'tainted
                                   "(defn tainted [a] (add (swap! a inc) 1))"
                                   :prompt "call add")]
          (is (nil? (:error r)) (pr-str r)))
        (is (seq (query/query-references sess 'demo 'add))))
      (testing "a cycle (add calls tainted, which already calls add) AUTO-DECLARES"
        ;; mutual recursion has no legal order — the pipeline inserts a marked
        ;; declare instead of refusing; the agent writes none
        (let [r (api/edit-replace! sess 'demo 'add
                                   "(defn add [x y] (tainted (atom (+ x y))))"
                                   :prompt "call tainted")]
          (is (nil? (:error r)) (pr-str r))
          (is (re-find #":auto-declare" (query/query-source sess 'demo)))))
      (testing "query.eval asks the live image (the oracle)"
        (is (= [7] (api/query-eval sess "(+ 3 4)"))))
      (testing "edit.replace-form updates store + hot-reloads image"
        (let [r (api/edit-replace! sess 'demo 'tainted "(defn tainted [a] a)"
                                   :prompt "defang")]
          (is (nil? (:error r)))
          (is (= [42] (api/query-eval sess "(demo/tainted 42)")))))
      (testing "query.lineage shows provenance (ingest + replaces, with prompts)"
        (let [lin (query/query-lineage sess 'demo 'tainted)]
          (is (contains? (set (map :op lin)) :ingest))
          (is (contains? (set (map :op lin)) :replace))
          (is (some #(= "defang" (:prompt %)) lin))))
      (testing "build materializes .clj on demand (C1/C6 explicit build)"
        (let [dir (str (Files/createTempDirectory "slopp-build"
                                                  (make-array FileAttribute 0)))]
          (external/build! sess dir)
          (is (= (query/query-source sess 'demo) (slurp (str dir "/src/demo.clj"))))
          (is (.exists (clojure.java.io/file dir "deps.edn")))
          (testing "X4 guard: never into the running system, absolute only, no deps.edn clobber"
            (is (:error (external/build! sess ".")))
            (is (:error (external/build! sess (System/getProperty "user.dir"))))
            (spit (str dir "/deps.edn") "{:paths [\"src\"] :custom true}\n")
            (external/build! sess dir)
            (is (re-find #":custom" (slurp (str dir "/deps.edn")))))))
      (finally (api/close! sess)))))

(deftest parse-test-summary-reads-the-runner-line
  (testing "a green clojure.test summary"
    (is (= {:ran 46 :assertions 1200 :failures 0 :errors 0 :status :green}
           (testrun/parse-test-summary
            "Testing slopp.foo\n\nRan 46 tests containing 1200 assertions.\n0 failures, 0 errors.\n"))))
  (testing "a red summary (singular/plural both parse)"
    (let [r (testrun/parse-test-summary
             "Ran 5 tests containing 10 assertions.\n2 failures, 1 error.\n")]
      (is (= :red (:status r)))
      (is (= 2 (:failures r)))
      (is (= 1 (:errors r)))))
  (testing "no summary present -> nil"
    (is (nil? (testrun/parse-test-summary "boom — the JVM died before any test ran")))))

(deftest ^:external build-routes-test-namespaces-to-test-dir
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
        (external/build! sess dir)
        (testing "production ns under src/, test ns under test/ (not src/)"
          (is (.exists (f "src/proj/core.clj")))
          (is (.exists (f "test/proj/core_test.clj")))
          (is (not (.exists (f "src/proj/core_test.clj")))))
        (testing "deps.edn puts test/ on a runnable :test extra-path"
          (let [m (clojure.edn/read-string (slurp (f "deps.edn")))]
            (is (= ["src"] (:paths m)))
            (is (= ["test"] (get-in m [:aliases :test :extra-paths]))))))
      (finally (api/close! sess)))))
(deftest parse-test-failures-extracts-blocks
  (let [out (str "\nRunning tests in #{\"test\"}\n\nTesting foo.bar-test\n\n"
                 "FAIL in (my-test) (foo/bar_test.clj:12)\n"
                 "rush orders double\n"
                 "expected: (= 1 2)\n"
                 "  actual: (not (= 1 2))\n\n"
                 "ERROR in (other-test) (foo/bar_test.clj:20)\n"
                 "expected: nil\n"
                 "  actual: java.lang.ArithmeticException: boom\n"
                 " at foo (bar.clj:1)\n\n"
                 "Ran 5 tests containing 9 assertions.\n2 failures, 1 errors.\n")
        fs  (testrun/parse-test-failures out)]
    (testing "each FAIL/ERROR block becomes {:test :detail}"
      (is (= ["my-test" "other-test"] (mapv :test fs)))
      (is (re-find #"expected: \(= 1 2\)" (:detail (first fs))))
      (is (re-find #"boom" (:detail (second fs)))))
    (testing "blocks are capped and limited"
      (is (every? #(<= (count (:detail %)) 520) fs))
      (is (= 1 (count (testrun/parse-test-failures out :limit 1)))))))
(deftest ^:external inline-test-stores-build-a-runnable-suite
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'il.core
                   (str "(ns il.core (:require [clojure.test :refer [deftest is]]))\n"
                        "(defn f [x] (inc x))\n"
                        "(deftest f-t (is (= 2 (f 1))))\n"))
      (let [r (external/external-test-run! sess)]
        (is (= :green (:status r)) (pr-str r))
        (is (= 1 (:ran r)) (pr-str r)))
      (finally (api/close! sess)))))
(deftest ^:external build-routes-tests-through-the-trace-runner-when-present
  ;; #121: the external tier can only trace if the built project carries the
  ;; trace runner. PRESENCE in the store is the condition — a store without it
  ;; must still build a deps.edn that runs, so it stays on plain cognitect.
  (let [sess (api/open!)
        tmp  #(str (java.nio.file.Files/createTempDirectory
                    % (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (api/ingest! sess 'tb.core
                   (str "(ns tb.core (:require [clojure.test :refer [deftest is]]))\n"
                        "(defn f [x] (inc x))\n"
                        "(deftest f-t (is (= 2 (f 1))))\n"))
      (testing "no trace runner in the store — the build stays on cognitect"
        (let [dir (tmp "slopp-trace-build")]
          (external/build! sess dir)
          (let [d (slurp (java.io.File. dir "deps.edn"))]
            (is (re-find #"\"-m\" \"cognitect\.test-runner\"" d))
            (is (not (re-find #"slopp\.testmain" d))))))
      (testing "the store provides one — both aliases route through it"
        (api/create-ns! sess 'slopp.testmain
                        :source (str "(ns slopp.testmain \"Stub: presence is the"
                                     " condition build! reads.\")\n"
                                     "(defn -main [& _args] nil)\n"))
        (let [dir (tmp "slopp-trace-build2")]
          (external/build! sess dir)
          (let [d (slurp (java.io.File. dir "deps.edn"))]
            (is (= 2 (count (re-seq #"\"-m\" \"slopp\.testmain\"" d))) d)
            (is (not (re-find #"\"-m\" \"cognitect\.test-runner\"" d))))))
      (finally (api/close! sess)))))
