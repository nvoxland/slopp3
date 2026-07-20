(ns slopp.api.query
  (:require [clojure.string :as str]
            [rewrite-clj.node :as n]
            [slopp.api.attrs :as attrs]
            [slopp.api.history :as history]
            [slopp.api.modules :as modules]
            [slopp.api.orient :as orient]
            [slopp.api.rules :as rules]
            [slopp.api.shape :as shape]
            [slopp.api.telemetry :as telemetry]
            [slopp.edit :as edit]
            [slopp.edit.modules :as edit.modules]
            [slopp.edit.refs :as refs]
            [slopp.render :as render]
            [slopp.store :as store] [slopp.index.derive :as derive] [slopp.index.analyze :as analyze]))

(defn ^:export query-sources
  "Batched read (ONE call, several targets): `targets` is a vector of
  {:ns sym} (whole namespace) or {:ns sym :name sym} (one form). Returns
  a vector of {:ns :name? :source} in target order; unknown targets get
  {:error} entries instead of failing the batch."
  [session targets]
  (let [st (:store @session)]
    (mapv (fn [{:keys [ns name]}]
            (cond
              (nil? (get-in st [:namespaces ns]))
              {:ns ns :error "no such namespace"}

              (nil? name)
              {:ns ns :source (render/render-ns st ns)}

              :else
              (if-let [e (store/form-named st ns name)]
                {:ns ns :name name :source (n/string (:node e))}
                {:ns ns :name name :error "no such form"})))
          targets)))

(defn ^:export query-source
  "Render `ns-sym`'s current source from the store (the VFS read)."
  [session ns-sym]
  (render/render-ns (:store @session) ns-sym))

(defn ^:export query-symbol
  "Describe the form defining `nm`: id, name, effectfulness (D6), source."
  [session ns-sym nm]
  (let [st  (:store @session)
        f   (store/form-named st ns-sym nm)
        eff (derive/effectful-vars (analyze/analyze (render/render-ns st ns-sym)))]
    (when f
      (cond-> {:id         (:id f)
               :name       (:name f)
               :effectful? (contains? eff (symbol (str ns-sym) (str nm)))
               :source     (n/string (:node f))}
        (edit/unsafe? (:node f)) (assoc :unsafe? true)
        (edit/reads? (:node f))  (assoc :reads? true)))))

(defn ^:export query-references
  "Usages of `ns-sym/nm` across EVERY namespace (F-3c3 — same-ns-only results
  sent an eval agent to query_search instead; analyses are memo-cached, so the
  full scan is cheap)."
  [session ns-sym nm]
  (let [st (:store @session)]
    (vec (mapcat (fn [n]
                   (derive/references (analyze/analyze (render/render-ns st n))
                                     ns-sym nm))
                 (sort (keys (:namespaces st)))))))

