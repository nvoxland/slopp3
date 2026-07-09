(ns slopp.ergonomics-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.edit :as edit]
            [slopp.api :as api]))

(deftest unparseable-source-returns-error-not-throw   ; F3
  (testing "pure gate"
    (let [r (edit/parse-form "(defn broken [x")]
      (is (:error r))
      (is (re-find #"unparseable" (:error r)))))
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'er.core "(ns er.core)\n(defn f [x] x)\n")
      (testing "add with unbalanced source: {:error}, nothing committed"
        (let [n (count (store/deltas (:store @sess)))
              r (api/add-form! sess 'er.core "(defn broken [x")]
          (is (:error r))
          (is (= n (count (store/deltas (:store @sess)))))))
      (testing "replace and ingest too"
        (is (:error (api/edit-replace! sess 'er.core 'f "(defn f [x")))
        (is (:error (api/ingest! sess 'er2.core "(ns er2.core"))))
      (testing "ingest returns a tidy map now, not the session atom (F8)"
        (let [r (api/ingest! sess 'er3.core "(ns er3.core)\n(def a 1)\n")]
          (is (= 'er3.core (:ns r)))
          (is (= 2 (:forms r)))))
      (finally (api/close! sess)))))

(deftest ingest-is-the-batch-write-for-NEW-namespaces   ; W1 (user decision)
  (let [sess (api/open!)]
    (try
      (testing "a whole namespace lands in one verified write"
        (let [r (api/ingest! sess 'w1.core
                             (str "(ns w1.core (:require [clojure.test :refer [deftest is]]))\n"
                                  "(defn triple [x] (* 3 x))\n"
                                  "(deftest triple-t (is (= 9 (triple 3))))\n"))]
          (is (= 3 (:forms r)))
          (is (= 1 (:pass (:test r))))
          (is (zero? (+ (:fail (:test r)) (:error (:test r)))))))
      (testing "red tests are reported (commit stands; compile failures don't commit)"
        (let [r (api/ingest! sess 'w1.red
                             (str "(ns w1.red (:require [clojure.test :refer [deftest is]]))\n"
                                  "(defn f [x] x)\n"
                                  "(deftest f-t (is (= 2 (f 1))))\n"))]
          (is (= 1 (:fail (:test r))))
          (is (seq (get-in r [:test :failures])))))
      (testing "overwriting an existing namespace is NOT allowed"
        (let [r (api/ingest! sess 'w1.core "(ns w1.core)\n(def replaced 1)\n")]
          (is (re-find #"already exists" (:error r)))
          (is (re-find #"triple" (api/query-source sess 'w1.core)))))
      (finally (api/close! sess)))))

(deftest create-ns-and-add-require                     ; F4 + F5
  (let [sess (api/open!)]
    (try
      (testing "create a namespace directly, with requires"
        (let [r (api/create-ns! sess 'fresh.core
                                :requires ["[clojure.test :refer [deftest is]]"])]
          (is (nil? (:error r)))
          (is (re-find #"\(ns fresh\.core" (api/query-source sess 'fresh.core)))
          (is (re-find #"clojure\.test" (api/query-source sess 'fresh.core)))))
      (testing "duplicate namespace rejected"
        (is (:error (api/create-ns! sess 'fresh.core))))
      (testing "add-require structurally extends the ns form and hot-reloads"
        (let [r (api/add-require! sess 'fresh.core "[clojure.string :as str]")]
          (is (nil? (:error r)))
          (is (re-find #"clojure\.string :as str" (api/query-source sess 'fresh.core)))
          ;; the alias is genuinely live in the image
          (api/add-form! sess 'fresh.core "(defn shout [s] (str/upper-case s))")
          (is (= ["HI"] (api/query-eval sess "(fresh.core/shout \"hi\")")))))
      (testing "duplicate require rejected"
        (is (:error (api/add-require! sess 'fresh.core "[clojure.string :as up]"))))
      (testing "ns without a :require clause gains one"
        (api/create-ns! sess 'bare.core)
        (let [r (api/add-require! sess 'bare.core "[clojure.set :as cset]")]
          (is (nil? (:error r)))
          (api/add-form! sess 'bare.core "(defn u [a b] (cset/union a b))")
          (is (= [#{1 2}] (api/query-eval sess "(bare.core/u #{1} #{2})")))))
      (finally (api/close! sess)))))

(deftest warnings-report-only-whats-new                ; T3
  (let [sess (api/open!)]
    (try
      (api/create-ns! sess 'w.core)
      (testing "first violation reported in full"
        (let [r (api/add-form! sess 'w.core "(defn stash [a v] (reset! a v))")]
          (is (= ['w.core/stash] (mapv :var (:warnings r))))))
      (testing "an unrelated green write doesn't repeat it — just counts it"
        (let [r (api/add-form! sess 'w.core "(defn pure-f [x] x)")]
          (is (empty? (:warnings r)))
          (is (= 1 (:existing-warnings r)))))
      (finally (api/close! sess)))))

(deftest failed-namespace-load-is-not-silently-committed   ; T4
  (let [sess (api/open!)]
    (try
      (testing "requiring a not-yet-created store ns fails loudly, nothing committed"
        (let [r (api/create-ns! sess 'dep.user :requires ["[dep.lib :as lib]"])]
          (is (:error r))
          (is (nil? (get-in (:store @sess) [:namespaces 'dep.user])))))
      (testing "after creating the dependency, it works"
        (api/create-ns! sess 'dep.lib)
        (is (nil? (:error (api/create-ns! sess 'dep.user
                                          :requires ["[dep.lib :as lib]"])))))
      (finally (api/close! sess)))))

(deftest query-eval-is-observe-only                    ; T5
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'g.core "(ns g.core)\n(defn f [x] x)\n")
      (testing "definitions and code mutation are rejected — use edit tools"
        (is (:error (api/query-eval sess "(def sneaky 1)")))
        (is (:error (api/query-eval sess "(in-ns 'g.core)")))
        (is (:error (api/query-eval sess "(ns-unmap 'g.core 'f)")))
        (is (:error (api/query-eval sess "(do (defn g [] 1) (g))"))))
      (testing "observation — including calling effectful fns — still works"
        (is (= [3] (api/query-eval sess "(g.core/f 3)")))
        (is (= [1] (api/query-eval sess "(let [a (atom 0)] (swap! a inc))"))))
      (finally (api/close! sess)))))
