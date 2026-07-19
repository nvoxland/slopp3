(ns slopp.effect-boundary-test
  "External deps M3: a call into an opaque Tier-1 dependency is treated as
  EFFECTFUL by default (worst-case — Koka io-top / gradual 'unknown = top'),
  because slopp can't see the dep's body. Narrowable by marking the dep var
  `:pure`. Store/stdlib calls are unaffected. Warnings, never rejections."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.api :as api]
            [slopp.edit :as edit])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir []
  (str (Files/createTempDirectory "slopp-eff-test" (make-array FileAttribute 0))))

(defn- warns-about? [sess ns-sym nm]
  (some #(re-find (re-pattern (str "\\b" nm "\\b")) (str %))
        (edit/ns-warnings (:store @sess) ns-sym)))

(deftest ^:isolated external-dep-call-is-effectful-by-default
  (let [sess (api/open! {:slopp.api/dir (temp-dir)})]     ; durable → surface is cached
    (try
      (api/deps-add! sess 'org.clojure/data.json {:mvn/version "2.5.0"}
                     :agent "a")
      ;; dump calls a NON-bang external var (json/write-str) — slopp can't see
      ;; its body, so dump is effectful and should be named dump!
      (api/ingest! sess 'ex.core
                   (str "(ns ex.core (:require [clojure.data.json :as json]))\n\n"
                        "(defn dump [x] (json/write-str x))\n"))
      (testing "the external call makes the caller effectful (a !-name warning)"
        (is (warns-about? sess 'ex.core 'dump)
            (pr-str (edit/ns-warnings (:store @sess) 'ex.core))))
      (testing "marking the dep var :pure narrows it — no more warning"
        (api/deps-pure! sess 'clojure.data.json/write-str :agent "a")
        (is (not (warns-about? sess 'ex.core 'dump))))
      (testing "the :pure annotation persists (delta + reopen)"
        (is (contains? (:dep-pure (:store @sess)) 'clojure.data.json/write-str)))
      (finally (api/close! sess)))))

(deftest ^:isolated pure-narrows-at-namespace-and-lib-granularity   ; M3 coarser :pure
  ;; slopp is built on wholesale-pure libs (rewrite-clj, clj-kondo); marking
  ;; every var pure one call at a time floods self-host code with warnings, so
  ;; :pure also lands at namespace and whole-dep granularity.
  (let [sess (api/open! {:slopp.api/dir (temp-dir)})]
    (try
      (api/deps-add! sess 'org.clojure/data.json {:mvn/version "2.5.0"}
                     :agent "a")
      (api/ingest! sess 'ex.core
                   (str "(ns ex.core (:require [clojure.data.json :as json]))\n\n"
                        "(defn dump [x] (json/write-str x))\n"))
      (is (warns-about? sess 'ex.core 'dump))
      (testing "marking the whole NAMESPACE pure narrows every var in it"
        (api/deps-pure! sess 'clojure.data.json :agent "a")
        (is (not (warns-about? sess 'ex.core 'dump)))
        (is (contains? (:dep-pure (:store @sess)) 'clojure.data.json)))
      (testing "un-pure at namespace granularity restores the warning"
        (api/deps-unpure! sess 'clojure.data.json :agent "a")
        (is (warns-about? sess 'ex.core 'dump)))
      (testing "marking the whole LIB pure expands to its provided namespaces"
        (let [r (api/deps-pure! sess 'org.clojure/data.json :agent "a")]
          (is (= 'org.clojure/data.json (:lib r)))
          (is (contains? (set (:namespaces r)) 'clojure.data.json)))
        (is (not (warns-about? sess 'ex.core 'dump)))
        (is (contains? (:dep-pure (:store @sess)) 'clojure.data.json)))
      (finally (api/close! sess)))))

(deftest ^:isolated reads-suppresses-the-effect-name-warning   ; per-form !-effect override
  ;; A fn that READS through an effectful-by-default external dep is flagged
  ;; effectful (should be `!`). `^:reads` asserts it is a READ, not a mutation,
  ;; so it takes no bang — the Clojure norm (slurp/deref/a SELECT read no bang).
  ;; Greppable + self-limiting, like `^:unsafe` for the dialect gate.
  (let [sess (api/open! {:slopp.api/dir (temp-dir)})]
    (try
      (api/deps-add! sess 'org.clojure/data.json {:mvn/version "2.5.0"} :agent "a")
      (api/ingest! sess 'rd.core
                   (str "(ns rd.core (:require [clojure.data.json :as json]))\n\n"
                        "(defn peek-json [x] (json/read-str x))\n"))
      (testing "a read through an external dep is flagged effectful by default"
        (is (warns-about? sess 'rd.core 'peek-json)))
      (testing "^:reads clears the naming warning"
        (api/edit-replace! sess 'rd.core 'peek-json
                           "^:reads\n(defn peek-json [x] (json/read-str x))")
        (is (not (warns-about? sess 'rd.core 'peek-json))))
      (testing "query_symbol surfaces :reads? (greppable), form still addressable"
        (let [q (api/query-symbol sess 'rd.core 'peek-json)]
          (is (:reads? q))
          (is (= 'peek-json (:name q)))))
      (finally (api/close! sess)))))

(deftest ^:isolated store-and-stdlib-calls-are-not-external
  (let [sess (api/open! {:slopp.api/dir (temp-dir)})]
    (try
      (api/deps-add! sess 'org.clojure/data.json {:mvn/version "2.5.0"}
                     :agent "a")
      ;; pure fn using only clojure.core/clojure.string + a store call
      (api/ingest! sess 'ex.pure
                   (str "(ns ex.pure (:require [clojure.string :as s]))\n\n"
                        "(defn shout [x] (s/upper-case (str x)))\n"))
      (testing "a stdlib-only fn is NOT flagged effectful"
        (is (not (warns-about? sess 'ex.pure 'shout))))
      (finally (api/close! sess)))))

(deftest ^:isolated dep-namespaces-persist-and-reopen
  (let [dir (temp-dir)]
    (let [sess (api/open! {:slopp.api/dir dir})]
      (try
        (api/deps-add! sess 'org.clojure/data.json {:mvn/version "2.5.0"}
                       :agent "a")
        (api/deps-pure! sess 'clojure.data.json/write-str :agent "a")
        (is (contains? (get (:dep-ns (:store @sess))
                            'org.clojure/data.json)
                       'clojure.data.json))
        (finally (api/close! sess))))
    (testing "a reopened session reconstructs :dep-ns and :dep-pure"
      (let [s2 (api/open! {:slopp.api/dir dir})]
        (try
          (is (contains? (get (:dep-ns (:store @s2)) 'org.clojure/data.json)
                         'clojure.data.json))
          (is (contains? (:dep-pure (:store @s2))
                         'clojure.data.json/write-str))
          (finally (api/close! s2)))))))
