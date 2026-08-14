(ns slopp.read.modules
  "The MODULE system's read side: what the architecture IS, derived rather
  than declared.

  A module is the first two segments of a namespace, which makes membership a
  fact about the name and not a registry anyone can forget to update. What
  lives here is everything that reads that structure back — the production
  manifest (edges with test namespaces removed, since a test reaching into a
  module is not the module depending on it), the substrate (which modules
  everything rests on), the browsable surface, and the reports the module
  gates cite when they refuse.

  The WRITE side is `slopp.edit.modules`: declaring an edge, a tier, a
  platform. This namespace never changes the manifest, it answers questions
  about it.

  It answers in FACTS. `substrate` arrived here from the reviewer UI's graph
  namespace when the UI became a separate project and the split made the line
  visible: naming the foundation is analysis, and only something holding the
  store can do it; placing those modules on a canvas is drawing, and belongs
  to whoever is rendering. Anything here that starts describing pixels has
  crossed back."
  (:require [clojure.string :as str]
            [rewrite-clj.node :as n]
            [slopp.store :as store] [slopp.edit.modules :as edit.modules] [slopp.index.refs :as refs] [slopp.read.orient :as orient] [clojure.set :as set] [slopp.edit.tiers :as tiers]))

(def ^:export tiers-resource-path
  "Where a build writes its purity tiers and where `deps_add` looks for them.

  A classpath resource, under the source root, so it rides into a published
  jar with the code it describes — which is the whole point: the tier is a
  declaration in the PRODUCER's store, and until now only the code travelled.
  Named once because two sides have to agree on it."
  "META-INF/slopp/tiers.edn")

(defn ^:export tiers-resource
  "The purity register projected for PUBLICATION — `{path tier}`, sorted, or
  nil when nothing is declared.

  Every declared tier travels, not only the `:pure` ones. A consumer that
  knows a namespace is `:external` knows something real; the alternative is
  inferring it from absence, which is exactly the mistake this fixes — an
  undeclared namespace reads as `:external` whether it was decided or never
  considered, and those are different facts."
  [store]
  (when (seq (:module-tiers store))
    (into (sorted-map) (:module-tiers store))))

(defn ^:export modules-config-entry
  "The module manifest PROJECTED as a structured-config entry — how the
  edge fold becomes a `modules` file in git commits and builds (read-only
  transparency; writes go through module_dep).

  Test-only edges project too, suffixed `(test)`, because a projection that
  showed only production would read as the whole manifest and quietly hide
  the declarations that permit a fixture to cross."
  [store]
  (let [test-edges (edit.modules/module-test-manifest store)]
    (when (or (seq (:modules store)) (seq test-edges))
      {:format :manifest
       :values (into (sorted-map)
                     (map (fn [m]
                            [m (clojure.string/join
                                " " (concat (sort (get (:modules store) m))
                                            (map #(str % " (test)")
                                                 (sort (get test-edges m)))))]))
                     (distinct (concat (keys (:modules store)) (keys test-edges))))})))

