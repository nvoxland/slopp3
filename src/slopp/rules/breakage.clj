(ns slopp.rules.breakage
  "Did this episode BREAK a contract that something outside the module depends
  on?

  Hickey's Spec-ulation, made mechanical: growth is always safe, breakage has
  to be visible. An agent editing one form can see its callers INSIDE the
  store; it cannot see the callers that are not in the store at all, and those
  are exactly the ones a narrowing hurts. So the baseline is the last done
  point, the subject is a CHANGED `defn` that WAS module-external then, and
  the only question asked is whether its surface got SMALLER.

  Three narrowings, each additive to a finding: a dropped fixed arity, a
  dropped `:=>` arg-map key, and visibility (was a boundary, now private or
  unexported). Widening is never reported.

  **Undeterminable is never a finding.** Variadic arities, absent schemas and
  unparseable forms all answer \"no narrowing\" rather than guessing. A false
  breakage flag on a safe change teaches agents to ignore the rule, and that
  costs more than the misses do.

  `^:breaking-ok` on the name discharges a finding, because privatising a
  function with no outside callers is a CORRECT change and a rule that cannot
  be discharged can only ever be advisory. Like `^:unused-ok` and
  `^:ambient-ok`, the marker POLICES ITSELF: on a changed form that narrowed
  nothing it reports `:stale-marker`, so it cannot be sprinkled ahead of time
  or left behind as a permanent opt-out. That self-check deliberately sits
  OUTSIDE the boundary filter — once a narrowing lands the new baseline is
  already private, so a guard on the old form's boundary status would never
  see the stale marker again."
  (:require [clojure.set :as set]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]
            [slopp.store :as store]
            [slopp.edit.modules :as edit.modules]))

