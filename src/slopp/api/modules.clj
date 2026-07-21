(ns slopp.api.modules
  (:require [clojure.string :as str]
            [rewrite-clj.node :as n]
            [slopp.store :as store] [slopp.edit.modules :as modules] [slopp.edit.refs :as refs]))

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

(defn production-manifest
  "Module dependency edges from PRODUCTION namespaces only — the
  architecture VIEW's graph. A `-test` namespace folds into its subject
  module (module-of strips `-test`), so its fixture deps would manufacture
  cycles that don't exist in production; excluding them tells the truth.
  Every production module is a key (external ones → layer 0). The stored
  manifest still carries the test edges — this derivation is for
  layers/cycles, not for enforcement."
  [store rows]
  (let [prod? #(not (str/ends-with? (str %) "-test"))
        base  (into {} (map (fn [n] [(modules/module-of n) #{}]))
                    (filter prod? (keys (:namespaces store))))]
    (reduce (fn [m {:keys [from-ns to]}]
              (if (prod? from-ns)
                (let [a (modules/module-of from-ns) b (modules/module-of to)]
                  (if (= a b) m (update m a (fnil conj #{}) b)))
                m))
            base rows)))

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
                                  (contains? '#{defn defmacro def} (first s))
                                  (symbol? (second s))
                                  (not (:private (meta (second s))))
                                  (or (not deep?)
                                      (:export (meta (second s)))))
                       :let [doc (first (filter string? (take 2 (drop 2 s))))
                             sig (first (filter vector? s))
                             ex  (:export (meta (second s)))]]
                   (cond-> {:ns nsx :name (second s)}
                     sig             (assoc :sig sig)
                     doc             (assoc :doc (first (str/split-lines doc)))
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
                         markers (set (keep :marker rs))
                         real?   (some #(not= :declared (:via %)) rs)]]
               {:q          (symbol (str nsx) (str (:name e)))
                :unused-ok? (contains? markers :unused-ok)
                :exempt?    (boolean (seq markers))
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
