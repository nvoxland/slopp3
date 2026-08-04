(ns slopp.rules-test
  "Cover for the done-advisories — and, as much, for the REGISTRY they live in.

  Two genres here, and the second is easy to overlook. Per-rule tests ask
  whether a check is correct. Registry tests ask whether the chassis still
  holds: does every rule have prose, does every rule still FIRE on its own
  fixture, does every rule declare whether it applies to tests. Those failures
  are silent and shared — a rule that stopped firing looks exactly like a clean
  codebase, which is how `ambient-state` reported one finding for its entire
  life while nine globals accumulated.

  So the registry tests are the ones that earn their keep by failing. Three
  new rules were refused by the coverage test in one session for shipping
  without prose, and the fires-on self-test caught a fixture that had been
  exercising the loader rather than the rule.

  Mostly `^:external`: a done-advisory's input is an episode, which needs a
  real session with a real baseline and real verification deltas behind it."
  (:require [clojure.test :refer [deftest testing is]]
            [slopp.rules :as rules] [slopp.store :as store] [slopp.ops :as api] [clojure.set :as set] [slopp.ops.external :as external] [slopp.rules.catalog :as catalog] [slopp.edit.web :as web] [slopp.edit.gates :as gates]))

(deftest done-advisory-registry-and-severity
  (testing "the registry carries every done-time advisory with a key, severity, and check"
    (is (set/subset? #{:schema-drift :key-typos :breaking-changes}
                     (set (map :key rules/done-advisories))))
    (is (every? (fn [r] (and (:severity r) (:check r))) rules/done-advisories))
    (testing "schema-drift is status-affecting (a lying schema is a real failure); the rest advise"
      (is (= :error (:severity (first (filter #(= :schema-drift (:key %)) rules/done-advisories)))))
      (is (= :advisory (:severity (first (filter #(= :key-typos (:key %)) rules/done-advisories)))))))
  (testing "status-affecting-fired? — only an :error-severity advisory with results flips it"
    (let [s0 (store/empty-store)]
      (is (true?  (rules/status-affecting-fired? s0 {:schema-drift [{:form 'a/b}]})))
      (is (false? (rules/status-affecting-fired? s0 {:key-typos [{:used :a/b}]})))
      (is (false? (rules/status-affecting-fired? s0 {:breaking-changes [{:form 'a/b}]})))
      (is (false? (rules/status-affecting-fired? s0 {})))
      (testing "a per-store severity override retunes it (dial up key-typos, down schema-drift)"
        (let [drift-off (first (store/record-config-put s0 "rules" :manifest "schema-drift" "advisory"))
              typos-err (first (store/record-config-put s0 "rules" :manifest "key-typos" "error"))]
          (is (false? (rules/status-affecting-fired? drift-off {:schema-drift [{:form 'a/b}]})))
          (is (true?  (rules/status-affecting-fired? typos-err {:key-typos [{:used :a/b}]}))))))))

(deftest ^:external per-store-severity-config-retunes-done
  (let [sess (external/open!)]
    (try
      (api/ingest! sess 'sv.core
                   (str "(ns sv.core)\n"
                        "(defn a [m] {:user/email (:x m)})\n"
                        "(defn b [m] {:user/email (:y m)})\n"
                        "(defn handle \"H.\" ([x] x) ([x y] (+ x y)))\n"))
      (external/done! sess :label "baseline")
      (api/config-file! sess "rules" :key "key-typos" :value "off"
                        :prompt "silence typos for this project")
      (api/config-file! sess "rules" :key "breaking-changes" :value "error"
                        :prompt "make a boundary break BLOCK here")
      (testing ":off silences an advisory end-to-end"
        (api/add-form! sess 'sv.core "(defn c [m] {:user/emial (:z m)})"
                       :prompt "typo — but the advisory is dialed off")
        (let [r (external/done! sess :label "typo-off")]
          (is (nil? (get-in r [:findings :key-typos])) (pr-str (:findings r)))))
      (testing ":error escalates an advisory to flip test-status red"
        (api/edit-replace! sess 'sv.core 'handle "(defn handle \"H.\" [x] x)"
                           :prompt "narrow away the 2-arity")
        (let [r (external/done! sess :label "narrow")]
          (is (seq (get-in r [:findings :breaking-changes])) (pr-str (:findings r)))
          (is (= :red (get-in r [:findings :test-status])) (pr-str (:findings r)))))
      (finally (api/close! sess)))))

(deftest catalog-covers-every-registered-rule
  (let [cataloged   (set (map :rule catalog/rule-catalog))
        write-gates (set (gates/write-gate-names))
        done-keys   (set (map :key rules/done-advisories))]
    (testing "every entry carries the declarative shape (severity joined in by rule-rows)"
      (is (every? (fn [r] (and (:rule r) (:grain r) (:severity r) (:escape r) (:teach r)))
                  (catalog/rule-rows (rules/declared-severities)))))
    (testing "every registered write gate is cataloged (drift guard)"
      (is (empty? (set/difference write-gates cataloged))
          (str "uncataloged write gates: " (set/difference write-gates cataloged))))
    (testing "every registered done advisory is cataloged (drift guard)"
      (is (empty? (set/difference done-keys cataloged))
          (str "uncataloged done advisories: " (set/difference done-keys cataloged))))))

(deftest ^:external query-rules-reports-write-gate-severity-honestly
  (let [sess (external/open!)]
    (try
      (testing "a write gate dialed :advisory now reports :advisory (warn-but-proceed)"
        (api/config-file! sess "rules" :key "schema-refusal" :value "advisory"
                          :prompt "soften a write gate to advisory")
        (let [sr (first (filter #(= :schema-refusal (:rule %)) (rules/query-rules sess)))]
          (is (= :form (:grain sr)) (pr-str sr))
          (is (= :advisory (:severity sr)) (pr-str sr))))
      (testing ":off on a write gate is honored and reported"
        (api/config-file! sess "rules" :key "schema-refusal" :value "off"
                          :prompt "turn it off")
        (let [sr (first (filter #(= :schema-refusal (:rule %)) (rules/query-rules sess)))]
          (is (= :off (:severity sr)) (pr-str sr))))
      (testing "a done advisory keeps its full severity range"
        (api/config-file! sess "rules" :key "key-typos" :value "error"
                          :prompt "escalate an advisory")
        (let [kt (first (filter #(= :key-typos (:rule %)) (rules/query-rules sess)))]
          (is (= :error (:severity kt)) (pr-str kt))))
      (finally (api/close! sess)))))

(deftest ambient-state-and-bare-throw-checks
  (let [src (str "(ns app.core)\n"
                 "(def cache (atom {}))\n"
                 "(def limit 5)\n"
                 "(defn handle [x] (throw (IllegalArgumentException. \"no\")))\n"
                 "(defn ok [x] (throw (ex-info \"no\" {})))\n")
        s   (store/ingest (store/empty-store) 'app.core src)
        all (mapv #(:id (store/form-named s 'app.core %)) '[cache limit handle ok])]
    (testing "ambient-state flags a global (def _ (atom …)), not a plain def"
      (is (= '[app.core/cache] (mapv :form (rules/ambient-state-check nil s all)))))
    (testing "bare-throw flags a boundary fn throwing a constructed non-ex-info exception"
      (is (= '[app.core/handle] (mapv :form (rules/bare-throw-check nil s all)))))))

(deftest ^:external done-surfaces-ambient-state-and-bare-throw
  (let [sess (external/open!)]
    (try
      (api/ingest! sess 'as.core "(ns as.core)\n\n(defn seed \"S.\" [x] x)\n")
      (external/done! sess :label "baseline")
      (api/add-form! sess 'as.core "(def cache (atom {}))" :prompt "a global atom")
      (api/add-form! sess 'as.core "(defn boom \"B.\" [x] (throw (IllegalArgumentException. \"e\")))"
                     :prompt "a bare throw at a boundary")
      (let [r (external/done! sess :label "check")]
        (testing "the ambient global atom is flagged"
          (is (= '[as.core/cache] (mapv :form (get-in r [:findings :ambient-state])))
              (pr-str (:findings r))))
        (testing "the boundary bare throw is flagged"
          (is (= '[as.core/boom] (mapv :form (get-in r [:findings :bare-throw])))
              (pr-str (:findings r)))))
      (finally (api/close! sess)))))

(deftest ^:external ambient-ok-marks-a-deliberate-global-and-polices-itself
  ;; ambient-state can only be BLOCKING if a legitimately-deliberate global has
  ;; a way to say so — a memo whose answer is immutable is the standing
  ;; counter-example. ^:ambient-ok is that escape, and it polices itself the
  ;; same way ^:unused-ok does: a marker on a def that is NOT ambient state is
  ;; itself a finding, so the flag can never drift into decoration.
  (let [sess (external/open!)]
    (try
      (api/ingest! sess 'am.core
                   (str "(ns am.core)\n\n"
                        "(def plain 41)\n\n"
                        "(def ^:ambient-ok memo (atom {}))\n\n"
                        "(def ^:ambient-ok not-ambient 42)\n\n"
                        "(def loose (atom {}))\n\n"
                        ;; a docstring sits at index 2, so an index-2 lookup for
                        ;; the value missed every DOCUMENTED global — i.e. every
                        ;; one someone had bothered to justify
                        "(def documented \"why\" (atom {}))\n"))
      (let [st   (:store @sess)
            fids (mapv :id (store/forms st 'am.core))
            hits (set (map :form (rules/ambient-state-check nil st fids)))]
        (testing "an unmarked global atom is still a finding"
          (is (contains? hits 'am.core/loose) (pr-str hits)))
        (testing "a DOCUMENTED global atom is a finding too"
          (is (contains? hits 'am.core/documented)
              (str "the docstring sits where the value lookup used to look: "
                   (pr-str hits))))
        (testing "^:ambient-ok discharges it"
          (is (not (contains? hits 'am.core/memo)) (pr-str hits)))
        (testing "a marker on something that isn't ambient state is a finding"
          (is (contains? hits 'am.core/not-ambient)
              (str "a stale flag must fail symmetrically: " (pr-str hits))))
        (testing "a plain def is untouched either way"
          (is (not (contains? hits 'am.core/plain)) (pr-str hits))))
      (finally (api/close! sess)))))

(deftest ^:external every-advisory-fires-on-its-own-fixture
  ;; A rule that has stopped firing is INDISTINGUISHABLE from a clean codebase.
  ;; ambient-state read a def's value at index 2 — where a docstring sits — and
  ;; so never once fired on a documented global: it reported a single finding
  ;; for its entire life and looked healthy while nine accumulated unseen.
  ;;
  ;; The registry now carries a positive fixture per rule and this test is the
  ;; guarantee. The point is that NOBODY has to remember to validate a
  ;; zero-findings sweep against a known-dirty input by hand — a broken check
  ;; turns the suite red instead of quietly reporting all-clear.
  (doseq [{:keys [key check fires-on selftest-note]} rules/done-advisories]
    (if fires-on
      (let [sess (external/open!)]
        (try
          (api/ingest! sess 'rf.core fires-on)
          (let [st   (:store @sess)
                ;; the fixture's LAST form is "the change"; anything before it is
                ;; established baseline. key-typos compares a new key against
                ;; keys already in the store, so it cannot fire on a fixture
                ;; where everything is new.
                fids [(:id (last (store/forms st 'rf.core)))]]
            (is (seq (check nil st fids))
                (str key " did not fire on its own :fires-on fixture — either"
                     " the check is broken or the fixture stopped exercising"
                     " it. Both are silent failures in production.")))
          (finally (api/close! sess))))
      (is (string? selftest-note)
          (str key " has neither a :fires-on fixture nor a :selftest-note"
               " explaining why it cannot have one")))))

(deftest ^:external done-asks-about-a-newly-widened-shell
  ;; Declaring a namespace :external makes the CORE smaller. That is sometimes
  ;; right and sometimes the path of least resistance, and the moment to ask is
  ;; while the reason is still in context. It fires only for the episode that
  ;; declared it, so it prompts once and cannot decay into standing noise.
  ;; Declarations here use the CANONICAL vocabulary — the advisory went dead
  ;; when the filter kept testing only the retired spellings.
  (let [sess (external/open!)]
    (try
      (api/ingest! sess 'sw.core
                   "(ns sw.core)\n(defn ^:unused-ok grab \"E.\" [p] (slurp p))\n")
      (external/done! sess :label "baseline")
      (testing "declaring a namespace :external raises the question at done"
        (api/module-tier! sess "sw.core" :external :prompt "needs to read a file")
        (let [f (get-in (external/done! sess :label "widened") [:findings :shell-widening])]
          (is (some #(= 'sw.core (:ns %)) f) (pr-str f))
          (is (re-find #"(?i)shell" (str (:why (first f)))) (pr-str f))))
      (testing "an immediate second done is a NO-OP — nothing happened, so there is
                no new episode to judge and no second boundary to record. It
                answers with the standing verdict and says so."
        ;; this block used to call done! here and assert the advisory was gone.
        ;; That was asserting the behaviour of a DUPLICATE done, which no longer
        ;; happens: done short-circuits when every delta since the last one is
        ;; bookkeeping. The property worth protecting — the prompt does not
        ;; decay into standing noise — is about the next REAL done, asserted
        ;; below.
        (let [again (external/done! sess :label "later")]
          (is (some? (:note again)) (pr-str again))
          (is (re-find #"(?i)nothing has happened" (:note again)) (pr-str again))))
      (testing "and it does NOT re-fire on the next done that judges something —
                one prompt, not nagging"
        (api/add-form! sess 'sw.core "(defn later-work [x] x)" :prompt "more work")
        (let [f (get-in (external/done! sess :label "later") [:findings :shell-widening])]
          (is (nil? f) (pr-str f))))
      (testing "and it is advisory: a question does not turn the done red"
        (api/module-tier! sess "sw.other" :external :prompt "another edge")
        (let [r (external/done! sess :label "still green")]
          (is (not= :red (get-in r [:findings :test-status])) (pr-str (:findings r)))))
      (finally (api/close! sess)))))

(deftest stale-reference-check-flags-prose-that-lies
  ;; The failure CLAUDE.md rule 4 exists to prevent, and which this session hit
  ;; four times: a docstring/teach-string names `a.b/c`, `c` moves or is
  ;; renamed, and the prose keeps pointing at the old address. Gates never see
  ;; it — a var inside a STRING is not a reference — so it ships, and an agent
  ;; that follows the guidance pays a failed call to learn it was wrong.
  ;;
  ;; Precision comes from requiring the NAMESPACE to exist in the store: a
  ;; string naming clojure.core/eval or some external lib can never fire,
  ;; because that namespace was never in the store to begin with.
  (let [st (-> (store/empty-store)
               (store/ingest 'sr.core
                             (str "(ns sr.core)\n"
                                  "(defn ^:unused-ok live \"L.\" [x] x)\n"
                                  "(defn ^:unused-ok teach\n"
                                  "  \"see sr.core/gone for the details\"\n"
                                  "  [x] x)\n")))
        fids (mapv :id (store/forms st 'sr.core))
        found (rules/stale-reference-check nil st fids)]
    (testing "a string naming a var that does not exist in an EXISTING ns fires"
      (is (seq found) (pr-str found))
      (is (some #(re-find #"sr\.core/gone" (str %)) found) (pr-str found)))
    (testing "a string naming a var that DOES exist is clean"
      (let [st2 (-> (store/empty-store)
                    (store/ingest 'sr.ok
                                  (str "(ns sr.ok)\n"
                                       "(defn ^:unused-ok live \"L.\" [x] x)\n"
                                       "(defn ^:unused-ok teach \"use sr.ok/live here\" [x] x)\n")))]
        (is (empty? (rules/stale-reference-check
                     nil st2 (mapv :id (store/forms st2 'sr.ok))))
            "a resolvable reference must not fire")))
    (testing "an EXTERNAL namespace never fires — it was never in the store"
      (let [st3 (-> (store/empty-store)
                    (store/ingest 'sr.ext
                                  (str "(ns sr.ext)\n"
                                       "(defn ^:unused-ok teach\n"
                                       "  \"clojure.core/eval and clojure.java.io/file are banned\"\n"
                                       "  [x] x)\n")))]
        (is (empty? (rules/stale-reference-check
                     nil st3 (mapv :id (store/forms st3 'sr.ext))))
            "external libs are not store namespaces — zero false positives")))
    (testing "a qualified KEYWORD is not a var reference and must not fire"
      ;; measured on slopp's own store: 4 of the first 10 hits were prose
      ;; naming :slopp.ops/dir-style option keys. A rule that cries wolf is a
      ;; rule nobody reads.
      (let [st4 (-> (store/empty-store)
                    (store/ingest 'sr.kw
                                  (str "(ns sr.kw)\n"
                                       "(defn ^:unused-ok teach\n"
                                       "  \"call sites pass {:sr.kw/dir d :sr.kw/agent-id a}\"\n"
                                       "  [x] x)\n")))]
        (is (empty? (rules/stale-reference-check
                     nil st4 (mapv :id (store/forms st4 'sr.kw))))
            "a qualified keyword in prose must not be read as a var")))))

(deftest stale-reference-suggests-where-it-went
  ;; A finding that only says "this doesn't resolve" hands the agent a hunt.
  ;; The two real causes each have a cheap answer: a MOVE (same name, new
  ;; namespace — exact lookup, no fuzzy matching needed, and the commonest
  ;; case) and a TYPO (one Damerau edit inside the named namespace).
  (testing "a moved form is located by name"
    (let [st (-> (store/empty-store)
                 (store/ingest 'sr.home "(ns sr.home)\n(defn ^:unused-ok gone \"G.\" [x] x)\n")
                 (store/ingest 'sr.old
                               (str "(ns sr.old)\n"
                                    "(defn ^:unused-ok teach \"see sr.old/gone\" [x] x)\n")))
          f  (first (rules/stale-reference-check
                     nil st (mapv :id (store/forms st 'sr.old))))]
      (is (= "sr.home/gone" (:suggest f)) (pr-str f))
      (is (re-find #"now lives at sr\.home/gone" (str (:teach f))) (pr-str f))))
  (testing "a typo gets a did-you-mean"
    (let [st (-> (store/empty-store)
                 (store/ingest 'sr.typo
                               (str "(ns sr.typo)\n"
                                    "(defn ^:unused-ok charge \"C.\" [x] x)\n"
                                    "(defn ^:unused-ok teach \"see sr.typo/charg\" [x] x)\n")))
          f  (first (rules/stale-reference-check
                     nil st (mapv :id (store/forms st 'sr.typo))))]
      (is (= "sr.typo/charge" (:suggest f)) (pr-str f))
      (is (re-find #"did you mean" (str (:teach f))) (pr-str f)))))

(deftest retired-vocabulary-catches-the-second-copy-not-the-marker
  ;; The tier rename migrated the gate and left the reporting arm holding its
  ;; own rank table — which NPE'd a live tool. The signal is a form ENUMERATING
  ;; the vocabulary with stale members, not the bare keyword: on this store a
  ;; lone :reads is the still-valid ^:reads MARKER in four production forms.
  (let [cfg (fn [st] (assoc-in st [:config "vocabulary" :values]
                               {"reads" "internal" "effects" "external"}))
        one (fn [src] (let [st (cfg (store/ingest (store/empty-store) 'rv.core src))]
                        (rules/retired-vocabulary-check
                         nil st (mapv :id (store/forms st 'rv.core)))))]
    (testing "a rank table mixing retired and current spellings fires"
      (let [f (first (one (str "(ns rv.core)\n"
                               "(defn ^:unused-ok rank [] {:pure 0 :reads 1 :effects 2})\n")))]
        (is (some? f) "the stale second copy must be caught")
        (is (= [:effects :reads] (:retired f)) (pr-str f))))
    (testing "a retired-ONLY filter fires too — this is how a rule went silently dead"
      ;; shell-widening matched (contains? #{:reads :effects} t) against tier
      ;; values that were all canonical by then, so it could never fire again
      ;; and looked exactly like a clean codebase.
      (let [f (first (one (str "(ns rv.core)\n"
                               "(defn ^:unused-ok widened? [t]\n"
                               "  (contains? #{:reads :effects} t))\n")))]
        (is (some? f) "a legacy-only match set is a second copy too")))
    (testing "a lone retired keyword does NOT fire — it is the marker, not the tier"
      (is (empty? (one (str "(ns rv.core)\n"
                            "(defn ^:unused-ok reads? [m] (:reads m))\n")))
          "bare :reads is the ^:reads marker in real code — 4 of 5 uses on this store"))
    (testing "the normalizer discharges with ^:legacy-ok"
      (is (empty? (one (str "(ns rv.core)\n"
                            "(defn ^:unused-ok ^:legacy-ok norm [t]\n"
                            "  ({:reads :internal :effects :external} t t))\n")))))
    (testing "and the marker polices itself when it stops earning its keep"
      (let [f (first (one (str "(ns rv.core)\n"
                               "(defn ^:unused-ok ^:legacy-ok clean [x] x)\n")))]
        (is (:stale-marker f) (pr-str f))))
    (testing "no declared vocabulary means no findings at all"
      (let [st (store/ingest (store/empty-store) 'rv.none
                             "(ns rv.none)\n(defn ^:unused-ok r [] {:pure 0 :reads 1})\n")]
        (is (empty? (rules/retired-vocabulary-check
                     nil st (mapv :id (store/forms st 'rv.none)))))))))

(deftest public-mutation-asks-at-done
  (let [src (str "(ns pm.api)\n\n"
                 "(defn ^{:web/method :post :web/path \"/signup\" :web/auth :public\n"
                 "        :web/effects [:user/insert]} signup \"S.\" [req] req)\n\n"
                 "(defn ^{:web/method :post :web/path \"/admin\" :web/auth :authenticated\n"
                 "        :web/effects [:user/insert]} admin \"A.\" [req] req)\n\n"
                 "(defn ^{:web/method :get :web/path \"/ping\" :web/auth :public} ping \"P.\" [req] req)\n")
        s0  (store/ingest (store/empty-store) 'pm.api src)
        on  (first (store/record-config-put s0 "capabilities" :manifest
                                            "web.enabled" "true"))
        ids (mapv :id (store/forms on 'pm.api))
        f   (fn [st] (rules/web-public-mutation-check nil st ids))]
    (testing "a public endpoint declaring effect kinds fires, naming the kinds"
      (let [r (f on)]
        (is (= 1 (count r)) (pr-str r))
        (is (= 'pm.api/signup (:form (first r))))
        (is (= [:user/insert] (:web/effects (first r))))))
    (testing "inert until web.enabled"
      (is (empty? (f s0))))))

(deftest client-stale-advisory-fires-on-endpoint-drift
  ;; the "explicit + advisory" regeneration decision's safety net: once a client
  ;; has been generated (a client/generated-sig is on record), a later contract
  ;; change makes the done-advisory nudge generate_client. It never nags a store
  ;; that never generated one.
  (let [mk (fn [resp] (-> (store/empty-store)
                          (store/ingest 'st.api
                                        (str "(ns st.api)\n\n"
                                             "(defn ^{:web/method :post :web/path \"/o\""
                                             " :web/request st.c/a :web/response " resp "} make [r] r)\n"))))
        old-sig (web/client-signature (mk "st.c/a"))]
    (testing "a recorded sig that no longer matches the current endpoints fires the advisory"
      (let [drifted (first (store/record-config-put (mk "st.c/b") "client" :manifest
                                                    "generated-sig" old-sig))]
        (is (seq (rules/client-stale-check nil drifted nil)))))
    (testing "a matching sig is quiet"
      (let [fresh-store (mk "st.c/b")
            fresh (first (store/record-config-put fresh-store "client" :manifest
                                                  "generated-sig" (web/client-signature fresh-store)))]
        (is (empty? (rules/client-stale-check nil fresh nil)))))
    (testing "never generated (no recorded sig) → never nags"
      (is (empty? (rules/client-stale-check nil (mk "st.c/a") nil))))))

(deftest inline-schema-dup-advisory-nudges-extraction
  ;; the DRY paved-road nudge (D-web-contracts part 2): 2+ endpoints declaring
  ;; the SAME structured inline schema should extract it to a named .cljc var.
  (testing "two endpoints sharing an identical inline schema fire the advisory"
    (let [st (-> (store/empty-store)
                 (store/ingest 'dup.api
                               (str "(ns dup.api)\n\n"
                                    "(defn ^{:web/method :post :web/path \"/a\""
                                    " :web/request [:map [:x :int]] :web/response :map} a [r] r)\n\n"
                                    "(defn ^{:web/method :post :web/path \"/b\""
                                    " :web/request [:map [:x :int]] :web/response :map} b [r] r)\n")))
          findings (rules/inline-schema-dup-check nil st nil)]
      (is (seq findings))
      (is (some #(re-find #"named .cljc" (:teach %)) findings))))
  (testing "distinct inline schemas do not fire; a shared bare keyword is too trivial to nag"
    (let [st (-> (store/empty-store)
                 (store/ingest 'dup2.api
                               (str "(ns dup2.api)\n\n"
                                    "(defn ^{:web/method :post :web/path \"/a\""
                                    " :web/request [:map [:x :int]] :web/response :map} a [r] r)\n\n"
                                    "(defn ^{:web/method :post :web/path \"/b\""
                                    " :web/request [:map [:y :string]] :web/response :map} b [r] r)\n")))]
      (is (empty? (rules/inline-schema-dup-check nil st nil))))))

(deftest catalog-severity-is-derived-not-restated
  (testing "no catalog row carries its own :severity — the registries own that fact"
    (is (empty? (filter :severity catalog/rule-catalog))
        (str "catalog rows restating :severity: "
             (mapv :rule (filter :severity catalog/rule-catalog)))))
  (let [declared (rules/declared-severities)
        rows     (catalog/rule-rows declared)]
    (testing "every cataloged rule still REPORTS a default, derived from its registry"
      (is (every? :severity rows)
          (str "rules with no declared default: " (mapv :rule (remove :severity rows)))))
    (testing "a write gate's declared default IS the one gate-check enforces"
      (let [gates (gates/write-gate-severities)]
        (is (= gates (select-keys declared (keys gates))))))
    (testing "a done advisory's declared default IS the one status-flipping reads"
      (is (every? (fn [{:keys [key severity]}] (= severity (get declared key)))
                  rules/done-advisories)))
    (testing "the catalog covers exactly what the registries declare"
      (is (= (set (map :rule catalog/rule-catalog)) (set (keys declared)))))))

(deftest a-finding-can-be-informational-inside-an-error-rule
  (let [s0 (store/empty-store)]
    (testing "an :info finding under an :error rule is REPORTED but does not flip status"
      (is (false? (rules/status-affecting-fired?
                   s0 {:schema-drift [{:form 'a/b :severity :info}]}))))
    (testing "an ungraded finding under an :error rule still flips (the default)"
      (is (true? (rules/status-affecting-fired?
                  s0 {:schema-drift [{:form 'a/b}]}))))
    (testing "a mixed list flips on the status-affecting member only"
      (is (true? (rules/status-affecting-fired?
                  s0 {:schema-drift [{:form 'a/b :severity :info}
                                     {:form 'c/d}]}))))
    (testing "a non-map finding has no grade to read and still flips"
      (is (true? (rules/status-affecting-fired? s0 {:schema-drift ["a/b drifted"]}))))
    (testing ":info does not resurrect a rule dialed below :error"
      (is (false? (rules/status-affecting-fired?
                   s0 {:key-typos [{:used :a/b :severity :info}]}))))))

(deftest dangling-route-advisory-reports-dynamic-refs-without-flipping
  (let [src (str "(ns shop.ui)\n\n"
                 "(defn ^{:web/method :get :web/path \"/todos\" :web/auth :public} todos-page \"T.\" [req]\n"
                 "  [:div [:a {:href \"/nowhere\"} \"bad\"]\n"
                 "        [:a {:href (:uri req)} \"dyn\"]])\n")
        s (store/ingest (store/empty-store) 'shop.ui src)
        s (first (store/record-config-put s "capabilities" :manifest "web.enabled" "true"))
        found (rules/dangling-route-refs-check nil s nil)
        by-sev (group-by :severity found)]
    (testing "the dangling ref is a status-affecting finding, as before"
      (is (= ["/nowhere"] (mapv :path (get by-sev nil)))))
    (testing "the dynamic ref RIDES ALONG as :info instead of being dropped"
      (is (= '[shop.ui/todos-page] (mapv :form (get by-sev :info)))))
    (testing "an :info-only result does not flip done red"
      (is (false? (rules/status-affecting-fired?
                   s {:web-dangling-route-refs (get by-sev :info)}))))
    (testing "the dangling ref still does"
      (is (true? (rules/status-affecting-fired?
                  s {:web-dangling-route-refs found}))))))

(deftest tracked-file-drift-reports-a-second-copy-that-moved
  (let [dir (str (System/getProperty "java.io.tmpdir")
                 "/slopp-drift-" (System/nanoTime))
        sess (atom {:dir dir})]
    (.mkdirs (java.io.File. dir))
    (try
      (let [s (first (store/record-file-put (store/empty-store) "build.clj" "(ns build)\n"))]
        (testing "no working-tree twin — nothing to compare, no finding"
          (is (empty? (rules/tracked-file-drift-check! sess s nil))))
        (spit (java.io.File. dir "build.clj") "(ns build)\n")
        (testing "an identical twin is not drift"
          (is (empty? (rules/tracked-file-drift-check! sess s nil))))
        (spit (java.io.File. dir "build.clj") "(ns build)\n;; edited on main\n")
        (testing "a twin that MOVED is reported, naming the path"
          (let [found (rules/tracked-file-drift-check! sess s nil)]
            (is (= ["build.clj"] (mapv :path found)))
            (is (= :advisory (:severity (first found)))
                "reconciling is a branch action — it reports, it does not flip done")))
        (testing "a blob is compared by content, not skipped for being big"
          (let [b (first (store/record-file-put s "public/x.bin" "AAA"
                                                :encoding "base64"))]
            (spit (java.io.File. dir "build.clj") "(ns build)\n")
            (is (empty? (rules/tracked-file-drift-check! sess b nil))
                "an untracked-on-disk blob has no twin to differ from"))))
      (finally
        (doseq [f (reverse (file-seq (java.io.File. dir)))] (.delete ^java.io.File f))))))

(deftest ^:external a-namespace-that-MOVES-under-a-stricter-tier-is-caught-at-done
  ;; `module_purity` verifies the forms it is about to govern — the
  ;; declaration side of the pair. Nothing checked the POPULATION side: a
  ;; rename that folds existing code under a stricter prefix makes it inherit
  ;; that tier by prefix, with no write to fire the functional-core gate on.
  ;; Measured in anger: `slopp.mine` and `slopp.store.db` (the SQLite layer)
  ;; both became `:pure` this way and `full_check` stayed GREEN, because the
  ;; gate fires on WRITE over the forms a write touches and no write had
  ;; touched them since. It surfaced weeks later on a docstring typo-fix.
  (let [sess (external/open!)]
    (try
      (api/ingest! sess 'tv.core "(ns tv.core)\n(defn pure-thing \"P.\" [] (inc 1))\n")
      (api/module-tier! sess "tv.core" :pure :prompt "the core is pure")
      (api/ingest! sess 'elsewhere.io
                   "(ns elsewhere.io)\n(defn read-it \"R.\" [] (slurp \"deps.edn\"))\n")
      (external/done! sess :label "baseline")
      (testing "the fold itself is allowed — it is a rename, not a write to the forms"
        (is (nil? (:error (api/ns-rename! sess 'elsewhere.io 'tv.core.io
                                          :prompt "fold it under the pure core")))))
      (testing "and done catches that the moved code cannot satisfy its new tier"
        (let [r (external/done! sess :label "after the fold")
              f (get-in r [:findings :tier-governance])]
          (is (seq f) (str "findings: " (pr-str (keys (:findings r)))))
          (is (= 'tv.core.io (:ns (first f))) (pr-str f))
          (is (= :pure (:tier (first f))))
          (is (= :external (:supports (first f))))
          (is (= :red (get-in r [:findings :test-status]))
              "a tier the code does not satisfy is a declaration that lies")))
      (finally (api/close! sess)))))

(deftest ^:external done-asks-about-assertions-that-were-never-watched-fail
  ;; The cheaper half of `assertions-that-cannot-fail`. Both instances in that
  ;; file were assertions ADDED to an already-green test — the one path
  ;; red-first does not cover by construction, since nothing about the new
  ;; assertion is ever observed failing.
  ;;
  ;; Needs a real session: the check reads a BASELINE (the last done) and reads
  ;; red out of the `:verify` deltas, and a source-only fixture has neither.
  (let [sess (external/open!)]
    (try
      (api/ingest! sess 'anr.core "(ns anr.core)\n(defn f \"F.\" [] 1)\n")
      (api/ingest! sess 'anr.core-test
                   (str "(ns anr.core-test\n"
                        "  (:require [clojure.test :refer [deftest is]]\n"
                        "            [anr.core :as c]))\n"
                        "(deftest t-f (is (= 1 (c/f))))\n"))
      (external/done! sess :label "baseline")
      (testing "an assertion added to a test that stayed green is asked about"
        (api/edit-replace! sess 'anr.core-test 't-f
                           "(deftest t-f (is (= 1 (c/f))) (is (some? (c/f))))"
                           :prompt "extend the test")
        (let [f (get-in (external/done! sess :label "extended") [:findings :assertions-never-red])]
          (is (some #(= 'anr.core-test/t-f (:form %)) f) (pr-str f))
          (is (re-find #"never went red" (str (:teach (first f)))) (pr-str f))))
      (testing "a test that actually BOUNCED is not asked about"
        ;; it went red, so its assertions were exercised — nothing to say
        (api/edit-replace! sess 'anr.core 'f "(defn f \"F.\" [] 2)"
                           :prompt "break it so the test goes red")
        (api/edit-replace! sess 'anr.core-test 't-f
                           "(deftest t-f (is (= 2 (c/f))) (is (some? (c/f))) (is (pos? (c/f))))"
                           :prompt "catch the test up and add another assertion")
        (let [f (get-in (external/done! sess :label "after a red") [:findings :assertions-never-red])]
          (is (nil? (some #(= 'anr.core-test/t-f (:form %)) f))
              (str "t-f was observed failing this episode: " (pr-str f)))))
      (testing "and it is advisory — a question does not turn the done red"
        (api/edit-replace! sess 'anr.core-test 't-f
                           "(deftest t-f (is (= 2 (c/f))) (is (some? (c/f))) (is (pos? (c/f))) (is (int? (c/f))))"
                           :prompt "one more assertion, nothing broken")
        (let [r (external/done! sess :label "still green")]
          (is (seq (get-in r [:findings :assertions-never-red])) "it fired")
          (is (not= :red (get-in r [:findings :test-status])) (pr-str (:findings r)))))
      (finally (api/close! sess)))))

(deftest every-done-advisory-declares-whether-it-applies-to-tests
  ;; Standing structural ask #5, from the modernization sweep: "Tests are
  ;; subject to different rules than production, and this keeps biting — a
  ;; :pure tier stranded its own test namespace; effect naming flagged three
  ;; test helpers. Each was fixed ad hoc. It should be a declared dimension of
  ;; the rule registry, instead of each rule rediscovering the question."
  ;;
  ;; The WRITE gates already declare it — `^{:rule/applies-to :production}` on
  ;; seven of them. The done-advisories never did, so each one answers by
  ;; accident: `assertions-never-red` is about tests ONLY, `namespace-purpose`
  ;; exempts them, `marker-why` fires in them incidentally and nobody decided
  ;; that. Three different answers, none written down.
  (doseq [{:keys [key applies-to]} rules/done-advisories]
    (is (contains? #{:production :tests :both} applies-to)
        (str key " does not declare :applies-to — :production, :tests or :both."
             " Undeclared means the next person reads the implementation to"
             " find out, which is how the same question got three different"
             " answers")))
  (testing "the declarations are not all the same, or the dimension is decorative"
    (let [vs (set (map :applies-to rules/done-advisories))]
      (is (< 1 (count vs))
          (str "every advisory claims " vs " — a dimension with one value is"
               " not carrying information")))))

(deftest every-done-advisory-declares-whether-a-whole-store-sweep-MEANS-anything
  ;; `done` is episode-scoped, so a `:grain :done` rule can only ever see forms
  ;; an episode CHANGED. A violation that predates the rule is therefore
  ;; permanently invisible — slopp-ui carried two `direct-http` violations
  ;; through a green `full_check` for exactly this reason (friction #27).
  ;;
  ;; The fix is a whole-store sweep, and the thing that makes it honest is this
  ;; declaration. Roughly a third of these checks compare against the last-done
  ;; BASELINE or read the episode's DELTAS, and running one of those over every
  ;; form does not report "clean" — it reports NOTHING, in the same shape.
  ;; `key-typos` is the sharpest: an established key is one that >= 2 UNCHANGED
  ;; forms use, so a sweep in which every form is changed establishes nothing
  ;; and is vacuously green forever.
  ;;
  ;; So `:sweep` is `true`, or a STRING saying why the sweep answers nothing —
  ;; and the string is what the sweep REPORTS, so a green whole-store verdict
  ;; states the population it did not cover instead of implying it covered all.
  (doseq [{:keys [key sweep]} rules/done-advisories]
    (is (or (true? sweep) (and (string? sweep) (seq sweep)))
        (str key " does not declare :sweep — `true` when running it over every"
             " form in the store is meaningful, or a string saying why it is"
             " not. Undeclared is the worst of the three, because the sweep"
             " then either drops it silently or runs it vacuously, and both"
             " read as a clean store")))
  (testing "both values are used, or the dimension is decorative"
    (let [vs (group-by #(true? (:sweep %)) rules/done-advisories)]
      (is (seq (get vs true))
          "no advisory sweeps — then the whole-store sweep checks nothing")
      (is (seq (get vs false))
          (str "every advisory claims to sweep — but a check that reads the"
               " episode's deltas cannot, and claiming otherwise is how a"
               " vacuous green gets mistaken for a clean store")))))

(deftest ^:external a-violation-older-than-the-episode-is-invisible-to-done-and-the-sweep-sees-it
  ;; Friction #27, filed by slopp-ui with the regression case attached: their
  ;; store holds exactly two `direct-http` violations, both predating the rule,
  ;; and `full_check` reported ZERO rule findings of any kind. Two violations,
  ;; three tools, no report.
  ;;
  ;; The cause is structural rather than a bug in any rule: `:grain :done`
  ;; means "runs over what the episode CHANGED", so a form nobody has touched
  ;; since the rule was written can never be judged by it. Every episode is
  ;; honestly clean and the store is not.
  ;;
  ;; This is `module-violations-standing-in-the-store-are-reported-by-full-check`
  ;; one layer over, and the same fix: the per-write and per-episode checks see
  ;; only what passes through them, so something has to ask again.
  (let [sess (external/open!)]
    (try
      (api/ingest! sess 'sw.core
                   (str "(ns sw.core \"Reaches the network by hand.\")\n\n"
                        "(defn ^{:unused-ok \"fixture: the standing violation\"} fetch\n"
                        "  \"Fetches.\"\n"
                        "  [u]\n"
                        "  (.send (java.net.http.HttpClient/newHttpClient) u nil))\n"))
      (testing "POSITIVE CONTROL — the episode that WROTE it is told"
        ;; without this half, everything below is equally consistent with
        ;; \"the rule never fires at all\", which is the failure this whole
        ;; friction is made of
        (is (= '[sw.core/fetch]
               (mapv :form (get-in (external/done! sess :label "wrote it")
                                   [:findings :direct-http])))))
      (api/add-form! sess 'sw.core
                     "(defn ^{:unused-ok \"fixture: unrelated work\"} tag \"Tags.\" [x] x)"
                     :prompt "an episode that touches something else")
      (testing "a LATER episode is not told — the violation is now permanently invisible"
        (is (empty? (get-in (external/done! sess :label "elsewhere")
                            [:findings :direct-http]))
            (str "if this ever fires, done stopped being episode-scoped and the"
                 " sweep below is no longer the thing under test")))
      (let [sw (rules/sweep-store! sess (:store @sess))]
        (testing "the whole-store sweep finds it — same rule, same finding, no second derivation"
          (is (= '[sw.core/fetch] (mapv :form (get-in sw [:findings :direct-http])))
              (pr-str (:findings sw))))
        (testing "and it grades on the SAME bar done uses, so there is one bar"
          (is (rules/status-affecting-fired? (:store @sess) (:findings sw))
              ":direct-http is an :error rule — a standing finding must be able to flip red"))
        (testing "it reports the population it swept"
          (is (pos? (:forms sw)) (pr-str sw))
          (is (contains? (set (:swept sw)) :direct-http) (pr-str (:swept sw))))
        (testing "and NAMES what it could not sweep, rather than implying it covered everything"
          ;; the Core 9 sharpening: a count is a check, and it reports on the
          ;; population it counted. key-typos is the worked example — over a
          ;; whole store nothing is "established", so it is green vacuously
          (let [scoped (into {} (map (juxt :rule :why)) (:not-swept sw))]
            (is (contains? scoped :key-typos) (pr-str (keys scoped)))
            (is (re-find #"UNCHANGED" (str (:key-typos scoped))) (pr-str scoped))
            (is (every? #(seq (str (:why %))) (:not-swept sw))
                "a rule named as unswept without a reason is just a hole")))
        (testing "the two lists are TOTAL — no advisory falls out of both unnoticed"
          ;; the shape that makes the report trustworthy rather than merely
          ;; present: a rule can be reported as passed or reported as unasked,
          ;; and there is no third state where it silently vanishes
          (is (= (set (map :key rules/done-advisories))
                 (into (set (:swept sw)) (map :rule) (:not-swept sw)))
              (pr-str {:swept (:swept sw) :not-swept (mapv :rule (:not-swept sw))}))
          (is (empty? (filter (set (:swept sw)) (map :rule (:not-swept sw))))
              "a rule cannot be both swept and named as unsweepable")))
      (finally (api/close! sess)))))

(deftest ^:external standing-rule-violations-are-reported-by-full-check
  ;; The wiring half of friction #27. `sweep-store!` existing is not the fix:
  ;; `module-debt` also existed — its own docstring said it "shows what already
  ;; stands" — and was wired into the module graph view and into `module_dep`
  ;; and not into the whole-store gate, which is how four real violations stood
  ;; through a green check. A check that exists and is never asked reads exactly
  ;; like a check that passes.
  ;;
  ;; Deliberately split from the sweep's own spec above so a future failure
  ;; localizes: that one is the rule, this one is the wiring, and they break for
  ;; opposite reasons.
  (let [sess (external/open!)]
    (try
      (api/ingest! sess 'fs.core
                   (str "(ns fs.core \"A clean namespace.\")\n\n"
                        "(defn ^{:unused-ok \"fixture: nothing calls it\"} tag\n"
                        "  \"Tags.\"\n"
                        "  [x]\n"
                        "  x)\n"))
      (testing "the must-NOT-flag half — same fixture, two forms earlier"
        (let [r (external/full-check! sess)]
          (is (empty? (get-in r [:rules :findings])) (pr-str (:rules r)))
          (is (= :green (:status r)) (pr-str (select-keys r [:status :rules])))
          (testing "and it states its population even when clean"
            ;; `:app {:behind 0}` is the precedent: silence on "yes" sends the
            ;; reader back to checking by hand, which was the original friction
            (is (pos? (:forms (:rules r))) (pr-str (:rules r)))
            (is (contains? (set (:swept (:rules r))) :direct-http)
                (pr-str (:swept (:rules r))))
            (is (seq (:not-swept (:rules r)))
                "a sweep that names nothing unswept is claiming coverage it does not have"))))
      (testing "an ADVISORY finding is reported and does NOT flip"
        ;; the other side of the one bar, and it has to come BEFORE the :error
        ;; rule below or it asserts nothing: in a store that is already red,
        ;; "advisory did not flip it" is indistinguishable from "advisory
        ;; flipped it too".
        ;;
        ;; The namespace carries a form on purpose. `namespace-purpose` exempts
        ;; EMPTY namespaces — there is no author to ask — so an ns form alone
        ;; exercises the exemption and not the rule.
        (api/ingest! sess 'fs.quiet
                     (str "(ns fs.quiet)\n\n"
                          "(defn ^{:unused-ok \"fixture: nothing calls it\"} hush\n"
                          "  \"Hushes.\"\n"
                          "  [x]\n"
                          "  x)\n"))
        (let [r (external/full-check! sess)]
          (is (= '[fs.quiet] (mapv :ns (get-in r [:rules :findings :namespace-purpose])))
              (pr-str (get-in r [:rules :findings])))
          (is (= :green (:status r))
              (str "an :advisory rule must be REPORTED without flipping — a"
                   " heuristic that goes red is a heuristic people dial off: "
                   (pr-str (select-keys r [:status :rules]))))))
      (api/add-form! sess 'fs.core
                     (str "(defn ^{:unused-ok \"fixture: the standing violation\"} fetch\n"
                          "  \"Fetches.\"\n"
                          "  [u]\n"
                          "  (.send (java.net.http.HttpClient/newHttpClient) u nil))")
                     :prompt "the violation, written once and never touched again")
      (external/done! sess :label "the last episode that will ever see it")
      (testing "full_check names it, red"
        (let [r (external/full-check! sess)]
          (is (= '[fs.core/fetch] (mapv :form (get-in r [:rules :findings :direct-http])))
              (pr-str (:rules r)))
          ;; discriminating: red proves nothing unless every OTHER red-maker is
          ;; clean — the lesson `tier-layering-is-reported-by-full-check`
          ;; records, where the first version passed while the finding it named
          ;; was still purely advisory
          (is (zero? (:lint-errors r)) (pr-str (:lint r)))
          (is (nil? (:module-violations r)) (pr-str (:module-violations r)))
          (is (empty? (:tier-layering r)) (pr-str (:tier-layering r)))
          (is (empty? (:unused-public r)) (pr-str (:unused-public r)))
          (is (zero? (+ (:fail (:test r) 0) (:error (:test r) 0))) (pr-str (:test r)))
          (is (= :red (:status r))
              (str "an :error rule standing in the store must FLIP the check red —"
                   " done and full_check grade on ONE bar, and a finding the agent"
                   " can scroll past is not a rule: "
                   (pr-str (select-keys r [:status :rules]))))))
      (finally (api/close! sess)))))

(deftest applies-to-actually-filters-and-never-silently-drops
  ;; Declaring the dimension is half of ask #5; the runner acting on it is the
  ;; half that makes it a guarantee. Without this test `:applies-to` is an
  ;; annotation, and the earlier test would pass on a registry nobody reads.
  (let [prod {:form 'app.core/f}
        test {:form 'app.core-test/t-f}
        nsf  {:ns 'app.core-test}
        wide {:note "about the store as a whole"}
        f    #'rules/in-scope]
    (testing ":production keeps production and drops tests"
      (is (= [prod] (f :production [prod test])))
      (is (= [] (f :production [nsf]))))
    (testing ":tests is the mirror"
      (is (= [test] (f :tests [prod test])))
      (is (= [nsf] (f :tests [nsf]))))
    (testing ":both keeps everything, and is the default the runner falls back to"
      (is (= [prod test nsf] (f :both [prod test nsf]))))
    (testing "a finding naming NEITHER a form nor a namespace is ALWAYS kept"
      ;; it is about the store, not about a namespace — dropping it because it
      ;; could not be classified would be the worse error, and silent
      (is (= [wide] (f :production [wide])))
      (is (= [wide] (f :tests [wide]))))))

(deftest a-stored-name-that-disagrees-with-its-source-is-reported
  ;; The store keeps a form's NAME on the element and its source in the node.
  ;; When those disagree, every name-addressed surface silently loses the form:
  ;; `rename_sweep` skips it (and its dry-run does not list it either, so the
  ;; preview agrees with the write about a form neither can see), `edit_subform
  ;; {form "x"}` refuses it as "no form named x", and `:left-behind` prints
  ;; `:form nil`. Meanwhile ID-addressed passes reach it perfectly — which is
  ;; why this survived: half the rename machinery works.
  ;;
  ;; Measured on slopp's own store the day this landed: 11 nameless forms, 6
  ;; correctly so (defmethod, use-fixtures) and 5 where `form-symbol` on the
  ;; STORED node returns a name the element does not carry.
  ;;
  ;; The fixture corrupts the element directly, and it has to: ingesting source
  ;; recomputes `:name`, so no source string can express this state. That is
  ;; also why the registry entry carries a `:selftest-note` instead of
  ;; `:fires-on` — a `:fires-on` fixture for this rule would be a fixture that
  ;; cannot fail.
  (let [st  (store/ingest (store/empty-store) 'rn.core
                          "(ns rn.core)\n(defn f \"F.\" [] 1)\n")
        fid (:id (store/form-named st 'rn.core 'f))
        bad (update-in st [:namespaces 'rn.core :elements]
                       (fn [es] (mapv #(cond-> % (= fid (:id %)) (assoc :name nil)) es)))]
    (testing "a healthy store reports nothing — the control, without which the
              finding below is equally consistent with firing on everything"
      (is (empty? (#'rules/stored-name-check nil st [fid]))))
    (testing "the disagreement is reported, and says what the SOURCE calls it,
              since that is the name the reader was looking for"
      (let [found (#'rules/stored-name-check nil bad [fid])]
        (is (= 1 (count found)) (pr-str found))
        (is (re-find #"\bf\b" (str (:teach (first found)))) (pr-str found))))))

(deftest a-regex-that-names-a-namespace-this-store-does-not-have-is-reported
  ;; A guard's search PATTERN is DATA, so a rename walks straight past it:
  ;; `ns_rename` rewrites requires, qualified refs, quoted symbols and prose,
  ;; and a regex spells its dots `\.`, so even the prose sweep's text match
  ;; misses the escaped spelling. The form stays green while searching for a
  ;; string that can no longer occur.
  ;;
  ;; Measured on slopp's own store before this rule existed: 1001 regex
  ;; literals, 110 carrying a dotted name. A rule reporting every dotted name
  ;; that does not resolve produced 119 findings of which 2 were real —
  ;; fixtures name `mv.core`, libraries name `clojure.set`, config keys name
  ;; `web.static`, assets name `logo.png`, and a pattern spanning `\s+` yields
  ;; `assertions.s`. Restricting to names whose ROOT SEGMENT this store owns
  ;; took 119 to 3, and all three were bugs.
  ;;
  ;; That restriction is not a heuristic, it is the fixture rule read from the
  ;; other end: a fixture that names no real production code is precisely a
  ;; fixture this check cannot see. The three quiet cases below are the three
  ;; thirds of the noise, one control each.
  (let [st  (-> (store/empty-store)
                (store/ingest 'rp.image.testmain "(ns rp.image.testmain)\n(defn run [] 1)\n")
                (store/ingest 'rp.core
                              (str "(ns rp.core)\n"
                                   "(defn stale [s] (re-find #\"rp\\.testmain\" s))\n"
                                   "(defn ext [s] (re-find #\"clojure\\.string\" s))\n"
                                   "(defn live [s] (re-find #\"rp\\.image\\.testmain\" s))\n"
                                   "(defn pre [s] (re-find #\"rp\\.image\" s))\n")))
        ids   (mapv #(:id (store/form-named st 'rp.core %)) '[stale ext live pre])
        found (#'rules/stale-pattern-check nil st ids)]
    (testing "the population is four forms — without this, one finding and
              three silences are equally consistent with a rule that read
              nothing at all"
      (is (= 4 (count ids)) (pr-str ids))
      (is (every? some? ids) (pr-str ids)))
    (testing "exactly the stale one is reported"
      (is (= 1 (count found)) (pr-str found))
      (is (= 'rp.core/stale (:form (first found))) (pr-str found)))
    (testing "and it says where the name went, derived by last segment the way
              a stranded alias's :suggest is"
      (is (= 'rp.image.testmain (:suggest (first found))) (pr-str found)))))