(defn fixed-arities
  "The set of FIXED param-counts a `defn` sexpr accepts, e.g. `#{1 2}`. nil when
   `form` is not a `defn` or ANY of its arities is variadic — variadic
   subsumption (does `[a & r]` still accept an old `[a b]` call?) is subtle, so
   v1 DEFERS it rather than risk a false breakage flag. Arg-vectors come from the
   shared `edit.modules/fn-arglists`."
  [form]
  (when (and (seq? form) (= 'defn (first form)))
    (let [avs (edit.modules/fn-arglists form)]
      (when (and (seq avs) (not-any? #(some #{'&} %) avs))
        (into #{} (map count) avs)))))

(defn removed-arities
  "The fixed arities present in `old-form` but gone from `new-form` — a NARROWING
   of the callable surface (breakage). Empty when either side is variadic or not a
   `defn` (undeterminable → never flagged) and empty when arities were only ADDED
   (accretion — Hickey's provide-more, always safe)."
  [old-form new-form]
  (let [o (fixed-arities old-form)
        n (fixed-arities new-form)]
    (if (and o n) (set/difference o n) #{})))

(defn node-boundary?
  "True when a `defn` `form` (sexpr) in `ns-sym` is reachable from OUTSIDE its
   module. Delegates to the shared `edit.modules/module-external?` (public in a
   module-root ns, or any truthy `^:export` in a deeper ns) — ONE definition, so
   the write gates and the breakage classifier can't diverge. Node-based, so it
   judges the OLD form version too (how visibility narrowing is detected)."
  [ns-sym form]
  (edit.modules/module-external? ns-sym form))

(defn arg-map-keys
  "The set of keys of a defn's `:=>` `:malli/schema` FIRST arg when that arg is a
   `:map` schema, else nil. `[:=> [:cat [:map [:a A] [:b B]]] R]` → `#{:a :b}`
   (a `:map` properties map and per-key `:optional` opts are tolerated).
   Shared with the shape tracer: the DECLARED half of a form's key contract has
   one reader, so the two answers can never drift apart."
  [form]
  (let [sch (:malli/schema (meta (second form)))]
    (when (and (vector? sch) (= :=> (first sch)))
      (let [cat  (second sch)
            arg1 (when (and (vector? cat) (= :cat (first cat))) (second cat))]
        (when (and (vector? arg1) (= :map (first arg1)))
          (into #{} (keep #(when (vector? %) (first %)) (rest arg1))))))))

(defn removed-schema-keys
  "Arg-map keys present in `old-form`'s `:=>` schema but GONE from `new-form`'s — a
   NARROWING of the accepted map (a caller still passing that key now fails
   validation). Empty when either side lacks a `:map` arg schema (undeterminable)
   or keys were only ADDED (accretion)."
  [old-form new-form]
  (let [o (arg-map-keys old-form)
        n (arg-map-keys new-form)]
    (if (and o n) (set/difference o n) #{})))

(defn- discharged-by-history?
  "A NEWLY-added ^:breaking-ok (absent at the last-done baseline) may be
  discharging a narrowing an EARLIER done already flagged — the baseline
  advanced at that same done, so comparing against it alone reads the late
  marker as stale (frictions #15; a merge-delivered narrowing made that the
  common case, and the escape was unusable exactly when it was needed).
  True when the form narrowed relative to ANY unmarked prior-done baseline
  (bounded by the caller). Not the FIRST unmarked sighting — an unrelated
  done landing after the narrowing sits narrow-unmarked and would answer
  'narrowed nothing since', re-opening the friction; the earlier WIDE
  baseline still discharges it (review V-F4). Marked baselines are skipped
  (the marker cannot vouch for itself)."
  [store fid ns-sym new-form prior-done-ids]
  (boolean
   (some (fn [did]
           (when-let [old-src (get (store/sources-at store did) fid)]
             (when-let [old (try (n/sexpr (p/parse-string old-src))
                                 (catch Exception _ nil))]
               (and (not (and (symbol? (second old))
                              (:breaking-ok (meta (second old)))))
                    (node-boundary? ns-sym old)
                    (boolean (or (seq (removed-arities old new-form))
                                 (seq (removed-schema-keys old new-form))
                                 (not (node-boundary? ns-sym new-form))))))))
         prior-done-ids)))

(defn breaking-changes
  "The episode's likely CONTRACT BREAKAGES: a CHANGED `defn` that WAS
   module-external at the last-done baseline and whose surface NARROWED — Hickey's
   Spec-ulation (growth is safe, breakage must be visible to the external callers a
   slice can't see). Three narrowings, each additive to the finding:
   `:removed-arities` (a fixed arity dropped), `:removed-keys` (a `:=>` arg-map key
   dropped), `:visibility-narrowed` (was a boundary, now private / unexported).
   Filtered on the OLD form's boundary status via `node-boundary?`, so a
   public→private narrowing is still seen. New forms (no baseline) and forms that
   were never a boundary are skipped.

   `^:breaking-ok` on the NAME discharges it: the break is DELIBERATE and the
   author owns telling downstream. Without an escape this rule could only ever
   be advisory — privatising a fn with no outside callers is a correct change,
   and a rule you cannot discharge has to be ignorable.

   Like `^:unused-ok` / `^:ambient-ok` / `^:foreign-keys` it POLICES ITSELF: a
   marker on a changed form that narrowed NOTHING yields `:stale-marker`, so it
   cannot be sprinkled ahead of time or left behind as a permanent opt-out.
   That check deliberately sits OUTSIDE the boundary filter — once a narrowing
   lands, the new baseline is already private, so a guard on the old form's
   boundary status would never see the stale marker again.

   Returns `[{:form …} …]`."
  [store changed-fids]
  (let [baseline (->> (store/deltas store)
                      (filter #(= :done (:op %)))
                      last :id)]
    (if-not baseline
      []
      (let [old-srcs (store/sources-at store baseline)]
        (vec (keep
              (fn [fid]
                (let [e       (store/form-by-id store fid)
                      ns-sym  (store/ns-of-form-id store fid)
                      old-src (get old-srcs fid)]
                  (when (and e ns-sym old-src (:name e))
                    (let [old-form (try (n/sexpr (p/parse-string old-src))
                                        (catch Exception _ nil))
                          new-form (try (n/sexpr (:node e)) (catch Exception _ nil))]
                      (when (and old-form new-form)
                        (let [qsym    (symbol (str ns-sym) (str (:name e)))
                              marked? (boolean (and (symbol? (second new-form))
                                                    (:breaking-ok (meta (second new-form)))))
                              was?    (node-boundary? ns-sym old-form)
                              rem-ar  (when was? (removed-arities old-form new-form))
                              rem-ks  (when was? (removed-schema-keys old-form new-form))
                              vis?    (and was? (not (node-boundary? ns-sym new-form)))
                              finding (cond-> {:form qsym}
                                        (seq rem-ar) (assoc :removed-arities (vec (sort rem-ar)))
                                        (seq rem-ks) (assoc :removed-keys (vec (sort rem-ks)))
                                        vis?         (assoc :visibility-narrowed true))
                              narrowed? (> (count finding) 1)]
                          (cond
                            (and marked? narrowed?) nil
                            marked?
                            ;; a marker ALREADY at the baseline had its
                            ;; discharge — remove-the-flag discipline stands;
                            ;; a marker ADDED SINCE may be discharging what an
                            ;; earlier done flagged (the baseline advanced at
                            ;; that same done — frictions #15), so it earns a
                            ;; bounded look past the baseline
                            (let [marked-old? (and (symbol? (second old-form))
                                                   (:breaking-ok (meta (second old-form))))
                                  prior (->> (store/deltas store)
                                             (filter #(= :done (:op %)))
                                             (map :id)
                                             butlast
                                             reverse
                                             (take 12))]
                              (when-not (and (not marked-old?)
                                       (discharged-by-history? store fid ns-sym
                                                               new-form prior)) {:form qsym :stale-marker true
                                 :note (str qsym " carries ^:breaking-ok but"
                                            " narrowed nothing — remove the flag")}))
                            narrowed? finding)))))))
              changed-fids))))))
