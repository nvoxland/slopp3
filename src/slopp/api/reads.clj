(ns slopp.api.reads
  "Read-only store browser: server-rendered hiccup pages over the query
  surfaces — the D-web-html dogfood. Plain links, full-page renders, zero
  writes; rendering arbitrary store source through the escaper is a
  standing security exercise.

  Lives in slopp.api.**, slopp's OWN webapp, and never in slopp.web.** —
  slopp.web is the framework every user's app is built on and ships in the
  slim jar, so an app page placed there would ride into every user's
  application. The dependency runs slopp.api → slopp.web, never back.

  Pages hold hiccup and nothing else; the data they render is assembled by
  slopp.api.model, which is where a static JSON sink would attach."
  (:require [rewrite-clj.node :as n]
            [slopp.store :as store]
            [slopp.api.model :as model] [clojure.string :as str] [slopp.web.contract :as contract] [slopp.edit.modules :as modules] [slopp.edit.tiers :as tiers]))

(defn ^{:web/read :browse/namespaces} namespaces-read
  "Read performer: `{:ns sym :forms n}` rows for every namespace, sorted."
  [{:keys [session]} _]
  (let [st (:store @session)]
    (mapv (fn [nsx] {:ns nsx :forms (count (filter :name (store/forms st nsx)))})
          (sort (keys (:namespaces st))))))

(defn ^{:web/read :browse/form-source} form-source-read
  "Read performer: one form's source text, or nil when the form is unknown."
  [{:keys [session]} {:keys [ns name]}]
  (let [st (:store @session)]
    (when-let [e (store/form-named st (symbol (str ns)) (symbol (str name)))]
      (n/string (:node e)))))

(defn ^{:web/read :ui/timeline} timeline-read
  "Read performer: the reviewer landing model — milestones plus the
  working set."
  [{:keys [session]} _]
  (model/timeline session))

