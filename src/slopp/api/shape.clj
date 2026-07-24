(ns slopp.api.shape
  (:require [slopp.store :as store]
            [slopp.api.breakage :as breakage]))

(defn arities
  "`([arglist & body] ...)` for a `defn`/`defn-` sexpr — docstring and attr-map
   skipped, single- and multi-arity handled alike. Anything else (a `def`, a macro, a reader-conditional) → nil."
  [form]
  (when (and (seq? form) (#{'defn 'defn-} (first form)))
    (let [tail (drop-while #(or (string? %) (map? %)) (drop 2 form))]
      (if (vector? (first tail))
        [tail]
        (filter #(and (seq? %) (vector? (first %))) tail)))))

(defn call-arg-shape
  "The first-argument shape of every call to `nm` inside caller sexpr `form`:
   `:keys` union'd across call sites that pass a map LITERAL, and `:unknown?`
   when any call passes something else — a variable, a `merge`, a fn result.
   That flag is the point: a syntactic reader cannot see through a binding, so
   the call sites it CANNOT answer for get counted instead of silently dropped.
   No call to `nm` at all (a value/higher-order reference) → nil."
  [form nm]
  (let [args (for [node (tree-seq coll? seq form)
                   :when (and (seq? node)
                              (symbol? (first node))
                              (= (name nm) (name (first node)))
                              (next node))]
               (second node))]
    (when (seq args)
      {:keys     (into #{} (mapcat #(when (map? %) (keys %))) args)
       :unknown? (boolean (some #(not (map? %)) args))})))

(defn binding-keys
  "The `symbol → map key` mapping a destructuring form establishes:
   `{:x/keys [a] b :k}` → `{a :x/a, b :k}`. THE decoder for destructuring —
   `destructured-keys` and `defaulted-keys` both derive from it rather than
   each re-deriving what `:keys` means, so they cannot drift apart."
  [bnd]
  (when (map? bnd)
    (into {}
          (mapcat
           (fn [[k v]]
             (cond
               (and (keyword? k) (= "keys" (name k)) (vector? v))
               (for [s v :let [sym (symbol s)]]
                 [(symbol (name sym))
                  (if-let [q (or (namespace k) (namespace sym))]
                    (keyword q (name sym))
                    (keyword (name sym)))])

               (and (symbol? k) (keyword? v)) [[k v]]
               :else nil)))
          bnd)))

(defn destructured-keys
  "The set of map keys a destructuring form BINDS: `{:keys [a b]}` → `#{:a :b}`,
   `{:x/keys [a]}` → `#{:x/a}`, `{v :k}` → `#{:k}`. `:as`, `:or`, `:strs` and
   `:syms` bind no map keys and contribute nothing."
  [bnd]
  (set (vals (binding-keys bnd))))

(defn defaulted-keys
  "The set of map keys a destructuring form gives an `:or` default —
   `{:keys [a b] :or {a 1}}` → `#{:a}`. These are OPTIONAL by construction, so
   no caller passing one is the design working, not a gap: measured on
   `slopp.image.repl/start!`, every `:read-never-passed` key it reported was an
   `:or`-defaulted option, i.e. a false positive three times out of three."
  [bnd]
  (let [sym->key (binding-keys bnd)]
    (into #{} (keep sym->key) (keys (when (map? bnd) (:or bnd))))))

(defn read-keys
  "What a form is read for through its FIRST parameter, by source:
   `:destructured` (keys its arglist binds), `:body` (`(:k p)` reads off a plain
   or `:as`-named param), `:schema` (keys its `:=>` schema DECLARES). Sources
   that contribute nothing are absent — an empty map means the form's first
   argument is not read as a map at all."
  [form]
  (let [ars     (arities form)
        dk      (into #{} (mapcat #(destructured-keys (ffirst %))) ars)
        params  (into #{} (keep (fn [[al]]
                                  (let [a (first al)]
                                    (cond (symbol? a) a
                                          (map? a)    (:as a)))))
                      ars)
        body-ks (into #{} (for [ar   ars
                                node (tree-seq coll? seq (rest ar))
                                :when (and (seq? node)
                                           (keyword? (first node))
                                           (contains? params (second node)))]
                            (first node)))
        sk      (breakage/arg-map-keys form)
        dfl     (into #{} (mapcat #(defaulted-keys (ffirst %))) ars)]
    (cond-> {}
      (seq dk)      (assoc :destructured dk)
      (seq body-ks) (assoc :body body-ks)
      (seq sk)      (assoc :schema sk)
      (seq dfl)     (assoc :optional dfl))))

(defn- return-key-set
  "The COMPLETE set of keyword keys expression `expr` can evaluate to as a map,
   or nil when that set cannot be BOUNDED — an over-approximation (or a bail),
   never an under-count, so a consumer that concludes 'key X is not returned' is
   never wrong. Handles the result shapes slopp's operations use: a map literal,
   `(assoc base …)`, `(cond-> base … (assoc :k …) …)`, threaded through
   `let`/`do`/`when-let`/`letfn` to the tail. `if`/`cond`/`case`/`merge`/opaque
   calls / non-keyword keys → nil (unbounded)."
  [expr]
  (cond
    (map? expr)
    (when (every? keyword? (keys expr)) (set (keys expr)))

    (not (seq? expr)) nil

    :else
    (let [h  (first expr)
          op (when (symbol? h) (name h))]
      (case op
        "assoc"
        (let [[m & kvs] (rest expr)
              base (return-key-set m)
              ks   (take-nth 2 kvs)]
          (when (and base (seq ks) (every? keyword? ks))
            (into base ks)))

        "cond->"
        (let [[init & clauses] (rest expr)
              base (return-key-set init)]
          (when (and base (even? (count clauses)))
            (reduce (fn [acc [_pred step]]
                      (if (and (seq? step) (= "assoc" (when (symbol? (first step))
                                                        (name (first step)))))
                        (let [ks (take-nth 2 (rest step))]
                          (if (every? keyword? ks) (into acc ks) (reduced nil)))
                        (reduced nil)))
                    base
                    (partition 2 clauses))))

        ("let" "when-let" "letfn" "do" "binding" "when" "when-not")
        (return-key-set (last expr))

        nil))))

(defn return-keys
  "The SOUND set of keyword keys the map returned by `defn`/`defn-` sexpr `form`
   can contain — the mirror of `read-keys`. nil when the return shape cannot be
   bounded (an opaque call, `merge`, disagreeing branches, non-keyword keys, or
   ANY one arity of a multi-arity fn), so `key-not-returned` never fires on a
   guess; an empty set means the fn provably returns no keyword-keyed map. The
   `key-not-returned` rule reads `(:k r)` off a local bound to a call to such a
   form and flags a `k` this set excludes — the slice guarantee for a test that
   asserts on a return value whose shape is off-slice."
  [form]
  (let [ars (arities form)]
    (when (seq ars)
      (reduce (fn [acc ar]
                (if-let [ks (return-key-set (last ar))]
                  (into acc ks)
                  (reduced nil)))
              #{}
              ars))))

(defn key-not-returned
  "Findings for caller sexpr `caller`: every `(empty? (:k local))` where `local`
   is let-bound (`let`/`when-let`) to a DIRECT call whose head symbol `resolver`
   maps to a SOUND `return-keys` set (nil = unknown → skipped) that EXCLUDES
   `:k`. Each finding is `{:key :k :local sym :callee head :returns #{…}}`.

   Scoped to `empty?` DELIBERATELY, because it is the one shape that passes
   SILENTLY when the read is always nil: `(empty? nil)` is true. `(nil? (:k r))`
   and `(not (:k r))` are legitimate absence assertions (the author MEANT to
   check the key is missing); `(= v (:k r))` for non-nil v FAILS on nil, so
   red-first already catches it. Only `(empty? (:k r))` on a key the callee
   never returns is coverage theatre — green no matter what the code does — and
   telling that apart from a deliberate absence check needs intent, which the
   predicate supplies. Reads scoped to calls (not literals): a value whose
   producer is off-slice is where the slice guarantee is missing."
  [caller resolver]
  (distinct
   (for [node (tree-seq coll? seq caller)
         :when (and (seq? node)
                    (contains? '#{let when-let} (first node))
                    (vector? (second node)))
         :let [bound (into {}
                           (for [[b v] (partition 2 (second node))
                                 :when (and (simple-symbol? b)
                                            (seq? v) (symbol? (first v)))
                                 :let [ks (resolver (first v))]
                                 :when ks]
                             [b {:returns ks :callee (first v)}]))]
         :when (seq bound)
         n (tree-seq coll? seq (drop 2 node))
         :when (and (seq? n) (= 2 (count n))
                    (symbol? (first n)) (= "empty?" (name (first n)))
                    (let [rd (second n)]
                      (and (seq? rd) (= 2 (count rd))
                           (keyword? (first rd)) (simple-symbol? (second rd)))))
         :let [rd   (second n)
               info (bound (second rd))]
         :when (and info (not (contains? (:returns info) (first rd))))]
     {:key (first rd) :local (second rd)
      :callee (:callee info) :returns (:returns info)})))

(defn shape-of
  "The map SHAPE flowing into `ns-sym/nm`: what the form READS off its first
   argument (`:reads`, by source), the literal keys each CALLER passes
   (`:producers`, grouped by key-set — *58 callers pass exactly `#{:dir}`* is the
   finding, not 58 separate lines; `:forms` samples 6, `:more` counts the
   rest), and the diff between them (`:mismatch` —
   `:passed-never-read` is a stale or misspelled key at a call site,
   `:read-never-passed` a key nothing supplies). The rename question answered
   mechanically instead of by eye.

   PARTIAL BY CONSTRUCTION, and says so: only map-LITERAL arguments are
   readable, so callers passing a variable are named in `:unknown-shape`. A
   clean `:mismatch` means what it says only as far as that list is empty —
   read the two together or don't read either. `callers` is `query-impact`'s
   caller list; nil when the form takes no map argument and none is passed."
  [st ns-sym nm callers]
  (let [sexpr-of  (fn [n m] (store/named-sexpr st n m))
        reads     (read-keys (sexpr-of ns-sym nm))
        read-set  (into #{} cat (vals reads))
        seen      (for [{cns :ns cform :form} callers
                        :let [sh (some-> (sexpr-of cns cform) (call-arg-shape nm))]
                        :when sh]
                    (assoc sh :ns cns :form cform))
        producers (vec (for [[ks grp] (->> (filter (comp seq :keys) seen)
                                           (group-by :keys)
                                           (sort-by (comp - count val)))
                             :let [fs (sort (map #(symbol (str (:ns %)) (str (:form %))) grp))]]
                         (cond-> {:keys ks :callers (count grp) :forms (vec (take 6 fs))}
                           (> (count fs) 6) (assoc :more (- (count fs) 6)))))
        unknown   (vec (sort (for [s seen :when (:unknown? s)]
                               (symbol (str (:ns s)) (str (:form s))))))
        passed    (into #{} (mapcat :keys) producers)
        supplied  (some-fn passed (:optional reads #{}))
        mismatch  (cond-> {}
                    (some (complement read-set) passed)
                    (assoc :passed-never-read (into #{} (remove read-set) passed))
                    (and (seq producers) (some (complement supplied) read-set))
                    (assoc :read-never-passed (into #{} (remove supplied) read-set)))]
    (when (or (seq reads) (seq producers))
      (cond-> {:reads reads :producers producers}
        (seq unknown)  (assoc :unknown-shape unknown)
        (seq mismatch) (assoc :mismatch mismatch)))))
