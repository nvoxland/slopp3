(ns slopp.f3c-test
  "Round-3c findings, fixed: F-3c1 project-wide test_run (one image eval),
  F-3c2 query-eval surfaces exceptions, F-3c3 cross-namespace references,
  F-3c5 trace-less group verification covers ALL touched namespaces."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.api :as api] [slopp.api.query :as query] [slopp.api.external :as external]))

(defn- seed! [sess]
  (api/ingest! sess 'ta.a
               (str "(ns ta.a (:require [clojure.test :refer [deftest is]]))\n"
                    "(defn f [x] (inc x))\n"
                    "(deftest a-t (is (= 2 (f 1))))\n"))
  (api/module-dep! sess "ta.b" "ta.a" :prompt "fixture edge")
  (api/ingest! sess 'ta.b
               (str "(ns ta.b (:require [ta.a :as a]\n"
                    "                   [clojure.test :refer [deftest is]]))\n"
                    "(defn g [x] (a/f (a/f x)))\n"
                    "(deftest b-t (is (= 3 (g 1))))\n")))

(deftest ^:external project-wide-test-run                          ; F-3c1
  (let [sess (external/open!)]
    (try
      (seed! sess)
      (testing "no :ns = every namespace's tests, ONE call"
        (let [r (api/test-run! sess nil)]
          (is (= 2 (:test r)))
          (is (zero? (+ (:fail r) (:error r))))))
      (testing "the single run traced BOTH namespaces' tests"
        (is (contains? (:test-map @sess) 'ta.a/a-t))
        (is (contains? (:test-map @sess) 'ta.b/b-t))
        (is (contains? (get (:test-map @sess) 'ta.b/b-t) 'ta.a/f)))
      (finally (api/close! sess)))))

(deftest ^:external query-eval-surfaces-errors                     ; F-3c2
  (let [sess (external/open!)]
    (try
      (seed! sess)
      (is (= [3] (api/query-eval sess "(ta.b/g 1)")))   ; values unchanged
      (testing "exceptions come back as {:error msg}, never a silent []"
        (is (re-find #"boom"
                     (:error (api/query-eval sess "(throw (ex-info \"boom\" {}))"))))
        (is (re-find #"(?i)wrong number of args"
                     (:error (api/query-eval sess "(ta.a/f)")))))
      (finally (api/close! sess)))))

(deftest ^:external references-cross-namespaces                    ; F-3c3
  (let [sess (external/open!)]
    (try
      (seed! sess)
      (let [refs (query/query-references sess 'ta.a 'f)]
        (is (some #(= 'ta.b (:from-ns %)) refs)
            "callers in OTHER namespaces must be visible")
        (is (some #(= 'ta.a (:from-ns %)) refs)))
      (finally (api/close! sess)))))

(deftest ^:external traceless-group-verification-covers-all-touched-nses ; F-3c5
  (let [sess (external/open!)]
    (try
      (seed! sess)
      ;; a reopened durable session has NO trace map — simulate that state
      (swap! sess assoc :test-map {})
      (let [r (api/edit-group! sess
                               [{:action :replace :ns 'ta.a :name 'f
                                 :source "(defn f [x] (- x))"}
                                {:action :replace :ns 'ta.b :name 'g
                                 :source "(defn g [x] (- x))"}]
                               :prompt "break both namespaces at once")
            failing (set (map :test (get-in r [:test :failures])))]
        (is (= 2 (+ (:fail (:test r) 0) (:error (:test r) 0)))
            "reds from EVERY touched namespace, not just the first step's")
        (is (= #{'ta.a/a-t 'ta.b/b-t} failing)))
      (finally (api/close! sess)))))
