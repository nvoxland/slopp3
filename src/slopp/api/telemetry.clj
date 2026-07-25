(ns slopp.api.telemetry
  (:require [rewrite-clj.node :as n]
            [slopp.api.rules.catalog :as catalog]))

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
                        ;; the CATALOG, not the executable registry: it is a
                        ;; verified superset (catalog-covers-every-registered-rule)
                        ;; and carries no check vars, so telemetry stays pure.
                        ;; Its extra write-gate keys are inert here — they simply
                        ;; never match a done finding.
                        (map :rule catalog/rule-catalog))
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

(defn ^:export call-timing
  "A turn's wall clock split into the part slopp spent working and the part it
  did not — the pure fold over `calls`, each `{:tool :start :end}` in epoch ms
  as the wire recorded them.

  Returns `{:calls :slopp-ms :outside-ms :elapsed-ms :slopp-share :top}`, or
  NIL when nothing was called: a zeroed record would read as \"measured, and
  the answer was nothing\", which is the conflation D-surface-honesty forbids.

  **`:outside-ms` is not \"thinking time\".** It is the gap between one answer
  going out and the next call arriving: agent reasoning, every non-slopp tool
  (file reads, shell, subagents), and the harness, none of which the server
  can tell apart. Naming it for what it MEASURES rather than what we suspect
  it contains is the point — and it is the number that was missing. Measured
  over one real session before this existed: 1,703s elapsed against 390s of
  recorded verification, so 78% of the wall clock had no producer at all.
  P7's standing complaint is exactly this: the cost of leaving slopp lands
  where no slopp metric sees it.

  `:top` is by total cost, largest first, aggregated per tool — the tool that
  cost the most may be the one called a hundred times cheaply, and a per-call
  median hides that."
  [calls]
  (when (seq calls)
    (let [in    (reduce + 0 (map #(- (:end %) (:start %)) calls))
          span  (- (:end (last calls)) (:start (first calls)))
          by    (->> (group-by :tool calls)
                     (map (fn [[t cs]] {:tool t :n (count cs)
                                        :ms (reduce + 0 (map #(- (:end %) (:start %)) cs))}))
                     (sort-by (juxt (comp - :ms) :tool))
                     vec)]
      {:calls      (count calls)
       :slopp-ms   in
       :outside-ms (- span in)
       :elapsed-ms span
       :slopp-share (str (int (* 100 (/ in (double (max span 1))))) "%")
       :top        (vec (take 5 by))
       ;; REFUSED calls — a malformed match, a lint error in the form being
       ;; written, an arity break. Each is a whole round trip that produced
       ;; nothing, and they live in the 78% of wall clock spent outside slopp,
       ;; where nothing had ever counted them. Always present, zero when
       ;; clean: an absent key would read as unmeasured.
       :refused    (let [r (filter :refused? calls)]
                     {:count (count r)
                      :pct   (int (* 100 (/ (count r) (double (count calls)))))
                      :by-tool (vec (sort-by (juxt (comp - :n) :tool)
                                             (map (fn [[t cs]] {:tool t :n (count cs)})
                                                  (group-by :tool r))))})})))
