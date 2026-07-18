(ns slopp.api.breakage
  (:require [clojure.set :as set]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]
            [slopp.store :as store]
            [slopp.edit.modules :as edit.modules]))

(defn fixed-arities
  "The set of FIXED param-counts a `defn` sexpr accepts, e.g. `#{1 2}`. nil when
   `form` is not a `defn` or ANY of its arities is variadic — variadic
   subsumption (does `[a & r]` still accept an old `[a b]` call?) is subtle, so
   v1 DEFERS it rather than risk a false breakage flag."
  [form]
  (when (and (seq? form) (= 'defn (first form)))
    (let [body (drop 2 form)
          body (cond->> body (string? (first body)) rest)
          body (cond->> body (map? (first body)) rest)
          avs  (if (vector? (first body))
                 [(first body)]
                 (keep #(when (and (seq? %) (vector? (first %))) (first %)) body))]
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
   module — a public defn in a module-root ns (<= 2 segments), or a defn with any
   truthy `^:export` in a deeper ns. Node-based (reads the sexpr's own metadata),
   so it judges an OLD form version too — which is what lets visibility NARROWING
   be detected. `:export` truthiness matches `edit.modules/export-level` (any
   truthy value = exported), so the boundary gates all agree."
  [ns-sym form]
  (and (seq? form) (= 'defn (first form))
       (not (:private (meta (second form))))
       (or (= (str ns-sym) (edit.modules/module-of ns-sym))
           (boolean (:export (meta (second form)))))))

(defn- arg-map-keys
  "The set of keys of a defn's `:=>` `:malli/schema` FIRST arg when that arg is a
   `:map` schema, else nil. `[:=> [:cat [:map [:a A] [:b B]]] R]` → `#{:a :b}`
   (a `:map` properties map and per-key `:optional` opts are tolerated)."
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
(defn breaking-changes
  "The episode's likely CONTRACT BREAKAGES: a CHANGED `defn` that WAS
   module-external at the last-done baseline and whose surface NARROWED — Hickey's
   Spec-ulation (growth is safe, breakage must be visible to the external callers a
   slice can't see). Three narrowings, each additive to the finding:
   `:removed-arities` (a fixed arity dropped), `:removed-keys` (a `:=>` arg-map key
   dropped), `:visibility-narrowed` (was a boundary, now private / unexported).
   Returns `[{:form …} …]` — ADVISORY (internal callers already turn the tests
   red). Filtered on the OLD form's boundary status via `node-boundary?`, so a
   public→private narrowing is still seen. New forms (no baseline) and forms that
   were never a boundary are skipped."
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
                      (when (and old-form new-form (node-boundary? ns-sym old-form))
                        (let [rem-ar (removed-arities old-form new-form)
                              rem-ks (removed-schema-keys old-form new-form)
                              vis?   (not (node-boundary? ns-sym new-form))
                              finding (cond-> {:form (symbol (str ns-sym) (str (:name e)))}
                                        (seq rem-ar) (assoc :removed-arities (vec (sort rem-ar)))
                                        (seq rem-ks) (assoc :removed-keys (vec (sort rem-ks)))
                                        vis?         (assoc :visibility-narrowed true))]
                          (when (> (count finding) 1) finding)))))))
              changed-fids))))))

