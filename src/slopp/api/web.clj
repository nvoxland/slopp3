(ns slopp.api.web
  (:require [slopp.api.capabilities :as capabilities]
            [slopp.edit.modules :as modules]))

(defn endpoints
  "Every declared endpoint in the store — a `:web/path` form's route row:
  `{:handler :ns :name :form-id :method :path :auth :web/effects :web/reads
  :schema? :effectful?}` (slopp's own vocabulary keys stay namespaced —
  the same rule the request envelope follows). Built on the SAME traversal
  the write gates check (`modules/web-endpoint-rows`), so what query_routes
  shows is what the gates enforced. A pure function of the store value."
  [store]
  (mapv (fn [{:keys [ns name form-id meta]}]
          {:handler   (symbol (str ns) (str name))
           :ns        ns
           :name      name
           :form-id   form-id
           :method    (:web/method meta)
           :path      (str (:web/path meta))
           :auth      (:web/auth meta)
           :web/effects (:web/effects meta)
           :web/reads   (:web/reads meta)
           :schema?   (contains? meta :malli/schema)
           :effectful? (boolean (:web/effectful meta))})
        (modules/web-endpoint-rows store)))

(defn performers
  "The app-defined performer vocabulary for `marker-key` (`:web/effect` or
  `:web/read`): {kind → performer qsym}. Delegates to the SAME derivation
  the undeclared-effect gate checks (`modules/web-performers`)."
  [store marker-key]
  (modules/web-performers store marker-key))

(defn routes-report
  "The `query_routes` payload. `http.enabled` false → `{:enabled false
  :routes [] :note …}` — a store that never opted into HTTP has no web
  surface and no web rules (the adoption story). Enabled → every endpoint
  row (`endpoints`) plus the derived performer vocabularies
  (`:effect-kinds` / `:read-kinds`)."
  [store]
  (if-not (capabilities/effective store "http.enabled")
    {:enabled false :routes []
     :note (str "http.enabled is false — config_file {path \"capabilities\" "
                "key \"http.enabled\" value \"true\"} opts this store into HTTP")}
    {:enabled true
     :routes (endpoints store)
     :effect-kinds (set (keys (performers store :web/effect)))
     :read-kinds (set (keys (performers store :web/read)))}))
