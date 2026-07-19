(ns slopp.api.shape
  (:require [slopp.store :as store]
            [slopp.api.breakage :as breakage]))

(defn arities
  "`([arglist & body] ...)` for a `defn`/`defn-` sexpr — docstring and attr-map
   skipped, single- and multi-arity handled alike. Anything else → nil."
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
   `slopp.repl/start!`, every `:read-never-passed` key it reported was an
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

