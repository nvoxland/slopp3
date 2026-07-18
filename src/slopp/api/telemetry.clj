(ns slopp.api.telemetry
  (:require [rewrite-clj.node :as n]
            [slopp.api.rules :as rules]))

(defn- escape-markers
  "Store-wide counts of the discharge markers agents add to opt OUT of the
   analyzer / gates — the write-side friction proxy. `^:unsafe`/`^:reads` ride the
   form; `^:unused-ok` rides the defined name."
  [store]
  (reduce
   (fn [acc [_ns {:keys [elements]}]]
     (reduce (fn [acc e]
               (if-let [node (:node e)]
                 (let [s  (try (n/sexpr node) (catch Exception _ nil))
                       fm (meta s)
                       nm (when (seq? s) (meta (second s)))]
                   (cond-> acc
                     (:unsafe fm)    (update :unsafe inc)
                     (:reads fm)     (update :reads inc)
                     (:unused-ok nm) (update :unused-ok inc)))
                 acc))
             acc elements))
   {:unsafe 0 :reads 0 :unused-ok 0}
   (:namespaces store)))

(defn rule-telemetry
  "Fire-rate + discharge signal for the D9 rules, computed READ-ONLY over the delta
   log — no new instrumentation: the log already records every done's `:findings`
   and every escape marker. Optional `:since` (a delta id) windows to deltas AFTER
   it (e.g. a milestone `:target`). Returns
   `{:window {:dones :since}
     :fire-rate {rule {:dones :instances :persisted :discharged}}
     :escape-markers {:unsafe :reads :unused-ok}
     :dials {:rules {…} :gates {…}}}`.
   `:persisted` = an instance flagged in MORE THAN ONE done (kept firing —
   un-discharged / ignored); `:discharged` = flagged exactly once (fixed or moved
   on). Metadata finding keys (`:test-status` etc.) aren't rules and aren't
   counted."
  [store & {:keys [since]}]
  (let [rule-keys (into #{:missing-doc :unused-public :stale-unused-ok}
                        (map :key rules/done-advisories))
        deltas    (:deltas store)
        window    (if since (rest (drop-while #(not= since (:id %)) deltas)) deltas)
        dones     (filter #(= :done (:op %)) window)
        inst-of   (fn [x] (if (map? x) (or (:form x) (:used x) x) x))
        fires     (for [d dones
                        [k v] (:findings d)
                        :when (and (rule-keys k) (coll? v) (seq v))]
                    {:rule k :insts (mapv inst-of v)})]
    {:window {:dones (count dones) :since (or since :all)}
     :fire-rate
     (into (sorted-map)
           (for [[rule fs] (group-by :rule fires)
                 :let [freqs (frequencies (mapcat :insts fs))]]
             [rule {:dones      (count fs)
                    :instances  (reduce + (map (comp count :insts) fs))
                    :persisted  (count (filter #(> (val %) 1) freqs))
                    :discharged (count (filter #(= 1 (val %)) freqs))}]))
     :escape-markers (escape-markers store)
     :dials {:rules (get-in store [:config "rules" :values] {})
             :gates (get-in store [:config "gates" :values] {})}}))
