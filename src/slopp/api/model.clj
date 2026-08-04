(ns slopp.api.model
  "The reviewer UI's READ MODELS: JSON-shaped data assembled from the
  operation API's pure surfaces. No hiccup, no HTTP, no writes.

  Four models, one per screen — `timeline` (the landing page),
  `change-view` (what happened between two milestones), `form-view`
  (one form's permalink) and `module-index` (the Code landing: the
  architecture as a drawable picture).

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
            [slopp.read.query :as query]
            [slopp.read.history :as history] [slopp.edit.modules :as modules] [slopp.index.refs :as refs] [slopp.read.orient :as orient] [rewrite-clj.node :as n] [clojure.string :as str]
            [slopp.read.modules :as read.modules] [slopp.edit.tiers :as tiers]))

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
  (let [st  (:store @session)
        ids (into #{} (map :id) (store/deltas st))]
    ;; a range arrives from a URL, so both ends are user input. "Nothing
    ;; changed here" and "that is not a range" are different answers, and
    ;; only the second one is a 404.
    (when (and (contains? ids from) (contains? ids to))
      (let [ch        (history/query-changes session :from from :to to)
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
         :arc     (vec (:verification-arc ch))}))))

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

(defn- snip
  "Cap `s` at `line-cap` characters with an ellipsis. A landing model is a
  SUMMARY: a milestone whose title line is a whole paragraph, or twenty
  prompts at full length, turn the page into the thing it exists to save
  you from reading. Capped in the MODEL, not the page, so a JSON sink is
  bounded too."
  [s]
  (let [line-cap 110]
    (when s
      (if (<= (count s) line-cap) s (str (subs s 0 line-cap) "…")))))

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
                             (cond-> (-> (select-keys row [:commit :description :more-lines
                                                           :status :at :agent :sha])
                                         (update :description snip))
                               prev (assoc :range (str prev ".." (:commit row))))))
                         rows))
        ds         (store/deltas st)
        last-commit (:id (last (filter #(= :commit (:op %)) ds)))
        since      (if last-commit
                     (rest (drop-while #(not= last-commit (:id %)) ds))
                     ds)
        mine       (filter #(contains? history/content-ops (:op %)) since)
        asks       (vec (keep :prompt mine))
        shown      8]
    {:milestones milestones
     :working (cond-> {:since      (or last-commit :log-start)
                       :forms      (count (distinct (mapcat history/delta-fids mine)))
                       :namespaces (vec (distinct (keep #(some-> (:ns %) str) mine)))
                       ;; the COUNT above is exact; only the listing is capped,
                       ;; and what is left out is stated rather than dropped
                       :prompts    (mapv snip (take shown asks))}
                (> (count asks) shown)
                (assoc :more-prompts (- (count asks) shown)))}))

(def ^:private special-heads
  "Symbols the tokenizer renders as SPECIAL. Deliberately short: these are
  the forms whose head changes what the rest of the form means, so seeing
  them at a glance is what makes a page skimmable. Every other symbol is
  plain text — a long keyword list buys little and a wrong entry actively
  misleads, which is worse than no colour."
  #{"def" "defn" "defn-" "defmacro" "defmulti" "defmethod" "defprotocol"
    "defrecord" "deftype" "defonce" "deftest" "ns" "let" "letfn" "fn" "if"
    "if-let" "if-not" "when" "when-let" "when-not" "cond" "condp" "case"
    "loop" "recur" "for" "doseq" "try" "catch" "finally" "throw" "do"
    "require" "testing" "is" "reify" "extend-type" "extend-protocol"})

(defn- leaf-class
  "The token class for a LEAF node: what the CST can tell apart without
  guessing. Anything unrecognised is `\"text\"` — carried, never dropped,
  and never coloured on a hunch."
  [node]
  (let [tag (n/tag node)]
    (cond
      (#{:comment} tag)                     "comment"
      (#{:whitespace :newline :comma} tag)  "ws"
      (#{:multi-line} tag)                  "string"
      (not= :token tag)                     "text"
      :else
      (let [v (try (n/sexpr node) (catch Exception _ ::unknown))]
        (cond
          (string? v)                       "string"
          (keyword? v)                      "keyword"
          (number? v)                       "number"
          (and (symbol? v)
               (special-heads (name v)))    "special"
          :else                             "text")))))

(defn- tokens-of
  "A CST node as `[[class text] ...]` — the highlight stream for a form,
  walked out of the tree the store ALREADY has. No lexer, no dependency,
  no client script, and no regex over text: a string containing a paren is
  one node here, so it stays one token.

  A branch's own delimiters (`(`, `#{`, `^`, the string quotes) are not
  separate nodes, so they are recovered as the difference between the
  branch's printed form and its children's — which stays correct for
  delimiter shapes nobody enumerated.

  The invariant the specs pin: concatenating the text reproduces the
  source exactly."
  [node]
  (if-not (n/inner? node)
    [[(leaf-class node) (n/string node)]]
    (let [s     (n/string node)
          kids  (n/children node)
          inner (apply str (map n/string kids))]
      (if (empty? inner)
        [["delim" s]]
        (let [at   (str/index-of s inner)
              open (subs s 0 at)
              shut (subs s (+ at (count inner)))
              mid  (vec (mapcat tokens-of kids))]
          (cond-> mid
            (seq open) (->> (into [["delim" open]]))
            (seq shut) (conj ["delim" shut])))))))

(def ^:private fidelities
  "The rendering FIDELITIES a form page can be asked for. One value today —
  literal Clojure — and it is a set rather than an assumption because the
  labeled notation is a live follow-up. Carrying the parameter now costs
  nothing; adding it later would mean every permalink already in the wild
  silently meant \"whatever the default became\"."
  #{"clojure"})

(def graph-node-cap
  "The most nodes a `neighbourhood` will return before the depth runs out.

  A DEPTH cap alone does not bound this, and the numbers are the argument.
  Measured over slopp's own store (2404 forms), breadth-first from one form,
  both directions:

  | seed                        | 1 hop | 2 hops | 3 hops |
  |-----------------------------|-------|--------|--------|
  | `store/form-by-id` (hot)    |    38 |    418 |   1231 |
  | `ops.engine/rebased-write!` |    13 |    276 |   1008 |
  | `render/test-ns?` (leaf)    |    10 |     99 |    805 |

  Three hops from a hot form is HALF THE STORE. So the ceiling belongs on
  nodes, and the depth stays what the caller asked for rather than doubling as
  the thing that protects the wire. 250 is ~10% of this store and comfortably
  more than a reader follows; the exact number matters less than that going
  over it is REPORTED rather than silently trimmed."
  250)

(defn neighbourhood
  "The call graph around `form-id` out to `depth` hops, BOTH directions, as
  `{:depth :nodes :edges :truncated}` — nil below depth 2, where the one-hop
  cards already ARE the answer.

  **Each node appears ONCE and the edges carry the repetition.** A tree is the
  obvious shape and the wrong one: slopp-ui measured an 8-row expansion
  becoming 97, because a frequently-called helper drags its whole subtree in
  at every occurrence. A node set plus an edge list says the same thing in the
  size of the GRAPH rather than the size of its unfolding — and a consumer
  that wants a tree can still build one, while the reverse is not true.

  **Every edge keeps its `:via`.** A chain that is `static` end to end is a
  claim you can lean on; one `observed` rung is not, and only `:via` says
  which you are holding. `form-view`'s standing note is that this graph is a
  floor rather than a census; carrying `:via` at every depth is how a
  multi-hop view keeps that promise instead of laundering it.

  Nodes are LEAN — identity plus signature and doc. At depth 1 you are
  READING, which is why `:callers`/`:callees` inline the whole card; past that
  you are NAVIGATING, and 250 full cards is a payload nobody reads.

  `:truncated` appears only when `graph-node-cap` stopped the walk, and names
  the depth actually reached — so a short graph is never mistaken for a small
  neighbourhood."
  [session form-id depth]
  (let [st    (:store @session)
        depth (min 3 (or depth 1))]
    (when (>= depth 2)
      (let [out   (group-by :from-form (refs/refs st))
            byq   (refs/refs-by-target st)
            id-of (fn [ns- nm] (:id (store/form-named st ns- nm)))
            q-of  (fn [id] (let [e (store/form-by-id st id)]
                             (when (:name e)
                               (symbol (str (store/ns-of-form-id st id)) (str (:name e))))))
            out-of (fn [id]
                     (concat
                      (for [r (get out id)
                            :let [t (id-of (:to-ns r) (:to-name r))]
                            :when t]
                        {:from id :to t :via (name (:via r))})
                      (for [q (keep q-of [id])
                            r (get byq q)
                            :when (and (:from-var r) (:from-form r))]
                        {:from (:from-form r) :to id :via (name (:via r))})))]
        (loop [seen #{form-id} es #{} d 0]
          (if (= d depth)
            (let [nodes (vec (sort-by :form
                                      (for [i seen
                                            :let [q (q-of i)]
                                            :when q]
                                        (merge {:form-id i :form (str q)
                                                :ns (namespace q)
                                                :module (modules/module-of (symbol (namespace q)))}
                                               (select-keys
                                                (json-card
                                                 (orient/form-card session
                                                                   (symbol (namespace q))
                                                                   (symbol (name q))))
                                                [:sig :doc])))))]
              {:depth d
               :nodes nodes
               :edges (vec (sort-by (juxt :from :to :via)
                                    (filter #(and (seen (:from %)) (seen (:to %))) es)))})
            (let [new-es (into #{} (mapcat out-of) seen)
                  seen'  (into seen (mapcat (juxt :from :to)) new-es)]
              (cond
                ;; over the cap: keep the LAST complete level rather than a
                ;; half-expanded frontier, and say so
                (> (count seen') graph-node-cap)
                (assoc (neighbourhood session form-id d)
                       :truncated {:node-cap graph-node-cap :depth-reached d})

                ;; nothing new — the neighbourhood is genuinely this small, so
                ;; report the depth ASKED FOR rather than where the walk stopped
                (= seen' seen) (recur seen' (into es new-es) depth)

                :else (recur seen' (into es new-es) (inc d))))))))))

(defn ^:export form-view
  "One form's page model, addressed by form ID — ids are stable across
  edits and names are not, so the id is the permalink.

  Built for COLD arrival from a link (Debugger Canvas called the failure
  the \"lonely bubble\"): the breadcrumb says where this is, `:callers` and
  `:callees` are backlink CARDS grouped by the `:via` that found each edge,
  and both carry their own signature and doc INLINED rather than linked —
  Code Bubbles measured two-thirds of its win as concurrent visibility, and
  a link is not visibility. The two directions carry the SAME weight, because
  that argument does not care which way an edge points.

  `view` is the rendering FIDELITY (`:views` names the ones that exist).
  It carries one value on purpose: a labeled notation is a live follow-up,
  and adding the parameter later would mean every permalink already in the
  wild silently meant \"whatever the default became\". An unknown fidelity
  is nil — the same answer as an unknown id — never a quiet downgrade to
  the one that happens to exist.

  `depth` (2 or 3) adds `:graph`, the multi-hop NEIGHBOURHOOD — each node
  once with an edge list, never a tree. It is ADDITIVE: at depth 1 the
  response is what it always was, for the same reason the fidelity parameter
  was introduced rather than defaulted into.

  nil for an unknown id, so a page can 404 instead of rendering blank."
  ([session form-id] (form-view session form-id nil))
  ([session form-id view] (form-view session form-id view 1))
  ([session form-id view depth]
   (let [st (:store @session)
         e  (store/form-by-id st form-id)]
     (when (and e (:name e) (contains? fidelities (or view "clojure")))
       (let [ns-sym  (store/ns-of-form-id st form-id)
             nm      (:name e)
             qsym    (symbol (str ns-sym) (str nm))
             row     (fn [ns- var-]
                       (let [e (store/form-named st ns- var-)]
                         (cond-> {:form   (str (symbol (str ns-) (str var-)))
                                  :ns     (str ns-)
                                  :module (modules/module-of ns-)}
                           ;; ids are the permalink, so every edge on the page
                           ;; is one — a name would break the moment it changes
                           e (assoc :form-id (:id e)))))
             callers (->> (refs/refs-to st qsym)
                          (filter :from-var)
                          (group-by :via)
                          (sort-by (comp str key))
                          (mapv (fn [[via rs]]
                                  (let [by (sort-by (comp str first)
                                                    (group-by (juxt :from-ns :from-var) rs))]
                                    {;; a STRING: that is what the contract says, JSON has no keyword
                                     ;; type, and a consumer generating a client from the schema is
                                     ;; entitled to take it literally
                                     :via   (name via)
                                     :count (count by)
                                     :forms (mapv (fn [[[fns fvar] us]]
                                                    ;; the CARD, same as a
                                                    ;; callee row: inlining
                                                    ;; rather than linking is
                                                    ;; what makes a cold page
                                                    ;; answerable, and that
                                                    ;; argument does not care
                                                    ;; which way the edge points
                                                    (merge (row fns fvar)
                                                           {:calls (count (keep :arity us))}
                                                           (dissoc (json-card
                                                                    (orient/form-card session fns fvar))
                                                                   :form)))
                                                  by)}))))
             callees (->> (refs/refs st)
                          (filter #(= form-id (:from-form %)))
                          (group-by (juxt :to-ns :to-name))
                          (sort-by (comp str first))
                          (mapv (fn [[[tns tnm] us]]
                                  (merge (row tns tnm)
                                         {:via   (name (:via (first us)))
                                          :calls (count (keep :arity us))}
                                         (dissoc (json-card (orient/form-card session tns tnm))
                                                 :form)))))
             graph   (neighbourhood session form-id depth)]
         (cond-> (merge {:form-id form-id
                         :form    (str qsym)
                         :name    (str nm)
                         :ns      (str ns-sym)
                         :module  (modules/module-of ns-sym)
                         :view    (or view "clojure")
                         :views   (vec (sort fidelities))
                         :source  (n/string (:node e))
                         :tokens  (tokens-of (:node e))
                         :callers callers
                         :callees callees
                         :note    (str "edges come from a syntactic reader over the store, so this"
                                       " is a floor, not a census — a call reached through a"
                                       " binding or built at runtime is not here")}
                        (dissoc (json-card (orient/form-card session ns-sym nm)) :form))
           graph (assoc :graph graph)))))))

(defn module-index
  "The Code landing model: the architecture as FACTS a consumer can draw.

  Test namespaces are COUNTED, never listed. A `-test` namespace folds into
  its subject's module, so listing it puts two things at the same rung that
  are not peers — and on slopp's own store that means 103 of 186 rows are
  tests. The names are reachable from the namespace they cover, which is
  where 'what tests this?' actually gets asked.

  The count is by REACH, not by folding. Folding alone reported `slopp.git`
  as having no tests: its three test namespaces are top-level
  (`slopp.git-projection-test`), so they fold into modules of their own and
  none folds into `slopp.git`. A zero here is meant to be a FINDING, and one
  wrong zero devalues every other zero on the screen — so a test namespace
  counts for every module it requires into, as well as the one it folds into.

  **No picture.** This used to assemble one — placed boxes, routed edges, a
  canvas extent — on the reasoning that the layering comes from the store and
  the client should not analyse. The first half is right and the conclusion
  was wrong: LAYERING is analysis, PLACEMENT is drawing, and shipping
  coordinates meant the only consumer that could ever exist was one that
  wanted this exact diagram. It showed up the moment the UI became its own
  project — a layout namespace ported across, tests and all, with nothing
  left for it to do.

  So what crosses is `:layers` (a topological fact, and only the store can
  compute it) and each row's `:deps` (without which a consumer cannot draw an
  edge at all — their absence is precisely why the picture had to be built
  here). Where the boxes go is the consumer's business.

  `:deps` is the FOUNDATION-FREE manifest, matching `:layers`: an edge into
  the substrate is not drawn, and a consumer should not have to re-derive
  which those are when `:foundation` already says so."
  [session]
  (let [st         (:store @session)
        nses       (sort (keys (:namespaces st)))
        test?      #(str/ends-with? (str %) "-test")
        prod       (remove test? nses)
        by-module  (group-by modules/module-of prod)
        home       (into {} (map (juxt identity modules/module-of)) prod)
        ;; a test counts for every module it reaches into, plus its own
        reach      (fn [t] (conj (set (keep home (store/ns-requires st t)))
                                 (modules/module-of t)))
        test-tally (frequencies (mapcat reach (filter test? nses)))
        tiers      (:module-tiers st)
        manifest   (read.modules/production-manifest st)
        band       (read.modules/substrate manifest)
        ;; layer the graph WITHOUT the foundation: leaving it in stretches
        ;; every module above it a rung further from what it actually needs.
        reduced    (into {} (for [[m ds] manifest :when (not (band m))]
                              [m (vec (remove band ds))]))
        {:keys [layers cycles]} (store/module-layers reduced)]
    {:modules (mapv (fn [m]
                      {:module     m
                       :namespaces (mapv str (sort (get by-module m)))
                       :tests      (get test-tally m 0)
                       :tier       (name (get tiers m :external))
                       :foundation (contains? band m)
                       :deps       (vec (sort (get reduced m)))})
                    (sort (keys by-module)))
     :layers  (mapv vec layers)
     :cycles  (mapv vec cycles)}))

(defn module-detail
  "One module from the INSIDE: its production namespaces, the ns→ns edges
  among them, the layering those edges imply, and the edges crossing its
  boundary. nil for a module with no production namespaces, so a page can 404
  rather than render an empty frame.

  The level below `module-index`, and it makes the same split one rung down —
  `:layers` is analysis only the store can do, placement is the consumer's.

  Edges come from `module-usage-rows`, THE reference graph, which is the same
  producer `production-manifest` reads. That is the point: the descended view
  and the module view cannot disagree about what an edge is, and a second
  derivation here would be free to drift (the `:sig`-had-three-producers bug,
  one system over).

  An internal edge lands in a namespace's `:deps` and nowhere else. Repeating
  it under `:boundary` would draw every internal arrow twice, and `:boundary`
  answers a different question: which namespaces face OUT, and which of them
  anything outside actually reaches. A module whose `:in` names one namespace
  has a front door; one where `:in` names six does not, and that is a finding
  a reader should be able to see without opening anything."
  [session module]
  (let [st      (:store @session)
        module  (str module)
        test?   #(str/ends-with? (str %) "-test")
        member? #(and (not (test? %)) (= module (modules/module-of %)))
        members (into (sorted-set) (filter member?) (keys (:namespaces st)))]
    (when (seq members)
      (let [edges  (into #{} (comp (remove #(test? (:from-ns %)))
                                   (map (juxt :from-ns :to))
                                   (remove (fn [[f t]] (= f t))))
                         (modules/module-usage-rows st))
            inside (filter (fn [[f t]] (and (members f) (members t))) edges)
            out    (sort-by (juxt :from :to)
                            (for [[f t] edges :when (and (members f) (not (members t)))]
                              {:from (str f) :to (str t)
                               :to-module (modules/module-of t)}))
            in     (sort-by (juxt :from :to)
                            (for [[f t] edges :when (and (not (members f)) (members t))]
                              {:from (str f) :from-module (modules/module-of f)
                               :to (str t)}))
            by-ns  (reduce (fn [m [f t]] (update m f (fnil conj #{}) t))
                           (into {} (map (juxt identity (constantly #{}))) members)
                           inside)
            {:keys [layers cycles]}
            (store/module-layers
             (into {} (map (fn [[k v]] [(str k) (into #{} (map str) v)])) by-ns))]
        {:module     module
         :tier       (name (tiers/tier-for st (symbol module)))
         :namespaces (vec (for [n members]
                            {:ns    (str n)
                             :forms (count (store/forms st n))
                             :tier  (name (tiers/tier-for st n))
                             :deps  (vec (sort (map str (get by-ns n))))}))
         :boundary   {:out (vec out) :in (vec in)}
         :layers     (mapv vec layers)
         :cycles     (mapv vec cycles)}))))

(defn tests-covering
  "The test namespaces that require `nsx` directly — what to open when the
  question is 'what tests this?'.

  This is the other half of taking tests out of the nav. Removing 103 rows
  from a listing is only an improvement if the names come back where they
  answer something, and the namespace page is that place.

  DIRECT requires only. A transitive closure on a real store reaches most of
  the suite and so distinguishes nothing, which is the same reason
  `covered-by` bounds its static reach. The trade is honest and worth
  naming: a test that exercises this namespace through an intermediary is
  not listed here. Form-granular coverage — with observed-versus-static
  provenance — is `slopp.index.refs/covered-by`'s job, and it answers a
  narrower question than a namespace page asks."
  [store nsx]
  (let [sym   (symbol (str nsx))
        test? #(str/ends-with? (str %) "-test")]
    (->> (keys (:namespaces store))
         (filter test?)
         (filter #(some #{sym} (store/ns-requires store %)))
         (map str)
         sort
         vec)))

(defn outline-metrics
  "The per-form facts a consumer needs to RANK a namespace's definitions,
  keyed by form name: `{:mass :calls :callers-out :callers-out-test
  :effectful? :exported?}`.

  Facts, not a score. slopp-ui asked for exactly this split and it is the
  same one `module-index` already makes by shipping layers and not a
  drawn `:picture`: the call graph and the size of a form have one right
  answer and only the store can see them; how to weight them into
  `importance` and how many perceptible steps that becomes is drawing,
  and a consumer must be able to tune it without a slopp release.

  **`:mass` is a node count over the SEXPR, never the CST.** rewrite-clj
  nodes carry whitespace and comments, so counting them would put
  formatting straight back into the metric that node-counting exists to
  escape — and lines and characters have the same bug, only louder: a
  40-line docstring over a 30-line body makes the documentation win. Over
  the sexpr a docstring is ONE node and the body's structure dominates.

  **`:calls` is same-namespace direct EDGES**, from THE reference graph,
  so a form reached through a carrier position counts as called — a
  `:calls` built from resolved calls alone draws dispatch targets as
  leaves, and those are the forms that matter most. Edges rather than
  the transitive closure because the walk is cheap and reusable, while a
  closure cannot be taken apart again. Empty rather than absent: a leaf
  is an answer.

  **Callers outside the namespace are TWO numbers, and this was measured
  rather than reasoned.** `:callers-out` counts production namespaces and
  `:callers-out-test` counts test ones. Run against `slopp-ui.views` while
  it was still a single integer, it was ranking by TEST COUNT: ten of the
  twelve cross-namespace callers were deftests, the top-ranked form held
  first place on four of them, and the entry point that IS the render sat
  fourth on its one production caller. The two add back up; one integer
  cannot be taken apart again. Outbound fan-in is the half that
  fan-in-alone gets wrong either way — an entry point is the most
  important form in its namespace and nothing there calls it.

  `:declared` edges are excluded from both directions. A `^{:covers}`
  marker is a declaration ABOUT a form, not a call to it, and counting it
  would make a well-marked helper look load-bearing."
  [store nsx]
  (let [sym      (symbol (str nsx))
        eff      (query/ns-effectful-vars store sym)
        inward   (refs/refs-by-target store)
        call?    #(not= :declared (:via %))
        test-ns? #(not= (str %) (str (modules/fold-test-ns %)))
        outside  (fn [q from-ns?]
                   (into #{}
                         (comp (filter call?)
                               (filter :from-var)
                               (remove #(= sym (:from-ns %)))
                               (filter #(from-ns? (:from-ns %)))
                               (map (juxt :from-ns :from-var)))
                         (get inward q)))
        mass     (fn [node]
                   (if-some [s (store/form-sexpr node)]
                     (count (tree-seq coll? seq s))
                     0))
        calls    (reduce (fn [m r]
                           (if (and (call? r) (:from-var r) (= sym (:to-ns r)))
                             (update m (:from-var r) (fnil conj #{}) (str (:to-name r)))
                             m))
                         {}
                         (refs/ns-refs store sym))]
    (into {}
          (for [e (store/forms store sym)
                :when (:name e)
                :let [nm (:name e)
                      q  (symbol (str sym) (str nm))]]
            [(str nm)
             {:mass             (mass (:node e))
              :calls            (vec (sort (get calls nm)))
              :callers-out      (count (outside q (complement test-ns?)))
              :callers-out-test (count (outside q test-ns?))
              :effectful?       (contains? eff q)
              :exported?        (boolean (modules/export-level store sym nm))}]))))
