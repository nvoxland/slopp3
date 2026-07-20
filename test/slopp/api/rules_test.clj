(ns slopp.api.rules-test
  (:require [clojure.test :refer [deftest testing is]]
            [slopp.api.rules :as rules] [slopp.store :as store] [slopp.api :as api] [slopp.edit.modules :as edit.modules] [clojure.set :as set]))

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

(deftest ^:isolated per-store-severity-config-retunes-done
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'sv.core
                   (str "(ns sv.core)\n"
                        "(defn a [m] {:user/email (:x m)})\n"
                        "(defn b [m] {:user/email (:y m)})\n"
                        "(defn handle \"H.\" ([x] x) ([x y] (+ x y)))\n"))
      (api/done! sess :label "baseline")
      (api/config-file! sess "rules" :key "key-typos" :value "off"
                        :prompt "silence typos for this project")
      (api/config-file! sess "rules" :key "breaking-changes" :value "error"
                        :prompt "make a boundary break BLOCK here")
      (testing ":off silences an advisory end-to-end"
        (api/add-form! sess 'sv.core "(defn c [m] {:user/emial (:z m)})"
                       :prompt "typo — but the advisory is dialed off")
        (let [r (api/done! sess :label "typo-off")]
          (is (nil? (get-in r [:findings :key-typos])) (pr-str (:findings r)))))
      (testing ":error escalates an advisory to flip test-status red"
        (api/edit-replace! sess 'sv.core 'handle "(defn handle \"H.\" [x] x)"
                           :prompt "narrow away the 2-arity")
        (let [r (api/done! sess :label "narrow")]
          (is (seq (get-in r [:findings :breaking-changes])) (pr-str (:findings r)))
          (is (= :red (get-in r [:findings :test-status])) (pr-str (:findings r)))))
      (finally (api/close! sess)))))

(deftest catalog-covers-every-registered-rule
  (let [cataloged   (set (map :rule rules/rule-catalog))
        write-gates (set (edit.modules/write-gate-names))
        done-keys   (set (map :key rules/done-advisories))]
    (testing "every entry carries the declarative shape"
      (is (every? (fn [r] (and (:rule r) (:grain r) (:severity r) (:escape r) (:teach r)))
                  rules/rule-catalog)))
    (testing "every registered write gate is cataloged (drift guard)"
      (is (empty? (set/difference write-gates cataloged))
          (str "uncataloged write gates: " (set/difference write-gates cataloged))))
    (testing "every registered done advisory is cataloged (drift guard)"
      (is (empty? (set/difference done-keys cataloged))
          (str "uncataloged done advisories: " (set/difference done-keys cataloged))))))

(deftest ^:isolated query-rules-reports-write-gate-severity-honestly
  (let [sess (api/open!)]
    (try
      (testing "a write gate dialed :advisory now reports :advisory (warn-but-proceed)"
        (api/config-file! sess "rules" :key "schema-refusal" :value "advisory"
                          :prompt "soften a write gate to advisory")
        (let [sr (first (filter #(= :schema-refusal (:rule %)) (api/query-rules sess)))]
          (is (= :form (:grain sr)) (pr-str sr))
          (is (= :advisory (:severity sr)) (pr-str sr))))
      (testing ":off on a write gate is honored and reported"
        (api/config-file! sess "rules" :key "schema-refusal" :value "off"
                          :prompt "turn it off")
        (let [sr (first (filter #(= :schema-refusal (:rule %)) (api/query-rules sess)))]
          (is (= :off (:severity sr)) (pr-str sr))))
      (testing "a done advisory keeps its full severity range"
        (api/config-file! sess "rules" :key "key-typos" :value "error"
                          :prompt "escalate an advisory")
        (let [kt (first (filter #(= :key-typos (:rule %)) (api/query-rules sess)))]
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

(deftest ^:isolated done-surfaces-ambient-state-and-bare-throw
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'as.core "(ns as.core)\n\n(defn seed \"S.\" [x] x)\n")
      (api/done! sess :label "baseline")
      (api/add-form! sess 'as.core "(def cache (atom {}))" :prompt "a global atom")
      (api/add-form! sess 'as.core "(defn boom \"B.\" [x] (throw (IllegalArgumentException. \"e\")))"
                     :prompt "a bare throw at a boundary")
      (let [r (api/done! sess :label "check")]
        (testing "the ambient global atom is flagged"
          (is (= '[as.core/cache] (mapv :form (get-in r [:findings :ambient-state])))
              (pr-str (:findings r))))
        (testing "the boundary bare throw is flagged"
          (is (= '[as.core/boom] (mapv :form (get-in r [:findings :bare-throw])))
              (pr-str (:findings r)))))
      (finally (api/close! sess)))))

(deftest ^:isolated ambient-ok-marks-a-deliberate-global-and-polices-itself
  ;; ambient-state can only be BLOCKING if a legitimately-deliberate global has
  ;; a way to say so — a memo whose answer is immutable is the standing
  ;; counter-example. ^:ambient-ok is that escape, and it polices itself the
  ;; same way ^:unused-ok does: a marker on a def that is NOT ambient state is
  ;; itself a finding, so the flag can never drift into decoration.
  (let [sess (api/open!)]
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

(deftest ^:isolated every-advisory-fires-on-its-own-fixture
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
      (let [sess (api/open!)]
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

(deftest ^:isolated done-asks-about-a-newly-widened-shell
  ;; Declaring a namespace :effects makes the CORE smaller. That is sometimes
  ;; right and sometimes the path of least resistance, and the moment to ask is
  ;; while the reason is still in context. It fires only for the episode that
  ;; declared it, so it prompts once and cannot decay into standing noise.
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'sw.core
                   "(ns sw.core)\n(defn ^:unused-ok grab \"E.\" [p] (slurp p))\n")
      (api/done! sess :label "baseline")
      (testing "declaring a namespace :effects raises the question at done"
        (api/module-tier! sess "sw.core" :effects :prompt "needs to read a file")
        (let [f (get-in (api/done! sess :label "widened") [:findings :shell-widening])]
          (is (some #(= 'sw.core (:ns %)) f) (pr-str f))
          (is (re-find #"(?i)shell" (str (:why (first f)))) (pr-str f))))
      (testing "it does NOT re-fire on the next done — one prompt, not nagging"
        (let [f (get-in (api/done! sess :label "later") [:findings :shell-widening])]
          (is (nil? f) (pr-str f))))
      (testing "and it is advisory: a question does not turn the done red"
        (api/module-tier! sess "sw.other" :effects :prompt "another edge")
        (let [r (api/done! sess :label "still green")]
          (is (not= :red (get-in r [:findings :test-status])) (pr-str (:findings r)))))
      (finally (api/close! sess)))))