(defn ^:export label-ancestors [agent-label]
  (when agent-label
    (let [parts (clojure.string/split agent-label #"/")]
      (map #(clojure.string/join "/" (take (inc %) parts))
           (range (count parts))))))

(defn ^:export turn-intents
  "delta-id → the enclosing turn's verbatim :intent (resolved through the
  delta's agent, sub-agent path labels riding their root's turn). Derived
  at query time; truncated for display."
  [ds]
  (loop [ds ds, open {}, out {}]
    (if-let [d (first ds)]
      (let [open (case (:op d)
                   :turn-begin (assoc open (:agent d) (:intent d))
                   :turn-end   (dissoc open (:agent d))
                   open)
            in   (some open (or (label-ancestors (:agent d)) []))
            out  (if in
                   (assoc out (:id d)
                          (if (> (count in) 160)
                            (str (subs in 0 157) "...")
                            in))
                   out)]
        (recur (rest ds) open out))
      out)))

(defn ^:export query-lineage
  "Provenance chain for `nm`: the deltas that created or changed its form (who
  touched it, via which op, driven by which prompt)."
  [session ns-sym nm]
  (let [st (:store @session)
        id (:id (store/form-named st ns-sym nm))]
    (when id
      (let [ti (turn-intents (store/deltas st))]
        (->> (store/deltas st)
             (filter (fn [d]
                       (or (= id (:form-id d))
                           (some #{id} (:form-ids d)))))
             ;; lean: bulk content lives in query-form-history, not here
             (mapv #(cond-> (dissoc % :sources :changeset :result)
                      (ti (:id %)) (assoc :turn-intent (ti (:id %))))))))))

(defn ^:export query-form-history
  "Every content version of `nm`'s form, oldest first, with the intent that
  produced it, when, and the verification state it landed in:
  [{:delta :op :prompt :source :status :at :turn-intent}]. `:status`
  (was-green-at, HM2) is the project's verification state governing each
  version — semantic × history, per form. `:format \"text\"` (HM4) renders
  the form's LIFE as a per-version LINE-diff story instead."
  [session ns-sym nm & {:keys [format]}]
  (let [st (:store @session)
        id (:id (store/form-named st ns-sym nm))]
    (when id
      (let [ti       (turn-intents (store/deltas st))
            versions (vec (for [d     (store/deltas st)
                                :let  [src (get-in d [:sources id])]
                                :when src]
                            (cond-> {:delta (:id d) :op (:op d)
                                     :prompt (:prompt d) :source src
                                     :status (history/status-after st (:id d))
                                     :at (history/human-time (:at d))}
                              (ti (:id d)) (assoc :turn-intent (ti (:id d))))))]
        (if (= "text" (some-> format name))
          (history/render-form-history-text (symbol (str ns-sym) (str nm)) versions)
          versions)))))

(defn ^:export query-search-history
  "Delta-log search — the 'which prompts touched X' query. Case-insensitive
  substring match of `pattern` against each delta's prompt, done label,
  commit/turn description, turn-end note, AND its enclosing turn intent;
  returns the matching deltas NEWEST-first with the forms they touched (as
  ns/name qsyms, resolved as of that delta) and the human time. `:limit`
  (default 25). Pairs with `query-form-at`/`query-lineage` to drill in."
  [session pattern & {:keys [limit] :or {limit 25}}]
  (if (str/blank? (str pattern))
    {:error "query-search-history needs a non-blank pattern"}
    (let [st  (:store @session)
          ds  (store/deltas st)
          ti  (turn-intents ds)
          pat (str/lower-case (str pattern))
          hit? (fn [d]
                 (some #(and % (str/includes? (str/lower-case (str %)) pat))
                       [(:prompt d) (:label d) (:description d) (:note d)
                        (ti (:id d))]))
          form-name (fn [d fid]
                      (or (some-> (get-in d [:sources fid]) store/name-of-source str)
                          (some-> (store/form-by-id st fid) :name str)
                          (when (= fid (:form-id d)) (some-> (:name d) str))
                          (str fid)))
          touched (fn [d]
                    (vec (for [fid (history/delta-fids d)]
                           (symbol (str (or (store/ns-of-form-id st fid) (:ns d)))
                                   (form-name d fid)))))]
      (->> ds
           reverse
           (filter hit?)
           (take (or limit 25))
           (mapv (fn [d]
                   (cond-> {:delta (:id d) :op (:op d) :at (history/human-time (:at d))}
                     (:prompt d)      (assoc :prompt (:prompt d))
                     (:label d)       (assoc :label (:label d))
                     (:description d) (assoc :description (:description d))
                     (:note d)        (assoc :note (:note d))
                     (ti (:id d))     (assoc :turn-intent (ti (:id d)))
                     (seq (history/delta-fids d)) (assoc :forms (touched d)))))))))

(defn ^:export query-history
  "The delta log as a story, newest first. Filters: `:ns`, `:contains`
  (substring of prompt/label — and, collapsed, of turn intents). `:limit`
  (default 20). `:collapse true` returns EPISODE rows instead of raw deltas —
  one row per agent-work-unit between done-points, the readable long-term
  view. All rows carry `:at` (local date-time)."
  [session & {:keys [ns contains limit collapse format] :or {limit 20}}]
  (let [
        rows
        (if collapse
          (let [ds       (store/deltas (:store @session))
                relevant (filter #(or (contains? #{:ingest :add :replace :delete
                                                   :rename :normalize :move :merge}
                                                 (:op %))
                                      (= :done (:op %)))
                                 ds)
                pos      (into {} (map-indexed (fn [i d] [(:id d) i])) ds)
                rows     (history/episode-rows relevant)]
            (history/collapse-rows  ds pos rows contains limit))
          (->> (store/deltas (:store @session))
               reverse
               (filter #(or (nil? ns) (= ns (:ns %))))
               (filter #(or (nil? contains)
                            (some (fn [s] (and s (clojure.string/includes? (str s) contains)))
                                  [(:prompt %) (:label %)])))
               (take limit)
               (mapv (fn [d]
                       (cond-> (select-keys d [:id :op :ns :prompt :label :group
                                               :agent :form-id :form-ids :old
                                               :new :before])
                         (:at d) (assoc :at (history/human-time (:at d))))))))]
    (cond-> rows
      (= "text" (some-> format name)) history/render-history-text)))

