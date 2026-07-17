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

(defn- boundary-fn?
  "True when `form-name` in `ns-sym` is a public `defn` reachable from OUTSIDE its
   module — the interface external callers (invisible from the editing slice) may
   depend on. Public (not `defn-`/`^:private`) AND either in a module-root ns
   (<= 2 segments) or `^:export` in a deeper ns. (Mirrors the boundary notion in
   `edit.modules/schema-refusal`; a shared `module-external?` could DRY the two.)"
  [store ns-sym form-name]
  (when-let [e (store/form-named store (symbol (str ns-sym)) (symbol (str form-name)))]
    (let [form (try (n/sexpr (:node e)) (catch Exception _ nil))]
      (and (seq? form) (= 'defn (first form))
           (not (:private (meta (second form))))
           (or (= (str ns-sym) (edit.modules/module-of ns-sym))
               (boolean (edit.modules/export-level store ns-sym form-name)))))))

(defn breaking-changes
  "The episode's likely CONTRACT BREAKAGES: a CHANGED module-external `defn` whose
   fixed-arity surface NARROWED vs its state at the last-done baseline (Hickey's
   Spec-ulation — growth is safe, breakage must be visible). Returns
   [{:form :removed-arities} …] — an ADVISORY (external callers a slice can't see
   may break; internal callers already turn red via the tests). A form added this
   episode has no baseline and is skipped; variadic/non-`defn` interfaces are not
   judged. Baseline = `sources-at` the previous `:done` delta, so it fires once,
   in the episode that narrows."
  [store changed-fids]
  (let [baseline (->> (store/deltas store)
                      (filter #(= :done (:op %)))
                      last :id)]
    (if-not baseline
      []
      (let [old-srcs (store/sources-at store baseline)]
        (vec (for [fid   changed-fids
                   :let  [e       (store/form-by-id store fid)
                          ns-sym  (store/ns-of-form-id store fid)
                          old-src (get old-srcs fid)]
                   :when (and e ns-sym old-src (:name e)
                              (boundary-fn? store ns-sym (:name e)))
                   :let  [old-form (try (n/sexpr (p/parse-string old-src))
                                        (catch Exception _ nil))
                          new-form (try (n/sexpr (:node e)) (catch Exception _ nil))
                          removed  (when (and old-form new-form)
                                     (removed-arities old-form new-form))]
                   :when (seq removed)]
               {:form (symbol (str ns-sym) (str (:name e)))
                :removed-arities (vec (sort removed))}))))))
