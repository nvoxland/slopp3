(ns slopp.index.derive
  (:require [clojure.string :as str]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]))

^:unsafe (def ^:export effectful-leaves
  "Fixed anchor set: core primitives that modify in-process or external state
  (D6 scope = modification). Extensible; reads / non-determinism are NOT here.

  Console IO (`println` &c) and watch registration are here but deliberately
  NOT in `external-leaves`: they break referential transparency (so `:pure`
  forbids them) yet are capturable/resettable in-process (`with-out-str`, a
  registry reset), so they need no test isolation and `:internal` allows them,
  exactly like `swap!`.

  `^:unsafe` because the set names `clojure.core/alter-var-root` as DATA — the
  dialect denylist matches it by name/core, so listing effect names here has
  the same standing as `banned-syms`/`observe-banned`."
  '#{clojure.core/swap! clojure.core/reset! clojure.core/swap-vals!
     clojure.core/reset-vals! clojure.core/compare-and-set!
     clojure.core/vreset! clojure.core/vswap!
     clojure.core/alter clojure.core/ref-set clojure.core/alter-var-root
     clojure.core/commute clojure.core/send clojure.core/send-off
     clojure.core/deliver clojure.core/conj! clojure.core/disj!
     clojure.core/assoc! clojure.core/dissoc! clojure.core/pop!
     clojure.core/add-watch clojure.core/remove-watch
     clojure.core/println clojure.core/prn clojure.core/print
     clojure.core/printf clojure.core/pr
     clojure.core/spit clojure.core/delete-file})

(defn ^:export node
  "Fully-qualified node key for a var: ns/name."
  [ns nm]
  (symbol (str ns) (str nm)))

