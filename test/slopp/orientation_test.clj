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
        (let [o       (api/query-outline sess 'o.core)
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
