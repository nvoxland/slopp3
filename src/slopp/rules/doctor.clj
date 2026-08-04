(ns slopp.rules.doctor
  "The LEGACY sweep — what is in this store that the current rules would never
  have let in.

  Every gate slopp has runs at the WRITE, which means none of them has ever
  seen code that arrived another way. `git_clone` and `import!` ingest an
  existing codebase wholesale, and that code predates every rule; so does
  anything written before a rule existed. Those elements are not incorrect —
  `full_check` is happy with them — they are simply unreachable by the tools
  that would normally fix them, which is how one hand-written `(declare …)`
  turned into a thirty-minute detour.

  The third question, and the reason it is its own namespace: `full_check`
  asks whether the store is CORRECT, `store_health` what it COSTS in bytes,
  this what it CARRIES that no longer belongs.

  Pure, so it runs over any store value. Every finding carries the call that
  fixes it — a finding without one is a complaint."
  (:require [slopp.store :as store]
            [slopp.rules.markers :as markers] [clojure.string :as str]))

(defn vocabulary-dead-ends
  "Retired-vocabulary rows whose VALUE goes nowhere in this store.

  The vocabulary (`config_file {path \"vocabulary\" key <old> value <new>}`) is
  the machine-readable twin of the naming glossary, and TWO checks read it: the
  `retired-vocabulary` done-advisory over store forms, and
  `bin/check-shipped-prose.sh` over the skills and `docs/` — prose that SHIPS.
  Neither validates it and nothing else does either, because `config_file` runs
  no gate over its values. A row can point anywhere at all, and the failure is
  silent in the worst place: a reader who looks up a retired name is answered
  with another dead name.

  Two ways a row goes wrong, reported apart because the fixes differ:

  - **`:chained`** — the value is another row's KEY, so *where did it go?* is
    answered with a name that also went somewhere. Phase 2 landed two of these
    in one day (`slopp.ui-api → slopp.http-api`, while `slopp.http-api` was
    itself being retired) and collapsed them by hand, which is exactly the
    manual step this removes.
  - **`:unresolved`** — the value occurs NOWHERE in the store. Measured on
    slopp's own vocabulary the day this shipped: 66 rows, ZERO unresolved and
    zero chained.

  **That zero was earned twice, and the first attempt is the part worth
  keeping.** The check's first run reported one finding —
  the row mapping `:slopp.api/agent-id` to `:slopp.ops/agent-id` — and the
  finding was WRONG.
  `:slopp.ops/agent-id` is live: it is declared in the malli schema on
  `slopp.ops.external/open!`. It hid because a schema lives in METADATA, and
  `tree-seq coll? seq` over a form's sexpr does not descend into metadata — a
  node's meta is not one of its children. So the blob this resolves against had
  a hole shaped exactly like every declared contract in the store, and the
  check's one finding was a name it could not see rather than a name that was
  not there. `diagnose` walks metadata now, and the fixture below pins it.

  A value with no DOT is skipped: a phrase row (`ui-hub → hub`) names a TERM
  rather than a name and has nothing to resolve to.

  Resolution is deliberately OCCURRENCE in `blob` — every namespace name,
  qualified symbol, keyword and string literal in the store — rather than exact
  lookup. A value that appears anywhere is a pointer a reader can follow, which
  is all a glossary owes them. Exact lookup would flag `devserver →
  webdev.live` for being a SUFFIX of `slopp.webdev.live`, and that row is
  perfectly legible."
  [st blob]
  (let [voc   (get-in st [:config "vocabulary" :values])
        keys* (set (map str (keys voc)))]
    (vec (for [[k v] (sort-by (comp str key) voc)
               :let [v   (str v)
                     why (cond (contains? keys* v)         :chained
                               (not (str/includes? v ".")) nil
                               (str/includes? blob v)      nil
                               :else                       :unresolved)]
               :when why]
           {:row (str k) :points-at v :why why
            :fix (if (= :chained why)
                   (str "point it at where " v " ENDED UP, not at " v
                        " — a reader following this row lands on another"
                        " retired name. config_file {path \"vocabulary\" key "
                        (pr-str (str k)) " value \"…\"}")
                   (str v " occurs nowhere in this store, so this row answers"
                        " \"where did " k " go?\" with a name that is not here."
                        " Either the rename did not land or the row names the"
                        " wrong replacement. config_file {path \"vocabulary\" key "
                        (pr-str (str k)) " value \"…\"} to correct it"))}))))

