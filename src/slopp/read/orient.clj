(ns slopp.read.orient
  "What a session needs to know before it does anything — assembled purely.

  `session_brief` is the one call a fresh context is told to make, so this is
  where the answer to \"where am I, and what should I distrust?\" is built. The
  sections are pure functions of already-recorded facts: the store value, the
  kernel's boot-info record, the delta counts. Nothing here queries; the
  effectful callers gather, and this shapes.

  The bias throughout is toward saying what is WRONG or STALE rather than what
  is present. A brief that lists everything is a brief nobody reads; a brief
  that names the one namespace whose reload failed is what stopped three
  debugging arcs from starting. Notes COMPOSE rather than replace each other,
  because a host can be stale for more than one reason at once and picking a
  winner hides the rest."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [rewrite-clj.node :as n]
            [slopp.store :as store] [slopp.store.fields :as fields] [slopp.edit.modules :as modules]))

(defn ^:export snip
  "Cap `s` at `n` chars with an ellipsis — composites (brief/report) carry
  MANY prose fields and must never give back the tokens they save; the
  full text stays one query away (report {contains}, query_history)."
  [s n]
  (let [s (str s)]
    (if (<= (count s) n) s (str (subs s 0 n) "…"))))

(def ^:private doc-summary-cap
  "The character budget for a doc summary. Composites carry MANY of these, so
  one verbose docstring must not eat the result."
  120)

