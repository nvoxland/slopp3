(ns slopp.api.orient
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [rewrite-clj.node :as n]
            [slopp.store :as store] [slopp.store.fields :as fields]))

(defn snip
  "Cap `s` at `n` chars with an ellipsis — composites (brief/report) carry
  MANY prose fields and must never give back the tokens they save; the
  full text stays one query away (report {contains}, query_history)."
  [s n]
  (let [s (str s)]
    (if (<= (count s) n) s (str (subs s 0 n) "…"))))

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
          sig     (or (some #(when (vector? %) %) (drop 1 (or body ())))
                      (let [arities (keep #(when (and (seq? %) (vector? (first %)))
                                             (first %))
                                          (drop 2 (or body ())))]
                        (when (seq arities) (vec arities))))
          why     (get (store/prompt-by-form (:store @session)) (:id e))
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
        doc  (assoc :doc (snip (first (str/split-lines doc)) 90))
        (str/ends-with? (str nm) "!") (assoc :effectful true)
        why  (assoc :why (snip why 90))
        examples (assoc :examples examples)))))

(defn fit-report
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

(defn ^:export host-brief
  "The serving host's code-currency section for session_brief, as data —
  pure assembly over the kernel's boot-info record (`info`), the count of
  CODE-affecting deltas landed after the host booted, and whether the
  session is on a branch line. Nil `info` (a process that didn't boot from a
  store) → nil, the section simply absent.

  The stances it teaches: a :snapshot host serves LAUNCH-time code, so
  post-boot deltas are inert until restart; a :live host tracks the MAIN
  journal only (a branch line's writes reload the image, never the host);
  a reload failure is NAMED, because a silently held-back namespace is how
  three debugging arcs started. The notes COMPOSE — a snapshot host ON a
  branch gets both stances (review V-F3, the branch caveat used to be
  unreachable in snapshot mode)."
  [info deltas-since-boot on-branch?]
  (when info
    (let [failed (seq (:failed info))
          parts  (cond-> []
                   failed
                   (conj (str "live-reload FAILED for " (str/join ", " failed)
                              " — the host still runs their previous code; the"
                              " next poll retries, and the failure is in the"
                              " server log"))

                   (and (not failed) (= :snapshot (:mode info))
                        (pos? (or deltas-since-boot 0)))
                   (conj (str deltas-since-boot " code delta(s) landed after this"
                              " server booted — snapshot mode serves launch-time"
                              " code, so serving-machinery changes are inert until"
                              " restart"))

                   on-branch?
                   (conj (str "host code tracks the MAIN journal — this branch"
                              " line's writes hot-reload the image only; verify"
                              " branch serving behavior there or in a fresh JVM")))
          note   (when (seq parts) (str/join " " parts))]
      (cond-> {:mode (:mode info) :booted-at (:booted-at info)}
        (:last-reload-at info) (assoc :last-reload-at (:last-reload-at info))
        failed                 (assoc :failed (vec failed))
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
  producing that verdict is knowingly running code the store has moved past.

  `host-brief` answers the same question for ORIENTATION, and this is the same
  producer aimed at the other reader: session_brief is read once at the start
  of a session, while `done` / `full_check` / `test_run` are read after every
  unit of work and their entire output is a claim about the code. A host that
  failed to reload said nothing on any of them, which is how one investigation
  spent four ruled-out hypotheses inside `rt` before finding a stale process.

  Two states warrant it, and nothing else does:
  - a **failed reload** (either mode) — the host TRIED to become current and
    could not, so it is running a namespace the store has superseded;
  - a **:snapshot host with code deltas since boot** — it serves launch-time
    code by design, so every one of those deltas is inert in it.

  A clean `:live` host is SILENT even with deltas outstanding: the watcher
  polls, so lagging by up to one interval is normal operation, and a warning
  on every done is a warning nobody reads.

  What it does NOT answer: whether every var in the process is byte-identical
  to the store. It answers whether the host has knowingly failed to load
  something. A reload that succeeded while a long-lived closure kept the old
  fn is invisible here."
  [info deltas-since-boot]
  (when info
    (let [failed?  (seq (:failed info))
          stale?   (and (= :snapshot (:mode info)) (pos? (or deltas-since-boot 0)))]
      (when (or failed? stale?)
        (assoc (host-brief info deltas-since-boot false)
               :verdict-note
               (str "this verdict was produced by a host running code the store"
                    " has moved past — treat it as suspect until the host is"
                    " current (restart the server, or check the reload failure"
                    " in its log)"))))))
