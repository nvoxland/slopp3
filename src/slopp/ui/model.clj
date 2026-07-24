(ns slopp.ui.model
  "The reviewer UI's READ MODELS: JSON-shaped data assembled from the
  operation API's pure surfaces. No hiccup, no HTTP, no writes.

  Three models, one per screen — `timeline` (the landing page), 
  `change-view` (what happened between two milestones) and `form-view`
  (one form's permalink).

  **JSON-shaped is a rule, not a style.** Every value here survives a JSON
  round trip: keyword keys, vectors rather than lists or sets, and no
  symbols — a qualified symbol reads back as a string and silently stops
  being a reference, so symbols and argument vectors become text exactly
  once, here. That is what keeps a static sink (dump the models, render
  them somewhere else) a later addition rather than a rewrite, and it is
  pinned by `json-shaped?` in the specs rather than left to discipline.

  :pure, and it earns it: everything comes from the pure deep namespaces of
  slopp.api. Reaching for `slopp.api` itself — which opens the db — is a
  core→shell dependency full_check's tier-layering check refuses."
  (:require [slopp.store :as store]
            [slopp.api.query :as query]
            [slopp.api.history :as history] [slopp.edit.modules :as modules] [slopp.edit.refs :as refs] [slopp.api.orient :as orient] [rewrite-clj.node :as n]))

(defn ^:export timeline
  "The reviewer landing model: milestones newest first, each carrying the
  `from..to` range that addresses its own change screen, plus the WORKING
  SET — what has been written since the newest milestone.

  The range is computed here rather than in the page so a template stays a
  template: a milestone's range runs from the milestone BEFORE it, and the
  oldest milestone has no `:range` at all rather than an empty one.

  Deliberately not `query-changes`: this page shows counts and recorded
  asks, never sources, and `query-changes` reconstructs the before/after
  text of every touched form to answer a question nobody asked here.

  Milestones come from `history/milestone-rows`, the pure fold, not from
  `query-commits`: this namespace is :pure and query-commits opens the db
  to join the git projection's pinned shas. The cost is exactly that —
  `:sha` appears only for milestones whose DELTA carries one."
  [session]
  (let [st         (:store @session)
        rows       (history/milestone-rows st :titles-only true)
        milestones (vec (map-indexed
                         (fn [i row]
                           (let [prev (:commit (nth rows (inc i) nil))]
                             (cond-> (select-keys row [:commit :description :more-lines
                                                       :status :at :agent :sha])
                               prev (assoc :range (str prev ".." (:commit row))))))
                         rows))
        ds         (store/deltas st)
        last-commit (:id (last (filter #(= :commit (:op %)) ds)))
        since      (if last-commit
                     (rest (drop-while #(not= last-commit (:id %)) ds))
                     ds)
        mine       (filter #(contains? query/content-ops (:op %)) since)]
    {:milestones milestones
     :working {:since      (or last-commit :log-start)
               :forms      (count (distinct (mapcat history/delta-fids mine)))
               :namespaces (vec (distinct (keep #(some-> (:ns %) str) mine)))
               :prompts    (vec (keep :prompt mine))}}))

(defn ^:export change-view
  "What changed between two milestones, grouped module → namespace → form
  with a count at every rung so a collapsed row still says how much is
  under it. `from`/`to` are milestone delta ids — exactly the pair
  `timeline` hands over in each row's `:range`.

  Two rungs, not three: wave 1 made components REAL namespace prefixes, so
  a component IS a module and `module-of` answers both.

  Per form: the recorded ask, the LINE diff (never whole sources — a
  reviewer reads changes), and how many distinct forms call it now. The
  caller count spans every `:via` the graph records, and the graph is a
  syntactic reader, so it is a floor rather than a census."
  [session from to]
  (let [st        (:store @session)
        ch        (query/query-changes session :from from :to to)
        by-target (refs/refs-by-target st)
        prompts   (store/prompt-by-form st)
        rows      (for [{:keys [form form-id status was now]} (:forms ch)
                        :let [ns-sym (symbol (namespace form))]]
                    (cond-> {:form    (str form)
                             :form-id form-id
                             :status  status
                             :ns      (str ns-sym)
                             :module  (modules/module-of ns-sym)
                             :diff    (vec (history/diff-lines was now))
                             :callers (count (distinct (map (juxt :from-ns :from-var)
                                                            (get by-target form []))))}
                      (get prompts form-id) (assoc :why (get prompts form-id))))]
    {:from    from
     :to      to
     :count   (count rows)
     :modules (->> rows
                   (group-by :module)
                   (sort-by key)
                   (mapv (fn [[m rs]]
                           {:module     m
                            :count      (count rs)
                            :namespaces (->> rs
                                             (group-by :ns)
                                             (sort-by key)
                                             (mapv (fn [[n fs]]
                                                     {:ns    n
                                                      :count (count fs)
                                                      :forms (vec (sort-by :form
                                                                           (map #(dissoc % :ns :module) fs)))})))})))
     :arc     (vec (:verification-arc ch))}))

(defn- json-card
  "A `form-card` as JSON-shaped data. Two fields cannot survive the trip:
  `:form` is a qualified symbol and `:sig` is a vector of symbols, and both
  read back as something that is no longer a reference. They become TEXT
  here, once, so no page has to know the difference."
  [card]
  (when card
    (cond-> (assoc (select-keys card [:doc :why :effectful :warranty :examples])
                   :form (str (:form card)))
      (:sig card) (assoc :sig (pr-str (:sig card))))))

(defn ^:export form-view
  "One form's page model, addressed by form ID — ids are stable across
  edits and names are not, so the id is the permalink.

  Built for COLD arrival from a link (Debugger Canvas called the failure
  the \"lonely bubble\"): the breadcrumb says where this is, `:callers` is a
  backlink CARD grouped by the `:via` that found each edge, and `:callees`
  carry their own signature and doc INLINED rather than linked — Code
  Bubbles measured two-thirds of its win as concurrent visibility, and a
  link is not visibility.

  nil for an unknown id, so a page can 404 instead of rendering blank."
  [session form-id]
  (let [st (:store @session)
        e  (store/form-by-id st form-id)]
    (when (and e (:name e))
      (let [ns-sym  (store/ns-of-form-id st form-id)
            nm      (:name e)
            qsym    (symbol (str ns-sym) (str nm))
            row     (fn [ns- var-]
                      {:form   (str (symbol (str ns-) (str var-)))
                       :ns     (str ns-)
                       :module (modules/module-of ns-)})
            callers (->> (refs/refs-to st qsym)
                         (filter :from-var)
                         (group-by :via)
                         (sort-by (comp str key))
                         (mapv (fn [[via rs]]
                                 (let [by (sort-by (comp str first)
                                                   (group-by (juxt :from-ns :from-var) rs))]
                                   {:via   via
                                    :count (count by)
                                    :forms (mapv (fn [[[fns fvar] us]]
                                                   (assoc (row fns fvar)
                                                          :calls (count (keep :arity us))))
                                                 by)}))))
            callees (->> (refs/refs st)
                         (filter #(= form-id (:from-form %)))
                         (group-by (juxt :to-ns :to-name))
                         (sort-by (comp str first))
                         (mapv (fn [[[tns tnm] us]]
                                 (merge (row tns tnm)
                                        {:via   (:via (first us))
                                         :calls (count (keep :arity us))}
                                        (dissoc (json-card (orient/form-card session tns tnm))
                                                :form)))))]
        (merge {:form-id form-id
                :form    (str qsym)
                :name    (str nm)
                :ns      (str ns-sym)
                :module  (modules/module-of ns-sym)
                :source  (n/string (:node e))
                :callers callers
                :callees callees
                :note    (str "edges come from a syntactic reader over the store, so this"
                              " is a floor, not a census — a call reached through a"
                              " binding or built at runtime is not here")}
               (dissoc (json-card (orient/form-card session ns-sym nm)) :form))))))