(defn ^:export diagnose
  "Scan `st` for elements that predate a rule slopp now enforces and that no
  ordinary tool can reach — the LEGACY sweep.

  This is not `full_check`, which asks whether the store is CORRECT, nor
  `store_health`, which asks what it COSTS in bytes. It asks a third thing:
  what is in here that the current rules would never have let in, and that
  nothing will surface because every gate runs at the WRITE and these were
  never written through one.

  **The population is an ADOPTED store.** `git_clone` and `import!` bring
  arbitrary existing code in, and that code predates every rule slopp has.
  Measured on slopp's own store the day this shipped: zero unmanaged declares,
  zero duplicate names, zero unknown markers — which is why every detector is
  pinned by a fixture instead of by a clean run here.

  Three classes today, each recurring across separate friction reports:

  - **`:unmanaged-declares`** — a hand-written `(declare …)`. The pipeline
    mints its own with `^{:auto-declare \"why\"}` and reorders around them; a
    hand-written one is invisible to that machinery, so ordering tools refuse
    to help and the fix is a manual detour.
  - **`:duplicate-names`** — two elements in one namespace defining one name.
    Legal in a file, meaningless in a store: form-addressed edits become
    ambiguous, and the LAST one silently wins at load.
  - **`:unknown-markers`** — metadata that looks like one of slopp's dials and
    is not (`^:unusedok` for `^:unused-ok`). It waives nothing while reading
    exactly as though it does, which is the worst of both.
  - **`:vocabulary-dead-ends`** — a retired-vocabulary row whose replacement
    goes nowhere in this store, or points at another row's KEY. `config_file`
    runs no gate over its values, so nothing has ever checked the mapping that
    the `retired-vocabulary` rule and `bin/check-shipped-prose.sh` both trust.

  Every finding carries `:fix` — the call that resolves it. A finding without
  one is a complaint."
  [st]
  (let [rows      (vec (for [nsx (keys (:namespaces st))
                             e   (store/forms st nsx)]
                         {:ns nsx :entry e
                          :sexpr (store/form-sexpr (:node e))
                          :defines (store/form-symbols (:node e))}))
        declares  (vec (for [{:keys [ns sexpr]} rows
                             :when (and sexpr (= 'declare (first sexpr))
                                        (not (:auto-declare (meta sexpr))))
                             nm (rest sexpr)]
                         {:name (symbol (str ns) (str nm))
                          :fix  (str "delete it — the write pipeline reorders forward"
                                     " references itself and mints its own marked"
                                     " declare for a genuine cycle. Nothing needs a"
                                     " hand-written one, and ordering tools cannot"
                                     " see this one")}))
        dupes     (vec (for [[[nsx nm] n] (frequencies (keep (fn [{:keys [ns entry]}]
                                                               (when (:name entry)
                                                                 [ns (:name entry)]))
                                                             rows))
                             :when (> n 1)]
                         {:name (symbol (str nsx) (str nm)) :count n
                          :fix  (str "rename or delete all but one — a form-addressed"
                                     " edit cannot say which of the " n " you mean, and"
                                     " the last one silently wins at load")}))
        unknown   (vec (for [k (sort (markers/undeclared st))]
                         {:marker k
                          :fix    (str "fix the spelling or drop it — ^" k " is not a"
                                       " marker slopp knows, so it waives nothing while"
                                       " reading as though it does. query_rules lists"
                                       " the real ones")}))
        ;; every name and every piece of text this store holds, in one string.
        ;; The vocabulary's values are tested for OCCURRENCE against it rather
        ;; than looked up, because a value a reader can FIND is a pointer they
        ;; can follow — see vocabulary-dead-ends for why exact lookup is worse.
        blob      (str/join "\n"
                            (concat (map str (keys (:namespaces st)))
                                    (for [{:keys [sexpr]} rows
                                          :when sexpr
                                          ;; METADATA is not a child, so a plain
                                          ;; `tree-seq coll? seq` walks past it —
                                          ;; and a malli schema on a var lives
                                          ;; exactly there. `slopp.ops.external/
                                          ;; open!` declares :slopp.ops/agent-id
                                          ;; that way, so without this the
                                          ;; vocabulary row naming it reads as a
                                          ;; dead end.
                                          top (tree-seq coll? seq sexpr)
                                          n   (cons top (when-let [m (meta top)]
                                                          (tree-seq coll? seq m)))
                                          :when (or (string? n)
                                                    (and (or (symbol? n) (keyword? n))
                                                         (namespace n)))]
                                      (str n))))
        vocab     (vocabulary-dead-ends st blob)]
    {:scanned              (count rows)
     :unmanaged-declares   declares
     :duplicate-names      dupes
     :unknown-markers      unknown
     :vocabulary-dead-ends vocab
     :healthy              (every? empty? [declares dupes unknown vocab])}))
