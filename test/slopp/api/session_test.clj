(ns slopp.api.session-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.api :as api]
            [slopp.edit :as edit]
            [slopp.store :as store] [slopp.api.session :as session]))

(deftest ^:isolated heal-path-replays-candidate-namespaces
  ;; the extract_ns live failure: hot-load-all!'s heal boots a FRESH image
  ;; from the COMMITTED store, so a candidate that CREATES a namespace lost
  ;; it — the parent's (:require new-ns) then hit the classpath and
  ;; FileNotFound'd. The heal must replay the candidate's touched nses from
  ;; the CANDIDATE (dependency order, full load-ns! so *loaded-libs* is
  ;; stamped) before retrying.
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'hp.core "(ns hp.core)\n\n(defn top \"T.\" [x] x)\n")
      (let [st   (:store @sess)
            st1  (store/ingest st 'hp.core.impl
                               "(ns hp.core.impl)\n\n(defn helper \"H.\" [x] (inc x))\n")
            [st2 _] (store/replace-node
                     st1 'hp.core 'hp.core
                     (:node (edit/parse-form
                             "(ns hp.core (:require [hp.core.impl :as impl]))")))
            [st3 _] (store/replace-node
                     st2 'hp.core 'top
                     (:node (edit/parse-form
                             "(defn top \"T.\" [x] (impl/helper x))")))
            ;; parent decl FIRST: its require fails (new ns never image-loaded,
            ;; no *loaded-libs* stamp) → simulates any transient first-attempt
            ;; failure → the heal path must recover from the CANDIDATE
            ids  (into [(:id (store/form-named st3 'hp.core 'hp.core))
                        (:id (store/form-named st3 'hp.core 'top))]
                       (mapv :id (store/forms st3 'hp.core.impl)))
            r    (#'session/hot-load-all! sess st3 ids)]
        (is (:healed r) (pr-str r))
        (is (= [3] (api/query-eval sess "(hp.core/top 2)"))))
      (finally (api/close! sess)))))
(deftest isolated-among-splits-traced-tests-by-tier
  ;; done! already knows PRECISELY which tests a change reaches — that is what
  ;; the trace map is — but only the external tier can execute ^:isolated ones,
  ;; so the narrowed set must be split before it can be routed.
  ;;
  ;; Routing by require-closure instead (what done! did until #127) selects a
  ;; median 43 of 46 isolated test namespaces — measured over every source ns
  ;; 2026-07-17 — which blows the cap of 4 and defers 84.6% of the time. The
  ;; evidence was computed four lines above and thrown away.
  (let [st (-> (store/empty-store)
               (store/ingest 'z.core "(ns z.core)\n\n(defn f \"F.\" [x] x)\n")
               (store/ingest 'z.core-test
                             (str "(ns z.core-test (:require [z.core :as c]\n"
                                  "                          [clojure.test :refer [deftest is]]))\n\n"
                                  "(deftest fast-t (is (= 1 (c/f 1))))\n\n"
                                  "(deftest ^:isolated slow-t (is (= 2 (c/f 2))))\n")))]
    (testing "only the ^:isolated members come back — the rest already ran in-image"
      (is (= '[z.core-test/slow-t]
             (session/isolated-among st '[z.core-test/fast-t z.core-test/slow-t]))))
    (testing "an all-in-image set routes nowhere: there is nothing for the
              external tier to do, which is NOT the same as a silent trace"
      (is (empty? (session/isolated-among st '[z.core-test/fast-t]))))))
(deftest impacted-isolated-routes-the-traced-tests-and-admits-silence
  ;; done!'s isolated tier selected by require-closure until #127 — median 43 of
  ;; 46 isolated test nses (measured over every source ns 2026-07-17), which
  ;; blows the cap of 4 and defers 84.6% of the time. The trace already knows.
  ;;
  ;; The three-way answer matters more than the narrowing. nil and [] are NOT
  ;; the same: nil means the evidence is silent about some changed form and the
  ;; caller MUST fall back to the closure; [] means the evidence speaks and no
  ;; isolated test is impacted, so there is genuinely nothing to run.
  (let [st   (-> (store/empty-store)
                 (store/ingest 'z.core "(ns z.core)\n\n(defn f \"F.\" [x] x)\n\n(defn g \"G.\" [x] x)\n")
                 (store/ingest 'z.core-test
                               (str "(ns z.core-test (:require [z.core :as c]\n"
                                    "                          [clojure.test :refer [deftest is]]))\n\n"
                                    "(deftest fast-t (is (= 1 (c/f 1))))\n\n"
                                    "(deftest ^:isolated slow-t (is (= 2 (c/f 2))))\n")))
        sess (atom {:test-map {'z.core-test/fast-t #{'z.core/f}
                               'z.core-test/slow-t #{'z.core/f}}})
        fid  (:id (store/form-named st 'z.core 'f))
        gid  (:id (store/form-named st 'z.core 'g))]
    (testing "traced: only the ^:isolated half routes out — fast-t already ran in-image"
      (is (= '[z.core-test/slow-t] (session/impacted-isolated sess st [fid]))))
    (testing "ONE untraced form makes the whole answer nil — the caller falls back"
      (is (nil? (session/impacted-isolated sess st [gid])))
      (is (nil? (session/impacted-isolated sess st [fid gid]))))))
