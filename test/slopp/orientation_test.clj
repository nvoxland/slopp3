(ns slopp.orientation-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.api :as api]))

(deftest ^:isolated outline-and-namespaces                        ; T2
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'o.core
                   (str "(ns o.core (:require [clojure.test :refer [deftest is]]))\n"
                        "(defn pure-f\n  \"Adds one.\n  Slowly.\"\n  [x] (inc x))\n"
                        "(defn mut! [a] (swap! a inc))\n"
                        "(deftest t (is (= 2 (pure-f 1))))\n"))
      (api/ingest! sess 'o.util "(ns o.util)\n(defn helper [x] x)\n")
      (testing "query-namespaces: what exists, without knowing names up front"
        (is (= [{:ns 'o.core :forms 4} {:ns 'o.util :forms 2}]
               (api/query-namespaces sess))))
      (testing "query-outline: names + arities + !-status + test-ness + doc line"
        (let [o       (api/query-outline sess 'o.core :detail true)
              by-name (into {} (map (juxt :name identity)) (:forms o))]
          (is (= [1] (:arities (by-name 'pure-f))))
          (is (= "Adds one." (:doc (by-name 'pure-f))))
          (is (nil? (:effectful? (by-name 'pure-f))))
          (is (true? (:effectful? (by-name 'mut!))))
          (is (true? (:test? (by-name 't))))
          (is (nil? (:effectful? (by-name 't))))))    ; T1: tests aren't flagged
      (testing "query-project: the whole store's shape in ONE call, with :head"
        (let [p (api/query-project sess)]
          (is (string? (:head p)))
          (is (= '[o.core o.util] (mapv :ns (:namespaces p))))
          (is (some #(= 'mut! (:name %))
                    (:forms (first (filter #(= 'o.core (:ns %))
                                           (:namespaces p))))))))
      (testing "query-search: the missing grep, form-addressed"
        (let [hits (api/query-search sess "swap!")]
          (is (= 1 (count hits)))
          (is (= 'o.core (:ns (first hits))))
          (is (= 'mut! (:form (first hits)))))
        (is (= 2 (count (api/query-search sess "defn \\w+-f|helper"))))
        (is (empty? (api/query-search sess "nonexistent-thing")))
        (testing "bad regex is a clean error"
          (is (:error (api/query-search sess "([")))))
      (finally (api/close! sess)))))
(deftest ^:isolated batched-source-reads
  (let [sess (api/open!)]
    (try
      (api/create-ns! sess 'bq.a :source "(ns bq.a)\n(defn f [] 1)\n(defn g [] 2)\n")
      (api/create-ns! sess 'bq.b :source "(ns bq.b)\n(defn h [] 3)\n")
      (let [r (api/query-sources sess [{:ns 'bq.a :name 'f}
                                       {:ns 'bq.b}
                                       {:ns 'bq.a :name 'nope}
                                       {:ns 'bq.zz}])]
        (is (= "(defn f [] 1)" (:source (nth r 0))))
        (is (re-find #"defn h" (:source (nth r 1))))
        (is (:error (nth r 2)))
        (is (:error (nth r 3))))
      (finally (api/close! sess)))))
(deftest ^:isolated query-project-since
  (let [sess (api/open!)]
    (try
      (api/create-ns! sess 'qs.core :source "(ns qs.core)\n(defn f [] 1)\n")
      (let [head (:head (api/query-project sess))]
        (testing "unchanged structure is a one-liner"
          (is (= {:unchanged-since head :head head}
                 (select-keys (api/query-project sess :since head)
                              [:unchanged-since :head]))))
        (testing "a write invalidates it"
          (api/add-form! sess 'qs.core "(defn g [] 2)" :agent "t")
          (let [r (api/query-project sess :since head)]
            (is (nil? (:unchanged-since r)))
            (is (seq (:namespaces r))))))
      (finally (api/close! sess)))))
(deftest ^:isolated outline-is-compact-by-default
  (let [sess (api/open!)]
    (try
      (api/create-ns! sess 'oc.core
                      :source "(ns oc.core)\n(defn f\n  \"Adds one.\"\n  [x] (inc x))\n")
      (testing "no doc lines unless asked"
        (is (nil? (-> (api/query-outline sess 'oc.core) :forms first :doc)))
        (is (= "Adds one."
               (-> (api/query-outline sess 'oc.core :detail true) :forms first :doc))))
      (testing "query-project passes detail through"
        (is (nil? (-> (api/query-project sess) :namespaces first :forms first :doc)))
        (is (some? (-> (api/query-project sess :detail true)
                       :namespaces first :forms first :doc))))
      (finally (api/close! sess)))))
(deftest ^:isolated query-brief-is-the-one-call-dossier
  (let [sess (api/open!)]
    (try
      (api/create-ns! sess 'qb.a :source "(ns qb.a (:require [clojure.test :refer [deftest is]]))\n(defn f\n  \"Core fn.\"\n  [x] (inc x))\n(deftest f-t (is (= 2 (f 1))))\n")
      (api/create-ns! sess 'qb.b :source "(ns qb.b (:require [qb.a :as a]))\n(defn g [x] (a/f x))\n")
      (api/edit-replace! sess 'qb.a 'f "(defn f\n  \"Core fn.\"\n  [x] (+ x 1))" :prompt "prefer + for clarity" :agent "t")
      (api/test-run! sess nil)
      (let [b (api/query-brief sess 'qb.a 'f)]
        (is (re-find #"\+ x 1" (:source b)))
        (testing "cross-ns callers included"
          (is (some #(= 'qb.b (:from-ns %)) (:callers b))))
        (testing "covering tests from the trace map"
          (is (= ['qb.a/f-t] (:covered-by b))))
        (testing "the recorded why is in the dossier"
          (is (= "prefer + for clarity" (get-in b [:why :prompt])))))
      (testing "unknown form is an honest error"
        (is (:error (api/query-brief sess 'qb.a 'nope))))
      (finally (api/close! sess)))))
