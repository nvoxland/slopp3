(ns slopp.rules
  "The checks that need an EPISODE, and the registry that runs them.

  A write gate sees one form and must decide immediately, so it can only ask
  questions one form answers. Everything here needs more: what the last done
  looked like, which tests bounced along the way, what the whole reference
  graph says. That is the dividing line — not severity, and not importance.

  Two standing rules shape every entry. **A finding must be
  DISCHARGEABLE**: a rule you cannot act on gets scrolled past, which is worse
  than no rule because it teaches that findings are noise. And **a rule must
  be measured over the real store before it ships** (D-rule-grounding) — the
  withdrawn `:positional-form-access` advisory produced 4-5 false positives out
  of 5 and would have looked thorough.

  `done-advisories` is the registry; `catalog` holds the prose and the
  coverage test keeps the two from drifting. Each entry carries either a
  `:fires-on` fixture or a `:selftest-note` saying why it cannot have one — a
  rule that silently stops firing is indistinguishable from a clean codebase,
  which is exactly what `ambient-state` did for its entire life."
  (:require [slopp.store :as store]
            [slopp.rules.schema :as schema]
            [slopp.rules.keywords :as attrs]
            [slopp.rules.breakage :as breakage] [slopp.edit.modules :as edit.modules] [rewrite-clj.node :as n] [clojure.string :as str] [slopp.rules.web :as rules.web] [slopp.rules.catalog :as catalog] [slopp.index.refs :as refs] [slopp.rules.shape :as shape] [rewrite-clj.parser :as p] [slopp.rules.markers :as markers] [slopp.edit.tiers :as tiers] [slopp.edit.gates :as gates]))

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
  "Done-advisory: a changed fn that throws a freshly-constructed non-`ex-info`
   exception. Prefer returning data or `(ex-info …)`, which carries `ex-data` a
   caller can dispatch on and `=`-test.

   RATCHETED 2026-07-31, from module-external-only to EVERY fn and from
   advisory to error. The widening was free: a store-wide search found zero
   production bare throws and exactly one in a test, because the advisory had
   already done its work. Ratcheting a rule while you are already clean is the
   cheapest it will ever be, and the argument for the scope is simpler than the
   one it replaces — `ex-info` always, rather than `ex-info` at boundaries and
   a judgement call everywhere else.

   The reason it is worth having at all is not tidiness. A bare exception can
   only be caught by TYPE, so a caller who wants to handle one failure ends up
   catching a whole class and swallowing every unrelated bug in the same block.
   That is not hypothetical: `slopp.hub/post!` wrapped its entire body
   in `(catch Exception _ nil)` and reported the project as ABSENT whenever any
   bug fired, and it was only that wide because the transport underneath threw
   a bare `IOException`. Give the throw `ex-data` and the catch can be narrow.

   `^{:bare-throw-ok \"why\"}` on the NAME discharges it, and it polices itself:
   a marker on a form with no bare throw is reported stale. Real cases exist —
   satisfying a Java API contract, an `InterruptedException`, or a test that
   throws a non-`ex-info` precisely to prove it gets masked. The marker had to
   exist BEFORE the severity moved, because the old escape was the prose \"or
   accept the throw\", which means nothing once a finding reds the done."
  [_session st* changed]
  (vec (keep (fn [fid]
               (when-let [e (store/form-by-id st* fid)]
                 (let [ns-sym (store/ns-of-form-id st* fid)
                       form   (try (n/sexpr (:node e)) (catch Exception _ nil))
                       bare?  (bare-throws? (:node e))
                       ok?    (boolean (and (seq? form) (symbol? (second form))
                                            (:bare-throw-ok (meta (second form)))))
                       q      (symbol (str ns-sym) (str (or (:name e) (:id e))))]
                   (cond
                     (and bare? (not ok?))
                     {:form q
                      :teach (str q " throws a freshly-constructed non-ex-info"
                                  " exception. A bare exception can only be"
                                  " caught by TYPE, so a caller handling one"
                                  " failure has to catch a whole class and"
                                  " swallows every unrelated bug with it. Use"
                                  " (ex-info msg {…}) so the catch can be"
                                  " narrow, or mark it"
                                  " ^{:bare-throw-ok \"why\"} if this exact type"
                                  " is required by something outside your"
                                  " control.")}

                     (and ok? (not bare?))
                     {:form q :stale-marker true
                      :teach (str q " carries ^{:bare-throw-ok …} but throws no"
                                  " bare exception — remove the flag")}))))
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
        looser? (fn [t was] (> (get tiers/tier-order t 2)
                               (get tiers/tier-order (or was t) 2)))]
    (vec (for [d recent
               :when (= :module-tier (:op d))
               :let [t   (tiers/canonical-tier (:tier d))
                     was (tiers/canonical-tier (prior (:module d)))]
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
        ;; the lookbehind excludes a qualified KEYWORD (:slopp.ops/dir) — prose
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

(defn tracked-file-drift-check!
  "Done-advisory: a file on the store's manifest whose WORKING-TREE twin has
   different content. `!` — it reads the working tree.

   Tracked files are the one thing that exists twice: the store owns a copy
   (projected onto the slopp branch) and the human branch carries a real file at
   the same path. Nothing compared them, so `build.clj` fell behind by both the
   slim-jar section and the atomic uber-rename — the jar-swap corruption fix —
   and a consumer building from a published checkout got the truncation bug back.

   `:advisory`, and every finding carries `:severity :advisory` explicitly: the
   two copies differ legitimately between a store write and the next projection
   (or pull), so this reports which way to reconcile rather than flipping done
   red on a normal intermediate state. A path with no file on disk has no twin
   and is not drift — generated blobs live only in the store."
  [session st* _changed]
  (when-let [dir (:dir @session)]
    (vec
     (keep (fn [path]
             (let [f (java.io.File. ^String dir ^String path)]
               (when (.isFile f)
                 (let [disk  (slurp f)
                       store (:content (store/file-content st* path))]
                   (when (and (string? store) (not= disk store))
                     {:path path :severity :advisory
                      :store-bytes (count store) :worktree-bytes (count disk)
                      :teach (str path " differs between the store manifest and the"
                                  " working tree — reconcile deliberately: file_put"
                                  " the working-tree copy if a human edited it, or"
                                  " project/pull if the store's copy is the newer")})))))
           (sort (keys (:files st*)))))))

(defn key-not-returned-check
  "Done-advisory: a changed form reads a key its callee never returns —
   `(:k local)` where `local` is let-bound to a call whose SOUND
   `shape/return-keys` (present only when the return shape is statically
   bounded) exclude `:k`. A read that can never be non-nil is a vacuous
   assertion: green proves nothing (assertions-that-cannot-fail). Precise by
   construction — fires ONLY when the callee's return shape is fully known (a
   map/assoc/cond-> result), so there is nothing to discharge but the bug.
   Callees resolve through THE reference graph, by name, unambiguous only."
  [_session st* changed]
  (let [by-from (group-by (juxt :from-ns :from-var) (refs/refs st*))
        rk      (memoize (fn [ns nm] (shape/return-keys (store/named-sexpr st* ns nm))))]
    (vec (for [fid changed
               :let [e (store/form-by-id st* fid)]
               :when (and e (:name e))
               :let [ns-sym   (store/ns-of-form-id st* fid)
                     caller   (try (n/sexpr (:node e)) (catch Exception _ nil))
                     ers      (get by-from [ns-sym (:name e)])
                     by-name  (into {} (for [[nm grp] (group-by :to-name ers)
                                             :when (and nm (apply = (map (juxt :to-ns :to-name) grp)))]
                                         [(name nm) (first grp)]))
                     resolver (fn [head] (when-let [t (get by-name (name head))]
                                           (rk (:to-ns t) (:to-name t))))]
               :when caller
               f (shape/key-not-returned caller resolver)]
           {:form  (symbol (str ns-sym) (str (:name e)))
            :teach (str "(" (:key f) " " (:local f) ") — " (:callee f)
                        " never returns " (:key f) "; its result is "
                        (:returns f) ", so the assertion is vacuous")}))))

(defn tier-governance-check
  "Namespaces this EPISODE MOVED that their new governing tier refuses.

  A purity tier binds a PAIR — a declaration and the population it governs —
  and that pair can change from either side. `module_purity` verifies the
  forms it is about to govern, so the DECLARATION side is covered. Nothing
  covered the POPULATION side: a rename or a relocation folds existing code
  under a different prefix, it inherits that prefix's tier, and no write ever
  touches those forms, so the functional-core gate — which fires on write over
  the forms a write touches — never sees them.

  Measured in anger: folding `slopp.mine` under `slopp.store` (declared
  `:pure`) made it `:pure`, along with `slopp.store.db`, the SQLite layer.
  `full_check` reported GREEN with both violations sitting in the store. It
  surfaced weeks later when a docstring typo-fix happened to touch one of the
  forms.

  Scoped to what MOVED rather than the whole store: a whole-store purity sweep
  is a per-namespace analyze pass and belongs in `full_check`, and the only
  way a namespace can newly violate a tier WITHOUT a write is by moving under
  a different one. Error-grade — a tier its code does not satisfy is a
  declaration that lies, and every reader downstream is relying on it: the
  tests you decide not to isolate, the reviewer trusting the core/shell
  split."
  [_session store _changed]
  (let [ds     (store/deltas store)
        since  (->> ds (keep-indexed #(when (= :done (:op %2)) %1)) last)
        recent (if since (drop (inc since) ds) ds)
        moved  (into #{}
                     (mapcat (fn [d]
                               (case (:op d)
                                 :rename-ns  [(:new d)]
                                 :move-forms (keys (:sources d))
                                 nil)))
                     recent)]
    (vec (for [n (sort (filter #(and (symbol? %)
                                     (contains? (:namespaces store) %)
                                     (not (str/ends-with? (str %) "-test")))
                               moved))
               :let [t (tiers/tier-for store n)]
               :when (not= :external t)
               :let [r (tiers/tier-report store n)]
               :when (> (get tiers/tier-order (:supports r) 2)
                        (get tiers/tier-order t 2))]
           {:ns n :tier t :supports (:supports r) :blocking (:blocking r)
            :why (str n " moved under a :" (name t) " prefix this episode and its"
                      " forms only support :" (name (:supports r))
                      ". A tier is inherited by prefix, so the move — not any"
                      " write — is what put this code under a rule it does not"
                      " satisfy, and nothing would have re-checked it until"
                      " someone happened to edit one of these forms. Declare"
                      " this namespace's own tier, or move the effects out.")}))))

(defn namespace-purpose-check
  "Done-advisory: a namespace the episode touched states no PURPOSE.

  Namespace-grained where its siblings are form-grained, which is the point —
  a namespace's INVENTORY is derived and shown everywhere (`query_project`,
  the module surface, the outline), so the docstring is the only home for
  what no tool can derive: why it exists, what to expect inside, how it
  relates to its neighbours.

  Scoped to the episode's namespaces so it nags where you are working;
  `review_scan`'s `:purpose` answers the same question for the whole store."
  [_session st* changed]
  (vec (sort-by :ns
                (keep #(edit.modules/namespace-purpose-warning st* %)
                      (distinct (keep #(store/ns-of-form-id st* %) changed))))))

(defn assertions-never-red-check
  "Done-advisory: a changed `deftest` GAINED assertions and was never observed
   FAILING since the last done — so the new assertions have only ever been seen
   green, which proves nothing about them.

   Red-first is usually stated as \"test before code\". The load-bearing part is
   narrower: every assertion must be watched fail at least once. Adding an `is`
   to an already-passing test skips that silently, and it is how both instances
   in `assertions-that-cannot-fail` got in — including one written two hours
   after its author put \"measure, don't assume\" into the shipped skill. A rule
   that relies on remembering is not a rule.

   Red is read from the `:verify` deltas since the baseline, which name the
   failing test: if it bounced at any point while you were working on it, its
   assertions were exercised and there is nothing to say. New tests are skipped
   — they have no baseline, and red-first stubs already make a genuinely new
   spec go red.

   Advisory, and it stays advisory: only the author knows whether an added
   assertion was watched fail in a way slopp did not record.

   **Measured over the store's whole lifetime before shipping** (D-rule-
   grounding), at WRITE grain: 104 assertion-additions, of which 82 were never
   observed red between the two versions and 22 were. So the shape is common —
   about four in five — which is the argument for the rule rather than against
   it, since every one of those 82 is an assertion nobody watched fail.

   Write grain OVERSTATES what this reports: the rule works at EPISODE grain
   against the last done, so an addition followed later in the same episode by
   a red collapses to \"watched\". Measured on the episode that introduced this
   rule, it fired zero times — new tests are skipped, and new tests are most of
   what a working episode adds.

   **Red is read from two journal citizens, and the second one closed a hole
   that made this unclearable for half the store.** A `:verify` delta is a
   claim a WRITE makes; an `:observe` delta (`store/record-observation`) is the
   narrower *these tests ran and this happened*, appended by the external tier.
   Before it, `traced-run` dropped `^:external` tests by design, the external
   tier appended nothing, and its failure list was `:failing` carrying the BARE
   name clojure.test prints — three gaps stacked, so the remedy this advisory
   TEACHES was performable and unrecordable for every one of the 512
   `^:external` deftests here. An advisory nobody can discharge teaches agents
   to ignore advisories.

   **The remaining false-positive path, stated because it is real:** a bare
   in-image `test_run` against an already-broken subject records a `:verify`
   only through `api/test-run!`, and a red observed some other way is not in
   the journal at all. Narrow — the ordinary way to break something is to write
   it — and it is why this asks rather than refuses."
  [_session st* changed]
  (let [ds       (store/deltas st*)
        baseline (->> ds (filter #(= :done (:op %))) last :id)]
    (if-not baseline
      []
      (let [old-srcs (store/sources-at st* baseline)
            after    (->> ds (drop-while #(not= baseline (:id %))) rest)
            ever-red (into #{} (for [d after
                                     f (get-in d [:result :failures])
                                     :let [t (:test f)]
                                     :when t]
                                 (str t)))]
        (vec (keep
              (fn [fid]
                (let [e       (store/form-by-id st* fid)
                      ns-sym  (store/ns-of-form-id st* fid)
                      old-src (get old-srcs fid)]
                  (when (and e ns-sym old-src (:name e))
                    (let [qsym (symbol (str ns-sym) (str (:name e)))
                          old  (try (n/sexpr (p/parse-string old-src)) (catch Exception _ nil))
                          new  (try (n/sexpr (:node e)) (catch Exception _ nil))
                          n    (when (and old new) (shape/assertions-added old new))]
                      (when (and n (pos? n) (not (ever-red (str qsym))))
                        {:form  qsym
                         :teach (str n " assertion form(s) added to a test that never went"
                                     " red this episode — a green you did not watch fail"
                                     " proves nothing. Break the subject once and confirm"
                                     " the NEW assertions go red, or they are coverage"
                                     " theatre that reads as verification")})))))
              changed))))))

(defn stored-name-check
  "Done-advisory: a form's STORED `:name` disagrees with the name its own
   source defines.

   The store keeps both — `:name` on the element, the source in `:node` — and
   `store/form-symbol` derives one from the other at every write. When they
   drift apart the form becomes addressable by ID and not by name, and **every
   name-addressed surface loses it silently**: `rename_sweep` skips it and
   reports its own form count as though complete, `edit_subform {form \"x\"}`
   refuses with `no form named x` (which reads as the caller's mistake), and
   `:left-behind` prints `:form nil` for the one field a reader navigates by.

   **Why it survived long enough to need a rule.** The rename verbs are split:
   `ns-rename-changeset` and `qualified-mention-changeset` both produce
   `{form-id node}` and are applied by ID, so they rewrite these forms
   correctly, while the prose sweep addresses forms by name and does not. Half
   the machinery works, the half that does not reports success, and the
   difference is invisible from either result.

   Measured on slopp's own store the day this landed: 11 forms with no `:name`,
   6 of them correctly (a `defmethod` has a dispatch value rather than a name,
   `use-fixtures` defines nothing) and 5 where `form-symbol` on the stored node
   returns a name the element does not carry.

   **The cause is not yet known, and that is the argument FOR the check rather
   than against it.** `form-symbol` has one version, ingested with its
   metadata-wrapper branch already present; `apply-changeset` recomputes both
   `:name` and `:names` on every entry. Three plausible causes were measured and
   falsified. So this fires on the state rather than on a suspected writer,
   which is what a check should do when the invariant is clear and the breach is
   not: whatever produces the next one, this names it in the act.

   Compares against `form-symbol` and nothing else, deliberately — a second
   derivation of \"what is this form called\" is how the last one of these
   started."
  [_session st* changed]
  (vec (for [fid changed
             :let [e (store/form-by-id st* fid)]
             :when e
             :let [actual (store/form-symbol (:node e))]
             :when (not= (:name e) actual)]
         {:form (symbol (str (store/ns-of-form-id st* fid))
                        (str (or (:name e) actual fid)))
          :teach (str "this form's stored :name is " (pr-str (:name e))
                      " but its source defines " (pr-str actual)
                      ". Every name-addressed surface — rename_sweep,"
                      " edit_subform {form}, :left-behind's :form — reaches"
                      " forms by the stored name, so a form the store cannot"
                      " name is one they all skip WITHOUT SAYING SO, while"
                      " id-addressed passes keep working. Rewrite the form"
                      " (edit_replace_form, or edit_subform addressing it by"
                      " its id " (pr-str fid) ") to restore agreement")})))

(defn marker-why-check
  "Done-advisory: a changed form carries an escape marker as a BARE keyword,
   so the dial says that a rule was waived and nothing about why.

   `(defn ^:unused-ok spare …)` — ok for what reason? `(defn ^:entry-point
   call-main! …)` — invoked by WHAT? The map form answers in place:
   `^{:unused-ok \"library surface for external consumers\"}`,
   `^{:entry-point \"boot --call dispatch, quoted in parse-args\"}`. The why
   rides the marker exactly as `:prompt` rides every delta, and the dial
   becomes provenance instead of a mute flag. `^{:covers \"ns/name — why\"}`
   and `^{:teach \"…\"}` already work this way and are the worked examples.

   Every read site tests the marker for TRUTH, so a string discharges exactly
   as `true` does and the two forms are interchangeable — pinned by
   `api.modules-test/an-escape-marker-carrying-its-WHY-discharges-the-same`,
   because asking for the richer form would be actively harmful if it silently
   stopped discharging.

   **The dials come from `markers/asking`**, not from a list here. This check
   shipped with its own inline copy on the same day the missing registry was
   diagnosed, which made it the FIFTH place hardcoding a marker list — the
   exact instance-over-class failure the registry exists to end.

   **Measured before shipping** (D-rule-grounding): 22 marker instances in the
   store, 21 bare, 19 of those in production code. A small, finite, wholly
   dischargeable population — and it fires only when you TOUCH such a form, so
   it cannot become standing noise."
  [_session st* changed]
  (vec (for [fid changed
             :let [e (store/form-by-id st* fid)]
             :when (and e (:name e))
             :let [s (store/form-sexpr (:node e))
                   m (when (and s (symbol? (second s))) (meta (second s)))]
             {:keys [marker asks]} (markers/asking)
             :when (true? (get m marker))]
         {:form  (symbol (str (store/ns-of-form-id st* fid)) (str (:name e)))
          :teach (str "^:" (name marker) " says a rule was waived and nothing"
                      " about why — " asks " Say it in place: ^{:" (name marker)
                      " \"…\"}. A string discharges exactly as the bare keyword"
                      " does, and the dial becomes provenance instead of a mute"
                      " flag")})))

(defn ambiguous-index-check
  "Done-advisory: a changed form reads INDEX 2 of a store form, where the
   meaning of that position depends on an optional earlier element.

   This codebase's worst bug: `ambient-def?` read `(nth s 2)` for a `def`'s
   VALUE — which is where a DOCSTRING sits — so it never once fired on a
   documented global, i.e. on every global anyone had bothered to justify, and
   looked healthy for its entire life while nine accumulated. The same class
   recurred in `contract-drift` a day later, and again in `slopp.http-api.reads/
   form-doc`, which showed a `def`'s value as its docstring on the reviewer
   page until this rule found it.

   Wrong-index reads are silent by construction — nil is falsy, so the check
   just does not fire — which is why discipline has never been enough here.

   See `shape/ambiguous-index-reads` for the predicate and the measurement.
   The two store ACCESSORS are exempt: they index 2 because they are the code
   that knows when it is a docstring and when it is a value, and flagging them
   would be flagging the fix."
  [_session st* changed]
  (let [exempt #{'slopp.store/form-docstring 'slopp.store/def-init}]
    (vec (for [fid changed
               :let [e (store/form-by-id st* fid)]
               :when (and e (:name e))
               :let [ns-sym (store/ns-of-form-id st* fid)
                     qsym   (symbol (str ns-sym) (str (:name e)))
                     s      (store/form-sexpr (:node e))
                     hits   (when s (shape/ambiguous-index-reads s (contains? exempt qsym)))]
               :when (seq hits)]
           {:form  qsym
            :teach (str (str/join ", " hits) " reads index 2 of a store form,"
                        " where a DOCSTRING and a def's VALUE share the position."
                        " Use store/form-docstring, store/def-init or"
                        " store/form-symbol — the accessors exist because a wrong"
                        " index yields nil rather than throwing, so the rule that"
                        " reads it simply stops firing and looks healthy")}))))

(defn- in-scope
  "`findings` filtered to those an advisory declaring `applies-to` should
  report — the enforcement half of standing structural ask #5.

  A finding names the form it is about (`:form`, qualified) or the namespace
  (`:ns`), so the runner can answer \"is this a test namespace?\" once instead
  of each check re-deriving it. Before this, three advisories gave three
  different answers to the same question and none of them said so: one was
  about tests ONLY, one exempted them, one fired in them by accident.

  A finding naming NEITHER a form nor a namespace is kept whatever the
  declaration says — it is about the store as a whole, and silently dropping
  it would be the worse error."
  [applies-to findings]
  (if (= :both applies-to)
    findings
    (let [test-ns? (fn [f]
                     (when-let [s (or (some-> (:form f) namespace)
                                      (some-> (:ns f) str))]
                       (str/ends-with? s "-test")))]
      (vec (filter (fn [f]
                     (case (test-ns? f)
                       nil  true                       ; store-wide: always kept
                       true (= :tests applies-to)
                       false (= :production applies-to)))
                   findings)))))

(defn direct-http-check
  "Done-advisory: changed forms that reach the network THEMSELVES instead of
   going through the HTTP client port.

   The rule is the reaches/is distinction made enforceable. A form that builds
   a `java.net.http.HttpClient`, or `slurp`s an `http(s)://` literal, IS the
   reaching — and raw reaching belongs in a declared ADAPTER, not scattered
   through callers. Everything else goes through `slopp.web.client/request`,
   which arrives as a parameter and therefore has a fake, a contract suite, and
   two adapters that must agree.

   `^{:adapter \"http — why\"}` on the NAME discharges it, and the value's first
   word is the PORT it adapts. That scoping matters: this check ignores an
   `^{:adapter \"postgres — …\"}` entirely rather than calling it stale, so each
   port's rule polices only its own markers and a second port can be added
   without teaching this one about it.

   It polices itself: an `http` adapter marker on a form
   that makes no direct call is reported stale, so the dial cannot decay into
   decoration.

   **Tests are NOT exempt, and that is deliberate.** The obvious exemption is
   also the wrong one, because using the port from a test does not make the
   test fake — `request` over a real socket IS a real call, and a test is faked
   only when it passes a fake. So an exemption would buy nothing and would
   carve out precisely the place where this boilerplate breeds: seven copies of
   build-a-client-send-a-request had already accumulated in this store's own
   tests when the port was written.

   SCOPED TO HTTP on purpose. A gate may only demand a port that EXISTS, and
   slopp ships one for HTTP and none for the filesystem or subprocesses —
   turning this on for every external class would refuse every file read in the
   store. The rule widens as ports get built, which is the right pressure and
   the right order."
  [_session st* changed]
  (vec (keep
        (fn [fid]
          (when-let [e (store/form-by-id st* fid)]
            (let [s     (try (n/sexpr (:node e)) (catch Exception _ nil))
                  nodes (tree-seq coll? seq s)
                  syms  (filter symbol? nodes)
                  strs  (filter string? nodes)
                  ;; a SYMBOL, never a string: `index.derive/external-classes`
                  ;; holds "java.net.http.HttpClient" as data and must not fire
                  client? (boolean
                           (some (fn [sym]
                                   (when-let [n (namespace sym)]
                                     (or (= n "HttpClient")
                                         (str/ends-with? n ".HttpClient"))))
                                 syms))
                  slurped? (boolean
                            (and (some #(= 'slurp %) syms)
                                 (some #(re-find #"^https?://" %) strs)))
                  direct? (or client? slurped?)
                  marker  (when (and (seq? s) (symbol? (second s)))
                            (:adapter (meta (second s))))
                  mine?   (str/starts-with?
                           (str/lower-case (str/trim (str marker))) "http")
                  q       (symbol (str (store/ns-of-form-id st* fid))
                                  (str (or (:name e) (:id e))))]
              (cond
                (and direct? (not (and marker mine?)))
                {:form q :direct (if client? :http-client :slurp-url)
                 :teach (str q " reaches the network itself — "
                             (if client?
                               "it builds a java.net.http.HttpClient"
                               "it slurps an http(s):// url")
                             ". Call slopp.web.client/request instead, taking it"
                             " as a parameter so callers can pass"
                             " client/fake-requester; you inherit its contract"
                             " suite and its fake. If this form IS an adapter,"
                             " mark it ^{:adapter \"http — why\"}.")}

                (and marker mine? (not direct?))
                {:form q :stale-marker true
                 :teach (str q " carries an ^{:adapter \"http …\"} marker but"
                             " makes no direct call — remove the flag")}))))
        changed)))

(defn module-governance-check
  "Module rule violations this EPISODE'S RELOCATIONS created.

  The exact sibling of [[tier-governance-check]], one system over and for the
  same reason: a purity tier and a module rule are BOTH inherited from a
  namespace's NAME, both enforced by gates that fire on WRITE, and a
  relocation changes the name without writing the forms. `ns_rename` even
  rewrites its own callers — and a caller a rename rewrote never passes a
  gate, so the operation most likely to drift the architecture is the one
  operation the architecture's own check cannot see.

  Measured in anger, the same way its sibling was: regrouping four namespaces
  into `slopp.project` and `slopp.lab` left four package-private violations
  standing, and every `done` in that episode was green.

  Scoped to the episode's relocations from EITHER END of the edge, which is
  where this differs from the tier check. The namespace that MOVED is usually
  not the one violating: taking a target from two segments to three makes it
  package-private, and it is the unmoved CALLER that is suddenly reaching in.
  Selecting only what moved would report nothing at all.

  Error-grade, and not a judgement call: every finding here is one a write
  gate would have REFUSED outright. Anything softer would make the
  whole-episode check the more permissive of the two, which is backwards."
  [_session store _changed]
  (let [ds     (store/deltas store)
        since  (->> ds (keep-indexed #(when (= :done (:op %2)) %1)) last)
        recent (if since (drop (inc since) ds) ds)
        moved  (into #{}
                     (mapcat (fn [d]
                               (case (:op d)
                                 :rename-ns [(:new d)]
                                 ;; every changeset op that RELOCATES; the
                                 ;; other changeset ops (:replace, :rename,
                                 ;; :normalize) are ordinary writes and the
                                 ;; gates already saw them
                                 (:move-forms :extract-ns :module-extract)
                                 (keys (:sources d))
                                 nil)))
                     recent)]
    (when (seq moved)
      (vec (for [v (edit.modules/store-violations
                    store (edit.modules/module-usage-rows store))
                 :when (or (contains? moved (:from-ns v))
                           (contains? moved (:target-ns v)))]
             {:ns (:from-ns v) :from-var (:from-var v)
              :target-ns (:target-ns v) :rule (:rule v)
              :why (str (:error v) ". This appeared without any write: a module"
                        " rule is inherited from the NAME, and a relocation"
                        " this episode moved one end of this call. No gate"
                        " re-checks a caller a rename rewrote, so nothing"
                        " would have asked again until someone happened to"
                        " edit one of these forms.")})))))

(defn stale-pattern-check
  "Done-advisory: a REGEX literal in a changed form spells a name in this
   store's OWN namespace family that is neither a namespace nor a prefix of
   one — a search pattern that can no longer match what it was written to find.

   **A pattern is data, so no rename verb reaches it.** `ns_rename` rewrites
   requires, qualified references, quoted symbols and prose; `rename_sweep`
   matches text — and a regex escapes its dots, so even the text pass misses
   the spelling. The form goes on compiling and goes on passing while searching
   for a string that can no longer occur. Both outcomes are bad and one is
   worse: a presence assertion turns red, while an absence assertion —
   `(is (not (re-find #\"ns.that.moved\" src)))` — becomes permanently true.

   **The scope is the store's own ROOT SEGMENTS, and that is what makes it
   usable.** Measured on slopp's store the day this landed: 1001 regex
   literals, 110 carrying a dotted name. Reporting every one that does not
   resolve gives 119 findings of which 2 are real — fixtures name `mv.core`,
   libraries name `clojure.set`, config keys name `web.static`, assets name
   `logo.png`, and a pattern spanning a whitespace class yields `assertions.s`.
   Requiring the root segment to be one this store owns takes 119 to 3, with no
   exemption list, and all three were bugs.

   That restriction is the fixture rule read backwards: a fixture that names no
   real production code is exactly a fixture this cannot see. Its corollary is
   the one known false positive — a guard asserting a name is NEVER used, where
   that name deliberately does not exist, has no spelling that satisfies this.
   None exists in slopp today, which is why there is no escape yet.

   `:suggest` names the namespace whose LAST SEGMENT matches, when exactly one
   does — the derivation `stranded-aliases` uses, and it resolved all three.

   **Stated reach limit.** The walk is `tree-seq coll? seq` over the form's
   sexpr, and a node's METADATA is not one of its children — so a regex living
   in metadata is invisible here. Measured the day this shipped: zero regex
   literals in metadata store-wide, so this is a hole with nothing in it rather
   than a known miss. `rules.doctor/diagnose` hit the same gap for real and
   walks metadata; copy that shape if an instance ever turns up."
  [_session st* changed]
  (let [nses  (set (map str (keys (:namespaces st*))))
        root  (fn [m] (first (str/split m #"\.")))
        tail  (fn [m] (last (str/split m #"\.")))
        roots (set (map root nses))
        cand  #"[a-z][a-z0-9-]*(?:\.[a-z][a-z0-9-]*)+"
        live? (fn [m] (or (contains? nses m)
                          (some #(str/starts-with? % (str m ".")) nses)))
        went  (fn [m] (let [hits (filter #(= (tail m) (tail %)) nses)]
                        (when (= 1 (count hits)) (symbol (first hits)))))]
    (vec (for [fid changed
               :let [e (store/form-by-id st* fid)]
               :when e
               :let [sx   (store/form-sexpr (:node e))
                     hits (distinct
                           (for [n (when sx (tree-seq coll? seq sx))
                                 :when (and (seq? n) (= 're-pattern (first n))
                                            (string? (second n)))
                                 m (re-seq cand (str/replace (second n) "\\" ""))
                                 :when (and (contains? roots (root m)) (not (live? m)))]
                             m))]
               :when (seq hits)]
           (let [sugg (went (first hits))]
             (cond-> {:form  (symbol (str (store/ns-of-form-id st* fid))
                                     (str (or (:name e) fid)))
                      :teach (str (str/join ", " hits) " is spelled inside a regex"
                                  " here, and this store owns the "
                                  (pr-str (root (first hits))) " family without"
                                  " having that namespace — so the pattern cannot"
                                  " match what it was written to find. A pattern is"
                                  " DATA: no rename verb rewrites it, and the prose"
                                  " sweep misses the escaped dots, so an absence"
                                  " assertion built on it is permanently true"
                                  (when sugg
                                    (str ". " sugg " is the only namespace with the"
                                         " same last segment")))}
               sugg (assoc :suggest sugg)))))))

(def done-advisories
  "The done-time advisory registry (D9 rule-registry — the done-grain sibling of
   `edit.modules/per-form-write-gates`): an ordered list of {:key :severity
   :applies-to :sweep :check :fires-on} entries. `:check` is `(session store
   changed) -> findings-seq` (empty when clean); `:severity` is `:error` (its
   findings flip `test-status` red — a real failure) or `:advisory` (a heuristic
   that never does). A NEW done-time finding registers HERE, in ONE entry,
   instead of hand-wiring a binding, a cond-> clause, and a status term into
   `done!`. Checks are held as VARS so a hot-reload is picked up and the
   reference graph sees them — and a carried `#'var` is NOT a call, so the
   analyzer no longer taints this data def effectful.

   `:applies-to` is `:production`, `:tests` or `:both`, and `run-done-advisories!`
   ENFORCES it. Standing structural ask #5: tests are subject to different rules
   than production and it kept biting, because every check answered the question
   for itself and none of them said so — `assertions-never-red` is about tests
   ONLY, `namespace-purpose` covers both, `bare-throw` is a boundary-contract
   rule that has no business in a fixture. Three answers, arrived at three
   different ways. The write gates already declared this
   (`^{:rule/applies-to :production}`); severity lives here rather than on the
   var for advisories, and so does this.

   `:sweep` is the OTHER scope axis, and it exists because `done` is
   episode-scoped: a rule here sees only forms an episode CHANGED, so a
   violation that predates the rule is invisible to it forever. `sweep-store!`
   answers the whole-store question, and this declares which checks it may ask.
   `true` means running the check over every form is meaningful; a STRING says
   why it is not, and the string is REPORTED so a green sweep states the
   population it did not cover.

   The distinction is not fastidiousness. About a third of these compare
   against the last-done BASELINE or read the episode's DELTAS, and a
   whole-store run of one of those does not report clean — it reports NOTHING,
   in the same shape. `key-typos` is the sharpest: an ESTABLISHED key is one
   that >= 2 UNCHANGED forms use, so a sweep in which every form is changed
   establishes nothing and is vacuously green for as long as it exists.

   `:fires-on` is a source string the check MUST report a finding for, enforced
   by `rules-test/every-advisory-fires-on-its-own-fixture`. A rule that stops
   firing is otherwise indistinguishable from a clean codebase: `ambient-state`
   read a def's value at index 2 — where a DOCSTRING sits — and so never once
   fired on a documented global, looking healthy for its entire life while
   nine of them accumulated. A rule with no automatic fixture must say why in
   `:selftest-note` rather than silently omitting one."
  [{:key :schema-drift     :severity :error    :applies-to :production :check #'schema-drift-check!
    ;; a schema that lies about its implementation lies whether or not the
    ;; episode touched it, and the generative oracle does not care where the
    ;; candidates came from
    :sweep true
    :selftest-note "generative mg/check against a live impl — needs a booted image, covered by api.schema-test/drift-flags-a-lying-schema"}
   {:key :key-typos        :severity :advisory :applies-to :both :check #'key-typos-check
    :sweep (str "an ESTABLISHED key is one that >= 2 UNCHANGED forms use, so a"
                " sweep in which every form is changed establishes nothing and"
                " reports clean vacuously — the one shape worse than not"
                " running")
    ;; an ESTABLISHED key must be used by >= 2 unchanged forms before a
    ;; near-duplicate counts as a typo rather than a new coinage
    :fires-on (str "(ns rf.core)\n"
                   "(defn one [] {:rf/status 1})\n"
                   "(defn two [] {:rf/status 2})\n"
                   "(defn typo [] {:rf/staus 3})\n")}
   {:key :breaking-changes :severity :advisory :applies-to :production :check #'breaking-check
    :sweep (str "narrowing is measured against the last-done BASELINE, and every"
                " unchanged form equals its own baseline — a sweep can only ever"
                " report nothing")
    :selftest-note "compares against the last-done BASELINE, so a fixture needs two done-points — covered by api.breakage-test"}
   {:key :ambient-state :severity :advisory :applies-to :both :check #'ambient-state-check
    :sweep true
    ;; a global atom in a test is exactly as invisible to a slice as one in
    ;; production, and test fixtures are where they breed
    :fires-on "(ns rf.core)\n(def cache (atom {}))\n"}
   ;; an escape marker that says nothing about WHY it is there. The dial is
   ;; provenance the moment it carries a reason — ^{:covers "ns/name — why"}
   ;; already works this way and is the worked example.
   {:key :marker-why :severity :advisory :applies-to :both :check #'marker-why-check
    :sweep true
    :fires-on "(ns rf.core)\n(defn ^:unused-ok spare \"S.\" [x] x)\n"}
;; the stored :name and the source's own name, which must agree. NOT
   ;; :fires-on-able: ingesting source recomputes :name, so no fixture text
   ;; can express the disagreement — which is exactly why nothing caught it.
   {:key :stored-name :severity :error :applies-to :both :check #'stored-name-check
    :sweep true
    :selftest-note (str "the disagreement cannot be built from source — every"
                        " ingest recomputes :name from the node, so a :fires-on"
                        " fixture for this rule would be one that cannot fail."
                        " Covered by rules-test/a-stored-name-that-disagrees-"
                        "with-its-source-is-reported, which corrupts an element"
                        " directly and carries a healthy-store control")}
   ;; a search PATTERN is data: no rename verb rewrites it, and the prose sweep
   ;; misses the escaped dots. Scoped to names whose ROOT SEGMENT this store
   ;; owns — unrestricted that is 119 findings of which 2 are real, restricted
   ;; it is 3 of which 3 are.
   {:key :stale-pattern :severity :error :applies-to :both :check #'stale-pattern-check
    ;; the form that goes stale is never the form the rename CHANGED, so an
    ;; episode-scoped pass cannot reach it. This rule is the sweep or it is
    ;; nothing.
    :sweep true
    :fires-on "(ns rf.core)\n(defn scan [s] (re-find #\"rf.gone\" s))\n"}
   ;; the single biggest behavioural consequence available in one piece of
   ;; metadata, and nothing said it: declaring :web/spa turns every path under
   ;; the prefix from 404 into 200 and moves not-found into the client.
   {:key :web-spa-consequences :severity :advisory :applies-to :production :check #'rules.web/web-spa-consequences-check
    :sweep (str "states a consequence ONCE, for the episode that declared the"
                " prefix — there is nobody to tell about a declaration that"
                " predates the sweep")
    :selftest-note (str "fires only when the declaration is NEW vs the last-done"
                        " baseline, so a source-only fixture (which has no"
                        " baseline) cannot show the transition; covered by"
                        " api.web-test/declaring-a-spa-prefix-says-what-it-changed")}
   ;; Pattern 1's bug class, with the predicate that finally discriminates:
   ;; not "positional access" (4-5 false positives out of 5) but indexing a
   ;; position whose MEANING depends on an optional earlier element, in code
   ;; that is demonstrably reading store forms.
   {:key :ambiguous-index :severity :advisory :applies-to :both :check #'ambiguous-index-check
    :sweep true
    ;; self-contained on purpose: an earlier fixture used `n/sexpr` and
    ;; `rf.core` requires no such alias, so it never loaded and the rule
    ;; "did not fire". The self-test caught it, which is the whole point of
    ;; having one — but a fixture must exercise the rule, not the loader.
    :fires-on (str "(ns rf.core)\n"
                   "(defn ^:unused-ok doc-of \"D.\" [e]\n"
                   "  (let [sx (:node e)] (nth sx 2 nil)))\n")}
   {:key :namespace-purpose :severity :advisory :applies-to :both :check #'namespace-purpose-check
    :sweep true
    ;; the fixture's ns form deliberately carries NO docstring — that is the
    ;; whole finding, and it is what 100 of slopp's own 177 namespaces looked
    ;; like when this rule was written
    :fires-on "(ns rf.core)\n(defn f [] 1)\n"}
   {:key :key-not-returned :severity :advisory :applies-to :both :check #'key-not-returned-check
    :sweep true
    ;; explicitly BOTH: a vacuous assertion does its worst damage in a test,
    ;; because a test is the only code whose job is to fail
    :fires-on (str "(ns rf.core)\n"
                   "(defn producer [] {:a 1})\n"
                   "(defn consumer [] (let [r (producer)] (empty? (:b r))))\n")}
   ;; the GENERAL case behind key-not-returned, which catches only the one
   ;; vacuous shape. This asks the question that shape was an instance of:
   ;; were these assertions ever watched fail? Both instances in
   ;; `assertions-that-cannot-fail` were assertions ADDED to an already-green
   ;; test, which is the one path red-first does not cover by construction.
   {:key :assertions-never-red :severity :advisory :applies-to :tests :check #'assertions-never-red-check
    :sweep (str "reads the :verify/:observe deltas SINCE the last done to learn what went"
                " red — an unchanged test gained no assertions and has no"
                " episode to have gone red in")
    :selftest-note (str "needs a prior done to have a baseline AND :verify deltas"
                        " to read red from — a source-only fixture has neither;"
                        " covered by rules-test/"
                        "done-asks-about-assertions-that-were-never-watched-fail")}
   {:key :bare-throw       :severity :error :applies-to :both :check #'bare-throw-check
    :sweep true
    ;; RATCHETED 2026-07-31. It was :advisory/:production on the argument that
    ;; this is a boundary-contract rule and a test helper is nobody's API. That
    ;; argument was too narrow: a bare throw's cost is that it can only be
    ;; caught by TYPE, which forces a broad catch that swallows unrelated bugs
    ;; — and a test that swallows its own bugs is worse than production doing
    ;; it, not better. Widening cost nothing: the store had zero production
    ;; bare throws left and exactly one in a test, which now carries
    ;; ^{:bare-throw-ok …}. Ratchet while you are already clean.
    :fires-on "(ns rf.core)\n(defn boom [] (throw (Exception. \"x\")))\n"}
   ;; teaching that LIES: a string naming a var the store no longer has. Gates
   ;; can't see a var inside a string, so renames/moves leave the prose behind
   ;; and it ships as confident wrong guidance (d9077 shipped
   ;; slopp.index/analyze in two places after the form moved).
   {:key :stale-reference :severity :advisory :applies-to :both :check #'stale-reference-check
    :sweep true
    :fires-on (str "(ns rf.core)\n"
                   "(defn live [x] x)\n"
                   "(defn teach \"see rf.core/gone for details\" [x] x)\n")}
   ;; Raw network contact outside a declared adapter. `:both` is the whole
   ;; point — the obvious exemption for tests is the WRONG one, because calling
   ;; the port from a test still makes a real call, so exempting them would buy
   ;; nothing and would carve out exactly the place where seven copies of
   ;; build-a-client-send-a-request had already bred.
   {:key :direct-http :severity :error :applies-to :both :check #'direct-http-check
    ;; the rule that made the sweep exist: slopp-ui carried two of these
    ;; through a green full_check because both forms predate the rule and no
    ;; episode has touched them since (friction #27)
    :sweep true
    :fires-on (str "(ns rf.core)\n"
                   "(defn fetch [u]\n"
                   "  (.send (java.net.http.HttpClient/newHttpClient) u nil))\n")}
   ;; the one entry that is a QUESTION, not a verdict — and therefore the one
   ;; that is legitimately :advisory. The system cannot know whether an effect
   ;; belonged in the namespace that was just widened; only the agent who did
   ;; it can. It fires ONLY for the episode that declared the tier, so it asks
   ;; once and cannot become a standing warning to scroll past.
   {:key :shell-widening  :severity :advisory :applies-to :production :check #'shell-widening-check
    :sweep (str "fires on a :module-tier DELTA, and it is a QUESTION for the"
                " agent who widened the shell — a standing declaration has"
                " nobody left to ask")
    :selftest-note "fires on a :module-tier DELTA, not on source — a fixture would need a tier declaration, covered by rules-test/done-asks-about-a-newly-widened-shell"}
   ;; the OTHER half of the same pair. shell-widening asks when the
   ;; DECLARATION moves; this fires when the POPULATION does — a rename or a
   ;; relocation folds existing code under a tier no write will ever re-check.
      {:key :tier-governance :severity :error :applies-to :production :check #'tier-governance-check
    :sweep (str "fires on a :rename-ns/:move-forms DELTA; the standing"
                " whole-store question it protects is tier LAYERING, which"
                " full_check already folds separately")
    :selftest-note (str "fires on a :rename-ns/:move-forms DELTA plus an inherited"
                        " tier — a source-only fixture can carry neither; covered"
                        " by rules-test/"
                        "a-namespace-that-MOVES-under-a-stricter-tier-is-caught-at-done")}
   ;; the MODULE system's copy of that same pair, for the same reason: a
   ;; relocation is the one path around every write gate. It differs in which
   ;; end it watches — going two segments to three makes the TARGET
   ;; package-private, so the namespace that violates is the CALLER, which did
   ;; not move.
   {:key :module-governance :severity :error :applies-to :both :check #'module-governance-check
    :sweep (str "fires on a :rename-ns/:move-forms DELTA; the standing"
                " whole-store question it protects is modules/module-debt,"
                " which full_check already folds separately (friction #19)")
    :selftest-note (str "fires on a :rename-ns/:move-forms DELTA plus a module"
                        " manifest — a source-only fixture carries neither;"
                        " covered by modules-test/"
                        "a-rename-that-strands-a-caller-is-caught-at-done")}
   ;; the only two copies of one fact this system keeps: a tracked manifest file
   ;; and the real file the human branch carries at the same path. Nothing
   ;; compared them until build.clj drifted far enough to reintroduce a fixed
   ;; jar-corruption bug for anyone building from a published checkout.
   {:key :tracked-file-drift :severity :advisory :applies-to :production :check #'tracked-file-drift-check!
    ;; already ignores `changed` — it walks the whole manifest either way, so
    ;; the sweep asks it the same question done does
    :sweep true
    :selftest-note (str "compares the manifest against the WORKING TREE, which a"
                        " source-only fixture has no copy of — covered by"
                        " rules-test/tracked-file-drift-reports-a-second-copy-that-moved")}
   ;; a publicly-writable endpoint should be a decision, not an omission —
   ;; the question grade, like shell-widening: only the author knows
   {:key :web-public-mutation :severity :advisory :applies-to :production :check #'rules.web/web-public-mutation-check
    :sweep true
    :selftest-note "gated on the store's web.enabled capability, which a source-only fixture cannot carry — covered by rules-test/public-mutation-asks-at-done"}
   {:key :web-dangling-route-refs :severity :error :applies-to :production :check #'rules.web/web-dangling-route-refs-check
    ;; already store-wide by construction — deleting a route dangles an
    ;; UNCHANGED form's link, which is this same friction one rule over
    :sweep true
    :selftest-note "gated on the store's web.enabled capability, which a source-only fixture cannot carry — covered by web-test/done-surfaces-dangling-route-refs"}
   {:key :web-page-reach :severity :advisory :applies-to :production :check #'rules.web/web-page-reach-check
    ;; the sweep is where this one EARNS its keep: an entry stranded by a
    ;; platform declaration on some OTHER namespace is invisible to every
    ;; episode-scoped done there will ever be, because no write to the entry
    ;; ever happens
    :sweep true
    :selftest-note (str "needs BOTH the store's web.enabled capability and a"
                        " :module-platform declaration, neither of which a"
                        " source-only fixture can carry — covered by"
                        " rules.web-test/a-page-reaching-cljs-cannot-be-opened-"
                        "and-done-says-so, which controls on both setup steps")
    :teach "a ^:web/page entry reaches a :cljs namespace, so no JVM can open this app — the screen tool and every headless test fall back to a hand-built lookalike, which passes while the real screen is wrong. The write gate sees only the ENTRY's namespace; this is the reach, and it can break when a namespace the entry never mentions is declared :cljs. Move the view/derive code it reaches to :jvm or :cljc and pass the browser-shaped parts in"}
   {:key :web-stale-client :severity :advisory :applies-to :production :check #'rules.web/web-stale-client-check
    :sweep true
    :selftest-note (str "needs a recorded client/generated-sig config (a source-only"
                        " fixture cannot carry one) — covered by rules-test/"
                        "web-stale-client-advisory-fires-on-endpoint-drift")}
   {:key :web-inline-schema-dup :severity :advisory :applies-to :production :check #'rules.web/web-inline-schema-dup-check
    :sweep true
    :fires-on (str "(ns ds.api)\n"
                   "(defn ^{:web/method :post :web/path \"/a\" :web/request [:map [:x :int]]"
                   " :web/response :map} a [r] r)\n"
                   "(defn ^{:web/method :post :web/path \"/b\" :web/request [:map [:x :int]]"
                   " :web/response :map} b [r] r)\n")}
   ;; the PRIOR question to the rule below: not whether a declared field says
   ;; what it means, but whether it is declared at all. A bare `:map` accepts
   ;; any map, so the generated client's response validation — the mechanism
   ;; built to catch exactly this — passes over it forever. slopp-ui ranked
   ;; this ABOVE the prose rule they had asked for the day before, which is the
   ;; kind of correction worth taking at face value.
   {:key :web-unconstrained-contract :severity :advisory :applies-to :production
    :check #'rules.web/web-unconstrained-contract-check
    :sweep true
    :fires-on (str "(ns dv.api)\n"
                   "(defn ^{:web/method :get :web/path \"/v\" :web/auth :public"
                   " :web/response [:map [:rows [:sequential :map]]]} v \"V.\" [r] r)\n")}
   ;; a published contract's fields say what SHAPE they are and never what they
   ;; MEAN. Asked for by slopp-ui, who measured it: every entry-property map in
   ;; slopp's own 9-endpoint document is `{:optional true}` and nothing else.
   ;; The sweep is the whole point — these endpoints are STABLE, so no episode
   ;; changes them and no episode-scoped pass can ever reach them.
   {:key :web-undocumented-contract :severity :advisory :applies-to :production
    :check #'rules.web/web-undocumented-contract-check
    :sweep true
    :fires-on (str "(ns du.api)\n"
                   "(defn ^{:web/method :get :web/path \"/t\" :web/auth :public"
                   " :web/response [:map [:total :int]]} t \"T.\" [r] r)\n")}
   ])

(defn- enabled?
  "True when this store has not dialed advisory `e` `:off`
  (`edit.modules/rule-severity` — the per-store override, else the registry
  default).

  Its own form because BOTH runners ask it, and a second copy of \"is this rule
  on\" is a second answer waiting to happen: the whole-store sweep reports which
  rules it ran, and that list has to be the same list the runner actually ran."
  [st* {:keys [key severity]}]
  (not= :off (gates/rule-severity st* key severity)))

(defn- run-checks
  "Run `entries`' `:check`s over `changed` and return `{:key findings}` for the
  ones that FIRED (non-empty), each filtered through its declared `:applies-to`
  (`in-scope`).

  The whole difference between the episode run and the whole-store sweep is
  WHICH entries and WHICH form ids; every other decision — scope filtering,
  dropping the clean ones, the shape of the result — is here once so the two
  cannot answer differently. Callers select their own entries (`enabled?`, plus
  `:sweep` for the sweep), because which rules ran is something each of them
  has to REPORT and not merely apply."
  [session st* changed entries]
  (into {}
        (keep (fn [{:keys [key check applies-to]}]
                (let [r (in-scope (or applies-to :both) (check session st* changed))]
                  (when (seq r) [key r]))))
        entries))

(defn run-done-advisories!
  "Run every registered done-advisory `:check` over the episode's changes —
   EXCEPT those a project dialed `:off` (`enabled?`) — and return `{:key
   findings}` for the checks that FIRED (non-empty result), ready to merge into
   `done!`'s findings. `!` — `schema-drift-check!` evals in the image.

   EPISODE-SCOPED, and that is a real limit rather than an implementation
   detail: `changed` is what this episode wrote, so a violation older than the
   rule is invisible here and will stay invisible, because no future episode
   changes it either. `sweep-store!` is the whole-store question; `full_check`
   asks it.

   Findings are filtered through each advisory's declared `:applies-to`
   (`in-scope`). Standing structural ask #5: tests are subject to different
   rules than production and it kept biting, because every check answered the
   question for itself — a `:pure` tier stranded its own test namespace, effect
   naming flagged three test helpers, and each was fixed ad hoc. The write
   gates already declared it (`^{:rule/applies-to :production}`); the
   done-advisories now do too, and the runner is what makes the declaration
   mean something rather than document an intention."
  [session st* changed]
  (run-checks session st* changed (filter #(enabled? st* %) done-advisories)))

(defn sweep-store!
  "Every SWEEPABLE done-advisory (`:sweep true`, not dialed `:off`) run over
  EVERY form in the store. `!` — same checks as `run-done-advisories!`, so it
  evals in the image and reads the working tree.

  Returns `{:forms n :swept [key …] :not-swept [{:rule :why} …] :findings {key
  findings}}`, where `:findings` has exactly the shape `done` reports and grades
  through the same `status-affecting-fired?`. One rule, one check, one bar — the
  sweep is a different POPULATION, never a different standard.

  **Why this exists.** `done` is episode-scoped: a `:grain :done` rule sees only
  forms the episode CHANGED, so a violation older than the rule is invisible to
  it — and stays invisible, because no later episode changes that form either.
  slopp-ui carried two `direct-http` violations through a green `full_check` for
  exactly this reason (friction #27). It is the sibling of `module-debt`, which
  was wired into `full_check` on the identical argument one layer down: the
  per-write gates see only code written THROUGH them.

  **`:not-swept` is the load-bearing half.** Roughly a third of the registry
  compares against the last-done BASELINE or reads the episode's DELTAS, and
  running one of those over every form does not report clean — it reports
  NOTHING, in the same shape. So each is named with WHY, and every advisory
  appears in exactly one of the two lists: a reader can tell a rule that passed
  from a rule that was never asked, which is the distinction a bare green
  destroys."
  [session st*]
  (let [swept (filterv #(and (true? (:sweep %)) (enabled? st* %)) done-advisories)
        kept  (set (map :key swept))
        ids   (into []
                    (comp (mapcat #(store/forms st* %)) (map :id))
                    (sort (keys (:namespaces st*))))]
    {:forms      (count ids)
     :swept      (mapv :key swept)
     :not-swept  (into []
                       (comp (remove #(kept (:key %)))
                             (map (fn [{:keys [key sweep] :as e}]
                                    {:rule key
                                     :why  (if (true? sweep)
                                             (str "dialed :off for this store —"
                                                  " config_file {path \"rules\" key \""
                                                  (name key) "\" value \"advisory\"}"
                                                  " asks it again")
                                             (str sweep))
                                     :severity (gates/rule-severity
                                                st* key (:severity e))})))
                       done-advisories)
     :findings   (run-checks session st* ids swept)}))

(defn status-affecting-fired?
  "True when an advisory whose EFFECTIVE severity is `:error` produced a
   STATUS-AFFECTING finding — a real failure that should flip `test-status` red.
   Effective severity is the per-store override (`edit.modules/rule-severity`)
   else the registry default, so a project can dial `key-typos` up to `:error` or
   `schema-drift` down to `:advisory`. `:advisory`/`:off` never flip status.

   A single finding may opt OUT with `:severity :info`: reported like any other,
   never status-flipping. Without it an `:error` rule was all-or-nothing, so a
   rule with both a hard failure and a genuinely informational observation had to
   drop the latter out of its findings to keep it from flipping — invisible at
   done, which is where it was worth seeing (`web-dangling-route-refs` and its
   dynamic refs). An ungraded finding — including a non-map one — flips, so the
   grade is opt-in and silence still means failure.

   Args: the store (for the config) and the `{:key findings}` map from
   `run-done-advisories!`."
  [store advisories]
  (boolean (some (fn [{:keys [key severity]}]
                   (and (= :error (gates/rule-severity store key severity))
                        (some #(not= :info (:severity %)) (get advisories key))))
                 done-advisories)))

(defn ^:export declared-severities
  "`{rule-key declared-severity}` across BOTH D9 grains — write gates from
   `edit.modules/write-gate-severities` (a gate's `:rule/severity` metadata) and
   done advisories from this namespace's registry `:severity`. The single source
   `catalog/rule-rows` reports from, so what `query_rules` shows and what
   refuses a write cannot disagree.

   Built here rather than in the catalog because the catalog is `:pure` and this
   namespace is `:external`: the data flows out to the leaf, the leaf never
   reaches into the shell."
  []
  (merge (gates/write-gate-severities)
         (into {} (map (juxt :key :severity)) done-advisories)))

(defn ^:export query-rules
  "The D9 enforcement catalog for THIS store: every rule with its grain, its
   EFFECTIVE per-store severity (the `rules` config override else the rule's
   DECLARED default), how to discharge it, and what it means. The one place to
   see what's enforced and at what grade — dial any rule with `config_file {path
   \"rules\" key <rule> value <severity>}` (`:off`/`:advisory`/`:error`/`:refuse`).

   Lives HERE, beside the registries, rather than in `api.query`: the declared
   default belongs to the code that ENFORCES each rule, and `api.query` is
   `:pure` while this namespace is `:external` — a query that reports what a
   shell owns cannot live in the core. `catalog/rule-rows` joins the prose to
   `declared-severities`, so what this reports and what refuses a write cannot
   disagree.

   WRITE-gate (`:form`) severity is one of `:off` (skip), `:advisory` (warn-but-
   proceed — the teaching rides the write result's `:advisories`), or `:refuse`
   (block); `:error` has no write-gate meaning and reports as `:refuse`.
   Done-grain rules keep the full `:off`/`:advisory`/`:error` range."
  [session]
  (let [st (:store @session)]
    (mapv (fn [{:keys [rule grain severity] :as r}]
            (let [eff (gates/rule-severity st rule severity)]
              (assoc r :severity
                     (if (= grain :form)
                       (case eff (:off :advisory) eff :refuse)
                       eff))))
          (catalog/rule-rows (declared-severities)))))