(defn ^:export query-outline
  "A namespace's shape at a glance (orientation, T2): every defined var with
  arities, `!`-effect status, and test-ness — a fraction of the tokens of
  reading the source. COMPACT by default; `:detail true` adds each var's
  docstring first line (the outline's token bulk)."
  [session ns-sym & {:keys [detail]}]
  (let [st  (:store @session)
        an  (analyze/analyze (render/render-ns st ns-sym))
        eff (derive/effectful-vars an)]
    {:ns ns-sym
     :forms
     (vec (for [d (:var-definitions an)
                :when (= ns-sym (:ns d))]
            (cond-> {:name (:name d)}
              (:fixed-arities d)      (assoc :arities (vec (sort (:fixed-arities d))))
              (:varargs-min-arity d)  (assoc :varargs-min (:varargs-min-arity d))
              (and detail (:doc d))   (assoc :doc (first (str/split-lines (:doc d))))
              (derive/test-definition? d) (assoc :test? true)
              (and (not (derive/test-definition? d))
                   (contains? eff (symbol (str ns-sym) (str (:name d)))))
              (assoc :effectful? true))))}))

(defn ^:export query-project
  "The WHOLE store's shape in one call: every namespace with its outline
  (item 1 — orientation was ~90% of tool calls in successful runs; this
  replaces the namespaces→outline×N chain). COMPACT by default (names,
  arities, flags); `:detail true` adds doc lines. Pass `:since <delta id>`
  on a re-check: when nothing STRUCTURAL changed after that delta the
  response is a one-liner instead of the full outline (verify/turn/
  milestone markers don't count as change)."
  [session & {:keys [since detail]}]
  (let [st   (:store @session)
        ds   (store/deltas st)
        head (:id (last ds))
        quiet-ops #{:verify :done :commit :turn-begin :turn-end}
        unchanged? (and since
                       (some #(= since (:id %)) ds)
                       (->> ds
                            (drop-while #(not= since (:id %)))
                            rest
                            (every? #(contains? quiet-ops (:op %)))))]
    (if unchanged?
      {:unchanged-since since :head head}
      {:head       head
       :namespaces (mapv (fn [ns-sym] (query-outline session ns-sym :detail detail))
                         (sort (keys (:namespaces st))))})))

(defn ^:export query-search
  "The missing grep: regex over all store source, form-addressed results
  [{:ns :form :line}], capped at `:limit` (default 30)."
  [session pattern & {:keys [limit] :or {limit 30}}]
  (try
    (let [re (re-pattern pattern)
          st (:store @session)]
      (->> (for [ns-sym (sort (keys (:namespaces st)))
                 e      (store/forms st ns-sym)
                 line   (str/split-lines (n/string (:node e)))
                 :when  (re-find re line)]
             {:ns ns-sym
              :form (or (:name e) (:id e))
              :line (str/trim line)})
           (take limit)
           vec))
    (catch Exception ex
      {:error (str "bad pattern: " (ex-message ex))})))

(def ^:export content-ops
  #{:ingest :add :replace :delete :rename :normalize :move :merge})

(defn ^:export episode-boundary
  "Where `agent-label`'s episode begins: its own last :done — or, for
  an agent that has never marked done, the last stable spot (ANY agent's
  done) before its first activity, so pre-existing history is never
  mistaken for contested work. nil = log start."
  [store agent-label]
  (let [ds  (store/deltas store)
        own (last (filter #(and (= :done (:op %))
                                (= agent-label (:agent %)))
                          ds))]
    (:id (or own
             (let [ckpts     (filter #(= :done (:op %)) ds)
                   first-own (first (filter #(and (contains? content-ops (:op %))
                                                  (= agent-label (:agent %)))
                                            ds))]
               (if first-own
                 (let [pos  (into {} (map-indexed (fn [i d] [(:id d) i])) ds)
                       fpos (get pos (:id first-own))]
                   (last (filter #(< (get pos (:id %)) fpos) ckpts)))
                 (last ckpts)))))))

(defn ^:export episode-span
  "Deltas after `agent`'s episode boundary (all agents' — callers filter)."
  [store agent]
  (let [ds (store/deltas store)]
    (if-let [b (episode-boundary store agent)]
      (rest (drop-while #(not= b (:id %)) ds))
      ds)))

(defn ^:export query-changes
  "The agent's EPISODE — everything since `:agent`'s last done: net
  per-form diffs (:was/:now), the step list, and the verification arc. The
  'what have I done since my last stable spot' view. Parallel agents with
  distinct :agent labels each see only their own work. `:format \"text\"`
  renders it as a human story with LINE diffs instead of full sources."
  [session & {:keys [agent from to format]}]
  (let [st       (:store @session)
        boundary (if from
                   ;; historical span: `from`/`to` are delta ids (e.g. from a
                   ;; collapsed history row); boundary = just BEFORE `from`
                   (:id (last (take-while #(not= from (:id %))
                                          (store/deltas st))))
                   (episode-boundary st agent))
        span     (if from
                   (let [ds (drop-while #(not= from (:id %))
                                        (store/deltas st))]
                     (if to
                       (let [[pre [t & _]] (split-with #(not= to (:id %)) ds)]
                         (concat pre (when t [t])))
                       ds))
                   (episode-span st agent))
        mine     (filter #(and (contains? content-ops (:op %))
                               (or (nil? agent) (= agent (:agent %))))
                         span)
        fids     (distinct (mapcat history/delta-fids mine))
        was      (store/sources-at st boundary)
        del-info (into {}
                       (keep (fn [d]
                               (when (= :delete (:op d))
                                 [(:form-id d) [(:ns d) (:name d)]])))
                       mine)
        at-end   (when to (store/sources-at st to))
        forms    (vec (keep (fn [fid]
                              (let [e   (store/form-by-id st fid)
                                    now (if to
                                          (get at-end fid)
                                          (some-> e :node n/string))
                                    old (get was fid)]
                                (when (not= old now)
                                  (let [[dns dnm] (get del-info fid)
                                        qform (if e
                                                (symbol (str (store/ns-of-form-id st fid))
                                                        (str (or (:name e) fid)))
                                                (symbol (str dns) (str (or dnm fid))))]
                                    (cond-> {:form    qform
                                             :form-id fid
                                             :status  (cond (nil? old) :added
                                                            (nil? now) :deleted
                                                            :else      :modified)}
                                      old (assoc :was old)
                                      now (assoc :now now))))))
                            fids))
        arc      (vec (for [d span
                            :when (= :verify (:op d))
                            :let [r (:result d)]]
                        {:delta (:id d)
                         :fail  (+ (:fail r 0) (:error r 0))}))]
    (cond-> {:agent agent
             :since (or boundary :log-start)
             :steps (mapv #(select-keys % [:id :op :ns :prompt]) mine)
             :forms forms
             :verification-arc arc}
      (= "text" (some-> format name)) history/render-changes-text)))

(defn ^:export callee-adjacency
  "qsym → sorted vector of STORE-INTERNAL callee qsyms, across every ns."
  [st]
  (let [internal? (:namespaces st)]
    (reduce
     (fn [adj ns-sym]
       (let [an (analyze/analyze (render/render-ns st ns-sym))]
         (reduce (fn [adj u]
                   (if (and (:from-var u) (internal? (:to u)))
                     (update adj
                             (symbol (str (:from u)) (str (:from-var u)))
                             (fnil conj (sorted-set))
                             (symbol (str (:to u)) (str (:name u))))
                     adj))
                 adj (:var-usages an))))
     {}
     (keys (:namespaces st)))))

(defn ^:export query-deps
  "The transitive CALLEE tree of ns/name (store-internal): what does this
  form reach? The planning input for extractions and blast-radius checks.
  Returns {:root q :calls {qsym [callees...]}} for every reachable form."
  [session ns-sym nm]
  (let [st   (:store @session)
        adj  (callee-adjacency st)
        root (symbol (str ns-sym) (str nm))]
    (loop [calls {} frontier [root]]
      (if-let [q (first frontier)]
        (if (contains? calls q)
          (recur calls (subvec frontier 1))
          (let [cs (vec (get adj q []))]
            (recur (assoc calls q cs) (into (subvec frontier 1) cs))))
        {:root root :calls calls}))))

(defn ^:export query-namespaces
  "What exists? Every store namespace with its form count (orientation, T2)."
  [session]
  (let [st (:store @session)]
    (vec (for [ns-sym (keys (:namespaces st))]
           {:ns ns-sym :forms (count (store/forms st ns-sym))}))))

(defn ^:export query-status-at
  "was-green-at: the project's verification state that GOVERNED delta `at`
  (a delta id, or a commit-point id → its target) — the last `:verify` at or
  before it. Returns {:at :status (:green|:red|:unknown) :verify <delta-id>}
  or {:error} for an unknown delta."
  [session & {:keys [at]}]
  (let [st (:store @session)]
    (cond
      (nil? at)              {:error "query-status-at needs :at"}
      (nil? (history/resolve-at st at)) {:error (str "no delta " at
                                             " in this branch's history")}
      :else (let [rid (history/resolve-at st at)]
              (cond-> {:at rid :status (history/status-at st rid)}
                (history/verify-at st rid) (assoc :verify (:id (history/verify-at st rid))))))))

(defn ^:export fid-ns-at
  "form-id → owning namespace as of delta `at-id`, folded from the log (each
  content delta carries its `:ns` and the form-ids it touched). Lets
  time-travel disambiguate same-named forms in different namespaces at a
  PAST point, without depending on the current store's membership."
  [store at-id]
  (reduce (fn [m d]
            (let [m (reduce #(assoc %1 %2 (:ns d)) m (history/delta-fids d))]
              (if (= at-id (:id d)) (reduced m) m)))
          {} (store/deltas store)))

(defn ^:export query-form-at
  "Time-travel: form `nm` in `ns-sym` as its SOURCE stood at delta `at` (a
  delta id, or a commit-point id → its target). Returns
  {:ns :name :at :source :status} — `:status` is the project's verification
  state that governed that point (was-green-at) — or {:error}. Names are
  resolved AT that delta (so a form that was later renamed still answers to
  the name it had then). The form's source is stored verbatim per version,
  so this is exact, not reconstructed."
  [session ns-sym nm & {:keys [at]}]
  (let [st (:store @session)]
    (cond
      (nil? at)
      {:error "query-form-at needs :at (a delta id or a commit-point id)"}

      (nil? (history/resolve-at st at))
      {:error (str "no delta " at " in this branch's history")}

      :else
      (let [rid   (history/resolve-at st at)
            srcs  (store/sources-at st rid)
            ns-of (fid-ns-at st rid)
            fid   (some (fn [[fid src]]
                          (when (and (= ns-sym (get ns-of fid))
                                     (= (str nm) (str (store/name-of-source src))))
                            fid))
                        srcs)]
        (if fid
          {:ns ns-sym :name nm :at rid :source (get srcs fid)
           :status (history/status-at st rid)}
          {:error (str nm " was not present in " ns-sym " at " rid)})))))

(defn ^:export query-flow
  "Rock 4: where a FIELD flows — every form using keyword `kw` (\":rush?\"),
  with the using lines. The cross-namespace thread an agent otherwise
  re-derives by reading each layer.

  Reads `edit.refs/keyword-refs` — THE keyword graph — rather than scanning
  text, so a key read by DESTRUCTURING is included: `{:user/keys [id]}` uses
  `:user/id` while containing no such token, and a text scan silently omitted
  exactly the module-boundary fns that destructure a handle. Rows from a
  destructuring carry `:via :destructuring`."
  [session kw]
  (let [target (keyword (str/replace (str kw) #"^:" ""))
        st     (:store @session)
        dpat   (re-pattern (str "(?<![\\w.:-])"
                                (java.util.regex.Pattern/quote
                                 (str ":" (some-> (namespace target) (str "/")) "keys"))
                                "(?![\\w-])"))
        lpat   (re-pattern (str "(?<![\\w.:-])"
                                (java.util.regex.Pattern/quote (str target))
                                "(?![\\w?!*+<>=-])"))]
    (->> (refs/keyword-refs st)
         (filter #(= target (:kw %)))
         (sort-by (juxt (comp str :from-ns) (comp str :from-var)))
         (mapv (fn [{:keys [from-ns from-var via]}]
                 (let [src   (some-> (store/form-named st from-ns from-var)
                                     :node n/string)
                       pat   (if (= :destructuring via) dpat lpat)
                       lines (when src
                               (filterv #(re-find pat %) (str/split-lines src)))]
                   (cond-> {:ns from-ns :form from-var
                            :lines (mapv str/trim (take 3 lines))}
                     (= :destructuring via) (assoc :via :destructuring))))))))

(defn ^:export coverage-view
  "The `:covered-by` shape both `query-impact` and `query-brief` report:
   `{:count n :tests [first 8] :more k}`. Capped because a central form is
   covered by HUNDREDS of tests — `slopp.api/open!` by 284 — and printing
   them all pushed the keys the caller actually asked for past the response
   trim, making a working answer read as a broken one. The remainder is
   COUNTED, never silently dropped."
  [test-syms]
  (let [ts (vec test-syms)]
    (cond-> {:count (count ts) :tests (vec (take 8 ts))}
      (> (count ts) 8) (assoc :more (- (count ts) 8)))))

(defn ^:export query-impact
  "Rock 4: the blast radius of reshaping `ns-sym/nm`, answered from THE
  reference graph — call sites grouped per caller form (:calls),
  value/higher-order references (:value-refs — a template rewrite can't
  reach those), CARRIER references (:carrier-refs — quoted-symbol
  positions; signature templates can't reach those either), outside-world
  declarations (:declared), and the tests runtime evidence says exercise
  it (:covered-by — the graph's :observed records, as {:count :tests :more}:
  capped at 8 with the remainder counted, since a central form has hundreds). change_signature's discovery as a READ: plan the edit before paying for it.

  When the form takes or is passed a MAP, `:shape` answers the other half —
  the keys it READS off its first argument (destructured, body, `:=>` schema,
  `:or`-optional) against the literal keys its callers PASS, grouped by
  key-set, with the diff in `:mismatch`. Renaming a key, or wondering who
  supplies one, is a read here rather than a grep. `:unknown-shape` names the
  callers passing a non-literal: a syntactic reader cannot see through a
  binding, so trust `:mismatch` only as far as that list is empty."
  [session ns-sym nm]
  (let [st (:store @session)]
    (if-not (store/form-named st ns-sym nm)
      (edit/missing-form-error st ns-sym nm)
      (let [qsym    (symbol (str ns-sym) (str nm))
            rs      (refs/refs-to st qsym)
            statics (filter #(= :static (:via %)) rs)
            callers (->> statics
                         (group-by (juxt :from-ns :from-var))
                         (mapv (fn [[[nsx from] us]]
                                 {:ns nsx :form from
                                  :calls (count (keep :arity us))
                                  :value-refs (count (remove :arity us))}))
                         (sort-by (juxt (comp str :ns) (comp str :form)))
                         vec)
            carried (vec (sort (distinct
                                (for [r rs :when (= :carrier (:via r))]
                                  (symbol (str (:from-ns r)) (str (:from-var r)))))))
            marks   (vec (sort (keep :marker rs)))
            all-ts  (->> (refs/observed-refs (:test-map @session))
                         (filter #(and (= ns-sym (:to-ns %)) (= nm (:to-name %))))
                         (map #(symbol (str (:from-ns %)) (str (:from-var %))))
                         sort vec)
            ;; a central form is covered by HUNDREDS of tests. Printing them
            ;; all pushed the keys actually asked for past the response
            ;; trim — a working answer read as a broken one.
            tests   (coverage-view all-ts)
            shp     (shape/shape-of st ns-sym nm callers)]
        (cond-> {:target qsym :callers callers :covered-by tests}
          (seq carried) (assoc :carrier-refs carried)
          (seq marks)   (assoc :declared marks)
          shp           (assoc :shape shp)
          (or (some (comp pos? :value-refs) callers) (seq carried))
          (assoc :hint (str "value/higher-order and carrier refs can't be"
                            " template-rewritten — change_signature handles"
                            " :calls; edit the others by hand")))))))

^:reads (defn ^:export query-slice
  "The focused read (driver, not doer): FULL source for the form you're
  about to edit + interface CARDS for what it reaches (same-ns private
  helpers and cross-ns callees, breadth-first to `:depth`, capped at
  `:limit` with an honest :omitted). Replaces outline→guess→fetch loops:
  name ONE entry point, receive the neighborhood. `:match` WINDOWS the
  target — only `:window` lines (default 25) each side of the first line
  containing it ride back, with :window metadata — so one clause of a
  giant form reads without paying for the whole thing."
  [session ns-sym nm & {:keys [depth limit match window] :or {depth 2 limit 8}}]
  (if-let [e (store/form-named (:store @session) ns-sym nm)]
    (let [root    (symbol (str ns-sym) (str nm))
          adj     (:calls (query-deps session ns-sym nm))
          reached (loop [level [root] seen #{root} acc [] d 0]
                    (if (>= d depth)
                      acc
                      (let [nxt (into []
                                      (comp (mapcat #(get adj % []))
                                            (remove seen)
                                            (distinct))
                                      level)]
                        (if (empty? nxt)
                          acc
                          (recur nxt (into seen nxt) (into acc nxt) (inc d))))))
          shown   (vec (take limit reached))
          cards   (into []
                        (keep (fn [q] (orient/form-card session
                                                 (symbol (namespace q))
                                                 (symbol (name q)))))
                        shown)
          src     (n/string (:node e))
          target  (if match
                    (let [lines (vec (str/split-lines src))
                          w     (or (some-> window str parse-long) 25)
                          idx   (first (keep-indexed
                                        (fn [i l] (when (str/includes? l (str match)) i))
                                        lines))]
                      (if idx
                        (let [lo (max 0 (- idx w))
                              hi (min (count lines) (+ idx w 1))]
                          {:form root
                           :source (str/join "\n" (subvec lines lo hi))
                           :window {:match (str match) :lines [(inc lo) hi]
                                    :of (count lines)}})
                        {:form root :source src
                         :note (str "match not found in the form: " match)}))
                    {:form root :source src})]
      (cond-> {:target target
               :cards cards}
        (> (count reached) limit) (assoc :omitted (- (count reached) limit))))
    (edit/missing-form-error (:store @session) ns-sym nm)))

^:reads (defn ^:export query-depends
  "The generic dependency front door: what depends on `on` (`:direction
  :dependents`, the default) or what `on` depends on (`:direction
  :dependencies`), where `on` is a NAMESPACE, a VAR (\"ns/name\"), or a
  KEYWORD (\":dest-zone\"). Dependents: ns → who requires it + qualified
  refs; var → blast radius (callers, value refs, covering tests); keyword
  → the field's flow. Dependencies: var → the transitive callee tree; ns
  → its requires. `:modules true` (no `on`) → the module graph (:manifest=DECLARED, :layers/:cycles=PRODUCTION-only; declared
  edges + any standing debt). One tool to ask — results carry :kind."
  [session on & {:keys [direction modules] :or {direction :dependents}}]
  (let [st (:store @session)]
    (if modules
      (if (seq (str on))
        (assoc (modules/module-surface session on) :kind :module-surface)
        (let [manifest (or (edit.modules/modules-manifest st) {})
              rows     (modules/module-usage-rows st)
              actual   (into #{}
                             (comp (map (fn [{:keys [from-ns to]}]
                                          [(edit.modules/module-of from-ns)
                                           (edit.modules/module-of to)]))
                                   (remove (fn [[a b]] (= a b))))
                             rows)
              unused   (vec (for [[m ds] (sort manifest)
                                  d      (sort ds)
                                  :when  (not (contains? actual [m d]))]
                              [m d]))
              ;; layers/cycles reflect PRODUCTION architecture (test fixtures excluded);
              ;; :manifest below stays the DECLARED/enforced set
              graph    (store/module-layers (modules/production-manifest st rows))]
          (cond-> {:kind :modules
                   :manifest (into (sorted-map)
                                   (map (fn [[m ds]] [m (vec (sort ds))]))
                                   manifest)
                   :layers (:layers graph)
                   :debt (modules/module-debt st rows)
                   :purity (modules/purity-standing st)}
            (seq (:cycles graph))
            (assoc :cycles (:cycles graph))

            (seq unused)
            (assoc :unused-edges unused
                   :unused-note (str "declared but no call uses them —"
                                     " module_dep {from .. to .. remove true}"
                                     " retires an edge")))))
      (let [on (str/trim (str on))]
        (cond
          (str/starts-with? on ":")
          {:kind :keyword :on on :rows (query-flow session on)}

          (str/includes? on "/")
          (let [[nsx nm] (str/split on #"/" 2)]
            (if (= :dependencies direction)
              (let [r (query-deps session (symbol nsx) (symbol nm))]
                (assoc r :kind :var :on on :direction :dependencies))
              (let [r (query-impact session (symbol nsx) (symbol nm))]
                (if (:error r) r (assoc r :kind :var :on on)))))

          (contains? (:namespaces st) (symbol on))
          (let [target   (symbol on)
                requires (vec (sort (distinct (vals (edit/require-aliases st target)))))]
            (if (= :dependencies direction)
              {:kind :namespace :on target :direction :dependencies
               :requires requires}
              (let [req-set     (fn [nsx] (set (vals (edit/require-aliases st nsx))))
                    required-by (vec (sort (filter #(and (not= % target)
                                                         (contains? (req-set %) target))
                                                   (keys (:namespaces st)))))
                    pat         (re-pattern (str "(?<![\\w.-])"
                                                 (java.util.regex.Pattern/quote on) "/"))
                    refs        (vec (for [nsx (sort (keys (:namespaces st)))
                                           :when (not= nsx target)
                                           e (store/forms st nsx)
                                           :when (and (:name e)
                                                      (re-find pat (n/string (:node e))))]
                                       {:ns nsx :form (:name e)}))]
                {:kind :namespace :on target
                 :required-by required-by
                 :requires requires
                 :qualified-refs (vec (take 20 refs))})))

          :else
          {:error (str "nothing named " on
                       " — `on` is a namespace, var (ns/name), or :keyword;"
                       " modules true reads the module manifest")})))))

^:unsafe (defn ^:export query-store
  "The STORE-VALUE oracle: evaluate one read-only `(fn [store] ...)` over
  the CURRENT immutable store value, in the server process where that
  value lives — the sanctioned home for ad-hoc codebase-as-data analysis
  (`query_eval` answers questions OF the code in the image; this answers
  questions ABOUT it). Gated hard: the form must be a single fn of the
  store, effect-free by the pure-eval walk (no `!`, defs, interop, IO,
  eval), and it runs on a worker with a timeout so runaway analysis can't
  wedge the serve loop (the store value is immutable — the pointer is safe
  by construction). Results must print small (pr-str capped); fully-qualify
  everything (no aliases in eval context). Returns {:result v :ms n} or
  {:error msg}."
  [session code & {:keys [timeout-ms] :or {timeout-ms 10000}}]
  (let [parsed (edit/parse-one (str code))]
    (if (:error parsed)
      {:error (str "query_store takes ONE (fn [store] ...) form — "
                   (:error parsed))}
      (let [sx (try (n/sexpr (:node parsed)) (catch Exception _ nil))]
        (cond
          (not (and (seq? sx) (contains? #{'fn 'fn*} (first sx))))
          {:error "query_store takes ONE (fn [store] ...) form — got something else"}

          :else
          (if-let [refusal (edit/pure-eval-refusal sx)]
            {:error refusal}
            (let [store (:store @session)
                  t0    (System/currentTimeMillis)
                  fut   (future
                          (try {:result ((eval sx) store)}
                               (catch Throwable e
                                 {:error (str "query_store threw: "
                                              (ex-message e))})))
                  out   (deref fut timeout-ms ::timeout)]
              (if (= ::timeout out)
                (do (future-cancel fut)
                    {:error (str "query_store timed out after " timeout-ms
                                 "ms — narrow the analysis (or raise"
                                 " :timeout-ms)")})
                (let [ms (- (System/currentTimeMillis) t0)]
                  (if (:error out)
                    (assoc out :ms ms)
                    (let [s (pr-str (:result out))]
                      (if (> (count s) 32768)
                        {:result (str (subs s 0 32768) " …")
                         :truncated true :ms ms}
                        (assoc out :ms ms)))))))))))))

(defn ^:export query-brief
  "The one-call dossier: everything the store knows about `ns-sym/nm` —
  source, effect flags, cross-ns callers, the tests that exercise it
  (trace map; `:coverage :unknown` until a test_run builds one), and the
  recorded WHY (the last change's prompt + its enclosing turn intent).
  Collapses the source→references→lineage read chain into one response."
  [session ns-sym nm]
  (if (nil? (store/form-named (:store @session) ns-sym nm))
    (edit/missing-form-error (:store @session) ns-sym nm)
    (let [
          sym     (query-symbol session ns-sym nm)
          callers (vec (query-references session ns-sym nm))
          tmap    (:test-map @session)
          tests   (let [e  (store/form-named (:store @session) ns-sym nm)
                        ks (store/form-trace-keys ns-sym e)]
                    ;; evidence can arrive under any name the form defines (#129)
                    (->> tmap
                         (keep (fn [[t forms]] (when (some forms ks) t)))
                         distinct sort vec))
          why     (last (query-lineage session ns-sym nm))]
      (cond-> {:ns ns-sym :name nm :source (:source sym)}
        (:effectful? sym) (assoc :effectful? true)
        (:reads? sym)     (assoc :reads? true)
        (:unsafe? sym)    (assoc :unsafe? true)
        (seq callers)     (assoc :callers callers)
        (seq tests)       (assoc :covered-by (coverage-view tests))
        (and (seq tmap) (empty? tests) (not (:test? sym)))
        (assoc :untested true)
        (empty? tmap)     (assoc :coverage :unknown)
        why               (assoc :why (cond-> {:op     (:op why)
                                               :prompt (:prompt why)}
                                        (:agent why)       (assoc :agent (:agent why))
                                        (:turn-intent why) (assoc :intent (:turn-intent why))))))))

(defn ^:export query-vocabulary
  "Browse the store's domain-keyword vocabulary — namespaced keys, most-used
   first — so you REUSE an established key (`:user/email`) instead of coining a
   near-duplicate the key-hygiene advisory would flag. Optional `ns` narrows to a
   keyword namespace (exact or dotted-child, e.g. \"user\" → :user/* and
   :user.address/*). Derived from the forms, so it reflects the current branch/
   revision exactly."
  [session & {:keys [ns]}]
  (let [attrs (attrs/vocabulary (:store @session) :ns-prefix ns)]
    {:count (count attrs) :attributes attrs}))

(defn ^:export query-rules
  "The D9 enforcement catalog for THIS store: every rule with its grain, its
   EFFECTIVE per-store severity (the `rules` config override else the default),
   how to discharge it, and what it means. The one place to see what's enforced
   and at what grade — dial any rule with `config_file {path \"rules\" key <rule>
   value <severity>}` (`:off`/`:advisory`/`:error`/`:refuse`). WRITE-gate (`:form`)
   severity is one of `:off` (skip), `:advisory` (warn-but-proceed — the teaching
   rides the write result's `:advisories`), or `:refuse` (block); `:error` has no
   write-gate meaning and reports as `:refuse`. Done-grain rules keep the full
   `:off`/`:advisory`/`:error` range."
  [session]
  (let [st (:store @session)]
    (mapv (fn [{:keys [rule grain severity] :as r}]
            (let [eff (edit.modules/rule-severity st rule severity)]
              (assoc r :severity
                     (if (= grain :form)
                       (case eff (:off :advisory) eff :refuse)
                       eff))))
          rules/rule-catalog)))

(defn ^:export query-rule-telemetry
  "The D9 rules' fire-rate + discharge signal for THIS store — the demand signal
   the severity dial is set by: how often each rule fires (`:dones`/`:instances`),
   whether its findings get `:discharged` (flagged once) or `:persisted` (keep
   recurring — ignored / friction), the `:escape-markers` density (agents opting
   out via `^:unsafe`/`^:reads`/`^:unused-ok`), and the current `:dials`. Read-only
   analysis over the delta log — no instrumentation. `:since` (a delta or
   commit-point id from `query_commits`) windows it."
  [session & {:keys [since]}]
  (telemetry/rule-telemetry (:store @session) :since since))
