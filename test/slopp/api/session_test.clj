(ns slopp.api.session-test
  (:require [clojure.test :refer [deftest is]]
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
