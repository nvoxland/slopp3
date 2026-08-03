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
            [slopp.rules.markers :as markers]))

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
                                       " the real ones")}))]
    {:scanned              (count rows)
     :unmanaged-declares   declares
     :duplicate-names      dupes
     :unknown-markers      unknown
     :healthy              (every? empty? [declares dupes unknown])}))