(defn ^:export production-manifest
  "Module dependency edges from PRODUCTION namespaces only — the
  architecture VIEW's graph. Two kinds of namespace are excluded, and they
  are one idea rather than two:

  - a `-test` namespace folds into its subject module (module-of strips
    `-test`), so its fixture deps would manufacture cycles that don't exist
    in production;
  - an `:instrument` namespace (`store/role-for`) is code a HUMAN runs by
    hand, so counting it stands a harness on top of the thing it measures.
    Measured on slopp's own store: `slopp.lab` sat at layer 8, the APEX of
    the product layer map, which made every layering statement about slopp
    read as though a benchmark harness were its highest concern.

  Every remaining production module is a key (external ones → layer 0). The
  stored manifest still carries the test edges — this derivation is for
  layers/cycles, not for enforcement.

  Module surface. It was scoped to the `slopp.http-api` subtree — the Code
  screen draws this graph — back when the operation surface lived in this same
  module and needed no marker to reach it. The regroup put the two callers in
  different subtrees, and a scoped export names exactly one."
  ([store] (production-manifest store (edit.modules/module-usage-rows store)))
  ([store rows]
   (let [prod? #(and (not (str/ends-with? (str %) "-test"))
                     (not= :instrument (store/role-for store %)))
         base  (into {} (map (fn [n] [(edit.modules/module-of n) #{}]))
                     (filter prod? (keys (:namespaces store))))]
     (reduce (fn [m {:keys [from-ns to]}]
               (if (prod? from-ns)
                 (let [a (edit.modules/module-of from-ns) b (edit.modules/module-of to)]
                   (if (= a b) m (update m a (fnil conj #{}) b)))
                 m))
             base rows))))

(defn ^:export module-debt
  "Whole-store module violations under the store's CURRENT declarations —
  compact rows, G13-capped — the debt a manifest change reveals (per-write
  gates block NEW violations; this shows what already stands).
  Pass precomputed `rows` (module-usage-rows) to share the kondo pass.

  nil for a pre-adoption store, which is the one case where the rules are
  off entirely — [[modules/store-violations]] answers that from `:modules`
  being nil, so this asks about the store rather than holding a manifest of
  its own."
  ([store] (module-debt store (edit.modules/module-usage-rows store)))
  ([store rows]
   (when-let [vs (edit.modules/store-violations store rows)]
     {:rows (vec (take 20 (map #(select-keys % [:from-ns :from-var :target-ns :rule]) vs)))
      :count (count vs)})))

(defn module-surface
  "What `m` OFFERS, where `m` is a MODULE (`logi.parcel`) or any NAMESPACE
  PATH inside one (`logi.parcel.impl.calc`) — compact rows
  `{:ns :name :sig :doc :export}`, `-test` namespaces and `^:private` vars
  excluded, plus `:deps` (declared edges) and `:consumers`.

  Namespace grain exists because tiers do: a pure core routinely lives one
  level below an effectful module, and `tier-for` resolves most-specific-wins.
  A surface view that could only address modules could not answer \"what does
  this offer?\" at the grain that carries the architecture.

  For a MODULE the surface is its depth<=2 namespaces plus every deeper var
  widened by `:export` — the OUTSIDE world's view. For a deeper NAMESPACE it
  is that namespace and anything under it WITHOUT the export filter: inside a
  module everything is already visible, so filtering by `:export` there would
  hide most of what a same-module caller may legitimately call.

  The cheap browse before calling in."
  [session m]
  (let [st       (:store @session)
        m        (str m)
        manifest (or (edit.modules/modules-manifest st) {})
        module?  (<= (count (str/split m #"\.")) 2)
        nses     (if module?
                   (filter #(= m (edit.modules/module-of %)) (keys (:namespaces st)))
                   (filter #(or (= m (str %)) (str/starts-with? (str %) (str m ".")))
                           (keys (:namespaces st))))
        rows     (for [nsx  (sort nses)
                       :when (not (str/ends-with? (str nsx) "-test"))
                       :let [deep? (and module?
                                        (> (count (str/split (str nsx) #"\.")) 2))]
                       e    (store/forms st nsx)
                       :let [s (try (n/sexpr (:node e)) (catch Exception _ nil))]
                       :when (and (seq? s)
                                  (contains? #{"defn" "defmacro" "def" "defmulti" "defprotocol"}
                                             (str (first s)))
                                  (symbol? (second s))
                                  (not (:private (meta (second s))))
                                  (or (not deep?)
                                      (:export (meta (second s)))))
                       :let [doc (first (filter string? (take 2 (drop 2 s))))
                             ;; the SHARED all-arities extraction, gated to forms
                             ;; that have arities. This was "the first vector in
                             ;; the form", so `(def rates [0.07 0.20])` offered
                             ;; itself as taking two arguments — on the very view
                             ;; whose job is the cheap browse before calling in.
                             sig (let [as (when (#{"defn" "defn-" "defmacro"} (str (first s)))
                                            (edit.modules/fn-arglists s))]
                                   (cond (= 1 (count as)) (first as)
                                         (seq as)         (vec as)))
                             ex  (:export (meta (second s)))]]
                   (cond-> {:ns nsx :name (second s)}
                     sig             (assoc :sig sig)
                     doc             (assoc :doc (orient/doc-summary doc))
                     (and deep? ex)  (assoc :export (if (true? ex) true (str ex)))))]
    (if (empty? nses)
      {:error (str "nothing named " m
                   " — query_depends {modules true} lists the modules, and a"
                   " namespace path inside one also works")}
      (cond-> {:module m :surface (vec rows)}
        module?       (assoc :deps      (vec (sort (get manifest m #{})))
                             :consumers (vec (sort (keep (fn [[k deps]]
                                                           (when (contains? deps m) k))
                                                         manifest))))
        (not module?) (assoc :tier   (tiers/tier-for st (symbol m))
                             :within (edit.modules/module-of (symbol m)))))))

(defn ^:export unused-report
  "PUBLIC defn/def vars in `nses` with NO references in THE graph
  (edit.refs — static, carrier, and declared records all count):
  {:unused [q ...]   ; nothing references it and no marker declares why —
                     ; dead code or unadvertised surface; gate-failing.
                     ; Delete it, mark ^:unused-ok, or ^:entry-point.
   :stale  [q ...]}  ; carry ^:unused-ok but static/carrier references
                     ; exist — remove the flag. (^:entry-point has no
                     ; stale symmetry: the outside world is unverifiable.)
  Self-calls never count; -main, privates, and test namespaces are exempt.
  Kondo covers unused PRIVATES per-namespace; this is the whole-store
  public counterpart, and it consumes the ONE reference graph — no
  private source fusion."
  [store nses]
  (let [by-target (group-by (juxt :to-ns :to-name) (refs/refs store))
        rows (for [nsx nses
                   :when (not (clojure.string/ends-with? (str nsx) "-test"))
                   e (store/forms store nsx)
                   :when (:name e)
                   :let [s  (try (n/sexpr (:node e)) (catch Exception _ nil))
                         nm (store/form-name-meta e)]
                   :when (and (seq? s)
                              (contains? #{'defn 'def} (first s))
                              (not (:private nm))
                              (not= '-main (:name e)))
                   :let [rs (get by-target [nsx (:name e)])
                         markers (disj (set (keep :marker rs)) :covers)
                         real?   (some #(not= :declared (:via %)) rs)]]
               {:q          (symbol (str nsx) (str (:name e)))
                :unused-ok? (contains? markers :unused-ok)
                :exempt?    (boolean (or (seq markers)
                                          ;; ^:generated wrappers await FE calls —
                                          ;; available surface, not dead
                                          (:generated nm)))
                :real?      (boolean real?)})]
    {:unused (vec (sort (keep #(when-not (or (:real? %) (:exempt? %)) (:q %))
                              rows)))
     :stale  (vec (sort (keep #(when (and (:real? %) (:unused-ok? %)) (:q %))
                              rows)))}))

(defn purity-standing
  "Where every module STANDS on the functional-core gate — the write-time
  purity check read back as a report over existing code.

  Returns `{:declared {module tier} :could-tighten {module {:declared d
  :supports s}}}`. `:could-tighten` names modules whose current forms would
  satisfy a STRICTER tier than they claim, which is the whole worklist for
  adopting the gate on a codebase that predates it. A module is judged by its
  weakest production namespace, since the tier binds all of them.

  Tiers rank and report in the canonical vocabulary
  (:pure/:internal/:external); stores that predate the rename may carry
  :reads/:effects, which normalize on read.

  Test namespaces are excluded: they exercise effects on purpose and would
  veto every module."
  [store]
  (let [rank   tiers/tier-order
        prod   (remove #(str/ends-with? (str %) "-test")
                       (keys (:namespaces store)))
        tiers  (:module-tiers store)
        by-mod (group-by edit.modules/module-of prod)]
    {:declared (into (sorted-map)
                     (map (fn [[m t]] [m (tiers/canonical-tier t)]))
                     tiers)
     :could-tighten
     (into (sorted-map)
           (keep (fn [[m ns-list]]
                   (let [declared (tiers/canonical-tier (get tiers m :external))
                         supports (last (sort-by rank
                                                 (map #(:supports
                                                        (tiers/tier-report store %))
                                                      ns-list)))]
                     (when (< (rank supports) (rank declared))
                       [m {:declared declared :supports supports}]))))
           by-mod)}))

(defn ^:export unlanded-exports
  "Of the move `rows` that PLANNED an export (`:to-export` truthy), the ones
  the store does NOT actually carry — a postcondition, read back from the
  committed value rather than from the plan.

  Verification must check REALITY, not intent. The incident this exists for:
  the marker pass silently skipped META-WRAPPED names, so `(def ^:dynamic
  *pre-commit-hook* …)` came out of a move unexported while the move's own
  gate pre-check passed — because the pre-check consulted the PLANNED export
  and the store carried no marker. It surfaced a session later, via the debt
  view, which reads reality.

  An operation that reports what it MEANT to do is indistinguishable from one
  that did it, and the difference only shows up somewhere else, later. One
  read-back at the tail of the op is the whole cost.

  Reads the callee off `:to-name`, which every module row now carries. While
  the destination rows omitted it this asked `export-level` about a var named
  nil, found nothing, and reported every LANDED export as unlanded — 39
  phantom findings on one move, each naming a bare namespace and no var. A
  postcondition that cannot name its subject is not a weaker check, it is a
  wrong one."
  [store rows]
  (vec (sort (for [{:keys [to to-name to-export]} rows
                   :when to-export
                   :when (not (edit.modules/export-level store to to-name))]
               (symbol (str to) (str to-name))))))

(defn ^:export substrate
  "The modules to draw as a FOUNDATION BAND rather than as nodes with edges.

  The band exists to stop drawing edges that carry no information: \"everything
  rests on the store\" reads better as position than as eight arrows. Two ways
  in, both computed, so this generalises to a store nothing like slopp's own:

  - a SINK (nothing in the graph it depends on) that at least TWO modules use.
    One dependent is not enough — that single edge is informative, and banding
    it would move the module away from its only consumer.
  - a HUB whose own dependencies are all banded sinks and whose fan-in reaches
    a quarter of the graph (minimum 3). That is what catches a `store`-shaped
    module: everyone calls it, it calls almost nothing.

  Promotion is ONE level deep on purpose. Cascading walks up the graph and
  swallows real components — on slopp's own manifest an unbounded rule reaches
  `git` and `image`, which are foundation by no reading.

  A graph with no sinks (everything mutually entangled) yields the empty set
  and every edge gets drawn. That degradation is correct: there is no
  foundation to name, and saying so is the honest picture."
  [manifest]
  (let [nodes     (set (keys manifest))
        deps-of   (fn [m] (filter nodes (get manifest m)))
        sinks     (into #{} (filter #(empty? (deps-of %))) nodes)
        fan-in    (frequencies (mapcat deps-of nodes))
        banded    (into #{} (filter #(and (sinks %) (<= 2 (get fan-in % 0)))) nodes)
        threshold (max 3 (quot (+ (count nodes) 3) 4))
        hub?      (fn [m] (and (not (sinks m))
                               (<= threshold (get fan-in m 0))
                               (every? banded (deps-of m))))]
    (set/union banded (into #{} (filter hub?) nodes))))

(defn ^:export merge-production-cycle
  "The PRODUCTION module cycle a merge CREATED, or nil.

  `before`/`after` are the store either side of the merge. Reported only
  when the merge actually GAINED a module edge: the graph is otherwise
  unchanged, and re-announcing a standing cycle on every unrelated merge
  is noise rather than news.

  Judged over `production-manifest`, never the declared one. A `-test`
  namespace folds into its subject module, so its fixture requires ARE
  declared edges and manufacture back-edges that exist in no production
  code. slopp's own store is the worked example: `slopp.store.db-test`
  requires `slopp.api`, which closes
  `slopp.api -> slopp.edit -> slopp.image -> slopp.store -> slopp.api`, so
  every merge into main warned of a cycle whose advice — retract an edge —
  would have broken the test that created it. Every other cycle surface
  (the graph view, `module_extract`'s plan, the write-time gate) already
  judged production edges; the merge note was the one that didn't.

  Lives here rather than in `slopp.store.merge` for a layering reason: the
  production derivation needs `module-of` and the usage rows, which sit
  well above the store, and the manifest can only be taken AFTER the merge
  has produced its store."
  [before after]
  (when (some (fn [[m deps]]
                (seq (remove (get (:modules before) m #{}) deps)))
              (:modules after))
    (store/modules-cycle (production-manifest after))))

(defn overstated-edges
  "Declared PRODUCTION edges that only `-test` namespaces cross — the manifest
  asserting a dependency the production code does not have.

  Sibling of the unused-edge report, and a DIFFERENT question: unused means
  nothing crosses at all, so an unused check structurally cannot see these.
  What makes them worth reporting rather than filing as tidiness is that
  declared edges are what the CYCLE check reads — an overstated edge is a
  production edge as far as `module_dep` is concerned, so it can refuse a
  legitimate declaration in a module that has nothing to do with it. Four
  stood in slopp's own manifest on 2026-08-02 and one of them blocked a
  regroup that had no relationship to it.

  Asked only of modules that HAVE production code: a module made entirely of
  tests can only ever be crossed by tests, so every edge it declares would
  answer yes and the finding would be noise — 80 rows over slopp's own store
  against 4 real ones. `production-manifest` already keys exactly the modules
  with a production namespace, so the restriction costs no second predicate.

  `module_dep {.. test_only true}` then `{.. remove true}` states it honestly.
  Rows are sorted `[module dep]` pairs."
  ([store] (overstated-edges store (edit.modules/module-usage-rows store)))
  ([store rows]
   (let [prod   (production-manifest store rows)
         actual (into #{}
                      (map (fn [{:keys [from-ns to]}]
                             [(edit.modules/module-of from-ns) (edit.modules/module-of to)]))
                      rows)]
     (vec (for [[m ds] (sort (edit.modules/modules-manifest store))
                d      (sort ds)
                :when  (and (contains? prod m)
                            (contains? actual [m d])
                            (not (contains? (get prod m #{}) d)))]
            [m d])))))

(defn ^:export canonical-alias
  "The one alias `ns-sym` should be required under: its shortest trailing
  segments that name exactly ONE namespace in this store, as a symbol.

  `slopp.lab` → `lab`. `slopp.read.modules` → `read.modules`, because
  `slopp.edit.modules` claims the same last segment and neither is more
  entitled to it — they BOTH widen, which is what makes the answer
  deterministic rather than a tiebreak somebody has to remember.

  DERIVED, deliberately, rather than declared per namespace. A declared alias
  is a second name for a thing that already has one, kept by hand, free to
  drift from what it describes — and a rename would have to carry it. A
  derivation cannot be wrong about a namespace it is computed from.

  The store is the scope, so external libs are not governed: `clojure.string
  :as str` is a convention slopp does not own and should not restate."
  [store ns-sym]
  (let [nses (map str (keys (:namespaces store)))
        sg   (str/split (str ns-sym) #"\.")]
    (symbol
     (loop [k 1]
       (let [tail (str/join "." (take-last k sg))
             claims (filter #(or (= % tail) (str/ends-with? % (str "." tail))) nses)]
         (if (or (= 1 (count claims)) (>= k (count sg)))
           tail
           (recur (inc k))))))))

(defn ^:export alias-drift
  "Every `:require` of a STORE namespace under something other than its
  `canonical-alias` — `[{:ns :lib :as :canonical}]`, sorted, plus
  `:ambiguous` on the rows that matter most.

  Why this is worth a check when the tools are immune to it: the reference
  graph is kondo-resolved and alias-blind, so renames, sweeps and
  `query_depends` were never confused. A READER is. When `modules` names
  `slopp.edit.modules` in one namespace and `slopp.read.modules` in another,
  `modules/module-surface` in a slice is two different functions and nothing
  on the page says which.

  **`:ambiguous` is the half a reader can be WRONG about**, and it carries
  every namespace the alias resolves to across this store. The rest is
  residue — a rename moved the namespace and left the `:as` behind — which
  costs a reader time and not correctness. Measured on a real store by
  slopp-ui: 20 rows, 2 ambiguous. Reporting all twenty identically meant the
  two were indistinguishable from the eighteen until a human worked out which
  aliases collide, which is a grade describing the alias (is it canonical)
  where the reader is asking about the consequence (does it mean two things
  here). Both halves still report: an unmarked row is not a non-finding.

  Ambiguity is judged over EVERY require, not just the drifted ones. An alias
  can be canonical for one namespace and drifted onto another, and it is the
  drifted row that reports — but it is only ambiguous BECAUSE of the
  canonical one, which would be invisible to a scan of drift alone.

  It also removes the one way a hand sweep can be wrong where the graph is
  right: enumerating call sites by searching for `alias/name` covers a subset
  when the namespace answers to several aliases, and the search reports what
  it found rather than what is there.

  **Reads the `ns` FORM, never the rendered text.** The first version scanned
  source with a regex and matched `[lib :as x]` inside STRING literals — a
  test fixture's own `(ns … (:require …))` — so it reported aliases that do
  not exist in any ns form, and `ns_realias` correctly refused them. A
  fixture's source is data; this is the same trap `ns_realias`'s
  `:left-behind` exists to report rather than rewrite.

  Namespace-grained and whole-store, like `empty-namespaces`: an `:as` is a
  relationship between two namespaces rather than a property of a form, and
  the drift is usually OLDER than any episode that would carry it to a
  done-advisory. `ns_realias` is the remedy, one namespace at a time.

  External libs are excluded — `clojure.string :as str` is not slopp's
  convention to police."
  [store]
  (let [requires (for [ns-sym (keys (:namespaces store))
                       :let [e (store/form-named store ns-sym ns-sym)
                             sx (when e (try (n/sexpr (:node e)) (catch Exception _ nil)))]
                       :when (seq? sx)
                       clause (rest sx)
                       :when (and (seq? clause) (= :require (first clause)))
                       spec (rest clause)
                       :when (and (vector? spec) (symbol? (first spec)))
                       :let [lib (first spec)
                             as  (second (drop-while #(not= :as %) spec))]
                       :when (and as (contains? (:namespaces store) lib))]
                   {:ns ns-sym :lib lib :as as})
        ;; over EVERY require, canonical or not — see the docstring
        by-alias (reduce (fn [m {:keys [as lib]}]
                           (update m as (fnil conj (sorted-set)) lib))
                         {} requires)]
    (vec
     (sort-by (juxt (comp str :ns) (comp str :lib))
              (for [{:keys [ns lib as]} requires
                    :let [want (canonical-alias store lib)]
                    :when (not= as want)
                    :let [libs (get by-alias as)]]
                (cond-> {:ns ns :lib lib :as as :canonical want}
                  (< 1 (count libs)) (assoc :ambiguous (vec libs))))))))

(defn ^:export empty-namespaces
  "Namespaces holding nothing but their own `ns` form, sorted.

  A HUSK is what a move leaves behind when it carries a namespace's whole
  contents somewhere else — `slopp.web-rules-test` after the R6 rules move
  took its tests to `slopp.rules.web-test`. It survived two days and a green
  `full_check`, because a husk is invisible to every other check by
  construction: there is no form to be dead, undocumented, uncovered or
  unreachable, and `namespace-purpose` deliberately EXEMPTS an empty namespace
  since a newborn one has nothing to describe yet.

  The exemption is the second reason. The first is ADDRESSING: a done-advisory
  is handed changed FORM IDS, and `rules/sweep-store!` builds its whole-store
  population the same way (`mapcat store/forms`), so a namespace with zero
  forms is in neither population and no rule can reach it however it is
  written. That is why this is a namespace-grained read rather than another
  rule.

  Reported by `full_check`, never refused — the newborn case is real, and it
  is discharged by the same act that ends it. `ns_delete` is the remedy for a
  genuine husk."
  [store]
  (vec (sort (for [ns-sym (keys (:namespaces store))
                   :let [es (store/forms store ns-sym)]
                   :when (empty? (remove #(= (str (:name %)) (str ns-sym)) es))]
               ns-sym))))
