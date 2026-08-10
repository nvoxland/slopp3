(ns slopp.ops.review-test
  "Cover for what a triage flag is allowed to CLAIM.

  These are not tests that the ranking arithmetic adds up — they are tests that
  each flag's evidence supports the word it uses. That is the failure mode this
  namespace exists for: a flag which overstates itself sends a reviewer to fix
  something that is not broken, or worse, buries the rows that are with ones
  nobody can discharge.

  So the cases here are mostly the INNOCENT reasons a signal can be absent —
  coverage declared by a `^{:covers}` marker rather than observed, a namespace
  the JVM oracle cannot load at all, a lint finding that belongs to the ns form —
  and each asserts that the scan says so instead of reporting a gap. Whole-store
  scanning (`:unused` needs every caller) is exercised from
  `slopp.orientation-test`, with the other orientation surfaces."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.ops.review :as review] [slopp.read.modules :as modules]))

(deftest review-scan-surfaces-ns-form-lint
  ;; Lint findings INSIDE the (ns …) form — dead imports, unused requires — have
  ;; no named form to hang off, so review-scan dropped them from :top/:totals and
  ;; reported "nearly lint-clean" while missing exactly the declarations a
  ;; refactor strands (slopp.git had 16 dead imports invisible to it). They now
  ;; surface per namespace in :ns-lint.
  (let [st   (-> (store/empty-store)
                 (store/ingest 'rv.core "(ns rv.core (:require [clojure.set :as s]))\n(defn f [] 1)\n"))
        r    (review/review-scan (atom {:store st :test-map {}}) :ns 'rv.core)]
    (testing "an unused require in the ns form is reported, per namespace"
      (is (= {'rv.core 1} (:ns-lint r)) (pr-str r)))
    (testing "the finding does NOT masquerade as a named-form :lint row"
      (is (nil? (get (:totals r) :lint)) (pr-str r))))
  (testing "a namespace whose requires are all used has no :ns-lint entry"
    (let [st2 (-> (store/empty-store)
                  (store/ingest 'rv.clean "(ns rv.clean (:require [clojure.set :as s]))\n(defn f [] (s/union #{} #{}))\n"))
          r2  (review/review-scan (atom {:store st2 :test-map {}}) :ns 'rv.clean)]
      (is (nil? (get (:ns-lint r2) 'rv.clean)) (pr-str r2)))))

(deftest review-scan-honours-declared-coverage
  ;; A form reached only through dispatch has no static caller and no trace, so
  ;; review-scan flags it :untested — a finding a ^{:covers} declaration is
  ;; exactly meant to discharge. Declared coverage (the graph's :covers edges)
  ;; now counts as coverage here, so the form is not untested. It is still
  ;; :unused (coverage is not liveness) — the two flags stay independent.
  (let [st (-> (store/empty-store)
               (store/ingest 'rv.disp "(ns rv.disp)\n(defn handler [x] x)\n")
               (store/ingest 'rv.disp-test
                             (str "(ns rv.disp-test (:require [clojure.test :refer [deftest is]]))\n"
                                  "(deftest ^{:covers \"rv.disp/handler — via dispatch\"} cover-t (is true))\n")))
        r    (review/review-scan (atom {:store st :test-map {}}) :ns 'rv.disp)
        row  (first (filter #(= 'rv.disp/handler (:form %)) (:top r)))]
    (testing "a declared-covered form is not flagged :untested"
      (is (some? row) (pr-str r))
      (is (not (contains? (set (:flags row)) :untested)) (pr-str row)))
    (testing "declared coverage is not liveness — the form is still :unused"
      (is (contains? (set (:flags row)) :unused) (pr-str row)))))

(deftest review-scan-ignores-marked-side-effect-requires
  ;; A require the done-point kept (marked ^:side-effect because removing it
  ;; breaks a cold load) is deliberately present — it must NOT read as unused.
  ;; kondo still flags it :unused-namespace; review_scan suppresses that.
  (let [marked (-> (store/empty-store)
                   (store/ingest 'rv.se
                                 "(ns rv.se (:require ^:side-effect [rv.dep :as d]))\n(defn f [] 1)\n"))
        plain  (-> (store/empty-store)
                   (store/ingest 'rv.un
                                 "(ns rv.un (:require [rv.dep :as d]))\n(defn f [] 1)\n"))]
    (testing "a ^:side-effect-marked unused require is not counted in :ns-lint"
      (is (nil? (get (:ns-lint (review/review-scan (atom {:store marked :test-map {}}) :ns 'rv.se))
                     'rv.se))))
    (testing "the same require WITHOUT the marker is still flagged"
      (is (= 1 (get (:ns-lint (review/review-scan (atom {:store plain :test-map {}}) :ns 'rv.un))
                    'rv.un))))))

(deftest review-scan-tells-unreachable-by-PLATFORM-from-untested
  ;; Measured on slopp-ui: 17 of 149 forms flagged :untested, and not one of them
  ;; was untested in the sense the word implies — 15 were `:cljs`, which the JVM
  ;; oracle cannot load at all. The compiler is their only possible check, so
  ;; "add a test" is advice nobody can take, and a triage list where the top
  ;; fifteen rows are undischargeable is a list people stop reading.
  ;;
  ;; The reasoning already existed here and was keyed to the wrong fact: a
  ;; `^:generated` form was excluded because "it is :cljs — never traced, never
  ;; reached from a test ns; not a finding". True of ALL :cljs, generated or
  ;; hand-written. Ask the platform instead of asking who typed it.
  ;;
  ;; Not silence, though — a separate flag. :cljs really is unverified, and this
  ;; project's own rule is "when client.app grows, ask which part stopped being
  ;; tested". Dropping the row would answer that question wrongly forever. So
  ;; :off-platform says the same thing with the cause attached, and carries less
  ;; risk than :untested because it is a standing structural fact rather than a
  ;; gap someone can close.
  (let [src (str "(ns cl.client)\n"
                 "(defn draw [x] x)\n")
        st  (-> (store/empty-store)
                (store/ingest 'cl.core "(ns cl.core)\n(defn f [x] x)\n")
                (store/ingest 'cl.client src)
                (assoc :module-platforms {"cl.client" :cljs}))
        r   (review/review-scan (atom {:store st :test-map {}}))
        row (fn [q] (first (filter #(= q (:form %)) (:top r))))]
    (testing "a :cljs form is NOT called untested — no test could ever reach it"
      (let [c (row 'cl.client/draw)]
        (is (some? c) (pr-str r))
        (is (not (contains? (set (:flags c)) :untested)) (pr-str c))))
    (testing "it is flagged :off-platform instead, so the row still appears and
              now says WHY nothing covers it"
      (is (contains? (set (:flags (row 'cl.client/draw))) :off-platform)
          (pr-str (row 'cl.client/draw))))
    (testing "and it ranks BELOW a genuinely untested JVM form — one is
              actionable and the other is a fact about the platform"
      (let [jvm (row 'cl.core/f)
            cljs (row 'cl.client/draw)]
        (is (contains? (set (:flags jvm)) :untested) (pr-str jvm))
        (is (< (:risk cljs) (:risk jvm))
            (str "off-platform must not outrank a real gap: "
                 (pr-str [cljs jvm])))))
    (testing "the totals separate them, so a reviewer can see at a glance how
              much of the store is outside the oracle rather than behind on tests"
      (is (pos? (:off-platform (:totals r))) (pr-str (:totals r)))
      (is (not (contains? (set (:flags (row 'cl.client/draw))) :untested))))))

(deftest review-scan-knows-an-endpoint-is-called-by-the-ROUTER
  ;; Measured on slopp's own store: all eight forms of `slopp.http-api.api` — the
  ;; entire HTTP API this project serves — flagged :unused, which review_scan
  ;; glosses as "dead public surface". Zero in-store callers is CORRECT and the
  ;; conclusion is wrong: an endpoint's caller is the router, which resolves it
  ;; by scanning the namespaces a server was handed, so there is no call edge to
  ;; find and there never will be.
  ;;
  ;; Same shape as every other item this session: an absence with an innocent
  ;; cause, reported as the presence of a problem. And costly in the same way —
  ;; a whole API's worth of undischargeable rows at the top of a triage list is
  ;; how a reviewer learns to skim past :unused.
  ;;
  ;; A `:web/path` form declares itself an entry point; that is what `-main` and
  ;; `^:entry-point` already mean here, so this is the same rule recognising one
  ;; more way of saying it.
  (let [st (-> (store/empty-store)
               (store/ingest 'ep.api
                             (str "(ns ep.api \"Fixture: an app's web surface.\")\n"
                                  "(defn ^{:web/method :get :web/path \"/api/things\"\n"
                                  "        :web/auth :public\n"
                                  "        :web/reads {:things [:ep/things []]}} things \"T.\" [req]\n"
                                  "  {:status 200 :body (:things (:web/reads req))})\n"
                                  "(defn ^{:web/read :ep/things} things-read \"R.\" [ctx _] [])\n"
                                  "(defn orphan \"O.\" [x] x)\n")))
        r   (review/review-scan (atom {:store st :test-map {}}))
        row (fn [q] (first (filter #(= q (:form %)) (:top r))))]
    (testing "an endpoint is NOT dead surface — the router calls it"
      (let [e (row 'ep.api/things)]
        (is (not (contains? (set (:flags e)) :unused))
            (str "an endpoint has no in-store caller BY CONSTRUCTION: " (pr-str e)))))
    (testing "a PERFORMER is the same case — resolved by vocabulary at server
              assembly, by name, so it has no caller either. Found by fixing the
              endpoint half and re-scanning: all eight of slopp's own
              `slopp.http-api.reads` performers were still reported dead."
      (let [p (row 'ep.api/things-read)]
        (is (some? p) (pr-str r))
        (is (not (contains? (set (:flags p)) :unused)) (pr-str p))))
    (testing "and a plain public fn nobody calls still IS, so this recognises
              framework entry points rather than switching the check off for the
              namespace"
      (is (contains? (set (:flags (row 'ep.api/orphan))) :unused)
          (pr-str (row 'ep.api/orphan))))))

(deftest the-two-dead-surface-derivations-must-agree
  ;; `review_scan` and `api.modules/unused-report` answer the same question by
  ;; different algorithms, and they disagreed about endpoints and performers for
  ;; as long as both existed — slopp's own /api/* surface and all eight of its
  ;; read performers were reported dead by one and live by the other. Nothing
  ;; compared them, so nobody found out.
  ;;
  ;; This is that comparison. It is the regression guard the refactor rests on:
  ;; `review-scan` is about to stop hand-deriving framework markers from form
  ;; metadata and start reading the reference graph's `:via :declared` edges,
  ;; which are the same five facts already modelled as data. The refactor must
  ;; move nothing, and this is what says so.
  (let [st (-> (store/empty-store)
               (store/ingest 'ag.api
                             (str "(ns ag.api \"Fixture: every way a form stays alive.\")\n"
                                  ;; the framework calls these — no in-store caller, ever
                                  "(defn ^{:web/method :get :web/path \"/api/x\"\n"
                                  "        :web/auth :public} endpoint \"E.\" [req] {:status 200})\n"
                                  "(defn ^{:web/read :ag/thing} performer \"P.\" [ctx _] [])\n"
                                  "(defn ^{:web/effect :ag/do} effector \"F.\" [ctx _] nil)\n"
                                  ;; declared alive by hand
                                  "(defn ^:entry-point cli \"C.\" [] :ok)\n"
                                  "(defn ^{:unused-ok \"kept for downstream\"} spare \"S.\" [] :ok)\n"
                                  ;; genuinely nothing
                                  "(defn orphan \"O.\" [x] x)\n"
                                  ;; called ONLY by the test — the one place the
                                  ;; two derivations could legitimately differ
                                  "(defn test-only \"T.\" [x] x)\n"))
               (store/ingest 'ag.api-test
                             (str "(ns ag.api-test (:require [clojure.test :refer [deftest is]]\n"
                                  "                          [ag.api :as a]))\n"
                                  ;; calls test-only and NOT orphan, so orphan is
                                  ;; genuinely dead and the comparison below has a
                                  ;; non-empty set to be right about
                                  "(deftest t (is (= 1 (a/test-only 1))))\n")))
        scan-unused (set (for [row (:top (review/review-scan
                                          (atom {:store st :test-map {}})))
                               :when (contains? (set (:flags row)) :unused)]
                           (:form row)))
        report-unused (set (:unused (modules/unused-report
                                     st ['ag.api 'ag.api-test])))]
    (testing "the comparison has TEETH — an equality between two empty sets is not
              a guard, and the first cut of this test was exactly that"
      (is (contains? scan-unused 'ag.api/orphan)
          (str "the fixture must contain something genuinely dead, or agreeing"
               " about nothing reads as agreeing: " (pr-str scan-unused)))
      (is (contains? report-unused 'ag.api/orphan) (pr-str report-unused)))
    (testing "nothing the FRAMEWORK invokes is dead surface, in either derivation"
      (doseq [q ['ag.api/endpoint 'ag.api/performer 'ag.api/effector]]
        (is (not (contains? scan-unused q)) (str "review-scan: " q))
        (is (not (contains? report-unused q)) (str "unused-report: " q))))
    (testing "nor is anything DECLARED alive by hand"
      (doseq [q ['ag.api/cli 'ag.api/spare]]
        (is (not (contains? scan-unused q)) (str "review-scan: " q))
        (is (not (contains? report-unused q)) (str "unused-report: " q))))
    (testing "and they agree EXACTLY — the point is not that each is defensible
              on its own, it is that one question has one answer"
      (is (= scan-unused report-unused)
          (str "review-scan says " (pr-str scan-unused)
               ", unused-report says " (pr-str report-unused))))))

(deftest coverage-is-a-WHOLE-STORE-question-however-narrowly-you-asked
  ;; Every other population in review-scan is already whole-store: `blast` and
  ;; `adj` come off the reference graph with a comment saying caller counts must
  ;; be true under :ns scoping, and declared coverage says the same of itself.
  ;; The STATIC COVERAGE SEED was the one left enumerating the scoped `nses` —
  ;; so unless you happened to scope the scan AT a -test namespace, nothing
  ;; seeded it and every form in the namespace came back :untested.
  ;;
  ;; Measured on this store before the fix: `{ns "slopp.index.refs"}` reported
  ;; 18 forms :untested that the whole-store scan reported covered, `covered-by`
  ;; among them — a form with two tests of its own. A triage answer that depends
  ;; on how widely you asked is worse than no answer, because the narrow ask is
  ;; the one a reviewer makes when they have already decided where to look.
  (let [st (-> (store/empty-store)
               (store/ingest 'sc.core "(ns sc.core)\n(defn reached [x] x)\n(defn orphan [x] x)\n")
               (store/ingest 'sc.core-test
                             (str "(ns sc.core-test (:require [clojure.test :refer [deftest is]]\n"
                                  "                            [sc.core :as core]))\n"
                                  "(deftest reach-t (is (= 1 (core/reached 1))))\n")))
        sess     (atom {:store st :test-map {}})
        untested (fn [r] (set (for [row (:top r)
                                    :when (contains? (set (:flags row)) :untested)
                                    :let [q (:form row)]
                                    :when (= "sc.core" (namespace q))]
                                q)))]
    ;; value-shaped on both sides: `orphan` must be NAMED, not merely
    ;; "not contradicted" — an empty :top would satisfy any absence assertion
    ;; here, and this whole namespace exists because of that failure mode.
    (testing "whole store: the test calls `reached`, nothing calls `orphan`"
      (is (= '#{sc.core/orphan} (untested (review/review-scan sess)))))
    (testing "scoped to the namespace, the same question gets the same answer"
      (is (= '#{sc.core/orphan} (untested (review/review-scan sess :ns 'sc.core)))))))

(deftest a-row-says-WHICH-KIND-of-evidence-stands-behind-it
  ;; `:untested` is one word doing the work of four facts. Its absence can mean
  ;; a test RAN this form, or a `^{:covers}` marker CLAIMS a path nothing can
  ;; observe, or merely that some form in a test namespace reaches it through a
  ;; chain of calls — and the third is nearly free. Measured on this store: 1120
  ;; of 1285 production forms count as covered by static reach, 297 of them at
  ;; three hops or more, where `test → ops → edit → store` marks the store form
  ;; covered. A reviewer reading a quiet row cannot tell which of those they
  ;; have, and the difference is the whole question they came to ask.
  ;;
  ;; So the row carries the class, in the vocabulary `refs/covered-by` already
  ;; uses for the same distinction — one derivation of the words, not two.
  (let [st (-> (store/empty-store)
               (store/ingest 'ev.core
                             (str "(ns ev.core)\n"
                                  "(defn observed [] 1)\n"
                                  "(defn direct [] 1)\n"
                                  "(defn deep [] 1)\n"
                                  "(defn mid [] (deep))\n"
                                  "(defn dispatched [] 1)\n"
                                  "(defn orphan [] 1)\n"
                                  "(def threshold 5)\n"))
               (store/ingest 'ev.client "(ns ev.client)\n(defn draw [x] x)\n")
               (store/ingest 'ev.core-test
                             (str "(ns ev.core-test (:require [clojure.test :refer [deftest is]]\n"
                                  "                            [ev.core :as c]))\n"
                                  "(deftest obs-t (is (= 1 (c/observed))))\n"
                                  "(deftest reach-t (is (= 1 (c/direct))) (is (= 1 (c/mid))))\n"
                                  "(deftest ^{:covers \"ev.core/dispatched — resolved by name\"} disp-t (is true))\n"))
               (assoc :module-platforms {"ev.client" :cljs}))
        ;; only obs-t ever RAN, and it only touched `observed`
        sess (atom {:store st :test-map {'ev.core-test/obs-t #{'ev.core/observed}}})
        rows (into {} (for [row (:top (review/review-scan sess :limit 100))]
                        [(:form row) (select-keys row [:evidence :hops])]))]
    (testing "each kind is named, and the strongest evidence wins — `observed`
              is statically reachable too, and the row says it RAN"
      (is (= '{ev.core/observed   {:evidence :observed}
               ev.core/dispatched {:evidence :declared}
               ev.core/direct     {:evidence :static :hops 1}
               ev.core/mid        {:evidence :static :hops 1}
               ev.core/deep       {:evidence :static :hops 2}
               ev.client/draw     {:evidence :off-platform}
               ev.core/orphan     {:evidence :none}
               ev.core/threshold  {:evidence :none}}
             rows)))
    (testing ":untested is a strict SUBSET of :none, and the difference is
              deliberate: a plain (def x <data>) has no invocation to observe,
              so it can never acquire evidence and flagging it would be a
              finding nobody can discharge. `:evidence` answers what we KNOW;
              `:untested` answers what is worth someone's afternoon."
      (let [top   (:top (review/review-scan sess :limit 100))
            by    (into {} (map (juxt :form identity)) top)
            none  (set (for [r top :when (= :none (:evidence r))] (:form r)))
            flagd (set (for [r top :when (contains? (set (:flags r)) :untested)] (:form r)))]
        (is (= '#{ev.core/orphan ev.core/threshold} none) (pr-str top))
        (is (= '#{ev.core/orphan} flagd) (pr-str top))
        (is (= :none (:evidence (by 'ev.core/threshold))))
        (is (not (contains? (set (:flags (by 'ev.core/threshold))) :untested))
            "a data def is evidence-less and is nonetheless not a gap")))
    (testing "and the same split in the SUMMARY, because nobody reads 954 rows —
              on this store 892 of them rest on static reach, which is a fact
              about the suite that no per-row reading would ever add up to"
      (is (= '{:declared 1 :none 2 :observed 1 :off-platform 1 :static 3}
             (:evidence (review/review-scan sess :limit 100)))))))
