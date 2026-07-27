(ns slopp.ui.client.api
  (:require [malli.core :as m]
            [malli.transform :as mt]
            slopp.ui.contracts))

(defonce ^:export base (atom ""))

(defn ^:export set-base! [b] (reset! base b))

(defn- url [p] (str @base p))

(defn ^{:generated "slopp.ui.api/namespaces"} ^:export namespaces
  "GET /api/namespaces — generated client wrapper (D-web-contracts)."
  []
  (-> (js/fetch (url "/api/namespaces") (clj->js {:method "GET"}))
      (.then (fn [resp] (.json resp)))
      (.then (fn [body]
               (let [data (m/decode slopp.ui.contracts/namespace-list (js->clj body :keywordize-keys true) (mt/json-transformer))]
                 (when-not (m/validate slopp.ui.contracts/namespace-list data)
                   (throw (ex-info "namespaces response failed validation"
                                   {:errors (m/explain slopp.ui.contracts/namespace-list data)})))
                 data)))))

(defn ^{:generated "slopp.ui.api/ns-outline"} ^:export ns-outline
  "GET /api/ns/:ns — generated client wrapper (D-web-contracts)."
  [params]
  (-> (js/fetch (url (str "/api/ns/" (:ns params))) (clj->js {:method "GET"}))
      (.then (fn [resp] (.json resp)))
      (.then (fn [body]
               (let [data (m/decode slopp.ui.contracts/ns-outline (js->clj body :keywordize-keys true) (mt/json-transformer))]
                 (when-not (m/validate slopp.ui.contracts/ns-outline data)
                   (throw (ex-info "ns-outline response failed validation"
                                   {:errors (m/explain slopp.ui.contracts/ns-outline data)})))
                 data)))))

(defn ^{:generated "slopp.ui.api/timeline"} ^:export timeline
  "GET /api/timeline — generated client wrapper (D-web-contracts)."
  []
  (-> (js/fetch (url "/api/timeline") (clj->js {:method "GET"}))
      (.then (fn [resp] (.json resp)))
      (.then (fn [body]
               (let [data (m/decode slopp.ui.contracts/timeline (js->clj body :keywordize-keys true) (mt/json-transformer))]
                 (when-not (m/validate slopp.ui.contracts/timeline data)
                   (throw (ex-info "timeline response failed validation"
                                   {:errors (m/explain slopp.ui.contracts/timeline data)})))
                 data)))))

(defn ^{:generated "slopp.ui.api/change"} ^:export change
  "GET /api/change/:range — generated client wrapper (D-web-contracts)."
  [params]
  (-> (js/fetch (url (str "/api/change/" (:range params))) (clj->js {:method "GET"}))
      (.then (fn [resp] (.json resp)))
      (.then (fn [body]
               (let [data (m/decode slopp.ui.contracts/change-view (js->clj body :keywordize-keys true) (mt/json-transformer))]
                 (when-not (m/validate slopp.ui.contracts/change-view data)
                   (throw (ex-info "change response failed validation"
                                   {:errors (m/explain slopp.ui.contracts/change-view data)})))
                 data)))))

(defn ^{:generated "slopp.ui.api/form"} ^:export form
  "GET /api/form/:id — generated client wrapper (D-web-contracts)."
  [params]
  (-> (js/fetch (url (str "/api/form/" (:id params))) (clj->js {:method "GET"}))
      (.then (fn [resp] (.json resp)))
      (.then (fn [body]
               (let [data (m/decode slopp.ui.contracts/form-view (js->clj body :keywordize-keys true) (mt/json-transformer))]
                 (when-not (m/validate slopp.ui.contracts/form-view data)
                   (throw (ex-info "form response failed validation"
                                   {:errors (m/explain slopp.ui.contracts/form-view data)})))
                 data)))))

(defn ^{:generated "slopp.ui.api/source"} ^:export source
  "GET /api/source/:ns/:name — generated client wrapper (D-web-contracts)."
  [params]
  (-> (js/fetch (url (str "/api/source/" (:ns params) "/" (:name params))) (clj->js {:method "GET"}))
      (.then (fn [resp] (.json resp)))
      (.then (fn [body]
               (let [data (m/decode slopp.ui.contracts/form-source (js->clj body :keywordize-keys true) (mt/json-transformer))]
                 (when-not (m/validate slopp.ui.contracts/form-source data)
                   (throw (ex-info "source response failed validation"
                                   {:errors (m/explain slopp.ui.contracts/form-source data)})))
                 data)))))

(defn ^{:generated "slopp.ui.api/modules"} ^:export modules
  "GET /api/modules — generated client wrapper (D-web-contracts)."
  []
  (-> (js/fetch (url "/api/modules") (clj->js {:method "GET"}))
      (.then (fn [resp] (.json resp)))
      (.then (fn [body]
               (let [data (m/decode slopp.ui.contracts/module-index (js->clj body :keywordize-keys true) (mt/json-transformer))]
                 (when-not (m/validate slopp.ui.contracts/module-index data)
                   (throw (ex-info "modules response failed validation"
                                   {:errors (m/explain slopp.ui.contracts/module-index data)})))
                 data)))))
