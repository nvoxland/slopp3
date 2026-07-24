(ns slopp.api.review-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.api.review :as review]))

(deftest review-scan-surfaces-ns-form-lint
  ;; Lint findings INSIDE the (ns …) form — dead imports, unused requires — have
  ;; no named form to hang off, so review-scan dropped them from :top/:totals and
  ;; reported "nearly lint-clean" while missing exactly the declarations a
  ;; refactor strands (slopp.git had 16 dead imports invisible to it). They now
  ;; surface per namespace in :ns-lint.
  (let [st   (-> (store/empty-store)
                 (store/ingest 'rv.core "(ns rv.core (:require [clojure.set :as s]))\n(defn f [] 1)\n"))
        r    (review/review-scan (atom {:store st :test-map {}}) :ns 'rv.core)]
    (testing "an unused require in the ns form is reported, per namespace"
      (is (= {'rv.core 1} (:ns-lint r)) (pr-str r)))
    (testing "the finding does NOT masquerade as a named-form :lint row"
      (is (nil? (get (:totals r) :lint)) (pr-str r))))
  (testing "a namespace whose requires are all used has no :ns-lint entry"
    (let [st2 (-> (store/empty-store)
                  (store/ingest 'rv.clean "(ns rv.clean (:require [clojure.set :as s]))\n(defn f [] (s/union #{} #{}))\n"))
          r2  (review/review-scan (atom {:store st2 :test-map {}}) :ns 'rv.clean)]
      (is (nil? (get (:ns-lint r2) 'rv.clean)) (pr-str r2)))))

(deftest review-scan-honours-declared-coverage
  ;; A form reached only through dispatch has no static caller and no trace, so
  ;; review-scan flags it :untested — a finding a ^{:covers} declaration is
  ;; exactly meant to discharge. Declared coverage (the graph's :covers edges)
  ;; now counts as coverage here, so the form is not untested. It is still
  ;; :unused (coverage is not liveness) — the two flags stay independent.
  (let [st (-> (store/empty-store)
               (store/ingest 'rv.disp "(ns rv.disp)\n(defn handler [x] x)\n")
               (store/ingest 'rv.disp-test
                             (str "(ns rv.disp-test (:require [clojure.test :refer [deftest is]]))\n"
                                  "(deftest ^{:covers \"rv.disp/handler — via dispatch\"} cover-t (is true))\n")))
        r    (review/review-scan (atom {:store st :test-map {}}) :ns 'rv.disp)
        row  (first (filter #(= 'rv.disp/handler (:form %)) (:top r)))]
    (testing "a declared-covered form is not flagged :untested"
      (is (some? row) (pr-str r))
      (is (not (contains? (set (:flags row)) :untested)) (pr-str row)))
    (testing "declared coverage is not liveness — the form is still :unused"
      (is (contains? (set (:flags row)) :unused) (pr-str row)))))

(deftest review-scan-ignores-marked-side-effect-requires
  ;; A require the done-point kept (marked ^:side-effect because removing it
  ;; breaks a cold load) is deliberately present — it must NOT read as unused.
  ;; kondo still flags it :unused-namespace; review_scan suppresses that.
  (let [marked (-> (store/empty-store)
                   (store/ingest 'rv.se
                                 "(ns rv.se (:require ^:side-effect [rv.dep :as d]))\n(defn f [] 1)\n"))
        plain  (-> (store/empty-store)
                   (store/ingest 'rv.un
                                 "(ns rv.un (:require [rv.dep :as d]))\n(defn f [] 1)\n"))]
    (testing "a ^:side-effect-marked unused require is not counted in :ns-lint"
      (is (nil? (get (:ns-lint (review/review-scan (atom {:store marked :test-map {}}) :ns 'rv.se))
                     'rv.se))))
    (testing "the same require WITHOUT the marker is still flagged"
      (is (= 1 (get (:ns-lint (review/review-scan (atom {:store plain :test-map {}}) :ns 'rv.un))
                    'rv.un))))))
