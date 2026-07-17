(ns slopp.store.merge
  (:require [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]
            [slopp.semver :as semver]
            [slopp.store :as store]))

(defn ^:export record-merge
  "Append a `:merge` delta — what arrived, from where, the surfaced conflicts
  (MV records the agent resolves by hand), and `:applied` — the ids of THEIR
  deltas now causally delivered here, which is what keeps iterated merges
  exact. Returns [store' delta]."
  [store from {:keys [merged conflicts new-nses applied id-map agent]}]
  (let [[did store'] (store/gen-id store "d")
        delta (cond-> {:id did :parent (:id (last (:deltas store)))
                       :op :merge :ns '*session* :from (str from)
                       :at (store/now-ms) :merged merged}
                agent           (assoc :agent agent)
                (seq applied)   (assoc :applied (vec applied))
                (seq id-map)    (assoc :id-map id-map)
                (seq conflicts) (assoc :conflicts (mapv #(dissoc % :ours) conflicts))
                (seq new-nses)  (assoc :new-nses (vec new-nses)))]
    [(update store' :deltas conj delta) delta]))

(defn ^:export tag-merged
  "Mark the just-appended delta as a replay of THEIR delta `their-id` — it is
  their work, not ours, and later merges must treat it that way."
  [store their-id]
  (update store :deltas
          (fn [ds] (conj (pop ds) (assoc (peek ds) :merged-from their-id)))))

(defn ^:export merge-logs
  "Phase 4 m2 (C4/C5 activation): merge `theirs` — a store sharing a common
  delta-log prefix with `ours` (a fork = a copied project dir) — into ours by
  REPLAYING theirs' suffix, form-id-keyed:
  - different-form work merges clean (the granularity dodge, across replicas)
  - identical changes converge silently
  - same-form divergence = MV conflict: ours kept, theirs surfaced
  - add/add id collisions are remapped to fresh ids
  - whole namespaces created on their side arrive intact (provenance kept)
  - :move deltas are skipped with a note (ordering is cosmetic in the image)
  Iterated merges stay exact via causal delivery: replayed deltas carry
  :merged-from (their id), the :merge delta records :applied, and neither
  replays again nor counts as OUR work in conflict detection.
  Returns {:store :merged :conflicts :notes :changed-form-ids :new-nses
           :applied :fork-point} — pure; the caller owns image loads +
  verification."
  [ours theirs & {:keys [from]}]
  (let [od         (:deltas ours)
        td         (:deltas theirs)
        ;; full-value comparison: both sides allocate the same NEXT id for
        ;; different work, so id equality would swallow the first divergence
        common     (count (take-while true? (map = od td)))
        fork-point (:id (last (take common od)))
        ours-sfx   (drop common od)
        ;; causal state from PRIOR merges of THIS source (delta ids collide
        ;; across different sources, so scope by :from): what's delivered,
        ;; and how their form ids were remapped into ours
        prior      (filter #(and (= :merge (:op %)) (= (str from) (:from %)))
                           od)
        delivered  (into #{} (mapcat :applied) prior)
        idmap0     (into {} (mapcat :id-map) prior)
        dropped    (filter #(delivered (:id %)) (drop common td))
        ;; recreated-source guard: a "delivered" delta whose content doesn't
        ;; match OUR replayed copy of it means the source was deleted and
        ;; recreated at the same path/name — its ids alias dead history, and
        ;; silently dropping its work would be corruption
        imposter   (some (fn [d]
                           (when-let [copy (first (filter #(= (:id d)
                                                              (:merged-from %))
                                                          od))]
                             ;; compare CONTENT — replay remaps the form-id
                             ;; keys, so only the source texts are stable
                             (when (and (:sources d)
                                        (not= (sort (vals (:sources d)))
                                              (sort (vals (:sources copy)))))
                               d)))
                         dropped)
        theirs-sfx (remove #(delivered (:id %)) (drop common td))
        touched    (store/suffix-touched (remove :merged-from ours-sfx))]
    (if imposter
      {:error (str "merge identity mismatch: delta " (:id imposter)
                   " looks like a recreated fork/branch at the same"
                   " path/name — use a fresh path (or a new branch)")}
      (loop [st ours, dds (seq theirs-sfx), idmap idmap0, merged 0,
             conflicts [], notes [], changed [], new-nses [], applied []]
        (if-let [d (first dds)]
          (let [ds        (rest dds)
                op        (:op d)
                done      (fn [st idmap merged conflicts notes changed new-nses applied]
                            [st idmap merged conflicts notes changed new-nses applied])
                [st idmap merged conflicts notes changed new-nses applied]
                (case op
                  (:verify :done :merge)
                  (done st idmap merged conflicts notes changed new-nses applied)

                  :deps-add
                  ;; a foreign dep declaration. No divergence (new lib or same
                  ;; coord) → land it. Divergence of two mvn versions → auto-
                  ;; resolve to the NEWER (numeric, via slopp.semver) with a note.
                  ;; Diverging incomparable coords (git sha, mixed) → a conflict.
                  (let [lib (:lib d) cur (get-in st [:deps lib]) theirs (:coord d)
                        land (fn [st*]
                               (-> st* (assoc-in [:deps lib] theirs)
                                   (assoc-in [:dep-ns lib] (set (:namespaces d)))))]
                    (cond
                      (or (nil? cur) (= cur theirs))
                      (done (land st) idmap (inc merged) conflicts notes changed
                            new-nses (conj applied (:id d)))

                      (and (:mvn/version cur) (:mvn/version theirs))
                      (let [theirs-newer? (semver/newer? (:mvn/version theirs)
                                                         (:mvn/version cur))]
                        (done (if theirs-newer? (land st) st)
                              idmap (inc merged) conflicts
                              (conj notes {:resolved :deps :lib lib
                                           :kept    (if theirs-newer? theirs cur)
                                           :dropped (if theirs-newer? cur theirs)
                                           :reason "version divergence auto-resolved to newer"})
                              changed new-nses (conj applied (:id d))))

                      :else
                      (done st idmap merged
                            (conj conflicts {:dep lib :ours cur :theirs theirs})
                            (conj notes {:conflict :deps :lib lib
                                         :reason "same dependency pinned to incomparable coords"})
                            changed new-nses (conj applied (:id d)))))

                  :deps-remove
                  (done (-> st (update :deps dissoc (:lib d))
                            (update :dep-ns dissoc (:lib d)))
                        idmap (inc merged) conflicts notes changed new-nses
                        (conj applied (:id d)))

                  :deps-pure
                  (done (update st :dep-pure
                                (fnil (if (:pure d) conj disj) #{}) (:sym d))
                        idmap (inc merged) conflicts notes changed new-nses
                        (conj applied (:id d)))

                  :module-tier
                  ;; per-module purity tier: theirs applies (register, last-writer-
                  ;; wins), mirroring :deps-pure — no divergence conflict surfaced (D9)
                  (done (assoc-in st [:module-tiers (:module d)] (:tier d))
                        idmap (inc merged) conflicts notes changed new-nses
                        (conj applied (:id d)))

                  :ingest
                  (let [ns-sym (:ns d)]
                    (if (get-in st [:namespaces ns-sym])
                      (done st idmap merged conflicts
                            (conj notes {:skipped :ingest :ns ns-sym
                                         :reason "namespace exists on our side"})
                            changed new-nses (conj applied (:id d)))
                      (let [src (apply str (map #(str (get (:sources d) %) "\n")
                                                (:form-ids d)))
                            st' (tag-merged (store/ingest st ns-sym src :agent (:agent d))
                                            (:id d))
                            new-ids (into [] (keep :id) (store/elements st' ns-sym))]
                        (done st' (merge idmap (zipmap (:form-ids d) new-ids))
                              (inc merged) conflicts notes changed
                              (conj new-nses ns-sym) (conj applied (:id d))))))

                  :add
                  (let [ns-sym (:ns d)
                        fid    (:form-id d)
                        src    (get (:sources d) fid)
                        node   (p/parse-string src)
                        nm     (store/form-symbol node)
                        cur    (when nm (store/form-named st ns-sym nm))]
                    (cond
                      (and cur (= (n/string (:node cur)) src)) ; converged
                      (done st (assoc idmap fid (:id cur)) merged conflicts notes
                            changed new-nses (conj applied (:id d)))

                      cur                                      ; name clash
                      (done st idmap merged
                            (conj conflicts {:form (symbol (str ns-sym) (str nm))
                                             :ns ns-sym :delta (:id d)
                                             :ours (n/string (:node cur))
                                             :theirs src
                                             :reason "both sides added this name"})
                            notes changed new-nses applied)

                      :else
                      (if-let [[st' d'] (store/append-form st ns-sym node
                                                     :prompt (:prompt d)
                                                     :agent (:agent d))]
                        (done (tag-merged st' (:id d))
                              (assoc idmap fid (:form-id d'))
                              (inc merged) conflicts notes
                              (conj changed (:form-id d')) new-nses
                              (conj applied (:id d)))
                        (done st idmap merged conflicts
                              (conj notes {:skipped :add :reason "no namespace"
                                           :ns ns-sym})
                              changed new-nses (conj applied (:id d))))))

                  :replace
                  (let [ns-sym (:ns d)
                        fid0   (:form-id d)
                        fid    (get idmap fid0 fid0)
                        src    (get (:sources d) fid0)
                        cur    (store/form-by-id st fid)]
                    (cond
                      (nil? cur)                               ; deleted on our side
                      (done st idmap merged
                            (conj conflicts {:form (store/qform st ns-sym fid0 theirs)
                                             :ns ns-sym :delta (:id d)
                                             :ours nil :theirs src
                                             :reason "we deleted it; they edited it"})
                            notes changed new-nses applied)

                      (= (n/string (:node cur)) src)           ; converged
                      (done st idmap merged conflicts notes changed new-nses
                            (conj applied (:id d)))

                      (and (touched fid) (not (contains? (set changed) fid)))
                      (done st idmap merged
                            (conj conflicts {:form (store/qform st ns-sym fid0 theirs)
                                             :ns ns-sym :delta (:id d)
                                             :ours (n/string (:node cur))
                                             :theirs src
                                             :reason "both sides edited this form"})
                            notes changed new-nses applied)

                      (nil? (:name cur))
                      (done st idmap merged conflicts
                            (conj notes {:skipped :replace :form fid
                                         :reason "anonymous form"})
                            changed new-nses (conj applied (:id d)))

                      :else
                      (let [[st' _] (store/replace-node st ns-sym (:name cur)
                                                  (p/parse-string src)
                                                  :prompt (:prompt d)
                                                  :agent (:agent d))]
                        (done (tag-merged st' (:id d)) idmap (inc merged)
                              conflicts notes (conj changed fid) new-nses
                              (conj applied (:id d))))))

                  :delete
                  (let [ns-sym (:ns d)
                        fid0   (:form-id d)
                        fid    (get idmap fid0 fid0)
                        cur    (store/form-by-id st fid)]
                    (cond
                      (nil? cur)                               ; converged
                      (done st idmap merged conflicts notes changed new-nses
                            (conj applied (:id d)))

                      (touched fid)
                      (done st idmap merged
                            (conj conflicts {:form (store/qform st ns-sym fid0 theirs)
                                             :ns ns-sym :delta (:id d)
                                             :ours (n/string (:node cur)) :theirs nil
                                             :reason "we edited it; they deleted it"})
                            notes changed new-nses applied)

                      :else
                      (let [[st' _] (store/remove-form st ns-sym (:name cur)
                                                 :prompt (:prompt d)
                                                 :agent (:agent d))]
                        (done (tag-merged st' (:id d)) idmap (inc merged)
                              conflicts notes changed new-nses
                              (conj applied (:id d))))))

                  (:rename :normalize)
                ;; changeset ops are all-or-conflict: a partially applied
                ;; rename would leave broken references
                  (let [srcs (:sources d)
                        fids (map #(get idmap % %) (keys srcs))
                        blocked (filter #(and (touched %)
                                              (not (contains? (set changed) %)))
                                        fids)]
                    (if (seq blocked)
                      (done st idmap merged
                            (conj conflicts {:form (store/qform st (:ns d)
                                                          (first blocked) theirs)
                                             :ns (:ns d) :delta (:id d)
                                             :theirs (pr-str (select-keys d [:old :new]))
                                             :reason "their rename touches forms we edited"})
                            notes changed new-nses applied)
                      (let [changeset (into {}
                                            (keep (fn [[fid0 src]]
                                                    (let [fid (get idmap fid0 fid0)]
                                                      (when (store/form-by-id st fid)
                                                        [fid (p/parse-string src)]))))
                                            srcs)
                            [st' _] (store/apply-changeset st (:op d) (:ns d) changeset
                                                     :prompt (:prompt d)
                                                     :agent (:agent d)
                                                     :extra (select-keys d [:old :new]))]
                        (done (tag-merged st' (:id d)) idmap (inc merged)
                              conflicts notes (into changed (keys changeset))
                              new-nses (conj applied (:id d))))))

                  :move
                  (done st idmap merged conflicts
                        (conj notes {:skipped :move :ns (:ns d)
                                     :reason "ordering is cosmetic; re-run edit_move if wanted"})
                        changed new-nses (conj applied (:id d)))

                  ;; module edges are CRDT-grain: fold theirs in (adds union,
                  ;; removes disj) — never a conflict. A union can close a
                  ;; cycle neither side saw; surface it as a note.
                  :module-edge
                  (let [st' (if (= :remove (:action d))
                              (let [deps (disj (get-in st [:modules (:from d)] #{})
                                               (:to d))]
                                (if (empty? deps)
                                  (update st :modules dissoc (:from d))
                                  (assoc-in st [:modules (:from d)] deps)))
                              (update-in st [:modules (:from d)]
                                         (fnil conj #{}) (:to d)))
                        cyc (when (= :add (:action d))
                              (store/modules-cycle (:modules st')))]
                    (done st' idmap (inc merged) conflicts
                          (cond-> notes
                            cyc (conj {:modules-cycle cyc
                                       :reason (str "the merged module graphs form a"
                                                    " cycle neither side saw — retract"
                                                    " an edge (module_dep {from .. to .."
                                                    " remove true})")}))
                          changed new-nses (conj applied (:id d))))

                ;; unknown op: never guess with someone's code
                  (done st idmap merged conflicts
                        (conj notes {:skipped (:op d) :delta (:id d)})
                        changed new-nses (conj applied (:id d))))]
            (recur st ds idmap merged conflicts notes changed new-nses applied))
          {:store st :merged merged :conflicts conflicts :notes notes
           :changed-form-ids (vec (distinct changed)) :new-nses new-nses
           :applied applied :id-map idmap :fork-point fork-point})))))
