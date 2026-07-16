(ns slopp.edit.modules (:require [clojure.string :as str] [rewrite-clj.node :as n] [slopp.index :as index] [slopp.render :as render] [slopp.store :as store]))

(defn quote-pruned-qualified-syms
  "Every namespace-qualified symbol in sexpr `x`, skipping quoted subtrees —
  a quoted symbol is data and never resolves, so it isn't a call."
  [x]
  (cond
    (and (seq? x) (= 'quote (first x))) nil
    (symbol? x) (when (namespace x) [x])
    (map-entry? x) (concat (quote-pruned-qualified-syms (key x))
                           (quote-pruned-qualified-syms (val x)))
    (coll? x) (mapcat quote-pruned-qualified-syms x)
    :else nil))
(defn ^:export modules-manifest
  "The module manifest — {module-string #{dep-module-strings}} — the FOLD
  of the store's :module-edge deltas (edge-grain: concurrent declarations
  merge as a union, and every edge carries its why). {} for a fresh store
  (enforcement is on from birth; the first cross-module call teaches
  declare-then-use). nil ONLY for a populated store that predates the
  module system — open! derives its manifest from reality (adoption)."
  [store]
  (:modules store))
(defn ^:export module-of
  "A namespace's MODULE: its first two segments (\"x.y\"), or the whole
  name for single-segment namespaces. A trailing \"-test\" folds into the
  subject's module (\"x.y-test\" → \"x.y\") — tests live with what they
  test, so the natural TDD flow needs no edge ceremony."
  [ns-sym]
  (let [segs (clojure.string/split (str ns-sym) #"\.")]
    (clojure.string/join "." (map #(clojure.string/replace % #"-test$" "")
                                  (take 2 segs)))))
(defn ^:export export-level
  "The :export declared on the target var's defn name — nil (package-
  private), true (hoisted to the module's WORLD surface, reachable by any
  module with a declared edge), or a namespace-prefix string (visible only
  to callers under that subtree — within-project widening without going
  public). The definition-site visibility dial: no var copying, no facade
  namespace — the gate reads it where the fn lives."
  [store to-ns to-name]
  (when (and to-ns to-name)
    (when-let [e (store/form-named store (symbol (str to-ns)) (symbol (str to-name)))]
      (let [x (some-> (try (n/sexpr (:node e)) (catch Exception _ nil))
                      second meta :export)]
        (cond (true? x)   true
              (string? x) x
              (symbol? x) (str x)          ; ^{:export a.pub} — unquoted, forgiven
              :else       (when x true))))))
(defn ^:export derive-module-edges
  "The ACTUAL cross-module dependency edges of a store — kondo-resolved
  var usages grouped by module — as {module #{dep-modules}}, dep-less
  modules absent. Adoption uses this: a manifest derived from reality is
  acyclic with zero violations by construction."
  [store]
  (let [nses (set (keys (:namespaces store)))]
    (reduce (fn [acc nsx]
              (let [cmod  (module-of nsx)
                    tmods (into #{}
                                (comp (filter #(contains? nses (:to %)))
                                      (map #(module-of (:to %)))
                                      (remove #{cmod}))
                                (:var-usages
                                 (index/analyze (render/render-ns store nsx))))]
                (if (seq tmods) (merge-with into acc {cmod tmods}) acc)))
            {}
            (sort nses))))
(defn ^:export fold-test-ns
  "The namespace with a trailing `-test` stripped from EACH segment. A test
  namespace folds into the package it tests — for recursive VISIBILITY as
  well as module membership (module-of) — so a spec can reach the
  package-private deep helpers it exists to test (x.y.z-test is part of
  x.y.z.*)."
  [ns-sym]
  (->> (clojure.string/split (str ns-sym) #"\.")
       (map #(clojure.string/replace % #"-test$" ""))
       (clojure.string/join ".")))
(defn ^:export module-violations
  "The module system's pure RULES over resolved usage rows (kondo
  var-usages shape: {:from-ns :from-var :to :to-export}) — nil `manifest`
  = a pre-adoption store, rules off. Two rules: (1) RECURSIVE VISIBILITY —
  an ns deeper than two segments is callable only from namespaces sharing
  its parent prefix, unless the target var's :export widens it (true =
  world surface; a prefix string = that subtree only); (2) DECLARED EDGES
  — a cross-module call requires the caller's module to list the target
  module in the manifest. Rows must already be filtered to store-internal
  targets. Returns violation maps ({:from-ns :from-var :target-ns :rule
  :error}), nil when clean."
  [manifest rows]
  (when manifest
    (->> (distinct rows)
         (keep (fn [{:keys [from-ns from-var to to-export]}]
                 (let [caller-mod (module-of from-ns)
                       ;; fold -test so a spec shares its subject package's prefix (deep helpers
                       ;; stay testable); edges already fold via module-of
                       caller-str (fold-test-ns from-ns)
                       tsegs      (str/split (str to) #"\.")
                       tmod       (module-of to)
                       parent     (str/join "." (butlast tsegs))
                       under?     (fn [prefix]
                                    (or (= caller-str prefix)
                                        (str/starts-with? caller-str (str prefix "."))))
                       visible?   (or (under? parent)
                                      (true? to-export)
                                      (and (string? to-export) (under? to-export)))]
                   (cond
                     (= (str from-ns) (str to)) nil

                     (and (> (count tsegs) 2) (not visible?))
                     {:from-ns from-ns :from-var from-var :target-ns to
                      :rule :visibility
                      :error (if (string? to-export)
                               (str from-ns "/" from-var " calls " to " which is"
                                    " exported only within " to-export ".* — call"
                                    " it from inside that subtree, raise its"
                                    " :export level, or use " tmod "'s public"
                                    " surface")
                               (str from-ns "/" from-var " calls " to " which is"
                                    " package-private to " parent ".* (recursive"
                                    " visibility) — call " tmod "'s public"
                                    " surface, mark the target ^:export in its"
                                    " defn to hoist it into that surface"
                                    " (^{:export \"prefix\"} exposes it to a"
                                    " subtree only), or move the definition up"
                                    " a level"))}

                     (and (not= tmod caller-mod)
                          (not (contains? (get manifest caller-mod #{}) tmod)))
                     {:from-ns from-ns :from-var from-var :target-ns to
                      :rule :undeclared-edge
                      :error (str from-ns "/" from-var " uses " to " but module "
                                  caller-mod " does not declare " tmod
                                  " — declare the edge: module_dep {from \""
                                  caller-mod "\" to \"" tmod "\"} (say why in"
                                  " prompt), or restructure the call")}

                     :else nil))))
         seq)))
(defn ^:export qualified-usage-rows
  "Usage rows kondo CANNOT produce: namespace-qualified symbols that name a
  store namespace the form never requires. Such a call compiles in the image
  (every store ns is loaded there), and kondo emits NO var-usage row for an
  unresolved namespace — so a bare `deep.ns/var` call would slip the module
  gate entirely. Quote-pruned. Same row shape the gates feed to
  module-violations."
  [candidate ns-sym form-names]
  (let [nses (set (keys (:namespaces candidate)))]
    (vec (for [fname form-names
               :let [e (store/form-named candidate ns-sym fname)]
               :when e
               s (distinct (quote-pruned-qualified-syms
                            (try (n/sexpr (:node e)) (catch Exception _ nil))))
               :let [to (symbol (namespace s))]
               :when (and (contains? nses to) (not= to ns-sym))]
           {:from-ns ns-sym :from-var fname :to to
            :to-export (export-level candidate to (symbol (name s)))}))))
(defn ^:export module-refusal
  "The per-form module gate over the CANDIDATE store (post-edit value):
  kondo-analyzes the namespace (memoized on source — the lint gate pays
  for the same analysis) and applies the module rules to `form-name`'s
  outbound usages. Resolution is kondo's, so :refer'd bare calls and
  full qualification are all seen. nil when clean or pre-adoption."
  [candidate ns-sym form-name]
  (when-let [manifest (modules-manifest candidate)]
    (let [nses (set (keys (:namespaces candidate)))
          rows (concat
                (for [u (:var-usages (index/analyze (render/render-ns candidate ns-sym)))
                      :when (and (= form-name (:from-var u))
                                 (contains? nses (:to u)))]
                  {:from-ns ns-sym :from-var (:from-var u) :to (:to u)
                   :to-export (export-level candidate (:to u) (:name u))})
                ;; kondo misses un-required qualified calls — synthesize
                (qualified-usage-rows candidate ns-sym [form-name]))]
      (when-let [vs (module-violations manifest rows)]
        (str/join "; " (map :error vs))))))
(defn ^:export module-scan
  "The whole-namespace module gate (ingest/ns_create counterpart of
  dialect-scan) over a candidate store value: nil when clean, else every
  violation joined."
  [candidate ns-sym]
  (when-let [manifest (modules-manifest candidate)]
    (let [nses (set (keys (:namespaces candidate)))
          rows (concat
                (for [u (:var-usages (index/analyze (render/render-ns candidate ns-sym)))
                      :when (contains? nses (:to u))]
                  {:from-ns ns-sym :from-var (:from-var u) :to (:to u)
                   :to-export (export-level candidate (:to u) (:name u))})
                ;; kondo misses un-required qualified calls — synthesize
                (qualified-usage-rows candidate ns-sym
                                      (keep :name (store/forms candidate ns-sym))))]
      (when-let [vs (module-violations manifest rows)]
        (str/join "; " (map :error vs))))))
(defn ^:export missing-doc-warning
  "Public-surface documentation rule (module system): a defn/defmacro on
  the module surface — depth<=2 namespace, or a deeper var hoisted by
  ^:export — should carry a docstring. One advisory row for the NAMED form
  (write paths attach it to their result; it never rides ns-warnings, so
  it nags only where you are working), or nil."
  [store ns-sym form-name]
  (when (and (modules-manifest store) form-name)
    (when-let [e (store/form-named store ns-sym form-name)]
      (let [s (try (n/sexpr (:node e)) (catch Exception _ nil))]
        (when (and (seq? s)
                   (contains? '#{defn defmacro} (first s))
                   (symbol? (second s))
                   (not (:private (meta (second s))))
                   (not (string? (nth s 2 nil)))
                   (or (<= (count (str/split (str ns-sym) #"\.")) 2)
                       ;; only a WORLD export is public surface — a subtree
                       ;; export stays internal, no docstring nag
                       (true? (:export (meta (second s))))))
          {:var (symbol (str ns-sym) (str (second s)))
           :missing-doc true})))))
