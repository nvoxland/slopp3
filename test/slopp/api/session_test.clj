(ns slopp.api.session-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.api :as api]
            [slopp.edit :as edit]
            [slopp.store :as store] [slopp.api.session :as session]))

(deftest ^:external heal-path-replays-candidate-namespaces
  ;; the extract_ns live failure: hot-load-all!'s heal boots a FRESH image
  ;; from the COMMITTED store, so a candidate that CREATES a namespace lost
  ;; it — the parent's (:require new-ns) then hit the classpath and
  ;; FileNotFound'd. The heal must replay the candidate's touched nses from
  ;; the CANDIDATE (dependency order, full load-ns! so *loaded-libs* is
  ;; stamped) before retrying.
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'hp.core "(ns hp.core)\n\n(defn top \"T.\" [x] x)\n")
      (let [st   (:store @sess)
            st1  (store/ingest st 'hp.core.impl
                               "(ns hp.core.impl)\n\n(defn helper \"H.\" [x] (inc x))\n")
            [st2 _] (store/replace-node
                     st1 'hp.core 'hp.core
                     (:node (edit/parse-form
                             "(ns hp.core (:require [hp.core.impl :as impl]))")))
            [st3 _] (store/replace-node
                     st2 'hp.core 'top
                     (:node (edit/parse-form
                             "(defn top \"T.\" [x] (impl/helper x))")))
            ;; parent decl FIRST: its require fails (new ns never image-loaded,
            ;; no *loaded-libs* stamp) → simulates any transient first-attempt
            ;; failure → the heal path must recover from the CANDIDATE
            ids  (into [(:id (store/form-named st3 'hp.core 'hp.core))
                        (:id (store/form-named st3 'hp.core 'top))]
                       (mapv :id (store/forms st3 'hp.core.impl)))
            r    (#'session/hot-load-all! sess st3 ids)]
        (is (:healed r) (pr-str r))
        (is (= [3] (api/query-eval sess "(hp.core/top 2)"))))
      (finally (api/close! sess)))))
(deftest external-among-splits-traced-tests-by-tier
  ;; done! already knows PRECISELY which tests a change reaches — that is what
  ;; the trace map is — but only the external tier can execute ^:external ones,
  ;; so the narrowed set must be split before it can be routed.
  ;;
  ;; Routing by require-closure instead (what done! did until #127) selects a
  ;; median 43 of 46 external test namespaces — measured over every source ns
  ;; 2026-07-17 — which blows the cap of 4 and defers 84.6% of the time. The
  ;; evidence was computed four lines above and thrown away.
  (let [st (-> (store/empty-store)
               (store/ingest 'z.core "(ns z.core)\n\n(defn f \"F.\" [x] x)\n")
               (store/ingest 'z.core-test
                             (str "(ns z.core-test (:require [z.core :as c]\n"
                                  "                          [clojure.test :refer [deftest is]]))\n\n"
                                  "(deftest fast-t (is (= 1 (c/f 1))))\n\n"
                                  "(deftest ^:external slow-t (is (= 2 (c/f 2))))\n")))]
    (testing "only the ^:external members come back — the rest already ran in-image"
      (is (= '[z.core-test/slow-t]
             (session/external-among st '[z.core-test/fast-t z.core-test/slow-t]))))
    (testing "an all-in-image set routes nowhere: there is nothing for the
              external tier to do, which is NOT the same as a silent trace"
      (is (empty? (session/external-among st '[z.core-test/fast-t]))))))
(deftest impacted-external-expands-untraced-forms-per-form
  ;; #127 gave the external tier trace-narrowing but kept the all-or-nothing
  ;; collapse: ONE untraced form made the answer nil and done! fell back to the
  ;; require-closure of EVERYTHING. #132 dissolves the silence per form: an
  ;; untraced form contributes its own namespace's reach, so the answer is
  ;; never nil — [] genuinely means no external test can be affected.
  (let [st   (-> (store/empty-store)
                 (store/ingest 'z.core "(ns z.core)\n\n(defn f \"F.\" [x] x)\n\n(defn g \"G.\" [x] x)\n")
                 (store/ingest 'z.core-test
                               (str "(ns z.core-test (:require [z.core :as c]\n"
                                    "                          [clojure.test :refer [deftest is]]))\n\n"
                                    "(deftest fast-t (is (= 1 (c/f 1))))\n\n"
                                    "(deftest ^:external slow-t (is (= 2 (c/f 2))))\n")))
        sess (atom {:store st
                    :test-map {'z.core-test/fast-t #{'z.core/f}
                               'z.core-test/slow-t #{'z.core/f}}})
        fid  (:id (store/form-named st 'z.core 'f))
        gid  (:id (store/form-named st 'z.core 'g))]
    (testing "traced: only the ^:external half routes out — fast-t already ran in-image"
      (is (= '[z.core-test/slow-t] (session/impacted-external sess st [fid]))))
    (testing "an UNTRACED form expands to its namespace's reach instead of
              collapsing the whole answer to nil"
      (is (= '[z.core-test/slow-t] (session/impacted-external sess st [gid]))))
    (testing "mixed: the union, still never nil"
      (is (= '[z.core-test/slow-t] (session/impacted-external sess st [fid gid]))))))
(deftest affected-tests-consults-every-name-and-refuses-opaque-bodies
  ;; Two consequences of D8 land here. (1) Evidence arrives keyed by the VAR
  ;; that ran — a test calling protocol method m records p.core/m — but the
  ;; form DEFINING m is the defprotocol, primary name P. Looking up only P
  ;; missed all of it. (2) defrecord/deftype method bodies and defmethod
  ;; bodies run where the tracer cannot fully see them (inline bodies compile
  ;; to class methods; the external tier records methods at multi grain), so
  ;; their evidence is PARTIAL — and narrowing on partial evidence is the
  ;; false-green shape. Those forms never narrow: nil means the caller falls
  ;; back to the closure, exactly as if the trace were silent.
  ;;
  ;; The test-map mirrors what instrument! actually writes: a dispatched call
  ;; records the multi ALWAYS, plus the method's form key when known — so
  ;; method evidence without multi evidence cannot occur. Form ids are derived,
  ;; not hardcoded: ids are store-global, and an earlier draft of this test
  ;; hardcoded f2 — which was a form in the OTHER namespace.
  (let [st (-> (store/empty-store)
               (store/ingest 'p.core
                             (str "(ns p.core)\n\n"
                                  "(defprotocol P \"P.\" (m [_] \"M.\") (n [_] \"N.\"))\n\n"
                                  "(defrecord R [x] P (m [_] 1) (n [_] 2))\n"))
               (store/ingest 'dm.core
                             (str "(ns dm.core)\n\n(defmulti area :shape)\n\n"
                                  "(defmethod area :square [s] 1)\n")))
        meth-id (symbol (:id (first (filter #(nil? (:name %))
                                            (store/forms st 'dm.core)))))
        sess (atom {:store st
                    :test-map {'p.t/proto-t  #{'p.core/m}
                               'p.t/ctor-t   #{'p.core/->R}
                               'dm.t/multi-t #{'dm.core/area}
                               'dm.t/meth-t  #{'dm.core/area
                                               (symbol "dm.core" (str meth-id))}}})]
    (testing "a defprotocol form NEVER narrows — found red (2026-07-17): its
              method vars ARE wrapped, but a protocol call site's inline cache
              hits the interface DIRECTLY for inline impls (the common case),
              bypassing the var. Evidence through the var exists only for
              extend-based dispatch, so it is partial, and partial must not
              select. The synthetic p.core/m evidence here is exactly the kind
              a real run might NOT produce."
      (is (nil? (session/affected-tests sess 'p.core 'P))))
    (testing "a defrecord form NEVER narrows — its method bodies are invisible
              to the tracer, so ->R evidence alone would under-select"
      (is (nil? (session/affected-tests sess 'p.core 'R))))
    (testing "a defmethod form never narrows — the external tier records it at
              multi grain, and partial evidence must not select"
      (is (nil? (session/affected-tests sess 'dm.core meth-id))))
    (testing "the defmulti itself narrows: every dispatched call records it, in
              both tiers, so its evidence is complete"
      (is (= '[dm.t/meth-t dm.t/multi-t]
             (vec (sort (session/affected-tests sess 'dm.core 'area))))))))
(deftest impacted-tests-falls-back-per-form-not-globally
  ;; THE collapse (measured 2026-07-17): done! discarded ALL narrowing when ONE
  ;; changed form had no trace evidence — (when (not-any? nil? per) ...). An ns
  ;; form can never be traced and ns_add_require edits one, so 54.4% of real
  ;; episodes (43.2% via ns forms alone) reverted to whole-closure runs, and
  ;; the evidence for every OTHER form in the episode was thrown away.
  ;;
  ;; Per-form is equally sound and far less pessimistic: an untraced form
  ;; contributes every test whose require-closure reaches ITS namespace — the
  ;; same tests the global fallback would run FOR THAT FORM, since
  ;; test-nses-reaching over a union of nses is the union of the per-ns calls —
  ;; while a traced form keeps contributing exactly its observed tests.
  (let [st (-> (store/empty-store)
               (store/ingest 'pf.a "(ns pf.a)\n\n(defn f \"F.\" [x] x)\n\n(defn g \"G.\" [x] x)\n")
               (store/ingest 'pf.b "(ns pf.b)\n\n(defn h \"H.\" [x] x)\n")
               (store/ingest 'pf.a-test
                             (str "(ns pf.a-test (:require [pf.a :as a]\n"
                                  "                        [clojure.test :refer [deftest is]]))\n\n"
                                  "(deftest f-t (is (= 1 (a/f 1))))\n\n"
                                  "(deftest g-t (is (= 1 (a/g 1))))\n"))
               (store/ingest 'pf.b-test
                             (str "(ns pf.b-test (:require [pf.b :as b]\n"
                                  "                        [clojure.test :refer [deftest is]]))\n\n"
                                  "(deftest h-t (is (= 1 (b/h 1))))\n")))
        sess (atom {:store st
                    :test-map {'pf.a-test/f-t #{'pf.a/f}
                               'pf.a-test/g-t #{'pf.a/g}
                               'pf.b-test/h-t #{'pf.b/h}}})
        fid  (fn [nsx nm] (:id (store/form-named st nsx nm)))]
    (testing "all-traced: exactly the evidence, nothing else"
      (is (= '[pf.a-test/f-t]
             (session/impacted-tests sess st [(fid 'pf.a 'f)]))))
    (testing "an untraced form (pf.b's NS FORM — the 43.2% case) expands to the
              tests reaching ITS namespace only"
      (is (= '[pf.b-test/h-t]
             (session/impacted-tests sess st [(fid 'pf.b 'pf.b)]))))
    (testing "mixed episode: the traced form KEEPS its narrow set — g-t, whose
              subject was not touched, is not dragged in by pf.b's ns form"
      (is (= '[pf.a-test/f-t pf.b-test/h-t]
             (session/impacted-tests sess st [(fid 'pf.a 'f) (fid 'pf.b 'pf.b)]))))
    (testing "a deleted fid is skipped, not an error"
      (is (= '[pf.a-test/f-t]
             (session/impacted-tests sess st [(fid 'pf.a 'f) "f999"]))))))