(defn doc-summary
  "The first SENTENCE of `doc`, capped — what a card or a module surface row
  shows.

  Replaces a raw character cut, which ended mid-word and produced fragments
  that look like content: `slopp.web.css/render`'s card used to read
  \"Garden rules → a minified CSS string. Every string in the rule data — a\".
  A trailing fragment is worse than a clean stop, because it reads as though
  the thought finished.

  A sentence ends at `.`/`!`/`?` followed by whitespace and a capital or an
  opening bracket — NOT at every period, or `1.5 KB` and `e.g.` split in half
  and the result reads worse than the cut it replaces.

  Over the cap it still truncates, but on a WORD boundary with an ellipsis:
  the budget is the point, and the full text is one `query_slice` away.

  nil for nil or blank — never `\"\"` or a bare ellipsis, which would put an
  empty `:doc` key on a card and read as \"documented, with nothing to say\"."
  [doc]
  (let [s (some-> doc str str/trim (str/replace #"\s+" " "))]
    (when-not (str/blank? s)
      (let [end (some-> (re-find #"^(.*?[.!?])(?=\s+[A-Z(\[])" s) second)
            one (or end s)]
        (if (<= (count one) doc-summary-cap)
          one
          (let [cut (subs one 0 doc-summary-cap)
                sp  (str/last-index-of cut " ")]
            (str (str/trimr (if (and sp (< 40 sp)) (subs cut 0 sp) cut)) "…")))))))

^:reads (defn ^:export form-card
  "The INTERFACE view of a form (opacity with a warranty): signature,
  doc line, effect marker, the recorded WHY (last ask), and the warranty
  (covering tests from the trace map) — what a CALLER needs, at ~10x less
  than source. Trusting it is mechanical, not hopeful: every edit re-runs
  the covering tests, so a violated contract turns red with :implicated.

  Exported: the reviewer UI inlines a callee's card beside the caller
  rather than linking to it, and assembling sig/doc/why/warranty a second
  time is how the two views drift apart."
  [session ns-sym nm]
  (when-let [e (store/form-named (:store @session) ns-sym nm)]
    (let [q       (symbol (str ns-sym) (str nm))
          s       (try (n/sexpr (:node e)) (catch Exception _ nil))
          body    (when (seq? s) s)
          doc     (some #(when (string? %) %) (take 3 (drop 2 (or body ()))))
          ;; through the SHARED all-arities extraction, and only for forms that
          ;; have arities at all. This was "the first vector anywhere after the
          ;; head", which reads `(def rates [0.07 0.20])` as a parameter list —
          ;; 32 defs in this store would have reported one, including
          ;; `capabilities/registry`, whose whole 19-entry vector drew as a
          ;; signature. A card is ^:export'ed so a consumer can inline a callee
          ;; INSTEAD of reading it, which makes a confident wrong sig the worst
          ;; kind of wrong here.
          ;;
          ;; Single arity stays unwrapped (`[cents rate]`, not `[[cents rate]]`)
          ;; because that is what every reader of this key already renders.
          sig     (let [as (when (#{"defn" "defn-" "defmacro"} (str (first (or body ()))))
                             (modules/fn-arglists body))]
                    (cond (= 1 (count as)) (first as)
                          (seq as)         (vec as)))
          why     (get (store/prompt-by-form (:store @session)) (:id e))
          ;; the TRAP, if the author declared one. Separate from :doc on
          ;; purpose: a doc's first line says what the form does, and the
          ;; thing that stops a caller misusing it is never in the first
          ;; line. Same :teach vocabulary every rule uses for
          ;; explain-at-point-of-use.
          ;; a STRING only. Metadata is read as data and never evaluated, so
          ;; ^{:teach (str "a" "b")} is a LIST — rendering it would put
          ;; `(str "a" "b")` on the card, confident-looking and useless.
          teach   (let [t (:teach (meta (second (or body ()))))]
                    (when (string? t) (not-empty t)))
          covered (let [ks (store/form-trace-keys ns-sym e)]
                    ;; any name the form defines can carry its evidence (#129):
                    ;; a defprotocol's card counts tests that called m or n
                    (count (keep (fn [[t fs]] (when (some fs ks) t))
                                 (:test-map @session))))
          examples (when-let [raw (get (:observed @session)
                                       (str "observed/" ns-sym "/" nm))]
                     (try
                       (->> (edn/read-string raw)
                            (take 2)
                            (mapv (fn [{:keys [args ret threw]}]
                                    (snip (str "(" nm " " (str/join " " args)
                                               ") → " (or ret threw))
                                          90)))
                            not-empty)
                       (catch Exception _ nil)))]
      (cond-> {:form q :warranty {:covered covered}}
        sig  (assoc :sig sig)
        doc  (assoc :doc (doc-summary doc))
        (str/ends-with? (str nm) "!") (assoc :effectful true)
        why   (assoc :why (snip why 90))
        teach (assoc :teach teach)
        examples (assoc :examples examples)))))

(defn ^:export fit-report
  "G13 at the gate boundary — by AGGREGATION, never amputation: an
  over-budget report first trims asks to 1/row, then ROLLS CHANGES UP by
  namespace ({:ns :forms :ops :asks}) — the information survives at a
  coarser grain and report {contains} expands any group. Amputation
  (take 20 of the rollup) is the last resort for pathological stores.
  (eval9: the take-20 amputation CAUSED the handoff fan-out — agents went
  hunting for what the report dropped.)"
  [r]
  (let [fits? #(<= (count (pr-str %)) 6500)]
    (if (fits? r)
      r
      (let [slim (update r :changes
                         (fn [cs] (mapv #(update % :asks (comp vec (partial take 1))) cs)))]
        (if (fits? slim)
          (assoc slim :note "asks trimmed to 1/row — report {contains} narrows")
          (let [rolled (->> (:changes slim)
                            (group-by :ns)
                            (mapv (fn [[nsx rows]]
                                    {:ns nsx :forms (count rows)
                                     :ops (vec (distinct (mapcat :ops rows)))
                                     :asks (vec (take 1 (distinct (mapcat :asks rows))))}))
                            (sort-by (comp str :ns)))
                slim2  (assoc slim :changes (vec rolled)
                              :note "changes rolled up by namespace — report {contains <ns or word>} expands a group")]
            (if (fits? slim2)
              slim2
              (-> slim2
                  (update :changes #(vec (take 20 %)))
                  (assoc :note (str "rolled up by namespace, showing 20 of "
                                    (count rolled) " — {contains} narrows"))))))))))

(def stuck-reload-attempts
  "Consecutive failed reload polls after which a namespace is STUCK rather
  than retrying — three.

  Low on purpose, and it can be: a reload is deterministic over the same
  source, so the second identical failure already tells you the third is
  coming. Three is one more than the argument needs, which leaves room for a
  genuine transient (a half-written db page, a contended read) without leaving
  room for false hope."
  3)

(defn ^:export host-brief
  "The serving host's code-currency section for session_brief, as data —
  pure assembly over the kernel's boot-info record (`info`), the count of
  CODE-affecting deltas landed after the host booted, whether the session is
  on a branch line, and the measured `drift` between the image and the store
  (`slopp.rules.currency/drift`, or nil when it was not computed). Nil `info`
  (a process that didn't boot from a store) → nil, the section simply absent.

  The stances it teaches: a :snapshot host serves LAUNCH-time code, so
  post-boot deltas are inert until restart; a :live host tracks the MAIN
  journal only (a branch line's writes reload the image, never the host);
  a reload failure is NAMED, because a silently held-back namespace is how
  three debugging arcs started. The notes COMPOSE — a snapshot host ON a
  branch gets both stances (review V-F3, the branch caveat used to be
  unreachable in snapshot mode).

  **A reload failure carries its REASON and its age.** It used to say the
  failure was \"in the server log\" — a file nothing here exposes, which on a
  system whose claim is that the store answers everything is the wrong answer
  twice over. And it promised \"the next poll retries\" identically for many
  minutes: a reload is deterministic over the same source, so repeated failure
  is not a transient to wait out. Past `stuck-reload-attempts` the note stops
  reassuring and says what actually fixes it.

  **And it no longer claims staleness it has not measured (friction 20a).** A
  failed reload used to read \"the host still runs their previous code\", which
  is an assertion about this process made without looking at it — and it was
  WRONG in the case that cost the most: five namespaces reported stale while
  the process already held every one of them at the store's current source.

  **Nor does it claim the opposite.** The first correction over-swung: a clean
  `drift` was read as proof the failure was only a stuck watcher. But `drift`
  measures the child ORACLE — `currency/stamp!` runs here, yet records what
  was pushed INTO that separate JVM — while the reload that failed is THIS
  process's. A failed host reload is exactly when the two diverge, so the
  oracle comparing clean is no evidence at all about the host.

  **So the host now measures ITSELF.** `slopp.boot/host-drift` compares what
  this process actually loaded — recorded by every door into this JVM,
  `load-store!` and the live watcher — against the store's current sources,
  kernel rendering on both sides. It arrives on `info` as `:host-drift`, and a
  failed reload finally has three honest answers instead of one guess:

  - `[]` — measured current, so the failure is the WATCHER being stuck, which
    is the claim friction 20a needed and could not earn;
  - a list — measured behind, and it NAMES the namespaces;
  - absent — nobody looked, and it says so rather than picking a side.

  The two subjects read side by side and cannot be confused: `:host-drift` /
  `:host-verified` for this process, `:oracle-drift` / `:oracle-verified` for
  the image that runs the tests. Same quantity, each named for who it is about.

  A comparison also cannot get STUCK the way a failure record can: 20a's
  watcher retried a renamed-away namespace forever, but a namespace the store
  no longer has is simply absent from the comparison.

  (`host-warning` is a different reader and is correct using oracle drift: a
  VERDICT is produced by the oracle, so the oracle's currency is precisely
  what should qualify it.)"
  [info deltas-since-boot on-branch? drift]
  (when info
    (let [failed  (seq (:failed info))
          why     (:failed-why info)
          checked (some? drift)
          clean?  (and checked (empty? drift))
          hdrift  (:host-drift info)
          hcheck  (some? hdrift)
          hclean  (and hcheck (empty? hdrift))
          reason  (fn [ns-sym]
                    (when-let [w (get-in why [ns-sym :why])]
                      (str ns-sym ": " w)))
          tries   (apply max 0 (keep #(get-in why [% :attempts]) failed))
          stuck?  (>= tries stuck-reload-attempts)
          parts   (cond-> []
                    failed
                    (conj (str "live-reload FAILED for " (str/join ", " failed)
                               (cond
                                 hclean
                                 (str " — but THIS process was COMPARED to the"
                                      " store and holds every namespace at its"
                                      " current source, so this is the watcher"
                                      " stuck, not stale code")

                                 hcheck
                                 (str " — and this process IS behind on "
                                      (str/join ", " hdrift))

                                 :else
                                 (str " — whether THIS process still holds the"
                                      " previous code has not been measured"))
                               (when-let [rs (seq (keep reason failed))]
                                 (str " (" (str/join "; " rs) ")"))
                               (if stuck?
                                 (str "; this has failed " tries
                                      " polls running and a reload is"
                                      " deterministic over the same source, so"
                                      " it will not fix itself — restart the"
                                      " server")
                                 "; the next poll retries")))

                    (and (not failed) (seq hdrift))
                    (conj (str (count hdrift) " namespace(s) in THIS process are"
                               " behind the store and no reload failed, so"
                               " nothing said so: " (str/join ", " (take 5 hdrift))
                               " — restart the server"))

                    (and (not failed) (seq drift))
                    (conj (str (count drift) " form(s) in the VERIFICATION image"
                               " differ from the store — no reload failed there,"
                               " so nothing said so: "
                               (str/join ", "
                                         (map #(str (:ns %) "/" (:form %)
                                                    " (" (name (:why %)) ")")
                                              (take 5 drift)))))

                    (and (not failed) (= :snapshot (:mode info))
                         (pos? (or deltas-since-boot 0)))
                    (conj (str deltas-since-boot " code delta(s) landed after this"
                               " server booted — snapshot mode serves launch-time"
                               " code, so serving-machinery changes are inert until"
                               " restart"))

                    (seq (:load-failures info))
                    (conj (str (count (:load-failures info))
                               " namespace(s) did NOT load at boot and this"
                               " process is running without them: "
                               (str/join ", " (map :ns (:load-failures info)))
                               ". The store stayed open on purpose so you can"
                               " FIX them — a broken namespace you can edit"
                               " beats a store you cannot reach. Restart once"
                               " they compile"))

                    on-branch?
                    (conj (str "host code tracks the MAIN journal — this branch"
                               " line's writes hot-reload the image only; verify"
                               " branch serving behavior there or in a fresh JVM")))
          note    (when (seq parts) (str/join " " parts))]
      (cond-> {:mode (:mode info) :booted-at (:booted-at info)}
        (:last-reload-at info) (assoc :last-reload-at (:last-reload-at info))
        failed                 (assoc :failed (vec failed))
        (seq why)              (assoc :failed-why why)
        (seq drift)            (assoc :oracle-drift (vec (take 20 drift))
                                      :oracle-drift-count (count drift))
        clean?                 (assoc :oracle-verified true)
        (seq hdrift)           (assoc :host-drift (vec hdrift))
        hclean                 (assoc :host-verified true)
        note                   (assoc :note note)))))

(defn ^:export code-deltas-since
  "How many CODE deltas landed after `at` (epoch ms) — the host-currency
  count, and the ONLY spelling of it.

  Markers (`store.fields/markers`: :verify :done :commit :merge :revert, the
  turn pair) are bookkeeping, not code: a host that has not \"loaded\" a :done
  delta is not behind on anything. A nil `at` counts everything rather than
  skipping the question, because a missing boot timestamp must never read as
  a clean bill of health."
  [store at]
  (let [at (or at 0)]
    (count (filter #(and (> (:at % 0) at)
                         (not (contains? fields/markers (:op %))))
                   (store/deltas store)))))

(defn ^:export host-warning
  "The host code-currency record for a VERDICT — nil unless the process
  producing that verdict is running code the store has moved past.

  `host-brief` answers the same question for ORIENTATION, and this is the same
  producer aimed at the other reader: session_brief is read once at the start
  of a session, while `done` / `full_check` / `test_run` are read after every
  unit of work and their entire output is a claim about the code. A host that
  failed to reload said nothing on any of them, which is how one investigation
  spent four ruled-out hypotheses inside `rt` before finding a stale process.

  What warrants doubting a verdict:
  - **measured drift** — forms this image does not hold as the store describes
    them, whether or not anything reported a failure. This is the direction
    that used to be entirely silent: a `def` holding a value captured from a
    form re-evaluated since, or a namespace written to the store and never
    loaded, threw nothing and so warned nobody;
  - a **failed reload that has NOT been checked against the image** (`drift`
    nil) — the honest cautious default when nothing looked;
  - a **:snapshot host with code deltas since boot** — it serves launch-time
    code by design, so every one of those deltas is inert in it.

  What does NOT: a failed reload on an image that COMPARES CLEAN. That was
  friction 20a — five namespaces reported stale, the image holding every one
  of them at the store's current source, and every verdict from that host
  marked suspect until a milestone was routed through a fresh JVM to escape a
  problem that was not there. A watcher that cannot reload is worth saying out
  loud in the brief; it is not a reason to distrust the tests.

  A clean `:live` host is SILENT even with deltas outstanding: the watcher
  polls, so lagging by up to one interval is normal operation, and a warning
  on every done is a warning nobody reads."
  [info deltas-since-boot drift]
  (when info
    (let [drifted? (seq drift)
          hdrift   (:host-drift info)
          behind?  (seq hdrift)
          ;; it is the HOST's reload that failed, so only a HOST measurement
          ;; can retire the doubt — the oracle used to stand in for it
          failed?  (and (seq (:failed info)) (nil? hdrift))
          stale?   (and (= :snapshot (:mode info)) (pos? (or deltas-since-boot 0)))]
      (when (or drifted? behind? failed? stale?)
        (assoc (host-brief info deltas-since-boot false drift)
               :verdict-note
               (cond
                 behind?
                 (str "this verdict was produced by a host that is behind the"
                      " store on " (str/join ", " (take 5 hdrift))
                      " — it runs the code that ORCHESTRATES verification, so"
                      " treat the verdict as suspect and restart the server")

                 drifted?
                 (str "this verdict was produced against a verification image"
                      " holding " (count drift) " form(s) the store has moved"
                      " past — treat it as suspect and restart the server; the"
                      " :oracle-drift list names them")

                 :else
                 (str "this verdict was produced by a host whose live-reload"
                      " failed, and whether it still runs the store's current"
                      " code has NOT been measured — treat it as suspect until"
                      " the host is current (restart the server)")))))))