(defn ^:export call-graph
  "Map of caller-node -> #{callee-node} for effect propagation. An edge is added
   for a usage UNLESS it's a `#'var` CARRIER — a non-call reference (kondo marks a
   call with `:arity`) whose position is a var-quote (`:var-quotes`). So a var HELD
   in data (a rule registry carrying `#'some-bang!`) doesn't taint its holder, but
   a CALL `(f …)`, a bare ALIAS `(def g f)`, and a higher-order value-arg
   `(map f xs)` all propagate (conservative — those are callable-as-`f`). Top-level
   usages (`:from-var` nil) are skipped. Reference-tracking for visibility/renames
   is a separate path (`edit.refs`)."
  [analysis]
  (let [vq (or (:var-quotes analysis) #{})]
    (reduce (fn [m u]
              (if (and (:from-var u)
                       (or (contains? u :arity)
                           (not (contains? vq [(:name-row u) (:name-col u)]))))
                (update m (node (:from u) (:from-var u))
                        (fnil conj #{}) (node (:to u) (:name u)))
                m))
            {}
            (:var-usages analysis))))

(defn ^:export bang-target?
  "A call target whose NAME is bang-marked counts as an effectful anchor (D6:
  '...or another ! function') — this is what carries effectfulness across
  namespace boundaries, since analysis is per-namespace (N1)."
  [target]
  (str/ends-with? (name target) "!"))

(def ^:export external-leaves
  "Core anchors that touch the world OUTSIDE this process — as opposed to the
  rest of `effectful-leaves`, which mutate in-process state (`swap!`,
  `reset!`, transients, refs, agents).

  This is the split the `:internal` tier rests on. Measured on slopp: the
  read/write axis put `render` and `edit.refs` in the same bucket as `db` and
  `repl`, because a memo `swap!` and a `git push` are both writes. The
  internal/external axis separates them, and it is the axis that decides how a
  thing must be TESTED: anything external needs isolation (fresh JVM, temp
  dirs, cleanup), while in-process state needs only a reset.

  Reads count here too — `slurp` needs the file to exist, so an external read
  demands the same test isolation as an external write."
  '#{clojure.core/spit clojure.core/delete-file clojure.core/slurp
     clojure.core/file-seq clojure.core/line-seq
     clojure.java.io/file clojure.java.io/reader clojure.java.io/writer
     clojure.java.io/copy clojure.java.io/delete-file
     clojure.java.shell/sh})

(defn ^:export effectful-vars
  "Set of user var nodes that transitively reach an effectful anchor (D6).
  An anchor is a `!`-leaf, a bang-named callee, OR (M3) a call into an OPAQUE
  external dependency — a target whose namespace `external-ns?` accepts and
  which `pure-vars` does not cover (worst-case: slopp can't see the dep's body,
  so the call is effectful unless the author asserts it pure). `pure-vars` is
  matched at TWO granularities: the fully-qualified var (`ext.lib/go`) OR its
  bare namespace (`ext.lib`) — so a whole pure library can be narrowed without
  enumerating every var. Monotonic fixpoint — cycle-safe. 1-arg = the pre-M3
  behavior (no external boundary)."
  ([analysis] (effectful-vars analysis nil nil))
  ([analysis external-ns? pure-vars]
   (let [edges (call-graph analysis)
         ext?  (or external-ns? (constantly false))
         pure  (or pure-vars #{})
         pure? (fn [t] (or (contains? pure t)
                           (contains? pure (some-> (namespace t) symbol))))
         anchor? (fn [t]
                   (or (effectful-leaves t)
                       (bang-target? t)
                       (and (ext? (some-> (namespace t) symbol))
                            (not (pure? t)))))]
     (loop [eff (set (for [[n ts] edges :when (some anchor? ts)] n))]
       (let [eff' (into eff (for [[n ts] edges :when (some eff ts)] n))]
         (if (= eff eff') eff (recur eff')))))))

(defn ^:export bang?
  "Does `nm` end in `!` — i.e. does it CLAIM to be effectful? The D6
  soundness bound rests on this: cross-namespace effects are visible to the
  analysis only where the callee is bang-named, so the naming gate and this
  predicate are two halves of one guarantee."
  [nm]
  (str/ends-with? (str nm) "!"))

(defn ^:export test-definition?
  "Is this var-definition a test (deftest)? Tests are exempt from the `!`
  naming rule (T1) — they routinely exercise effectful code but are never
  bang-named by convention."
  [d]
  (= 'clojure.test/deftest (:defined-by d)))

(defn ^:export effect-violations
  "Vars that are computed effectful (D6) but NOT `!`-named — the one actionable
  direction: name it `!`. Each {:var node :effectful? true :named-bang? false
  :suggest name!}. `external-ns?`/`pure-vars` (M3) extend the anchors to
  opaque-dependency calls. Exemptions:
  - `deftest` vars (T1) — tests exercise effects but are never banged.
  - `-main` — an effectful entry point, never banged by convention.
  - the REVERSE direction (banged but computed pure) is NOT reported: a `!` is a
    human assertion of effectfulness, and the analyzer can't see interop/opaque
    effects (`.close`, a socket/JGit write), so it must not demand the `!` be
    removed. Only a MISSING `!` is a real signal."
  ([analysis] (effect-violations analysis nil nil))
  ([analysis external-ns? pure-vars]
   (let [eff (effectful-vars analysis external-ns? pure-vars)]
     (for [d (:var-definitions analysis)
           :when (not (test-definition? d))
           :when (not= '-main (:name d))
           :let [n (node (:ns d) (:name d))]
           :when (and (contains? eff n) (not (bang? (:name d))))]
       {:var n :effectful? true :named-bang? false
        :suggest (str (:name d) "!")}))))

(defn ^:export references
  "Usages of `to-ns/to-name` — who references this var."
  [analysis to-ns to-name]
  (for [u (:var-usages analysis)
        :when (and (= to-ns (:to u)) (= to-name (:name u)))]
    {:from-ns (:from u) :from-var (:from-var u) :row (:row u) :col (:col u)}))

(defn ^:export forward-refs
  "Same-namespace var usages positioned BEFORE the var's first definition or
  declare — code a LIVE image hot-loads happily (the var already exists) but
  a FRESH namespace load cannot resolve (boot, restart, a new image). The
  cold-load half of the compile gate. Position is (row, col) lexicographic —
  forms can share a line. Returns [{:symbol :row :col :form :def-row}].
  Known over-approximation: a syntax-quoted own-ns symbol counts as a usage
  (kondo doesn't distinguish it) — a declare satisfies the gate there too."
  [analysis ns-sym]
  (let [before? (fn [[r1 c1] [r2 c2]]
                  (or (< r1 r2) (and (= r1 r2) (< c1 c2))))
        dpos    (reduce (fn [m d]
                          (let [p [(:row d) (:col d)]]
                            (update m (:name d)
                                    #(if (and % (before? % p)) % p))))
                        {}
                        (filter #(= ns-sym (:ns %)) (:var-definitions analysis)))]
    (vec (for [u (:var-usages analysis)
               :when (and (= ns-sym (:to u)) (= ns-sym (:from u)))
               :let [d (get dpos (:name u))]
               :when (and d (before? [(:row u) (:col u)] d))]
           {:symbol  (:name u)
            :row     (:row u)
            :col     (:col u)
            :form    (:from-var u)
            :def-row (first d)}))))

(def ^:export nondeterministic-leaves
  "Core primitives whose result is NOT a function of the args — randomness and
   external reads. These are NOT `!`-effects (D6 scopes `!` to MODIFICATION, and
   reads/non-determinism deliberately take no bang), but they break REFERENTIAL
   TRANSPARENCY, so the `:pure` tier — the strictest, RT core — forbids reaching
   them. Interop non-determinism (`System/currentTimeMillis`, `.nextInt`) is out of
   scope: the analyzer sees core vars, not interop — the same gap D6 has."
  '#{clojure.core/rand clojure.core/rand-int clojure.core/rand-nth
     clojure.core/random-uuid clojure.core/shuffle
     clojure.core/slurp clojure.core/line-seq clojure.core/read-line})

(defn ^:export externally-effectful-vars
  "Set of user var nodes that transitively reach the world OUTSIDE this
   process: an `external-leaves` anchor, or a call into an OPAQUE external
   dependency that `pure-vars` does not cover. In-process mutation
   (`swap!`, `reset!`, transients, refs) is NOT an anchor here.

   This is what the `:internal` tier enforces. `effectful-vars` answers a
   different question — did anything change at all — which put a memoized
   projection in the same class as a subprocess spawn. Monotonic fixpoint,
   cycle-safe; mirrors `effectful-vars`."
  [analysis external-ns? pure-vars]
  (let [edges (call-graph analysis)
        ext?  (or external-ns? (constantly false))
        pure  (or pure-vars #{})
        pure? (fn [t] (or (contains? pure t)
                          (contains? pure (some-> (namespace t) symbol))))
        anchor? (fn [t]
                  (or (contains? external-leaves t)
                      (and (ext? (some-> (namespace t) symbol))
                           (not (pure? t)))))]
    (loop [eff (set (for [[n ts] edges :when (some anchor? ts)] n))]
      (let [eff' (into eff (for [[n ts] edges :when (some eff ts)] n))]
        (if (= eff eff') eff (recur eff'))))))

(defn ^:export nondeterministic-vars
  "Set of user var nodes that transitively reach a `nondeterministic-leaves`
   anchor — randomness or an external read — over the call graph (monotonic
   fixpoint, cycle-safe; mirrors `effectful-vars`). What the `:pure` tier adds on
   top of the effect check to enforce referential transparency."
  [analysis]
  (let [edges   (call-graph analysis)
        anchor? (fn [t] (contains? nondeterministic-leaves t))]
    (loop [nd (set (for [[n ts] edges :when (some anchor? ts)] n))]
      (let [nd' (into nd (for [[n ts] edges :when (some nd ts)] n))]
        (if (= nd nd') nd (recur nd'))))))

(defn ^:export var-quote-positions
  "The positions `[row col]` of the SYMBOL inside every `#'var` var-quote HELD
   AS DATA in `source` — a carrier (a `#'var` in a registry, a higher-order
   arg). A var-quote in the HEAD of a LIST is EXCLUDED: `(#'f x)` derefs the
   var and CALLS it, so it must propagate effects like any call (kondo sets no
   `:arity` there, so `call-graph` cannot tell it from a carrier without this).
   These align with kondo's `:name-row`/`:name-col` for the usage, so
   `call-graph` excludes a carrier from effect propagation while KEEPING a
   called var-quote and a bare value reference."
  [source]
  (let [root (try (p/parse-string-all source) (catch Exception _ nil))]
    (if-not root
      #{}
      (let [pos-of  (fn [vq] (let [m (meta (first (n/children vq)))]
                               (when (and (:row m) (:col m)) [(:row m) (:col m)])))
            ;; a var-quote at the HEAD of a list is a CALL, not a carrier
            called  (into #{}
                          (comp (filter #(= :list (n/tag %)))
                                (keep (fn [lst]
                                        (let [h (first (filter n/sexpr-able?
                                                               (n/children lst)))]
                                          (when (and h (= :var (n/tag h)))
                                            (pos-of h))))))
                          (tree-seq n/inner? n/children root))]
        (into #{}
              (comp (filter #(= :var (n/tag %)))
                    (keep pos-of)
                    (remove called))
              (tree-seq n/inner? n/children root))))))

(def ^:export kondo-config
  "SLOPP'S OWN clj-kondo configuration — static, shipped with slopp, applied to
  every project it hosts. Deliberately NOT a file in the user's repo and NOT a
  per-store knob: linter levels are part of slopp's definition of clean code,
  the same way the dialect and the rule registry are.

  It must be passed EXPLICITLY rather than left to kondo's own resolution.
  Kondo otherwise reads config relative to the process CWD, which is exactly
  the bug #134 fixed for the cache: findings that vary by which directory the
  process happened to start in. The tree is fileless, so a store cloned
  elsewhere must lint identically or `done` means different things on
  different machines.

  EVERYTHING IS `:error`. Nothing slopp keeps is 'just a warning' — a finding
  an agent may scroll past is not a rule. `:level` here means SEVERITY ONLY;
  it does NOT decide what refuses a write. That is `edit/write-blocking-lint`,
  a separate structural set, because a form mid-edit may legitimately carry an
  unused binding and refusing it makes red-first TDD unreachable (measured:
  33 assertions across 14 tests). See D-rule-grain."
  {:output  {:analysis true}
   :linters
   {;; ── OFF: measured and rejected, with the numbers, so the next person does
    ;; not re-derive the same good-sounding rationale and re-measure it. ──
    ;;
    ;; 156 findings, 81 of them the parameter `agent` shadowing
    ;; clojure.core/agent, which this codebase never calls. The rule an agent
    ;; actually wants is "a symbol means one thing in one form", but the linter
    ;; cannot tell "shadows something unused" from "shadows a fn used in this
    ;; very form", and the volume is slopp's own domain vocabulary (agent, ns,
    ;; new, name, key, format). Enforcing it renames the RIGHT words to prevent
    ;; a hazard that has not bitten.
    :shadowed-var                 {:level :off}
    ;; 62 findings, and the wrong TOOL: require order is mechanical, so the
    ;; normalizer should SORT it. Never warn about what a tool can fix.
    :unsorted-required-namespaces {:level :off}

    ;; ── ERROR: blocks, at every grain. ──
    ;;
    ;; THE TEST for putting a type here: could a form legitimately look like
    ;; this MID-EDIT, on the way to something correct? If no, refusing it
    ;; immediately saves a wasted episode. If yes, it belongs below.
    ;;
    ;; (kondo's own defaults already put :syntax, :unresolved-symbol and
    ;; :invalid-arity at :error — they are not restated here.)
    ;;
    ;; a value computed and DISCARDED in non-tail position is almost always a
    ;; forgotten result — the language-level form of the bug that bit this
    ;; codebase four times at the wire layer (computed, then dropped).
    :unused-value                 {:level :error}
    ;; (if x y) returns an implicit nil. slopp's recurring failure mode is a
    ;; plausible wrong value rather than a crash; an unstated nil is that.
    :missing-else-branch          {:level :error}
    :redundant-fn-wrapper         {:level :error}
    :single-key-in                {:level :error}
    :redefined-var                {:level :error}
    :duplicate-require            {:level :error}
    :conflicting-alias            {:level :error}
    :misplaced-docstring          {:level :error}
    :deprecated-var               {:level :error}
    :type-mismatch                {:level :error}
    :inline-def                   {:level :error}
    :not-empty?                   {:level :error}
    :equals-true                  {:level :error}

    ;; ── WARNING: `done` LISTS these; nothing blocks on them. ──
    ;;
    ;; Every one is routinely, correctly true of a form MID-EDIT: you bind a
    ;; value before writing the line that uses it, you require a namespace
    ;; before calling into it, you wrap a body in a `do` you are about to
    ;; fill. MEASURED — promoting these to :error turned 13 assertions red
    ;; across 7 tests, killing red-first TDD (a spec naming a not-yet-written
    ;; fn), the module lifecycle (:unresolved-namespace on a legitimate
    ;; forward reference), and carried-lint compression.
    :unused-binding               {:level :warning}
    :unused-referred-var          {:level :warning}
    :unused-namespace             {:level :warning}
    :unused-private-var           {:level :warning}
    :unresolved-namespace         {:level :warning}
    :redundant-do                 {:level :warning}
    :redundant-let                {:level :warning}
    :redundant-expression         {:level :warning}
    :unreachable-code             {:level :warning}}})