(defn ^{:web/read :ui/change} change-read
  "Read performer: the review of one `from..to` range, or nil when the
  range is malformed or names deltas that do not exist — the page needs
  those to be the same answer, since both are a 404."
  [{:keys [session]} range-str]
  (let [[from to] (str/split (str range-str) #"\.\." 2)]
    (when (and (seq from) (seq to))
      (model/change-view session from to))))

(defn ^{:web/read :ui/form} form-view-read
  "Read performer: one form's page model by ID, at the requested rendering
  FIDELITY and call-graph DEPTH. Addressed by BOTH halves of the URL — the id
  from the path, `?view=` and `?depth=` from the query — so it is declared
  over the whole request rather than one segment.

  nil when no form has that id, or when the fidelity does not exist. Both are
  a 404, and neither is a reason to render the other thing.

  An unreadable or absent `?depth=` is 1, NOT a 404, and the asymmetry with
  `?view=` is deliberate: an unknown fidelity has no right answer, so serving
  a different notation would be lying; depth has an obviously correct floor,
  and 1 is exactly what every link written before the parameter existed
  meant. The model clamps the ceiling."
  [{:keys [session]} {:keys [path-params query-params]}]
  (model/form-view session (str (:id path-params)) (:view query-params)
                   (or (parse-long (str (:depth query-params))) 1)))

(defn ^{:web/read :browse/modules} modules-read
  "Read performer: the architecture as module rows plus a drawable canvas.

  Named `:browse/modules` to sit beside `:browse/namespaces` — reads are
  addressed by VOCABULARY rather than by var, so any second representation
  of the architecture shares this one answer instead of re-deriving it."
  [{:keys [session]} _]
  (model/module-index session))

(defn ^{:web/read :ui/contract} contract-read
  "The shape of this app's own API, for a consumer that generates a typed
  client against it.

  The namespace list arrives through `perform-ctx` rather than being reached
  for here: only the SERVER knows what it serves, and a performer that imported
  that list would invert the dependency (slopp.api.server already requires this
  namespace). It is data on the way in, like every other dep."
  [ctx _]
  (contract/contract-document (:served-namespaces ctx)))

(defn- form-doc
  "A form's docstring, or nil — through `store/form-docstring`, which is the
  only thing that knows when index 2 is a docstring and when it is a `def`'s
  VALUE.

  It read index 2 directly and took any string it found, so
  `(def greeting \"hello\")` rendered \"hello\" as the form's documentation.
  Wrong-index reads do not throw; they return something plausible, which is
  why this class keeps surviving review."
  [e]
  (store/form-docstring (:node e)))

(defn- form-shape
  "What a SOURCE-shaped listing needs beyond name and doc: the kind of form,
  the arg vector of each arity, whether it is private, and any declared schema.

  `:kind` is the head symbol as WRITTEN (`defn` / `defn-` / `def` / `deftest`
  / `ns`), because a pane laid out like source states things as fact, and a
  value drawn as though it were callable is a false one.

  `:sig` comes from `modules/fn-arglists`, and the `kind` guard beside the call
  is load-bearing rather than tidy: `fn-arglists` reads position 2+ of whatever
  it is handed, so `(def geometry [1 2 3])` comes back claiming a one-arg
  signature. Its contract is a `defn` sexpr and the CALLER owes it that.

  Stated precisely because the imprecise version cost something. This docstring
  used to say fn-arglists 'knows a `def` has no arities'; it does not, the
  guard below does. A later reader took the claim at face value, called
  `fn-arglists` unguarded, and shipped `:sig`'s FOURTH producer with the bug
  the other three had — caught on a live listener advertising a registry's
  value as its signature.

  It is the same wrong-index read `form-doc` above had to be rescued from:
  index 2 is a docstring in a `defn` and a VALUE in a `def`, and neither
  mistake throws. They return something plausible, which is why the class keeps
  surviving review.

  nil rather than `[]` for a missing signature, since `[]` is a real
  zero-arity. `:private?` is always a boolean: absent and public would render
  identically, and only one of those is a finding."
  [e]
  (let [s    (store/form-sexpr (:node e))
        head (when (seq? s) (first s))
        kind (str head)
        nm   (when (seq? s) (second s))
        ;; matched as TEXT rather than as quoted symbols: the dialect denylist
        ;; reads a banned symbol anywhere in a form as a USE of it, including
        ;; inside a set whose whole job is to RECOGNISE one.
        args (when (#{"defn" "defn-" "defmacro"} kind) (modules/fn-arglists s))]
    {:kind     kind
     :sig      (when (seq args) (mapv pr-str args))
     :private? (boolean (or (= "defn-" kind) (:private (meta nm))))
     :schema   (some-> (:malli/schema (meta nm)) pr-str)}))

(defn ^{:web/read :browse/ns-outline} ns-outline-read
  "Read performer: one namespace's form rows in store order — name, doc,
  shape, and the facts a consumer needs to rank them — plus the test
  namespaces covering it, or nil for an unknown namespace.

  `outline-metrics` is called ONCE for the namespace and looked up per row.
  The reverse reference index it reads is a whole-store grouping; asking it
  per form would be quadratic in a namespace's size, which is exactly the
  shape `refs-by-target` was introduced to retire."
  [{:keys [session]} nsx]
  (let [st  (:store @session)
        sym (symbol (str nsx))]
    (when (contains? (:namespaces st) sym)
      (let [metrics (model/outline-metrics st sym)]
        {:ns sym
         :forms (into []
                      (keep (fn [e]
                              (when (:name e)
                                (merge (form-shape e)
                                       (get metrics (str (:name e)))
                                       {:name    (:name e)
                                        :doc     (form-doc e)
                                        ;; the ADDRESS, so a row can link to
                                        ;; the form page rather than to source
                                        :form-id (:id e)}))))
                      (store/forms st sym))
         :tested-by (model/tests-covering st sym)
         ;; NAMESPACE grain, deliberately not a row field. A row's
         ;; `:effectful?` says what THAT form does; the tier says what this
         ;; namespace is ALLOWED to do, and the two disagree constantly — a
         ;; namespace with permission to do IO is mostly pure functions.
         ;; Repeated per row it would state one fact N times and read as a
         ;; form fact, which is the mistake it exists to prevent.
         ;; Always present: undeclared resolves to :external, so there is no
         ;; "nobody said" for an absent key to mean.
         :tier (name (tiers/tier-for st sym))
;; NAMESPACE grain like :tier, and for the same reason: the module
         ;; rollup answers "which box is thin", this answers "and where in it".
         ;; A consumer holding only the rollup would have to sum the rows it is
         ;; already rendering, which is the arithmetic this exists to save.
         :gaps (get (model/gaps-by-ns st (:test-map @session)) sym)}))))

(defn ^{:web/read :browse/module} module-detail-read
  "Read performer: one module from the inside — its production namespaces,
  the ns→ns edges among them, the layering, and the boundary crossings; nil
  for a module with no production namespaces.

  `:browse/module` beside `:browse/modules`, singular against plural, because
  they are the same subject at two grains and a reader following one to the
  other should not have to learn a second vocabulary."
  [{:keys [session]} m]
  (model/module-detail session m))

(defn ^{:web/read :browse/search} search-read
  "Read performer: everything matching `?q=`, ranked across modules,
  namespaces and forms, cut to `?limit=`.

  Declared over the WHOLE request rather than one segment, like `:ui/form`:
  both parameters travel in the query string, and neither is optional to the
  performer even though both are optional to the caller — a missing `q` is the
  empty state and a missing `limit` is the declared default, and the model
  answers each rather than the route.

  `:browse/search` sits beside `:browse/module` and `:browse/modules`: it is
  the same subject matter reached by asking rather than by descending, and a
  reader following one to the other should not have to learn a second
  vocabulary."
  [{:keys [session]} {:keys [query-params]}]
  ;; an unreadable limit falls back to the declared default rather than
  ;; refusing: a row budget has an obvious right answer, the same stance
  ;; `?depth=banana` takes one endpoint over
  (let [limit (when (re-matches #"\d+" (str (:limit query-params)))
                (parse-long (str (:limit query-params))))]
    (model/search (:store @session) (:q query-params) limit)))
