(ns slopp.kernel.parity
  "Keeping the KERNEL's two copies honest.

  `slopp.kernel.rt` and `slopp.kernel.boot` are the one part of slopp that is not only a
  store namespace: each also exists as a hand-maintained file on `main`, and
  both copies are live. That makes the kernel the single layer a store-wide
  sweep cannot see — a rename that rewrites every reference in the store walks
  straight past a file — and it has drifted three times.

  Everything here is PURE and takes source STRINGS, which is the load-bearing
  constraint rather than a style preference: in every context a test runs, the
  \"file\" IS the store's own rendering, so a test that reads it compares the
  store to itself and passes vacuously. Only a caller standing where both
  copies are real can supply them, so the comparison must be something such a
  caller can invoke — a CI lane, or the MCP server in the repo root."
  (:require [rewrite-clj.parser :as p]
            [rewrite-clj.node :as n]
            [clojure.walk :as walk]
            [clojure.string :as str]))

(defn- canonical
  "`form` with the two things that differ between the copies for no reason
  flattened away: reader-generated names, and whitespace inside strings.

  **Reader names.** `n/sexpr` expands `#(inc %)` into `(fn* [p1__67039#] (inc
  p1__67039#))` with a FRESH gensym on every call, so two parses of one string
  are never `=` — a source does not even equal itself. Syntax-quote's
  `x__123__auto__` has the same property. Numbering by FIRST APPEARANCE is what
  keeps this from hiding real drift: two different anonymous fns in one form
  get different placeholders, so swapping them is still a difference.

  **String whitespace.** `^:reads (defn- open-conn` pushes its whole body ten
  columns right in the file and not in the store's rendering, so every
  docstring in both kernels wraps differently. Prose is compared by its WORDS:
  wrapping is free, and a docstring whose words changed is still drift — which
  is what the 2026-07-17 audit caught by hand, and the only non-cosmetic
  difference it found.

  The limit, stated because it is real: this reaches EVERY string, not only
  docstrings. A kernel string whose internal line breaks are load-bearing —
  generated source, a formatted banner — would compare equal across a
  re-wrap. Nothing in either kernel has one today, and picking out \"the
  docstring\" by position is the ambiguous-index bug this codebase has already
  shipped once."
  [form]
  (let [gen? (fn [x] (and (symbol? x)
                          (re-matches #".*__\d+(#|__auto__)" (name x))))
        gs   (->> (tree-seq coll? seq form) (filter gen?) distinct)
        ws   (zipmap gs (map #(symbol (str "g__" %)) (range)))]
    (walk/postwalk
     (fn [x] (cond
               (string? x) (str/join " " (str/split (str/trim x) #"\s+"))
               (symbol? x) (get ws x x)
               :else x))
     form)))

(defn- definitions
  "The top-level DEFINITIONS in `src`, as `{name form}` — everything whose
  second element is a symbol, which is every `def`/`defn`/`defn-`/`defmacro`/
  `defmulti` shape and nothing else.

  The `(ns …)` form is deliberately excluded: its `:require` set differs
  legitimately between the two copies (the store's rendering carries what the
  store needs to load; the injected file copy is eval'd into an image that
  already has them), and it is not surface. Returns nil if the source will not
  parse — the caller turns that into a refusal rather than an empty map, since
  \"would not parse\" and \"has no definitions\" are different answers."
  [src]
  (try
    (->> (n/children (p/parse-string-all (str src)))
         (remove n/whitespace-or-comment?)
         (keep (fn [node]
                 (let [f (n/sexpr node)]
                   (when (and (seq? f) (symbol? (first f)) (symbol? (second f))
                              (not= 'ns (first f)))
                     [(second f) (canonical f)]))))
         (into {}))
    (catch Exception _ nil)))

(defn ^:export kernel-parity
  "Compare two copies of one kernel source and report where they have DRIFTED.

  `slopp.kernel.rt` and `slopp.kernel.boot` are the kernel: they exist as a hand-maintained
  file on `main` AND as namespaces in the store, and both copies are live — the
  file serves a `main`-checkout dev run (which is how the benchmarks execute),
  the store's projection is what the jar ships and what `build!` materializes
  for the external tier. They have drifted four times. The first
  three were found by a human diffing on a hunch, days later. The fourth was
  found by this function — which by then had been written, was correct, and
  had never once run, because the CI lane that calls it lived on a branch
  nobody had pushed. A guard that has produced no verdicts is
  indistinguishable from one that always passes, and it reads in the repo
  exactly like coverage.

  **The invariant is surface + behaviour parity, NOT identity.** Three things
  differ legitimately and forever:

  - **form ORDER** — the store orders by its own logic, the file by human
    grouping. Keying by NAME makes order free rather than something to
    tolerate.
  - **markers** — `^:entry-point`, `^:ambient-ok`, `^:unsafe` earn their keep
    in the store (unreferenced-form analysis, gate discharges) and mean
    nothing to a copy that gets slurped and eval'd into a child image. Clojure
    `=` ignores metadata, so this falls out for free — which is exactly why
    VISIBILITY is compared explicitly below: it is surface, it can ride in
    metadata, and meta-blindness would otherwise hide it.
  - **the `(ns …)` form** — never compared, because only `def*` forms are.
    It MAY differ, since the two copies are loaded by different things; it is
    excluded on that principle rather than because it does. Measured
    2026-08-02: byte-identical. So do not plan a reconciliation around \"the
    requires differ and must be merged by hand\" — that belief cost a
    scheduled manual pass before anyone checked.

  Anything else is drift. `accepted` names the forms whose difference is
  DECLARED — prose true in one copy and meaningless in the other is the case
  it exists for — and the declaration polices itself: a name that no longer
  differs comes back as `:stale-accepted`, because a blocking check with no
  escape gets switched off and an escape nobody prunes stops meaning
  anything.

  **As of 2026-08-02 the list is EMPTY and both copies are fully
  reconciled**, which is the state to keep. `watch-live!` was the one standing
  entry; the reconciliation made it identical, and the staleness check is what
  said so rather than letting a mute button survive its reason.

  Takes SOURCE STRINGS and nothing else, because the two copies are only both
  reachable from the repo root — the MCP server's cwd and no test's. A test
  that reads \"the file\" compares the store to its own rendering and passes
  vacuously; that tautology was written once here already. So the comparison
  is pure and the caller supplies both sides.

  Returns `{:ok true :compared {:file n :store n}}`, or `:ok false` with
  `:file-only` / `:store-only` (sorted names) and `:differing`
  (`{:name :what}`, `:what` being `:body` or `:visibility`). An unparseable or
  definition-free side is an `:error`, never a pass: a guard that reports
  clean on a population of zero is worse than no guard, which this repo has
  also already paid for."
  ([file-src store-src] (kernel-parity file-src store-src #{}))
  ([file-src store-src accepted]
   (let [f (definitions file-src)
         s (definitions store-src)]
     (cond
       (nil? f) {:error "the file copy would not parse — nothing was compared"}
       (nil? s) {:error "the store copy would not parse — nothing was compared"}
       (empty? f) {:error "the file copy has no definitions — a pass here would be vacuous"}
       (empty? s) {:error "the store copy has no definitions — a pass here would be vacuous"}
       :else
       (let [ok?  (set accepted)
             only (fn [a b] (sort (remove (set (keys b)) (keys a))))
             vis  (fn [form] [(first form) (select-keys (meta (second form))
                                                        [:private :dynamic :macro])])
             raw  (vec (sort-by :name
                                (keep (fn [[nm ff]]
                                        (when-let [sf (get s nm)]
                                          (cond
                                            (not= (vis ff) (vis sf)) {:name nm :what :visibility}
                                            (not= ff sf)             {:name nm :what :body})))
                                      f)))
             ;; every name that differs in ANY way, before the declared ones
             ;; are taken out — staleness has to be judged against what is
             ;; actually different, or a name accepted for being ABSENT reads
             ;; as a declaration about nothing and the escape flags the very
             ;; case it exists to permit
             all   (into (set (map :name raw)) (concat (only f s) (only s f)))
             fonly (vec (remove ok? (only f s)))
             sonly (vec (remove ok? (only s f)))
             diff  (vec (remove (comp ok? :name) raw))
             ;; a declaration that no longer describes a difference is the
             ;; ^:unused-ok shape: it reads as "checked and allowed" while
             ;; checking nothing, so it has to fail asking to be removed
             stale (vec (sort (remove all ok?)))]
         (cond-> {:ok true :compared {:file (count f) :store (count s)}}
           (seq ok?)
           (assoc :accepted (vec (sort ok?)))
           (or (seq fonly) (seq sonly) (seq diff) (seq stale))
           (merge {:ok false
                   :file-only  fonly
                   :store-only sonly
                   :differing  diff
                   :stale-accepted stale})))))))
