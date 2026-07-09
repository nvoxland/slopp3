(ns slopp.normalize
  "Deterministic 'if the form looks like X, rewrite to Y' normalization —
  conservative, provably behavior-preserving, kibit-style rules for the
  patterns agents habitually over-write. Runs at explicit checkpoints (never
  silently mid-edit), and the rewrite is committed as a tracked `:normalize`
  delta whose behavior the normal verification loop re-checks.

  Rules rebuild replacement lists from the ORIGINAL child nodes, so inner
  formatting/comments of the pieces survive; only the rewritten wrapper is
  re-spaced."
  (:require [rewrite-clj.parser :as p]
            [rewrite-clj.node :as n]
            [rewrite-clj.zip :as z]))

(defn- kid-locs [zloc]
  (->> (z/down zloc) (iterate z/right) (take-while some?)))

(defn- lnode [nodes]
  (n/list-node (interpose (n/spaces 1) nodes)))

(defn- tok [sym] (n/token-node sym))

(defn- inner-nodes
  "Value children of a child LIST node (e.g. the X in `(not X)`)."
  [node]
  (remove #(or (n/whitespace? %) (n/comment? %)) (n/children node)))

(defn- rewrite-list
  "If the list at `zloc` matches a rule, return [replacement-node rule-desc]."
  [zloc]
  (let [s    (z/sexpr zloc)
        head (when (seq? s) (first s))
        kids (mapv z/node (kid-locs zloc))
        cnt  (count kids)]
    (cond
      ;; (do X) => X
      (and (= 'do head) (= 2 cnt))
      [(nth kids 1) "(do x) => x"]

      ;; (if T X) / (if T X nil) => (when T X)
      (and (= 'if head)
           (or (= 3 cnt) (and (= 4 cnt) (nil? (nth s 3)))))
      [(lnode [(tok 'when) (nth kids 1) (nth kids 2)])
       "(if t x nil) => (when t x)"]

      ;; (if T nil E) => (when-not T E)
      (and (= 'if head) (= 4 cnt) (nil? (nth s 2)))
      [(lnode [(tok 'when-not) (nth kids 1) (nth kids 3)])
       "(if t nil e) => (when-not t e)"]

      ;; (if X true false) => (boolean X)
      (and (= 'if head) (= 4 cnt) (true? (nth s 2)) (false? (nth s 3)))
      [(lnode [(tok 'boolean) (nth kids 1)])
       "(if x true false) => (boolean x)"]

      ;; (if (not X) A B) => (if-not X A B)
      (and (= 'if head) (= 4 cnt)
           (seq? (second s)) (= 'not (first (second s))) (= 2 (count (second s))))
      [(lnode (into [(tok 'if-not) (second (inner-nodes (nth kids 1)))]
                    (subvec kids 2)))
       "(if (not x) a b) => (if-not x a b)"]

      ;; (when (not X) & BODY) => (when-not X & BODY)
      (and (= 'when head) (>= cnt 3)
           (seq? (second s)) (= 'not (first (second s))) (= 2 (count (second s))))
      [(lnode (into [(tok 'when-not) (second (inner-nodes (nth kids 1)))]
                    (subvec kids 2)))
       "(when (not x) ...) => (when-not x ...)"]

      ;; (not (= & ARGS)) => (not= & ARGS)
      (and (= 'not head) (= 2 cnt)
           (seq? (second s)) (= '= (first (second s))))
      [(lnode (into [(tok 'not=)] (rest (inner-nodes (nth kids 1)))))
       "(not (= ...)) => (not= ...)"]

      ;; (= X nil) / (= nil X) => (nil? X)
      (and (= '= head) (= 3 cnt) (or (nil? (nth s 1)) (nil? (nth s 2))))
      [(lnode [(tok 'nil?) (nth kids (if (nil? (nth s 1)) 2 1))])
       "(= x nil) => (nil? x)"]

      ;; (not (nil? X)) => (some? X)
      (and (= 'not head) (= 2 cnt)
           (seq? (second s)) (= 'nil? (first (second s)))
           (= 2 (count (second s))))
      [(lnode [(tok 'some?) (second (inner-nodes (nth kids 1)))])
       "(not (nil? x)) => (some? x)"]

      ;; (into [] XS) => (vec XS)
      (and (= 'into head) (= 3 cnt) (= [] (nth s 1)))
      [(lnode [(tok 'vec) (nth kids 2)])
       "(into [] xs) => (vec xs)"]

      ;; (filter (complement P) XS) => (remove P XS)
      (and (= 'filter head) (= 3 cnt)
           (seq? (second s)) (= 'complement (first (second s)))
           (= 2 (count (second s))))
      [(lnode [(tok 'remove) (second (inner-nodes (nth kids 1))) (nth kids 2)])
       "(filter (complement p) xs) => (remove p xs)"]

      ;; (cond T X) => (when T X)   (single-clause cond)
      (and (= 'cond head) (= 3 cnt) (not (keyword? (nth s 1))))
      [(lnode [(tok 'when) (nth kids 1) (nth kids 2)])
       "(cond t x) => (when t x)"]

      :else nil)))

(defn normalize-source
  "Apply the rules to `src` bottom-up until fixpoint.
  Returns {:src src' :applied [rule-desc ...]}."
  [src]
  (loop [zloc (z/of-string src), applied []]
    (let [hit (->> (iterate z/next zloc)
                   (take-while (complement z/end?))
                   (keep (fn [l]
                           (when (= :list (z/tag l))
                             (when-let [[repl desc] (try (rewrite-list l)
                                                         (catch Exception _ nil))]
                               [l repl desc]))))
                   first)]
      (if-let [[l repl desc] hit]
        (recur (z/of-string (z/root-string (z/replace l repl)))
               (conj applied desc))
        {:src (z/root-string zloc) :applied applied}))))

(defn normalize-form
  "Normalize one form node. Returns {:node node' :applied [...]}
  (`:applied` empty ⇒ node unchanged)."
  [node]
  (let [{:keys [src applied]} (normalize-source (n/string node))]
    {:node (if (seq applied)
             (first (filter n/sexpr-able? (n/children (p/parse-string-all src))))
             node)
     :applied applied}))
