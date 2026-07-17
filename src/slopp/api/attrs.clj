(ns slopp.api.attrs
  (:require [rewrite-clj.node :as n]
            [slopp.store :as store]))

(defn form-keywords
  "The set of NAMESPACED domain keywords a form `node` uses. Unqualified keys
   (`:x`, `:limit`) are excluded as too noisy, and destructuring directives
   (`:keys`/`:as`/`:or`, incl. namespaced `:user/keys`) are excluded as not data.
   Reads the node's sexpr and walks it (metadata — where malli schemas live — is
   not traversed, so schema-internal keywords never pollute the vocabulary).
   Guarded: an unreadable node yields the empty set."
  [node]
  (let [specials #{"keys" "syms" "strs" "as" "or"}]
    (try
      (->> (n/sexpr node)
           (tree-seq coll? seq)
           (filter keyword?)
           (filter namespace)
           (remove #(specials (name %)))
           set)
      (catch Exception _ #{}))))

(defn keyword-inventory
  "The store's domain-keyword vocabulary as a DERIVED index: {namespaced-kw ->
   #{form-ids using it}}. A pure function of the forms — so under the CRDT /
   multi-branch / history model it is correct by construction on every branch,
   after every merge, and at any past revision (the merge machinery reconciles
   FORMS; f(forms) follows for free — no index-specific merge or per-delta
   snapshot). Recompute when needed; memoize behind a store-version key only if a
   profile ever calls for it."
  [store]
  (reduce
   (fn [acc [_ns {:keys [elements]}]]
     (reduce (fn [acc e]
               (if-let [fid (:id e)]
                 (reduce (fn [acc kw] (update acc kw (fnil conj #{}) fid))
                         acc (form-keywords (:node e)))
                 acc))
             acc elements))
   {} (:namespaces store)))

(defn- edit-1?
  "True iff `a` and `b` are exactly ONE Damerau-Levenshtein edit apart: a single
   substitution, a single insertion/deletion, or a single ADJACENT TRANSPOSITION
   (`email`/`emial`) — transpositions are among the commonest keyword typos, so
   plain Levenshtein-1 (which scores them 2) would miss them."
  [a b]
  (let [a (str a) b (str b) la (count a) lb (count b)]
    (cond
      (= a b) false
      (= la lb)
      (let [diffs (keep-indexed (fn [i [x y]] (when (not= x y) i))
                                (map vector a b))]
        (or (= 1 (count diffs))
            (and (= 2 (count diffs))
                 (= (second diffs) (inc (first diffs)))
                 (= (nth a (first diffs)) (nth b (second diffs)))
                 (= (nth a (second diffs)) (nth b (first diffs))))))
      (= 1 (Math/abs (- la lb)))
      (let [[s l] (if (< la lb) [a b] [b a])]
        (loop [i 0 j 0 skips 0]
          (cond (> skips 1)        false
                (= i (count s))    (<= (- (count l) j) 1)
                (= (nth s i) (nth l j)) (recur (inc i) (inc j) skips)
                :else              (recur i (inc j) (inc skips)))))
      :else false)))

(defn near-duplicate-keys
  "Over the DERIVED keyword inventory, the likely-typo findings for this episode:
   a namespaced key introduced by a CHANGED form that (a) is new to the store
   (no UNCHANGED form uses it), (b) has a name of length >= 4 (short names are
   noise), and (c) is exactly one Damerau edit from an ESTABLISHED same-namespace
   key (used by >= 2 unchanged forms). Returns [{:used :suggest :seen} …] — an
   ADVISORY (the open-map guardrail: a typo'd key silently nil-puns, the one
   failure a slice-limited agent can't see). Being derived, it needs no history
   or CRDT handling of its own."
  [store changed-fids]
  (let [changed     (set changed-fids)
        inv         (keyword-inventory store)
        established (into {} (keep (fn [[kw fids]]
                                     (let [n (count (remove changed fids))]
                                       (when (pos? n) [kw n]))))
                         inv)
        changed-kws (into #{} (mapcat (fn [fid]
                                        (when-let [e (store/form-by-id store fid)]
                                          (form-keywords (:node e))))
                                      changed))]
    (vec (for [k     changed-kws
               :when (and (not (contains? established k))
                          (>= (count (name k)) 4))
               :let  [nbr (some (fn [[k2 c]]
                                  (when (and (= (namespace k) (namespace k2))
                                             (>= c 2)
                                             (edit-1? (name k) (name k2)))
                                    k2))
                                established)]
               :when nbr]
           {:used k :suggest nbr :seen (get established nbr)}))))
