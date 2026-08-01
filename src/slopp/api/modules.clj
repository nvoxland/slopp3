(ns slopp.api.modules
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
            [slopp.store :as store] [slopp.edit.modules :as modules] [slopp.edit.refs :as refs] [slopp.api.orient :as orient] [clojure.set :as set]))

(def tiers-resource-path
  "Where a build writes its purity tiers and where `deps_add` looks for them.

  A classpath resource, under the source root, so it rides into a published
  jar with the code it describes — which is the whole point: the tier is a
  declaration in the PRODUCER's store, and until now only the code travelled.
  Named once because two sides have to agree on it."
  "META-INF/slopp/tiers.edn")

(defn tiers-resource
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

(defn modules-config-entry
  "The module manifest PROJECTED as a structured-config entry — how the
  edge fold becomes a `modules` file in git commits and builds (read-only
  transparency; writes go through module_dep)."
  [store]
  (when (seq (:modules store))
    {:format :manifest
     :values (into (sorted-map)
                   (map (fn [[m ds]] [m (clojure.string/join " " (sort ds))]))
                   (:modules store))}))

(defn module-usage-rows
  "Every store-internal usage row ({:from-ns :from-var :to :to-export}) for
  the debt view and the drift (declared-but-unused) view — consumed from
  THE reference graph (edit.refs), so carrier references count as usage
  exactly like resolved calls; declarations don't (they aren't calls)."
  [store]
  (vec (for [r (refs/refs store)
             :when (not= :declared (:via r))]
         {:from-ns   (:from-ns r)
          :from-var  (:from-var r)
          :to        (:to-ns r)
          :to-export (modules/export-level store (:to-ns r) (:to-name r))})))

(defn ^{:export "slopp.ui-api"} production-manifest
  "Module dependency edges from PRODUCTION namespaces only — the
  architecture VIEW's graph. A `-test` namespace folds into its subject
  module (module-of strips `-test`), so its fixture deps would manufacture
  cycles that don't exist in production; excluding them tells the truth.
  Every production module is a key (external ones → layer 0). The stored
  manifest still carries the test edges — this derivation is for
  layers/cycles, not for enforcement.

  Exported to the `slopp.ui-api` subtree because that is the architecture view:
  the Code screen draws this graph. Not public — no other caller has asked."
  ([store] (production-manifest store (module-usage-rows store)))
  ([store rows]
   (let [prod? #(not (str/ends-with? (str %) "-test"))
         base  (into {} (map (fn [n] [(modules/module-of n) #{}]))
                     (filter prod? (keys (:namespaces store))))]
     (reduce (fn [m {:keys [from-ns to]}]
               (if (prod? from-ns)
                 (let [a (modules/module-of from-ns) b (modules/module-of to)]
                   (if (= a b) m (update m a (fnil conj #{}) b)))
                 m))
             base rows))))

(defn module-debt
  "Whole-store module violations under the store's CURRENT manifest —
  compact rows, G13-capped — the debt a manifest change reveals (per-write
  gates block NEW violations; the advisory shows what already stands).
  Pass precomputed `rows` (module-usage-rows) to share the kondo pass."
  ([store] (module-debt store (module-usage-rows store)))
  ([store rows]
   (when-let [manifest (modules/modules-manifest store)]
     (let [vs (modules/module-violations manifest rows)]
       (when vs
         {:rows (vec (take 20 (map #(select-keys % [:from-ns :from-var :target-ns :rule]) vs)))
          :count (count vs)})))))

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
        manifest (or (modules/modules-manifest st) {})
        module?  (<= (count (str/split m #"\.")) 2)
        nses     (if module?
                   (filter #(= m (modules/module-of %)) (keys (:namespaces st)))
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
                             sig (first (filter vector? s))
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
        (not module?) (assoc :tier   (modules/tier-for st (symbol m))
                             :within (modules/module-of (symbol m)))))))

(defn unused-report
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
                   :let [s (try (n/sexpr (:node e)) (catch Exception _ nil))]
                   :when (and (seq? s)
                              (contains? #{'defn 'def} (first s))
                              (not (:private (meta (second s))))
                              (not= '-main (:name e)))
                   :let [rs (get by-target [nsx (:name e)])
                         markers (disj (set (keep :marker rs)) :covers)
                         real?   (some #(not= :declared (:via %)) rs)]]
               {:q          (symbol (str nsx) (str (:name e)))
                :unused-ok? (contains? markers :unused-ok)
                :exempt?    (boolean (or (seq markers)
                                          ;; ^:generated wrappers await FE calls —
                                          ;; available surface, not dead
                                          (:generated (meta (second s)))))
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
  (let [rank   modules/tier-order
        prod   (remove #(str/ends-with? (str %) "-test")
                       (keys (:namespaces store)))
        tiers  (:module-tiers store)
        by-mod (group-by modules/module-of prod)]
    {:declared (into (sorted-map)
                     (map (fn [[m t]] [m (modules/canonical-tier t)]))
                     tiers)
     :could-tighten
     (into (sorted-map)
           (keep (fn [[m ns-list]]
                   (let [declared (modules/canonical-tier (get tiers m :external))
                         supports (last (sort-by rank
                                                 (map #(:supports
                                                        (modules/tier-report store %))
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
  read-back at the tail of the op is the whole cost."
  [store rows]
  (vec (sort (for [{:keys [to name to-export]} rows
                   :when to-export
                   :when (not (modules/export-level store to name))]
               (symbol (str to) (str name))))))

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

(defn merge-production-cycle
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
