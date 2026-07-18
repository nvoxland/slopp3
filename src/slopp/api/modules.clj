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
  Every production module is a key (isolated ones → layer 0). The stored
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
  "What module `m` OFFERS: the public defns/defmacros/defs of its depth<=2
  namespaces plus every deeper var widened by :export (with its level) —
  compact rows {:ns :name :sig :doc :export?}, -test namespaces and
  ^:private vars excluded. Plus :deps (its declared edges) and :consumers
  (modules declaring an edge to it). The cheap browse before calling into
  a module."
  [session m]
  (let [st       (:store @session)
        m        (str m)
        manifest (or (modules/modules-manifest st) {})
        nses     (filter #(= m (modules/module-of %)) (keys (:namespaces st)))
        rows     (for [nsx  (sort nses)
                       :when (not (str/ends-with? (str nsx) "-test"))
                       :let [deep? (> (count (str/split (str nsx) #"\.")) 2)]
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
      {:error (str "no namespaces in module " m
                   " — query_depends {modules true} lists the modules")}
      {:module    m
       :surface   (vec rows)
       :deps      (vec (sort (get manifest m #{})))
       :consumers (vec (sort (keep (fn [[k deps]] (when (contains? deps m) k))
                                   manifest)))})))
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
