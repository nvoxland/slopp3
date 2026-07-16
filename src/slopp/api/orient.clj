(ns slopp.api.orient
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [rewrite-clj.node :as n]
            [slopp.db :as db]
            [slopp.store :as store]))

(defn snip
  "Cap `s` at `n` chars with an ellipsis — composites (brief/report) carry
  MANY prose fields and must never give back the tokens they save; the
  full text stays one query away (report {contains}, query_history)."
  [s n]
  (let [s (str s)]
    (if (<= (count s) n) s (str (subs s 0 n) "…"))))

^:reads (defn form-card
  "The INTERFACE view of a form (opacity with a warranty): signature,
  doc line, effect marker, the recorded WHY (last ask), and the warranty
  (covering tests from the trace map) — what a CALLER needs, at ~10x less
  than source. Trusting it is mechanical, not hopeful: every edit re-runs
  the covering tests, so a violated contract turns red with :implicated."
  [session ns-sym nm]
  (when-let [e (store/form-named (:store @session) ns-sym nm)]
    (let [q       (symbol (str ns-sym) (str nm))
          s       (try (n/sexpr (:node e)) (catch Exception _ nil))
          body    (when (seq? s) s)
          doc     (some #(when (string? %) %) (take 3 (drop 2 (or body ()))))
          sig     (or (some #(when (vector? %) %) (drop 1 (or body ())))
                      (let [arities (keep #(when (and (seq? %) (vector? (first %)))
                                             (first %))
                                          (drop 2 (or body ())))]
                        (when (seq arities) (vec arities))))
          why     (->> (store/deltas (:store @session))
                       reverse
                       (some #(when (and (:prompt %)
                                         (or (= (:id e) (:form-id %))
                                             (some #{(:id e)} (or (:form-ids %) []))))
                                (:prompt %))))
          covered (count (keep (fn [[t fs]] (when (contains? fs q) t))
                               (:test-map @session)))
          examples (when-let [conn (:db @session)]
                     (try
                       (when-let [raw (db/get-meta conn (str "observed/" ns-sym "/" nm))]
                         (->> (edn/read-string raw)
                              (take 2)
                              (mapv (fn [{:keys [args ret threw]}]
                                      (snip (str "(" nm " " (str/join " " args)
                                                 ") → " (or ret threw))
                                            90)))
                              not-empty))
                       (catch Exception _ nil)))]
      (cond-> {:form q :warranty {:covered covered}}
        sig  (assoc :sig sig)
        doc  (assoc :doc (snip (first (str/split-lines doc)) 90))
        (str/ends-with? (str nm) "!") (assoc :effectful true)
        why  (assoc :why (snip why 90))
        examples (assoc :examples examples)))))

(defn fit-report
  "G13 at the gate boundary — by AGGREGATION, never amputation: an
  over-budget report first trims asks to 1/row, then ROLLS CHANGES UP by
  namespace ({:ns :forms :ops :asks}) — the information survives at a
  coarser grain and report {contains} expands any group. Amputation
  (take 20 of the rollup) is the last resort for pathological stores.
  (eval9: the take-20 amputation CAUSED the handoff fan-out — agents went
  hunting for what the report dropped.)"
  [r]
  (let [fits? #(<= (count (pr-str %)) 6500)]
    (if (fits? r)
      r
      (let [slim (update r :changes
                         (fn [cs] (mapv #(update % :asks (comp vec (partial take 1))) cs)))]
        (if (fits? slim)
          (assoc slim :note "asks trimmed to 1/row — report {contains} narrows")
          (let [rolled (->> (:changes slim)
                            (group-by :ns)
                            (mapv (fn [[nsx rows]]
                                    {:ns nsx :forms (count rows)
                                     :ops (vec (distinct (mapcat :ops rows)))
                                     :asks (vec (take 1 (distinct (mapcat :asks rows))))}))
                            (sort-by (comp str :ns)))
                slim2  (assoc slim :changes (vec rolled)
                              :note "changes rolled up by namespace — report {contains <ns or word>} expands a group")]
            (if (fits? slim2)
              slim2
              (-> slim2
                  (update :changes #(vec (take 20 %)))
                  (assoc :note (str "rolled up by namespace, showing 20 of "
                                    (count rolled) " — {contains} narrows"))))))))))
