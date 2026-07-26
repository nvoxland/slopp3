(ns slopp.api.rules.markers-test
  "Cover for the marker registry.

  Testing a registry is mostly testing that it still describes reality, so the
  load-bearing tests here ask the STORE rather than the list — is every marker
  in use declared, does anything fall between this registry and the crossings
  one. The coherence checks (well-formed entries, only escapes police
  themselves) are cheap and catch a typo; the store-facing ones catch the
  failure that actually happens.

  Split by tier for a reason: the coherence tests need nothing, the
  completeness test needs the WHOLE store and so is `^:external`, reaching it
  through `built-store`."
  (:require [clojure.test :refer [deftest testing is]]
            [slopp.store :as store]
            [slopp.api.rules.markers :as markers] [slopp.api.external :as external]))

(deftest the-marker-registry-is-coherent
  (testing "every entry is well-formed — a registry with holes teaches wrong"
    (doseq [{:keys [marker kind on discharges asks self-polices?] :as m} markers/marker-registry]
      (is (keyword? marker) (pr-str m))
      (is (contains? #{:escape :declaration :internal} kind) (pr-str m))
      (is (contains? #{:name :form} on) (pr-str m))
      (is (or (nil? discharges) (keyword? discharges)) (pr-str m))
      (is (or (nil? asks) (string? asks)) (pr-str m))
      (is (boolean? self-polices?) (pr-str m))))
  (testing "markers are declared once"
    (let [ms (map :marker markers/marker-registry)]
      (is (= (count ms) (count (distinct ms))))))
  (testing "only an ESCAPE can police itself"
    ;; a declaration asserts something slopp cannot derive, so it has nothing
    ;; to be checked against — ^:entry-point has no stale symmetry because the
    ;; outside world is unverifiable
    (doseq [{:keys [kind self-polices?] :as m} markers/marker-registry]
      (when self-polices? (is (= :escape kind) (pr-str m)))))
  (testing "asking for a WHY is what marker-why keys off, so it must be derivable"
    (is (seq (markers/asking)) "no marker asks for a reason — marker-why has nothing to do")
    (is (every? :asks (markers/asking)))
    (is (every? #(= :name (:on %)) (markers/asking))
        "the check reads NAME metadata, so a :form marker asking for a why
         would be a question nothing can see")
    (testing "and the questions nothing can ask are NAMED, not omitted"
      ;; a gap written down is a backlog item; a gap merely absent from a list
      ;; is indistinguishable from a decision
      (is (= [:unsafe] (mapv :marker (markers/asking-unenforced)))
          "^:unsafe should carry a why and sits on the FORM, which no current
           check can read — if this set grows, the check needs to grow with it"))))

(deftest the-registry-describes-the-STORE-not-a-wish
  ;; The failure mode of any registry: it describes the system it was written
  ;; against. Both directions have to hold, and the second is the one that
  ;; catches the marker somebody adds next year.
  (let [st (store/ingest (store/empty-store) 'mr.core
                         (str "(ns mr.core)\n"
                              "(defn ^:unused-ok a \"A.\" [x] x)\n"
                              "(defn ^{:entry-point \"the CLI\"} b \"B.\" [x] x)\n"))]
    (testing "a marker the registry knows is recognised as slopp's"
      (is (markers/known? :unused-ok))
      (is (markers/known? :entry-point)))
    (testing "a marker nobody declared is NOT slopp's, whatever it looks like"
      (is (not (markers/known? :looks-official-ok)))
      (is (not (markers/known? :web/websocket))))
    (testing "the store's markers in use are reported, so drift is visible"
      (is (= '#{:unused-ok :entry-point} (markers/in-use st))))
    (testing "and an UNDECLARED marker in use comes back as the finding"
      (let [st2 (store/ingest st 'mr.other
                              "(ns mr.other)\n(defn ^:invented-ok c \"C.\" [x] x)\n")]
        (is (= #{:invented-ok} (markers/undeclared st2)))
        (is (= #{} (markers/undeclared st))
            "and a clean store reports an empty set, not nil")))))

(deftest ^:external slopps-own-store-declares-every-marker-it-uses
  ;; The registry's first cut missed three markers that were in live use
  ;; (`:external`, `:live-handle`, `:teach`) and `undeclared` found all three
  ;; the moment it existed. This is the standing version of that check: the
  ;; store slopp ships is the one population the registry has no excuse to be
  ;; wrong about.
  ;;
  ;; External because it needs the WHOLE store, which the in-image tier cannot
  ;; reach — `built-store` reconstructs it from the materialized project.
  (let [st (external/built-store)]
    (testing "there is a population — this is the vacuity that ate a sibling guard"
      (is (< 50 (count (:namespaces st))))
      (is (seq (markers/in-use st))))
    (testing "every marker slopp's own code uses is declared"
      (is (= #{} (markers/undeclared st))
          "an undeclared marker is either a new dial nobody registered or a
           typo of a real one — both silently do nothing"))))
