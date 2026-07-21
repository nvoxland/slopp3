(ns slopp.api.rules
  (:require [slopp.store :as store]
            [slopp.api.schema :as schema]
            [slopp.api.attrs :as attrs]
            [slopp.api.breakage :as breakage] [slopp.edit.modules :as edit.modules] [rewrite-clj.node :as n] [clojure.string :as str]))

(defn- changed-qsyms
  "The qualified symbols of the CHANGED forms this episode."
  [st* changed]
  (into #{}
        (keep (fn [fid]
                (when-let [e (store/form-by-id st* fid)]
                  (symbol (str (store/ns-of-form-id st* fid))
                          (str (or (:name e) (:id e)))))))
        changed))

(defn schema-drift-check!
  "Done-advisory check: generative schema drift over the episode's changed forms
   (image-side)."
  [session st* changed]
  (schema/drift! (:image @session) st* (changed-qsyms st* changed)))

(defn key-typos-check
  "Done-advisory check: near-duplicate-key typos among the episode's changes."
  [_session st* changed]
  (attrs/near-duplicate-keys st* changed))

(defn breaking-check
  "Done-advisory check: contract narrowing (arity breakage) among the changes."
  [_session st* changed]
  (breakage/breaking-changes st* changed))

(defn- ambient-def?
  "True when `node` is a top-level `(def name (atom|ref|agent|volatile! …))` —
   ambient MUTABLE state a slice-limited editor can't track.

   Reads the initializer through `store/def-init` rather than by index. This
   fn is the origin of the project's worst bug: it indexed position 2, which
   is where a DOCSTRING sits, so it never fired on a documented global — every
   global anyone had bothered to justify — while reporting one finding for its
   whole life and looking clean."
  [node]
  (let [v (store/def-init node)]
    (boolean (and (seq? v)
                  (contains? '#{atom ref agent volatile!} (first v))))))

(defn- bare-throws?
  "True when `node`'s body throws a freshly-CONSTRUCTED exception that isn't
   `ex-info` — `(throw (SomeException. …))` or `(throw (new … ))`. A re-throw of a
   bound exception `(throw e)` and a structured `(throw (ex-info …))` are fine."
  [node]
  (let [form (try (n/sexpr node) (catch Exception _ nil))]
    (boolean
     (some (fn [x]
             (and (seq? x) (= 'throw (first x))
                  (let [arg (second x)]
                    (and (seq? arg)
                         (let [h (first arg)]
                           (and (symbol? h)
                                (or (= 'new h)
                                    (str/ends-with? (name h) "."))))))))
           (tree-seq coll? seq form)))))

(defn ambient-state-check
  "Done-advisory: changed forms that are ambient MUTABLE state — a global
   `(def _ (atom/ref/agent/volatile! …))`. A global for LOGIC is spooky action a
   slice-limited editor can't track — pass state in instead.

   `^:ambient-ok` on the NAME discharges it, for the case that is genuinely
   deliberate (a memo whose answer is immutable, a process-local cache). The
   marker POLICES ITSELF, exactly as `^:unused-ok` does: a marker on a def that
   is NOT ambient state is itself a finding, so the flag can never drift into
   decoration once whatever justified it is gone."
  [_session st* changed]
  (vec (keep (fn [fid]
               (when-let [e (store/form-by-id st* fid)]
                 (let [ambient? (ambient-def? (:node e))
                       marked?  (let [s (try (n/sexpr (:node e))
                                             (catch Exception _ nil))]
                                  (boolean (and (seq? s) (= 'def (first s))
                                                (symbol? (second s))
                                                (:ambient-ok (meta (second s))))))
                       q        (symbol (str (store/ns-of-form-id st* fid))
                                        (str (or (:name e) (:id e))))]
                   (cond
                     (and ambient? (not marked?)) {:form q}
                     (and marked? (not ambient?))
                     {:form q :stale-marker true
                      :teach (str q " carries ^:ambient-ok but is not ambient"
                                  " state — remove the flag")}))))
             changed)))

(defn bare-throw-check
  "Done-advisory: a changed MODULE-EXTERNAL fn that throws a freshly-constructed
   non-`ex-info` exception. At a boundary, prefer returning data or
   `(ex-info …)` (carrying `ex-data`) — a bare throw lands where a slice-limited
   caller can't see or `=`-test it."
  [_session st* changed]
  (vec (keep (fn [fid]
               (when-let [e (store/form-by-id st* fid)]
                 (let [ns-sym (store/ns-of-form-id st* fid)
                       form   (try (n/sexpr (:node e)) (catch Exception _ nil))]
                   (when (and (edit.modules/module-external? ns-sym form)
                              (bare-throws? (:node e)))
                     {:form (symbol (str ns-sym) (str (or (:name e) (:id e))))}))))
             changed)))
(defn shell-widening-check
  "Namespaces this EPISODE moved into (or further toward) the shell — a new
   `:external`/`:internal` declaration, or a loosening of an existing one.
   Tier spellings normalize on read, so stores that predate the
   :internal/:external rename fire it the same way.

   Declaring a namespace `:external` makes the functional CORE smaller. That is
   sometimes exactly right and sometimes the path of least resistance when a
   gate refuses a write, and the moment to ask is while the reason is still in
   the agent's context — not at review time, when nobody remembers.

   The one rule in the registry that is a QUESTION rather than a verdict, and
   legitimately advisory: the system cannot know whether the effect belonged
   there. It fires only for the episode that made the declaration, so it
   prompts once and cannot decay into a standing warning to scroll past."
  [_session store _changed]
  (let [ds      (store/deltas store)
        since   (->> ds (keep-indexed #(when (= :done (:op %2)) %1)) last)
        recent  (if since (drop (inc since) ds) ds)
        prior   (fn [m] (->> (take (or since (count ds)) ds)
                             (filter #(and (= :module-tier (:op %))
                                           (= m (:module %))))
                             last :tier))
        looser? (fn [t was] (> (get edit.modules/tier-order t 2)
                               (get edit.modules/tier-order (or was t) 2)))]
    (vec (for [d recent
               :when (= :module-tier (:op d))
               :let [t   (edit.modules/canonical-tier (:tier d))
                     was (edit.modules/canonical-tier (prior (:module d)))]
               ;; a FIRST declaration fires too. An undeclared namespace is already
               ;; effectively :external, so this is not a loosening — but writing
               ;; the declaration down IS the decision, and the decision is what
               ;; deserves the question.
               :when (and (contains? #{:internal :external} t)
                          (or (nil? was) (looser? t was)))]
           {:ns (symbol (str (:module d)))
            :tier t
            :why (str (:module d) " moved toward the SHELL (:" (name t) ") this"
                      " episode"
                      (when was (str ", loosened from :" (name was)))
                      " — the core got smaller. Did the effect have to live"
                      " there, or does it belong in an existing shell"
                      " namespace, with the pure part left in core? Accept by"
                      " doing nothing; this asks once.")}))))

(defn stale-reference-check
  "Done-advisory: changed forms whose STRINGS name a var that no longer
   exists — a docstring, teach string, or tool description pointing at
   `a.b/c` where namespace `a.b` is in this store but form `c` is not.

   The gates cannot see this: a var named inside a string is not a
   reference, so renames and moves leave the prose behind. It then ships as
   CONFIDENT WRONG GUIDANCE — the d9077 case shipped the pre-move address of
   `analyze` in two places after the form moved from slopp.index into
   slopp.index.analyze, and an agent following the refusal's own advice got an
   unresolved var. Stale teaching
   is worse than missing teaching, and it costs a failed call to discover.

   PRECISION: only fires when the NAMESPACE exists in the store. A string
   mentioning `clojure.core/eval` or any third-party var can never fire,
   because that namespace was never a store namespace — so the check has no
   false positives on external references, which is what lets it stay quiet
   enough to be believed."
  [_session st* changed]
  (let [nses (:namespaces st*)
        ;; a qualified symbol: at least one dot in the namespace, so bare
        ;; `foo/bar` aliases and prose like `and/or` never match
        ;; the lookbehind excludes a qualified KEYWORD (:slopp.api/dir) — prose
        ;; names those constantly and they are not vars. A rule that cries wolf
        ;; is a rule nobody reads, so precision comes before reach here.
        pat  #"(?<![:\w.-])([a-z][a-zA-Z0-9.-]*\.[a-zA-Z0-9.-]+)/([a-zA-Z0-9*+!?<>=_-]+)"]
    (vec (distinct
          (for [fid   changed
                :let  [e (store/form-by-id st* fid)]
                :when e
                :let  [s (try (n/sexpr (:node e)) (catch Exception _ nil))]
                text  (filter string? (tree-seq coll? seq s))
                [_ nsx nm] (re-seq pat text)
                :let  [ns-sym (symbol nsx)]
                ;; only a namespace THIS store owns — external libs are unknown
                ;; to it and must never fire
                :when (contains? nses ns-sym)
                :when (nil? (store/form-named st* ns-sym (symbol nm)))]
            (let [;; the commonest cause is a MOVE: same name, new home. That needs no
                  ;; fuzzy matching at all — just ask who has this name now.
                  moved (first (for [[other _] nses
                                     :when (not= other ns-sym)
                                     :when (store/form-named st* other (symbol nm))]
                                 (str other "/" nm)))
                  ;; otherwise a TYPO: one Damerau edit inside the named ns
                  typo  (when-not moved
                          (first (for [f     (store/forms st* ns-sym)
                                       :when (:name f)
                                       :when (attrs/edit-1? nm (str (:name f)))]
                                   (str ns-sym "/" (:name f)))))]
              {:form (symbol (str (store/ns-of-form-id st* fid))
                             (str (or (:name e) (:id e))))
               :names (str nsx "/" nm)
               :suggest (or moved typo)
               :teach (str "the text names " nsx "/" nm " but " ns-sym
                           " has no form " nm
                           (cond moved (str " — it now lives at " moved)
                                 typo  (str " — did you mean " typo "?")
                                 :else " — it moved, was renamed, or never existed")
                           ". Fix the prose or the reference; guidance that"
                           " lies costs a failed call to discover.")}))))))

(defn retired-vocabulary-check
  "Done-advisory: changed forms holding a SECOND, stale copy of a vocabulary
   the store has retired. Reads `config \"vocabulary\"` — `{retired current}`,
   e.g. `{\"reads\" \"internal\" \"effects\" \"external\"}`.

   Fires only when a form MIXES a retired member with a CANONICAL one, and
   that co-occurrence is the whole precision argument. Measured on this store:
   a bare `:reads` appears in four production forms and every one is the
   still-valid `^:reads` MARKER — a different concept that happens to share a
   name with the retired tier. Name-matching alone would be four false
   positives out of five, which is the trap CLAUDE.md rule 4 warns about
   directly. A form that ranks `{:pure 0 :reads 1 :effects 2}` is unambiguous:
   it is enumerating the vocabulary, and it is out of date.

   That form is exactly how the tier rename shipped a live crash — the gate
   was migrated, the reporting arm kept its own rank table, and
   `query_depends {modules true}` NPE'd on any store carrying a canonical
   tier.

   `^:legacy-ok` on the NAME discharges it for the one form that must hold
   both — the normalizer itself — and polices itself: a marker on a form that
   mixes nothing is reported stale, so it cannot decay into decoration.

   A retired-ONLY set is caught too, by the same rule: `#{:reads :effects}` —
   the filter that made the shell-widening advisory silently dead, matching a
   value nothing could produce any more — enumerates two retired members and
   fires. Co-occurrence with a replacement was the first design and it MISSED
   that case, because `:pure` is canonical without being a replacement value.

   BOUND: a form holding exactly ONE retired member is never flagged. That is
   the deliberate price of not drowning the four legitimate `^:reads` marker
   forms, and it is why `:fires-on` discipline on the rule registry still
   matters as the second line of defence."
  [_session st* changed]
  (let [vocab   (get-in st* [:config "vocabulary" :values])
        retired (into #{} (map keyword) (keys vocab))
        current (into #{} (map keyword) (vals vocab))]
    (if (empty? vocab)
      []
      (vec (keep
            (fn [fid]
              ;; -test namespaces EXERCISE legacy spellings on purpose — pinning
              ;; that legacy-in still resolves is exactly their job. Exempting
              ;; them matches tier-violations and full-check!'s layer loop;
              ;; without it, six of this store's seven findings are tests doing
              ;; the right thing.
              (when-let [e (when-not (str/ends-with?
                                      (str (store/ns-of-form-id st* fid)) "-test")
                             (store/form-by-id st* fid))]
                (let [s    (try (n/sexpr (:node e)) (catch Exception _ nil))
                      kws  (into #{} (filter keyword?) (tree-seq coll? seq s))
                      old  (seq (filter retired kws))
                      ;; ENUMERATION is the signal: two retired members in one form, or one
                      ;; beside its own replacement. A LONE retired keyword is left
                      ;; alone — on this store every bare :reads is the still-valid
                      ;; ^:reads marker, a different concept sharing a name.
                      mix? (and old (or (>= (count old) 2) (some current kws)))
                      ok?  (boolean (and (seq? s) (symbol? (second s))
                                         (:legacy-ok (meta (second s)))))
                      q    (symbol (str (store/ns-of-form-id st* fid))
                                   (str (or (:name e) (:id e))))]
                  (cond
                    (and mix? (not ok?))
                    {:form q
                     :retired (vec (sort old))
                     :teach (str q " enumerates the vocabulary with retired"
                                 " spellings " (vec (sort old)) " alongside"
                                 " current ones — a second copy that did not"
                                 " get the memo. Route through the normalizer,"
                                 " or mark the form ^:legacy-ok if it IS the"
                                 " normalizer. (Retired → current: "
                                 (pr-str vocab) ")")}

                    (and ok? (not mix?))
                    {:form q :stale-marker true
                     :teach (str q " carries ^:legacy-ok but mixes no retired"
                                 " vocabulary — remove the flag")}))))
            changed)))))

(def done-advisories
  "The done-time advisory registry (D9 rule-registry — the done-grain sibling of
   `edit.modules/per-form-write-gates`): an ordered list of {:key :severity
   :check :fires-on} entries. `:check` is `(session store changed) ->
   findings-seq` (empty when clean); `:severity` is `:error` (its findings flip
   `test-status` red — a real failure) or `:advisory` (a heuristic that never
   does). A NEW done-time finding registers HERE, in ONE entry, instead of
   hand-wiring a binding, a cond-> clause, and a status term into `done!`.
   Checks are held as VARS so a hot-reload is picked up and the reference graph
   sees them — and a carried `#'var` is NOT a call, so the analyzer no longer
   taints this data def effectful.

   `:fires-on` is a source string the check MUST report a finding for, enforced
   by `rules-test/every-advisory-fires-on-its-own-fixture`. A rule that stops
   firing is otherwise indistinguishable from a clean codebase: `ambient-state`
   read a def's value at index 2 — where a DOCSTRING sits — and so never once
   fired on a documented global, looking healthy for its entire life while
   nine of them accumulated. A rule with no automatic fixture must say why in
   `:selftest-note` rather than silently omitting one."
  [{:key :schema-drift     :severity :error    :check #'schema-drift-check!
    :selftest-note "generative mg/check against a live impl — needs a booted image, covered by api.schema-test/drift-flags-a-lying-schema"}
   {:key :key-typos        :severity :advisory :check #'key-typos-check
    ;; an ESTABLISHED key must be used by >= 2 unchanged forms before a
    ;; near-duplicate counts as a typo rather than a new coinage
    :fires-on (str "(ns rf.core)\n"
                   "(defn one [] {:rf/status 1})\n"
                   "(defn two [] {:rf/status 2})\n"
                   "(defn typo [] {:rf/staus 3})\n")}
   {:key :breaking-changes :severity :advisory :check #'breaking-check
    :selftest-note "compares against the last-done BASELINE, so a fixture needs two done-points — covered by api.breakage-test"}
   {:key :ambient-state    :severity :advisory :check #'ambient-state-check
    :fires-on "(ns rf.core)\n(def cache (atom {}))\n"}
   {:key :bare-throw       :severity :advisory :check #'bare-throw-check
    :fires-on "(ns rf.core)\n(defn boom [] (throw (Exception. \"x\")))\n"}
   ;; teaching that LIES: a string naming a var the store no longer has. Gates
   ;; can't see a var inside a string, so renames/moves leave the prose behind
   ;; and it ships as confident wrong guidance (d9077 shipped
   ;; slopp.index/analyze in two places after the form moved).
   {:key :stale-reference :severity :advisory :check #'stale-reference-check
    :fires-on (str "(ns rf.core)\n"
                   "(defn live [x] x)\n"
                   "(defn teach \"see rf.core/gone for details\" [x] x)\n")}
   ;; a SECOND copy of a vocabulary the store retired — the shape that shipped
   ;; a live NPE (a rank table still spelling the old tiers) and that made
   ;; shell-widening silently dead (a match set nothing could satisfy).
   {:key :retired-vocabulary :severity :advisory :check #'retired-vocabulary-check
    :selftest-note (str "needs a store-level `vocabulary` config to have anything"
                        " retired — a source-only fixture cannot carry one;"
                        " covered by rules-test/"
                        "retired-vocabulary-catches-the-second-copy-not-the-marker")}
   ;; the one entry that is a QUESTION, not a verdict — and therefore the one
   ;; that is legitimately :advisory. The system cannot know whether an effect
   ;; belonged in the namespace that was just widened; only the agent who did
   ;; it can. It fires ONLY for the episode that declared the tier, so it asks
   ;; once and cannot become a standing warning to scroll past.
   {:key :shell-widening  :severity :advisory :check #'shell-widening-check
    :selftest-note "fires on a :module-tier DELTA, not on source — a fixture would need a tier declaration, covered by rules-test/done-asks-about-a-newly-widened-shell"}
   ])

(defn run-done-advisories!
  "Run every registered done-advisory `:check` over the episode's changes —
   EXCEPT those a project dialed `:off` (`edit.modules/rule-severity`) — and
   return `{:key findings}` for the checks that FIRED (non-empty result), ready
   to merge into `done!`'s findings. `!` — `schema-drift-check!` evals in the
   image."
  [session st* changed]
  (into {}
        (keep (fn [{:keys [key severity check]}]
                (when (not= :off (edit.modules/rule-severity st* key severity))
                  (let [r (check session st* changed)]
                    (when (seq r) [key r])))))
        done-advisories))

(defn status-affecting-fired?
  "True when an advisory whose EFFECTIVE severity is `:error` produced findings —
   a real failure that should flip `test-status` red. Effective severity is the
   per-store override (`edit.modules/rule-severity`) else the registry default, so
   a project can dial `key-typos` up to `:error` or `schema-drift` down to
   `:advisory`. `:advisory`/`:off` never flip status. Args: the store (for the
   config) and the `{:key findings}` map from `run-done-advisories!`."
  [store advisories]
  (boolean (some (fn [{:keys [key severity]}]
                   (and (= :error (edit.modules/rule-severity store key severity))
                        (seq (get advisories key))))
                 done-advisories)))

