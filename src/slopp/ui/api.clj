(ns slopp.ui.api
  (:require [slopp.ui.contracts :as contracts]))

(defn ^{:web/method :get :web/path "/api/namespaces" :web/auth :public
        :web/response contracts/namespace-list
        :web/reads {:namespaces [:browse/namespaces []]}}
  namespaces
  "GET /api/namespaces — every namespace with its form count, sorted.

  Reuses the `:browse/namespaces` read the HTML page already declares. Reads
  are addressed by VOCABULARY rather than by var, so the performer is shared
  store-wide and this endpoint cannot drift from what `/store` renders — one
  answer, two representations.

  `:ns` is stringified here because the wire is JSON and JSON has no symbols.
  Doing it at the boundary rather than in the read keeps the read useful to
  the HTML side, which wants the symbol."
  [req]
  {:status 200
   :body (mapv (fn [{:keys [ns forms]}] {:ns (str ns) :forms forms})
               (:namespaces (:web/reads req)))})

(defn ^{:web/method :get :web/path "/api/ns/:ns" :web/auth :public
        :web/response contracts/ns-outline
        :web/reads {:outline [:browse/ns-outline [:path-params :ns]]}}
  ns-outline
  "GET /api/ns/:ns — one namespace's forms in store order.

  An unknown namespace is a 404 rather than an empty outline: `{:forms []}`
  would say the namespace exists and holds nothing, which is a different
  statement and a false one. The read already returns nil for the unknown
  case, so the distinction costs a `when-let`."
  [req]
  (if-let [{:keys [ns forms]} (:outline (:web/reads req))]
    {:status 200
     :body {:ns (str ns)
            :forms (mapv (fn [{:keys [name doc]}]
                           {:name (str name) :doc doc})
                         forms)}}
    {:status 404 :body {:error "no such namespace"}}))

(defn ^{:web/method :get :web/path "/api/timeline" :web/auth :public
        :web/response contracts/timeline
        :web/reads {:timeline [:ui/timeline []]}}
  timeline
  "GET /api/timeline — milestones newest first, plus the working set.

  A projection, not new logic: `slopp.ui.model/timeline` already returns a
  JSON-shaped value, which is why the SPA rewrite is mostly moving rendering
  rather than inventing data."
  [req]
  {:status 200 :body (:timeline (:web/reads req))})

(defn ^{:web/method :get :web/path "/api/change/:range" :web/auth :public
        :web/response contracts/change-view
        :web/reads {:change [:ui/change [:path-params :range]]}}
  change
  "GET /api/change/:range — one milestone reviewed, `from..to`.

  A range arrives from a URL, so both ends are user input. The read already
  separates \"nothing changed here\" from \"that is not a range\", and only the
  second is a 404."
  [req]
  (if-let [c (:change (:web/reads req))]
    {:status 200 :body c}
    {:status 404 :body {:error "no such change range"}}))

(defn ^{:web/method :get :web/path "/api/form/:id" :web/auth :public
        :web/response contracts/form-view
        :web/reads {:view [:ui/form []]}}
  form
  "GET /api/form/:id — one form's permalink model, at the requested
  rendering FIDELITY (`?view=`).

  Declared over the WHOLE request rather than one segment, because it is
  addressed by both halves of the URL. An unknown id and an unknown fidelity
  are the same answer — 404 — and neither is a reason to quietly render the
  other thing."
  [req]
  (if-let [v (:view (:web/reads req))]
    {:status 200 :body v}
    {:status 404 :body {:error "no such form"}}))

(defn ^{:web/method :get :web/path "/api/source/:ns/:name" :web/auth :public
        :web/response contracts/form-source
        :web/reads {:source [:browse/form-source [:path-params]]}}
  source
  "GET /api/source/:ns/:name — one form's source text.

  The text arrives as a STRING and is escaped by the client when it renders.
  Serving arbitrary store source safely is the standing security dogfood
  here, and moving the render to the browser does not retire it — it moves
  it to the one place that must never build markup by concatenation."
  [req]
  (let [{:keys [ns name]} (:path-params req)]
    (if-let [src (:source (:web/reads req))]
      {:status 200 :body {:ns (str ns) :name (str name) :source src}}
      {:status 404 :body {:error "no such form"}})))
