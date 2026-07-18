(ns slopp.ergonomics-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.edit :as edit]
            [slopp.api :as api]))

(deftest ^:isolated unparseable-source-returns-error-not-throw   ; F3
  (testing "pure gate"
    (let [r (edit/parse-form "(defn broken [x")]
      (is (:error r))
      (is (re-find #"unparseable" (:error r)))))
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'er.core "(ns er.core)\n(defn f [x] x)\n")
      (testing "add with unbalanced source: {:error}, nothing committed"
        (let [n (count (store/deltas (:store @sess)))
              r (api/add-form! sess 'er.core "(defn broken [x")]
          (is (:error r))
          (is (= n (count (store/deltas (:store @sess)))))))
      (testing "replace and ingest too"
        (is (:error (api/edit-replace! sess 'er.core 'f "(defn f [x")))
        (is (:error (api/ingest! sess 'er2.core "(ns er2.core"))))
      (testing "ingest returns a tidy map now, not the session atom (F8)"
        (let [r (api/ingest! sess 'er3.core "(ns er3.core)\n(def a 1)\n")]
          (is (= 'er3.core (:ns r)))
          (is (= 2 (:forms r)))))
      (finally (api/close! sess)))))

(deftest ^:isolated ingest-is-the-batch-write-for-NEW-namespaces   ; W1 (user decision)
  (let [sess (api/open!)]
    (try
      (testing "a whole namespace lands in one verified write"
        (let [r (api/ingest! sess 'w1.core
                             (str "(ns w1.core (:require [clojure.test :refer [deftest is]]))\n"
                                  "(defn triple [x] (* 3 x))\n"
                                  "(deftest triple-t (is (= 9 (triple 3))))\n"))]
          (is (= 3 (:forms r)))
          (is (= 1 (:pass (:test r))))
          (is (zero? (+ (:fail (:test r)) (:error (:test r)))))))
      (testing "red tests are reported (commit stands; compile failures don't commit)"
        (let [r (api/ingest! sess 'w1.red
                             (str "(ns w1.red (:require [clojure.test :refer [deftest is]]))\n"
                                  "(defn f [x] x)\n"
                                  "(deftest f-t (is (= 2 (f 1))))\n"))]
          (is (= 1 (:fail (:test r))))
          (is (seq (get-in r [:test :failures])))))
      (testing "overwriting an existing namespace is NOT allowed"
        (let [r (api/ingest! sess 'w1.core "(ns w1.core)\n(def replaced 1)\n")]
          (is (re-find #"already exists" (:error r)))
          (is (re-find #"triple" (api/query-source sess 'w1.core)))))
      (finally (api/close! sess)))))

(deftest ^:isolated create-ns-and-add-require                     ; F4 + F5
  (let [sess (api/open!)]
    (try
      (testing "create a namespace directly, with requires"
        (let [r (api/create-ns! sess 'fresh.core
                                :requires ["[clojure.test :refer [deftest is]]"])]
          (is (nil? (:error r)))
          (is (re-find #"\(ns fresh\.core" (api/query-source sess 'fresh.core)))
          (is (re-find #"clojure\.test" (api/query-source sess 'fresh.core)))))
      (testing "duplicate namespace rejected"
        (is (:error (api/create-ns! sess 'fresh.core))))
      (testing "add-require structurally extends the ns form and hot-reloads"
        (let [r (api/add-require! sess 'fresh.core "[clojure.string :as str]")]
          (is (nil? (:error r)))
          (is (re-find #"clojure\.string :as str" (api/query-source sess 'fresh.core)))
          ;; the alias is genuinely live in the image
          (api/add-form! sess 'fresh.core "(defn shout [s] (str/upper-case s))")
          (is (= ["HI"] (api/query-eval sess "(fresh.core/shout \"hi\")")))))
      (testing "duplicate require rejected"
        (is (:error (api/add-require! sess 'fresh.core "[clojure.string :as up]"))))
      (testing "ns without a :require clause gains one"
        (api/create-ns! sess 'bare.core)
        (let [r (api/add-require! sess 'bare.core "[clojure.set :as cset]")]
          (is (nil? (:error r)))
          (api/add-form! sess 'bare.core "(defn u [a b] (cset/union a b))")
          (is (= [#{1 2}] (api/query-eval sess "(bare.core/u #{1} #{2})")))))
      (finally (api/close! sess)))))

(deftest ^:isolated warnings-report-only-whats-new                ; T3
  (let [sess (api/open!)]
    (try
      (api/create-ns! sess 'w.core)
      (testing "first violation reported in full"
        (let [r (api/add-form! sess 'w.core "(defn stash \"Stash.\" [a v] (reset! a v))")]
          (is (= ['w.core/stash] (mapv :var (:warnings r))))))
      (testing "an unrelated green write doesn't repeat it — just counts it"
        (let [r (api/add-form! sess 'w.core "(defn pure-f \"Id.\" [x] x)")]
          (is (empty? (:warnings r)))
          (is (= 1 (:existing-warnings r)))))
      (finally (api/close! sess)))))

(deftest ^:isolated failed-namespace-load-is-not-silently-committed   ; T4
  (let [sess (api/open!)]
    (try
      (testing "requiring a not-yet-created store ns fails loudly, nothing committed"
        (let [r (api/create-ns! sess 'dep.user :requires ["[dep.lib :as lib]"])]
          (is (:error r))
          (is (nil? (get-in (:store @sess) [:namespaces 'dep.user])))))
      (testing "after creating the dependency, it works"
        (api/create-ns! sess 'dep.lib)
        (is (nil? (:error (api/create-ns! sess 'dep.user
                                          :requires ["[dep.lib :as lib]"])))))
      (finally (api/close! sess)))))

(deftest ^:isolated query-eval-is-observe-only                    ; T5
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'g.core "(ns g.core)\n(defn f [x] x)\n")
      (testing "definitions and code mutation are rejected — use edit tools"
        (is (:error (api/query-eval sess "(def sneaky 1)")))
        (is (:error (api/query-eval sess "(in-ns 'g.core)")))
        (is (:error (api/query-eval sess "(ns-unmap 'g.core 'f)")))
        (is (:error (api/query-eval sess "(do (defn g [] 1) (g))"))))
      (testing "banned tokens as QUOTED DATA are observation (position-aware gate)"
        (is (= [2] (api/query-eval sess "(count '#{defn defmacro})")))
        (is (= [true] (api/query-eval sess "(contains? '#{defn} 'defn)"))))
      (testing "observation — including calling effectful fns — still works"
        (is (= [3] (api/query-eval sess "(g.core/f 3)")))
        (is (= [1] (api/query-eval sess "(let [a (atom 0)] (swap! a inc))"))))
      (finally (api/close! sess)))))
(deftest spawning-tests-must-be-isolated
  (let [store (store/ingest (store/empty-store) 'iso.demo-test
                            (str "(ns iso.demo-test (:require [clojure.test :refer [deftest is]] [slopp.api :as api]))\n"
                                 "(defn setup [] 1)\n"))]
    (testing "an untagged deftest calling api/open! is refused, fix named (Q7)"
      (let [r (edit/replace-form store 'iso.demo-test 'setup
                                 "(deftest t (is (some? (api/open!))))")]
        (is (:error r) (pr-str r))
        (is (re-find #"\^:isolated" (str (:error r))) (pr-str r))))
    (testing "the ^:isolated tag admits it"
      (let [r (edit/replace-form store 'iso.demo-test 'setup
                                 "(deftest ^:isolated t (is (some? (api/open!))))")]
        (is (nil? (:error r)) (pr-str r))))))
(deftest missing-form-errors-teach
  (let [store (store/ingest (store/empty-store) 'mf.core
                            "(ns mf.core)\n(defn compute-total [x] x)\n(defn compute-tax [x] x)\n")]
    (testing "a near-miss names the candidates (Q9)"
      (is (re-find #"compute-total"
                   (:error (edit/missing-form-error store 'mf.core 'compute)))))
    (testing "a cold miss points at the outline"
      (is (re-find #"query_source"
                   (:error (edit/missing-form-error store 'mf.core 'zzz)))))))
(deftest ^:isolated red-first-specs-land-as-red
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'rf.core "(ns rf.core)\n(defn seed \"S.\" [x] x)\n")
      (api/ingest! sess 'rf.core-test
                   (str "(ns rf.core-test (:require [rf.core :as c]\n"
                        "                           [clojure.test :refer [deftest is]]))\n"
                        "(deftest seed-t (is (= 1 (c/seed 1))))\n"))
      (testing "a spec referencing a NOT-YET-WRITTEN fn lands as a real red"
        (let [r (api/add-form! sess 'rf.core-test
                               "(deftest future-t (is (= 4 (c/future-fn 2))))"
                               :prompt "red first")]
          (is (nil? (:error r)) (pr-str r))
          (is (= ['rf.core/future-fn] (:red-first r)) (pr-str r))
          (is (pos? (+ (:fail (:test r) 0) (:error (:test r) 0)))
              "the red is a failing test, not a refusal")))
      (testing "implementing the fn turns the same spec green"
        (let [r (api/add-form! sess 'rf.core
                               "(defn future-fn \"Doubles.\" [x] (* 2 x))"
                               :prompt "green")]
          (is (nil? (:error r)) (pr-str r))
          (is (zero? (+ (:fail (:test r) 0) (:error (:test r) 0))) (pr-str (:test r)))))
      (finally (api/close! sess)))))
(deftest ^:isolated red-first-is-command-agnostic
  ;; the seam is the compile gate, not the command: EVERY write path that
  ;; loads a -test namespace inherits red-first — groups, whole-ns ingest,
  ;; :refer'd bare names, and image restarts with stubs outstanding
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'rg.core "(ns rg.core)\n(defn seed \"S.\" [x] x)\n")
      (api/ingest! sess 'rg.core-test
                   (str "(ns rg.core-test (:require [rg.core :as c]\n"
                        "                           [clojure.test :refer [deftest is]]))\n"
                        "(deftest seed-t (is (= 1 (c/seed 1))))\n"))
      (testing "a GROUP step spec lands red with the missing var named"
        (let [r (api/edit-group! sess
                                 [{:action :add :ns 'rg.core-test
                                   :source "(deftest dbl-t (is (= 4 (c/dbl 2))))"}]
                                 :prompt "red first via group")]
          (is (nil? (:error r)) (pr-str r))
          (is (= ['rg.core/dbl] (:red-first r)) (pr-str r))))
      (testing "a whole spec NS with a :refer to a missing fn lands red (ingest path)"
        (let [r (api/create-ns! sess 'rg.core.extra-test
                                :source (str "(ns rg.core.extra-test\n"
                                             "  (:require [rg.core :refer [trbl]]\n"
                                             "            [clojure.test :refer [deftest is]]))\n"
                                             "(deftest trbl-t (is (= 6 (trbl 2))))\n"))]
          (is (nil? (:error r)) (pr-str r))
          (is (= ['rg.core/trbl] (:red-first r)) (pr-str r))))
      (testing "a fresh image survives outstanding red-first stubs"
        (is (nil? (:error (api/restart! sess)))))
      (testing "implementing the fns turns the whole store green"
        (api/add-form! sess 'rg.core "(defn dbl \"Doubles.\" [x] (* 2 x))"
                       :prompt "green dbl")
        (let [r (api/add-form! sess 'rg.core "(defn trbl \"Triples.\" [x] (* 3 x))"
                               :prompt "green trbl")]
          (is (nil? (:error r)) (pr-str r))
          (is (zero? (+ (:fail (:test r) 0) (:error (:test r) 0)))
              (pr-str (:test r)))))
      (finally (api/close! sess)))))
(deftest ^:isolated incremental-signature-change-is-unforced
  ;; REPL-native flow: growing a signature one write at a time must not be
  ;; refused — stale callers are CARRIED to the done-point, not blockers;
  ;; an error inside the form being written still refuses
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'sg2.core
                   (str "(ns sg2.core)\n"
                        "(defn base \"B.\" [x] (* 2 x))\n"
                        "(defn ^:unused-ok caller \"C.\" [x] (base x))\n"))
      (testing "editing the defn's signature ALONE lands, stale callers carried"
        (let [r (api/edit-replace! sess 'sg2.core 'base
                                   "(defn base \"B.\" [x y] (* x y))"
                                   :prompt "grow the signature incrementally")]
          (is (nil? (:error r)) (pr-str r))
          (is (some #(= 'sg2.core/caller (:form %)) (:carried-errors r))
              (pr-str r))))
      (testing "an error IN the written form itself still refuses"
        (let [r (api/edit-replace! sess 'sg2.core 'caller
                                   "(defn caller \"C.\" [x] (base))"
                                   :prompt "zero-arity call in MY OWN form")]
          (is (:error r) (pr-str r))))
      (testing "catching the caller up, then done → clean lint"
        (api/edit-replace! sess 'sg2.core 'caller
                           "(defn ^:unused-ok caller \"C.\" [x] (base x 3))"
                           :prompt "caller catches up")
        (let [r (api/done! sess :label "signature change complete")]
          (is (empty? (filter #(= :error (:level %)) (:lint r)))
              (pr-str (:lint r)))))
      (testing "done with a STANDING stale caller reports it in findings"
        (api/edit-replace! sess 'sg2.core 'base
                           "(defn base \"B.\" [x y z] (* x y z))"
                           :prompt "grow again, forget the caller")
        (let [r (api/done! sess :label "left broken")]
          (is (pos? (get-in r [:findings :lint-errors] 0)) (pr-str r))))
      (finally (api/close! sess)))))
(deftest ^:isolated renaming-via-replace-refuses-when-callers-dangle
  ;; replacing a form with a DIFFERENTLY-NAMED one strands committed callers
  ;; on the old name — the store stops cold-loading (the self-hosting bind
  ;; in miniature). The write must refuse and teach the atomic tool.
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'rn.core
                   (str "(ns rn.core)\n\n"
                        "(defn helper \"H.\" [x] x)\n\n"
                        "(defn ^:unused-ok user \"U.\" [x] (helper x))\n"))
      (testing "a caller-stranding rename is refused with teaching"
        (let [r (api/edit-replace! sess 'rn.core 'helper
                                   "(defn assist \"H.\" [x] x)")]
          (is (re-find #"edit_rename" (str (:error r))) (pr-str r))
          (is (re-find #"rn\.core/user" (str (:error r))))))
      (testing "a rename with NO callers lands (leaf tidy-up)"
        (let [r (api/edit-replace! sess 'rn.core 'user
                                   "(defn ^:unused-ok consumer \"U.\" [x] (helper x))")]
          (is (nil? (:error r)) (pr-str r))))
      (finally (api/close! sess)))))
(deftest ^:isolated compile-failures-reach-the-agent-as-anchors
  ;; a write that fails to compile must hand the agent an ACTIONABLE
  ;; address — the owning form + a match-ready snippet — not a VFS
  ;; file:line no tool consumes; the coordinate never rides the message.
  ;; (Java interop is the genuine kondo-miss → raw compiler path.)
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'ca.core "(ns ca.core)\n\n(defn ok \"O.\" [x] x)\n")
      (let [r (api/edit-replace! sess 'ca.core 'ok
                                 "(defn ok \"O.\" [x] (String/noSuchStaticThing x))")]
        (is (:error r) (pr-str r))
        (is (= 'ca.core/ok (:form r)) "the owning form is named")
        (is (re-find #"noSuchStaticThing" (str (:at r))) "a match-ready snippet")
        (is (re-find #"No matching method" (str (:error r)))
            "the semantic message survives")
        (is (not (re-find #"\.clj:\d" (str (:error r))))
            "no file:line in the message"))
      (finally (api/close! sess)))))
(deftest ^:isolated forward-refs-auto-reorder-no-declare
  ;; the agent writes in any order; a forward reference is RESOLVED by the
  ;; pipeline reordering defs above callers — SILENTLY: no refusal, no declare,
  ;; and no ordering signal leaks back (order is store truth, not the agent's
  ;; concern). The reorder is proven by the store order flipping [a b] → [b a].
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'ar.core
                   "(ns ar.core)\n\n(defn a \"A.\" [x] x)\n\n(defn b \"B.\" [x] x)\n")
      (is (= '[ar.core a b] (mapv :name (store/forms (:store @sess) 'ar.core)))
          "precondition: a defined before b")
      (testing "editing `a` to call `b` (defined below) auto-reorders, not refuses"
        (let [r (api/edit-replace! sess 'ar.core 'a "(defn a \"A.\" [x] (b x))")]
          (is (nil? (:error r)) (pr-str r))
          (is (nil? (:reordered r)) "the reorder is SILENT — no ordering key leaks to the agent")))
      (testing "b now precedes a; the ns cold-loads; no declare"
        (is (= '[ar.core b a] (mapv :name (store/forms (:store @sess) 'ar.core)))
            "the def was moved above its caller")
        (is (nil? (edit/cold-load-errors (:store @sess) '[ar.core])))
        (is (not (re-find #"\(declare" (api/query-source sess 'ar.core)))))
      (finally (api/close! sess)))))
(deftest ^:isolated add-caller-before-callee-auto-reorders
  ;; the .ideas motivating case: the agent adds a caller anchored ABOVE the
  ;; callee it references. That is a forward ref — the pipeline reorders the
  ;; callee above the caller silently, no declare, no refusal.
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'cc.core "(ns cc.core)\n\n(defn callee \"C.\" [x] (inc x))\n")
      (testing "adding a caller :before its callee resolves the forward ref"
        (let [r (api/add-form! sess 'cc.core "(defn caller \"C.\" [x] (callee x))"
                               :before 'callee)]
          (is (nil? (:error r)) (pr-str r))
          (is (nil? (:reordered r)) "silent — no ordering key leaks")))
      (testing "callee precedes caller; cold-loads; no declare"
        (is (= '[cc.core callee caller] (mapv :name (store/forms (:store @sess) 'cc.core))))
        (is (nil? (edit/cold-load-errors (:store @sess) '[cc.core])))
        (is (not (re-find #"\(declare" (api/query-source sess 'cc.core)))))
      (finally (api/close! sess)))))
(deftest ^:isolated genuine-cycle-auto-declares-with-marker
  ;; mutual recursion has no legal form order — the pipeline OWNS the declare:
  ;; it inserts a MARKED (declare …) itself so the ns cold-loads. The agent
  ;; never writes one, and no declare key leaks back (like the reorder).
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'cy.core "(ns cy.core)\n(defn ping [n] n)\n(defn pong [n] (ping n))\n")
      (testing "editing ping into mutual recursion auto-declares (no refusal)"
        (let [r (api/edit-replace! sess 'cy.core 'ping "(defn ping [n] (pong n))"
                                   :prompt "cyc")]
          (is (nil? (:error r)) (pr-str r))
          (is (nil? (:declared r)) "silent — no declare key leaks to the agent")))
      (testing "the ns cold-loads via an auto-inserted, marked declare"
        (is (nil? (edit/cold-load-errors (:store @sess) '[cy.core])))
        (let [src  (api/query-source sess 'cy.core)
              decl (re-find #"\(declare[^)]*\)" src)]
          (is (re-find #":auto-declare" src) "the declare carries the marker/why")
          (is (and decl (re-find #"ping" decl)) "ping is declared")
          (is (and decl (re-find #"pong" decl)) "pong is declared")))
      (finally (api/close! sess)))))
(deftest ^:isolated hand-written-declares-are-refused
  ;; the pipeline OWNS declares — an agent never writes one. A hand-written
  ;; (declare …) on the EDIT path is refused with teaching; imports (ingest)
  ;; and the pipeline's own inserts are unaffected.
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'nd.core "(ns nd.core)\n(defn a [] 1)\n")
      (testing "add_form of a bare declare is refused with teaching"
        (let [r (api/add-form! sess 'nd.core "(declare later)" :prompt "x")]
          (is (:error r))
          (is (re-find #"declare" (str (:error r))))
          (is (re-find #"order" (str (:error r)))
              "teaches that ordering is automatic")))
      (testing "ingest of ported code containing a declare is still allowed"
        (let [r (api/ingest! sess 'nd.imp
                             "(ns nd.imp)\n(declare h)\n(defn f [] (h))\n(defn h [] 2)\n")]
          (is (nil? (:error r)) (pr-str r))))
      (finally (api/close! sess)))))
(deftest ^:isolated inline-run-defers-isolated-tests
  ;; ^:isolated tests only behave in a FRESH image — the in-image per-write
  ;; run must never execute them (a false green/red); it reports them
  ;; :isolated-pending. The whole-ns fallback (no trace) is where they leak in.
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'ir.test
                   (str "(ns ir.test (:require [clojure.test :refer [deftest is]]))\n"
                        "(deftest fast (is true))\n"))
      (let [r (api/add-form! sess 'ir.test "(deftest ^:isolated slow (is true))")]
        (is (some #{'slow} (:tests (:isolated-pending (:test r))))
            (str "slow must be deferred, not run in-image: " (pr-str (:test r)))))
      (finally (api/close! sess)))))

(deftest ^:isolated a-write-reports-what-it-changed-that-you-did-not-name
  ;; A refactor silently dropped ^Repository and ^java.sql.Connection from a
  ;; destructuring, turning direct interop into reflection. It compiled, passed
  ;; every gate, and reported green — I found it only because I happened to
  ;; re-read the form. Re-reading after every write is compensation for the
  ;; write result not saying what it did.
  ;;
  ;; A replacement that quietly changes the CONTRACT — losing type hints or a
  ;; docstring, changing arity — should say so. Not refuse: each is a
  ;; legitimate intentional edit. Just never silent.
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'dw.core
                   (str "(ns dw.core)\n\n"
                        "(defn f\n  \"Has a docstring.\"\n"
                        "  [{:keys [^String a]} b] [a b])\n"))
      (testing "losing a type hint is reported"
        (let [r (api/edit-replace! sess 'dw.core 'f
                                   "(defn f\n  \"Has a docstring.\"\n  [{:keys [a]} b] [a b])"
                                   :prompt "drop the hint")]
          (is (nil? (:error r)) (pr-str r))
          (is (some #(= :metadata-lost (:kind %)) (:drift r)) (pr-str r))))
      (testing "losing a docstring is reported"
        (let [r (api/edit-replace! sess 'dw.core 'f
                                   "(defn f [{:keys [a]} b] [a b])"
                                   :prompt "drop the docstring")]
          (is (some #(= :docstring-lost (:kind %)) (:drift r)) (pr-str r))))
      (testing "changing arity is reported"
        (let [r (api/edit-replace! sess 'dw.core 'f
                                   "(defn f [{:keys [a]}] a)"
                                   :prompt "drop an arg")]
          (is (some #(= :arity-changed (:kind %)) (:drift r)) (pr-str r))))
      (testing "an ordinary edit reports no drift at all"
        (let [r (api/edit-replace! sess 'dw.core 'f
                                   "(defn f [{:keys [a]}] (identity a))"
                                   :prompt "same contract")]
          (is (empty? (:drift r)) (pr-str r))))
      (finally (api/close! sess)))))
