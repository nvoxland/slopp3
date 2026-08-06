(ns slopp.edit.refactor-test
  "The refactor PLANNERS, asserted as pure analysis over ingested stores —
  move-forms (external callers, dependency direction, selective requires,
  refusals with teaching), module-extract, export-changeset, the `$n`
  templating, and realias. The executors live behind `slopp.ops` and are
  covered end to end elsewhere — `surgeon-test` for moves, `chsig-test` for
  signatures, `realias-test` for aliases; here the plans are cheap to assert
  and a fixture is three lines of source text."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.edit.refactor :as refactor]
            [slopp.store :as store] [clojure.string :as str] [rewrite-clj.node :as n]))

(defn- fixture-store
  "mv.core defines a private util + a public mid + entry; mv.app (another
  module) and mv.core-test both call across; mv.other aliases nothing."
  []
  (-> (store/empty-store)
      (store/ingest 'mv.core
                    (str "(ns mv.core (:require [clojure.string :as str]))\n\n"
                         "(defn- util \"U.\" [x] (str/trim x))\n\n"
                         "(defn mid \"M.\" [x] (util x))\n\n"
                         "(defn entry \"E.\" [x] (mid x))\n"))
      (store/ingest 'mv.app
                    (str "(ns mv.app (:require [mv.core :as core]))\n\n"
                         "(defn go \"G.\" [x] (core/mid x))\n"))
      (store/ingest 'mv.core-test
                    (str "(ns mv.core-test (:require [mv.core :as core]\n"
                         "                           [clojure.test :refer [deftest is]]))\n\n"
                         "(deftest mid-t (is (= \"a\" (core/mid \" a \"))))\n"))))

(deftest plan-rewrites-external-callers-and-selects-requires
  ;; the v1 killer: moving a form with callers in OTHER namespaces. The plan
  ;; must rewrite every caller (prod + test) to the new alias, inject the
  ;; require where missing, and give the new ns ONLY the requires the moved
  ;; forms use.
  (let [st (fixture-store)
        p  (refactor/move-plan st 'mv.core '[util mid] 'mv.helpers {})]
    (is (nil? (:error p)) (pr-str (:error p)))
    (testing "the new ns carries only what the moved forms use"
      (is (:new-ns? p))
      (is (re-find #"\[clojure\.string :as str\]" (:new-src p)))
      (is (not (re-find #"clojure\.test" (:new-src p))))
      (is (re-find #"defn util" (:new-src p)) "privates publicized"))
    (testing "every caller ns is rewritten to the new alias"
      (let [rewritten (set (map :ns (vals (:rewrites p))))]
        (is (contains? rewritten 'mv.core) "entry calls mid")
        (is (contains? rewritten 'mv.app))
        (is (contains? rewritten 'mv.core-test)))
      (is (some #(re-find #"helpers/mid" (:src %)) (vals (:rewrites p)))))
    (testing "callers gain the require; from-ns keeps none it doesn't need"
      (is (= "[mv.helpers :as helpers]"
             (get-in p [:require-adds 'mv.app])
             (get-in p [:require-adds 'mv.core-test])
             (get-in p [:require-adds 'mv.core]))
          (pr-str (:require-adds p))))
    (testing "module rows ride out for the executor's gate check"
      (is (some #(and (= 'mv.app (:from-ns %)) (= 'mv.helpers (:to %)))
                (:module-rows p))))))

(deftest plan-analyzes-dependency-direction
  (let [st (fixture-store)]
    (testing "stay→moved: from-ns requires the new ns back (the v1 case)"
      (let [p (refactor/move-plan st 'mv.core '[util mid] 'mv.helpers {})]
        (is (= "[mv.helpers :as helpers]" (get-in p [:require-adds 'mv.core])))))
    (testing "moved→stay: the new ns requires from-ns; bare refs to PUBLIC
              stay-behinds become qualified"
      (let [p (refactor/move-plan st 'mv.core '[entry] 'mv.front {})]
        (is (nil? (:error p)) (pr-str (:error p)))
        (is (re-find #"\[mv\.core :as core\]" (:new-src p)))
        (is (re-find #"core/mid" (:new-src p))
            "entry's bare (mid x) is qualified in its new home")
        (is (nil? (get-in p [:require-adds 'mv.core]))
            "nothing left behind calls entry — no require back")))
    (testing "moved forms calling PRIVATE stay-behinds refuse with teaching"
      (let [p (refactor/move-plan st 'mv.core '[mid] 'mv.front {})]
        (is (re-find #"util" (str (:error p))))
        (is (re-find #"move|public" (str (:error p))))))
    (testing "a two-way split refuses, naming both directions"
      ;; move util+entry: util is called by staying mid; entry calls staying mid
      (let [p (refactor/move-plan st 'mv.core '[util entry] 'mv.front {})]
        (is (:error p))))))

(deftest plan-handles-existing-targets-refers-and-export
  (let [st (fixture-store)]
    (testing "moving into an EXISTING ns appends there instead of creating"
      (let [st2 (store/ingest st 'mv.extra "(ns mv.extra)\n\n(defn spare \"S.\" [x] x)\n")
            p   (refactor/move-plan st2 'mv.core '[util mid] 'mv.extra {})]
        (is (nil? (:error p)) (pr-str (:error p)))
        (is (not (:new-ns? p)))
        (is (= 2 (count (:append p)))
          "just the publicized nodes — the planner mints no declare")
        (is (= ["[clojure.string :as str]"] (:to-require-adds p))
            "the existing target gains only what the moved forms need")))
    (testing "a name collision in the target refuses"
      (let [st2 (store/ingest st 'mv.extra "(ns mv.extra)\n\n(defn mid \"S.\" [x] x)\n")
            p   (refactor/move-plan st2 'mv.core '[util mid] 'mv.extra {})]
        (is (re-find #"mid" (str (:error p))))))
    (testing ":refer'd moved names refuse with the exact ns named"
      (let [st2 (store/ingest st 'mv.refuser
                              (str "(ns mv.refuser (:require [mv.core :refer [mid]]))\n\n"
                                   "(defn use-it \"R.\" [x] (mid x))\n"))
            p   (refactor/move-plan st2 'mv.core '[util mid] 'mv.helpers {})]
        (is (re-find #"mv\.refuser" (str (:error p))))
        (is (re-find #"refer" (str (:error p))))))
    (testing "export: true marks moved vars ^:export in the new source"
      (let [p (refactor/move-plan st 'mv.core '[util mid] 'mv.core.impl
                                  {:export true})]
        (is (re-find #"\^:export" (:new-src p)) (:new-src p))))
    (testing "a string export scopes the hoist to that subtree only"
      (let [p (refactor/move-plan st 'mv.core '[util mid] 'mv.core.impl
                                  {:export "mv.app"})]
        (is (re-find #"\{:export \"mv\.app\"\}" (:new-src p)) (:new-src p))))))

(deftest plan-dequalifies-refs-into-the-target
  ;; moving a form INTO a namespace it already calls: its alias-qualified
  ;; refs to the target must become BARE names, or the moved source can't
  ;; compile in its new home (the target ns gets no self-alias).
  (let [st (-> (store/empty-store)
               (store/ingest 'dq.base "(ns dq.base)\n\n(defn ground \"G.\" [x] x)\n")
               (store/ingest 'dq.mid
                             (str "(ns dq.mid (:require [dq.base :as base]))\n\n"
                                  "(defn lift \"L.\" [x] (base/ground x))\n")))
        p  (refactor/move-plan st 'dq.mid '[lift] 'dq.base {})]
    (is (nil? (:error p)) (pr-str (:error p)))
    (let [moved-src (apply str (map str (:append p)))]
      (is (re-find #"\(ground x\)" moved-src)
          (str "qualified ref must go bare: " moved-src))
      (is (not (re-find #"base/ground" moved-src))))))

(deftest plan-mints-no-declare-ordering-is-the-pipelines
  ;; A source ns may order callers before definitions behind a declare that
  ;; STAYS BEHIND (it's anonymous, never in the moved set), so the moved set
  ;; CAN land with a forward ref. The planner used to mint its own
  ;; (declare ...) for that, reasoning "over-declaring is harmless". It is
  ;; NOT harmless: a later move that lifts one of those names out leaves a
  ;; PHANTOM — declared here, defined nowhere, minting an unbound var so a
  ;; typo'd unqualified call resolves silently. That is exactly how
  ;; slopp.api's own 17-name declare rotted (7 phantoms).
  ;; Ordering belongs to the pipeline: move-forms! calls resolve-cold-load on
  ;; the target, which REORDERS (no declare at all) or inserts its own MARKED
  ;; declare for a genuine cycle. See surgeon-test/
  ;; move-forms-leaves-ordering-to-the-pipeline for the end-to-end proof.
  (let [st (-> (store/empty-store)
               (store/ingest 'fw.core
                             (str "(ns fw.core)\n\n(declare helper)\n\n"
                                  "(defn run \"R.\" [x] (helper x))\n\n"
                                  "(defn helper \"H.\" [x] x)\n")))
        p  (refactor/move-plan st 'fw.core '[run helper] 'fw.moved {})]
    (is (nil? (:error p)) (pr-str (:error p)))
    (let [src (:new-src p)]
      (is (not (re-find #"\(declare" src))
          (str "the planner must mint NO declare:\n" src))
      (is (re-find #"defn run" src) src)
      (is (re-find #"defn helper" src) src))))

(deftest plan-publicizes-meta-wrapped-privates
  ;; effect-tagged forms (^:reads / ^:unsafe on the WHOLE defn) wrap the
  ;; list in a :meta node — publicize/export-mark must transform the defn
  ;; underneath and keep the wrapper.
  (let [st (-> (store/empty-store)
               (store/ingest 'mw.core
                             (str "(ns mw.core)\n\n"
                                  "^:reads (defn- peek* \"P.\" [x] x)\n\n"
                                  "(defn use-it \"U.\" [x] (peek* x))\n")))
        p  (refactor/move-plan st 'mw.core '[peek*] 'mw.deep {:export true})]
    (is (nil? (:error p)) (pr-str (:error p)))
    (is (re-find #"\(defn \^:export peek\*" (:new-src p)) (:new-src p))
    (is (re-find #"\^:reads" (:new-src p)) "the effect tag survives the move")))

(deftest plan-exports-meta-wrapped-names
  ;; (def ^:dynamic *hook* ...) — the NAME is a :meta node, not a :token;
  ;; export-mark must stack the export onto it, not silently skip (the
  ;; pre-commit hook moved unexported this way and left standing debt).
  (let [st (-> (store/empty-store)
               (store/ingest 'mh.core
                             (str "(ns mh.core)\n\n"
                                  "(def ^:dynamic *hook* \"H.\" nil)\n\n"
                                  "(defn fire \"F.\" [] *hook*)\n")))
        p  (refactor/move-plan st 'mh.core '[*hook*] 'mh.core.impl
                               {:export "mh.watchers"})]
    (is (nil? (:error p)) (pr-str (:error p)))
    (is (re-find #"\{:export \"mh\.watchers\"\}" (:new-src p)) (:new-src p))
    (is (re-find #"\^:dynamic" (:new-src p)) "the dynamic marker survives")))

(deftest plan-copies-imports-the-moved-code-uses
  ;; interop code imports classes; a moved form using them must carry the
  ;; matching (:import ...) entries — selectively, by simple name: static
  ;; calls, ctors, bare references, AND type hints (the git.client move
  ;; failed on a ^Repository hint).
  (let [st (-> (store/empty-store)
               (store/ingest 'imp.core
                             (str "(ns imp.core (:import [java.util UUID Random Date]"
                                  " [java.io File]))\n\n"
                                  "(defn fresh-id \"F.\" [] (str (UUID/randomUUID)))\n\n"
                                  "(defn as-file \"A.\" [^Random r p] (File. (str p)))\n\n"
                                  "(defn plain \"P.\" [x] x)\n")))
        p  (refactor/move-plan st 'imp.core '[fresh-id as-file] 'imp.ids {})]
    (is (nil? (:error p)) (pr-str (:error p)))
    (is (re-find #"\(:import \[java\.io File\] \[java\.util Random UUID\]\)"
                 (:new-src p))
        (:new-src p))
    (is (not (re-find #"Date" (:new-src p)))
        "unused classes don't ride")))

(deftest requalify-call-args-is-scoped-to-one-fns-first-argument
  ;; The reason a keyword sweep cannot do this: :dir means three different
  ;; things in this store. Only the map passed as arg 1 to THIS fn is ours.
  (let [src (str "(ns c.core)\n"
                 "(defn a [] (api/open! {:dir \"x\"}))\n"
                 "(defn b [] (api/open! {:dir \"y\" :warm-spare? true}))\n"
                 "(defn c [] (db/open! {:dir \"z\"}))\n"
                 "(defn d [] (other! {:dir \"w\"}))\n"
                 "(defn e [] {:dir \"loose\"})\n"
                 "(defn f [m] (api/open! m))\n"
                 "(defn g [] (api/open!))\n")
        out (refactor/requalify-call-args src #{"api/open!"} "dir" "slopp.api")]
    (testing "the target fn's first-arg literals are qualified"
      (is (re-find #"\(api/open! \{:slopp\.api/dir \"x\"\}\)" out) out)
      (is (re-find #"\{:slopp\.api/dir \"y\" :warm-spare\? true\}" out) out))
    (testing "a SAME-NAMED fn in another namespace is untouched — the bug a
              dry-run caught by reporting 62 forms where the graph said 60"
      (is (re-find #"\(db/open! \{:dir \"z\"\}\)" out) out))
    (testing "another fn's identically-spelled key is untouched"
      (is (re-find #"\(other! \{:dir \"w\"\}\)" out) out))
    (testing "and a bare map that is nobody's argument is untouched"
      (is (re-find #"\(defn e \[\] \{:dir \"loose\"\}\)" out) out))
    (testing "a non-literal or absent argument is left alone, not corrupted"
      (is (re-find #"\(api/open! m\)" out) out)
      (is (re-find #"\(api/open!\)" out) out))
    (testing "nothing else in the source moved"
      (is (= (count (str/split-lines src)) (count (str/split-lines out)))))))

(deftest plan-survives-an-unresolvable-callee
  ;; kondo marks a call it cannot resolve with the KEYWORD sentinel
  ;; :clj-kondo/unknown-namespace as the usage's :to — a proxy method body is
  ;; the common source. That :to flowed into needed-libs, which is sorted, and
  ;; `compare` threw "Symbol cannot be cast to Keyword". slopp.api/open! was
  ;; the store's only proxy and so the only form that could not be moved.
  ;; A non-symbol :to is never a library to require.
  ;;
  ;; The second require is LOAD-BEARING: with clojure.core removed the
  ;; sentinel was the only element left, and sorting one element never calls
  ;; compare — the first version of this test passed over a live bug.
  (let [st (-> (store/empty-store)
               (store/ingest 'px.core
                             (str "(ns px.core\n"
                                  "  (:require [clojure.string :as str]))\n\n"
                                  "(defn spin \"S.\" [t]\n"
                                  "  (.schedule t (proxy [java.util.TimerTask] []\n"
                                  "                 (run [] (str/upper-case \"x\")))\n"
                                  "               1000 1000))\n")))
        p  (refactor/move-plan st 'px.core '[spin] 'px.moved {})]
    (is (nil? (:error p)) (pr-str (:error p)))
    (is (re-find #"defn spin" (:new-src p)) (:new-src p))
    (testing "the unresolvable callee is not mistaken for a library to require"
      (is (not (re-find #"clj-kondo" (:new-src p))) (:new-src p)))))

(deftest plan-drops-requires-the-move-orphans
  ;; Sequential-move artifact: `f` leaves and takes the last reference to
  ;; clojure.string with it, orphaning the require. The cold-load gate refuses
  ;; the resulting state, so this cost a hand-fix every time. The move should
  ;; clean up after ITSELF.
  ;;
  ;; Scope is deliberate: only libs the MOVED forms were the last users of.
  ;; Pruning every unused require would happily drop one kept for side effects
  ;; (defmethod registration), which kondo cannot distinguish.
  (let [st (-> (store/empty-store)
               (store/ingest 'dr.core
                             (str "(ns dr.core\n"
                                  "  (:require [clojure.string :as str]\n"
                                  "            [clojure.set :as set]))\n\n"
                                  "(defn f \"F.\" [x] (str/upper-case x))\n\n"
                                  "(defn g \"G.\" [a b] (set/union a b))\n")))
        p  (refactor/move-plan st 'dr.core '[f] 'dr.moved {})]
    (is (nil? (:error p)) (pr-str (:error p)))
    (testing "the lib only the moved form used is dropped"
      (is (= '[clojure.string] (:from-require-drops p))))
    (testing "a lib the stay-behinds still use is kept"
      (is (not (contains? (set (:from-require-drops p)) 'clojure.set))))))

(deftest plan-drops-a-callers-require-when-nothing-is-left
  ;; The mirror of plan-drops-requires-the-move-orphans. A caller is rewritten
  ;; to the new home and gains that require — but if the moved forms were the
  ;; ONLY things it used from the source namespace, the old require is now
  ;; dead weight, and a :pure caller keeps inheriting the source namespace's
  ;; tier for a dependency it no longer has. That is exactly what kept
  ;; slopp.refactor / .edit.modules / .edit.refs / .api.query from layering
  ;; after the analysis split: four namespaces requiring slopp.index while
  ;; using nothing from it.
  (let [st (-> (store/empty-store)
               (store/ingest 'cr.src
                             (str "(ns cr.src)\n\n"
                                  "(defn moved \"M.\" [x] (inc x))\n\n"
                                  "(defn stays \"S.\" [x] (dec x))\n"))
               (store/ingest 'cr.only
                             (str "(ns cr.only\n  (:require [cr.src :as src]))\n\n"
                                  "(defn a \"A.\" [x] (src/moved x))\n"))
               (store/ingest 'cr.both
                             (str "(ns cr.both\n  (:require [cr.src :as src]))\n\n"
                                  "(defn b \"B.\" [x] (+ (src/moved x) (src/stays x)))\n")))
        p  (refactor/move-plan st 'cr.src '[moved] 'cr.dest {})]
    (is (nil? (:error p)) (pr-str (:error p)))
    (testing "a caller left using nothing from the source drops its require"
      (is (= '[cr.only] (:caller-require-drops p))))
    (testing "a caller that still uses a stay-behind keeps it"
      (is (not (contains? (set (:caller-require-drops p)) 'cr.both))))))

(deftest plan-refuses-an-empty-move
  ;; move-plan with [] passed every check (nothing missing, no collisions, no
  ;; cycles — vacuously) and planned a brand-new EMPTY target namespace that
  ;; the executor would happily commit.
  (let [s (store/ingest (store/empty-store) 'mv.solo
                        "(ns mv.solo)\n(defn ^:unused-ok u \"D.\" [] 1)\n")
        p (refactor/move-plan s 'mv.solo [] 'mv.ghost {})]
    (is (:error p) (pr-str p))))

(deftest symbol-mention-re-handles-non-word-boundaries
  (testing "a trailing-? name is matched as a whole token"
    (is (re-find (refactor/symbol-mention-re "valid?") "check (valid? x) here"))
    (is (re-find (refactor/symbol-mention-re "valid?") "the docstring mentions valid? plainly")))
  (testing "a leading-punctuation name is matched"
    (is (re-find (refactor/symbol-mention-re "->row") "call (->row m) now")))
  (testing "an ordinary hyphenated name still matches"
    (is (re-find (refactor/symbol-mention-re "bulk-rate") "\"bulk-rate applies\"")))
  (testing "it does NOT match inside a larger symbol token"
    (is (nil? (re-find (refactor/symbol-mention-re "rate") "bulk-rate")))
    (is (nil? (re-find (refactor/symbol-mention-re "valid") "valid?")))))

(deftest module-extract-plan-names-what-must-be-exported
  ;; Pulling ex.helper under ex.core makes it a DEEP namespace (3 segments),
  ;; so callers outside ex.core.* lose visibility unless the var is hoisted.
  ;; ns_rename rewrites every reference correctly and leaves exactly these
  ;; vars as module-gate violations; the plan must name them, and NAME WHO
  ;; forces each one, before a single write lands.
  (let [st (-> (store/empty-store)
               (store/ingest 'ex.helper
                             (str "(ns ex.helper)\n\n"
                                  "(defn shared \"S.\" [x] x)\n\n"
                                  "(defn local \"L.\" [x] x)\n"))
               (store/ingest 'ex.core
                             (str "(ns ex.core (:require [ex.helper :as h]))\n\n"
                                  "(defn a \"A.\" [x] (h/local x))\n"))
               (store/ingest 'ex.other
                             (str "(ns ex.other (:require [ex.helper :as h]))\n\n"
                                  "(defn b \"B.\" [x] (h/shared x))\n")))
        p  (refactor/module-extract-plan st '[ex.helper] 'ex.core)]
    (is (nil? (:error p)) (pr-str (:error p)))
    (is (= {'ex.helper 'ex.core.helper} (:renames p)))
    (testing "only the var an OUTSIDE caller reaches needs hoisting"
      (is (= #{'shared} (set (map :name (:exports p))))
          (pr-str (:exports p))))
    (testing "the plan says who forces the hoist, not just that one is needed"
      (is (= ['ex.other/b] (:forced-by (first (:exports p))))))
    (testing "the edge the extraction necessitates is named too"
      (is (some #{["ex.other" "ex.core"]} (:edges-add p))
          (pr-str (:edges-add p))))))

(deftest module-extract-plan-judges-cycles-on-production-edges-only
  ;; cy.helper-test needs cy.app as a fixture, so folding -test into its
  ;; subject's module puts cy.helper → cy.app in the DECLARED manifest while
  ;; cy.app → cy.helper is the real production edge. Pulling cy.helper under
  ;; cy.app must NOT read that as a cycle — it is the same false positive
  ;; store/module-layers already excludes, and judging it on the declared
  ;; manifest refused the whole slopp.store regroup.
  (let [st (-> (store/empty-store)
               (store/ingest 'cy.helper
                             "(ns cy.helper)\n\n(defn shared \"S.\" [x] x)\n")
               (store/ingest 'cy.app
                             (str "(ns cy.app (:require [cy.helper :as h]))\n\n"
                                  "(defn run \"R.\" [x] (h/shared x))\n"))
               (store/ingest 'cy.helper-test
                             (str "(ns cy.helper-test"
                                  " (:require [cy.app :as app] [cy.helper :as h]))\n\n"
                                  "(defn fixture \"F.\" [] (app/run (h/shared 1)))\n")))
        p  (refactor/module-extract-plan st '[cy.helper] 'cy.app)]
    (is (nil? (:error p))
        (str "a test-only back-edge is not a production cycle: " (:error p)))
    (is (= 'cy.app.helper (get (:renames p) 'cy.helper)))
    (testing "the test namespace rides along with its subject"
      (is (= 'cy.app.helper-test (get (:renames p) 'cy.helper-test))))))

(deftest export-changeset-hoists-only-the-named-vars
  ;; The write half of a module regroup: the planner names the vars that lose
  ;; visibility, this turns that list into a changeset. Two edges matter — a
  ;; name that is already meta-wrapped (the marker stacks) and a name that
  ;; already carries :export (no double-marking, and no delta churn).
  (let [st  (-> (store/empty-store)
                (store/ingest 'ex.h
                              (str "(ns ex.h)\n\n"
                                   "(defn shared \"S.\" [x] x)\n\n"
                                   "(defn ^:dynamic *hooked* \"H.\" [] 1)\n\n"
                                   "(defn ^:export already \"A.\" [x] x)\n\n"
                                   "(defn untouched \"U.\" [x] x)\n")))
        cs  (refactor/export-changeset
             st '[{:ns ex.h :name shared} {:ns ex.h :name *hooked*}
                  {:ns ex.h :name already}])
        src (fn [nm] (some (fn [[fid node]]
                             (when (= fid (:id (store/form-named st 'ex.h nm)))
                               (n/string node)))
                           cs))]
    (testing "a var that is already exported contributes no change"
      (is (= 2 (count cs)) (pr-str (map first cs)))
      (is (nil? (src 'already))))
    (is (re-find #"\(defn \^:export shared" (src 'shared)) (src 'shared))
    (testing "the marker stacks on an already meta-wrapped name"
      (is (re-find #"\^:export \^:dynamic \*hooked\*" (src '*hooked*))
          (src '*hooked*)))
    (testing "nothing else is touched"
      (is (nil? (src 'untouched))))))

(deftest export-changeset-marks-through-a-top-level-meta-wrapper
  ;; A form written `^:reads (defn f ...)` is a top-level :meta node whose
  ;; children are a keyword and a list — no symbol token among them. Scanning
  ;; those children for the defn head finds nothing, and the marker walk NPEs
  ;; rather than refusing. slopp.db/load-store is exactly this shape, so the
  ;; regroup that motivated this tripped on it immediately; edit_move_forms
  ;; {export true} moving such a form has the same bug.
  (let [st  (-> (store/empty-store)
                (store/ingest 'mw.h
                              (str "(ns mw.h)\n\n"
                                   "^:reads (defn wrapped \"W.\" [x] x)\n")))
        cs  (refactor/export-changeset st '[{:ns mw.h :name wrapped}])
        src (n/string (second (first cs)))]
    (is (= 1 (count cs)))
    (testing "the outer marker survives and :export lands on the NAME"
      (is (re-find #"\^:reads" src) src)
      (is (re-find #"\(defn \^:export wrapped" src) src))))

(deftest fill-template-is-the-one-dollar-n-substitution
  ;; `change_signature` already templates call sites with `$1..$9`, inlined in
  ;; `rewrite-call-sites`. `wrap` needs the same thing, and a second copy of a
  ;; substitution rule is how two surfaces come to disagree about what `$1`
  ;; means — Pattern 2, four instances.
  (testing "positional substitution, the change_signature case"
    (is (= "(f b a)" (refactor/fill-template "(f $2 $1)" ["a" "b"]))))
  (testing "one hole used once, the wrap case"
    (is (= "(let [x 1] (inc y))"
           (refactor/fill-template "(let [x 1] $1)" ["(inc y)"]))))
  (testing "a hole used TWICE substitutes both"
    (is (= "(if p (f x) (f x))" (refactor/fill-template "(if p $1 $1)" ["(f x)"]))))
  (testing "a template with no hole comes back unchanged"
    ;; the CALLER decides whether that is an error — for wrap it is, for a
    ;; zero-arg call-site rewrite it is correct
    (is (= "(f)" (refactor/fill-template "(f)" []))))
  (testing "a hole with no argument is left alone rather than throwing"
    ;; change_signature checks arity separately and reports it as a finding;
    ;; silently dropping the text would be worse than leaving it visible
    (is (= "(f $3)" (refactor/fill-template "(f $3)" ["a"])))))

(deftest subform-wrap-nests-a-matched-form-in-a-template
  ;; The measured friction: introducing `(let [why (f r)] …)` around existing
  ;; code means matching a fragment that opens a delimiter it does not close,
  ;; which is correctly refused — so the only expressible edit was to restate
  ;; the whole enclosing form. Three times in one session, ~40 lines each.
  ;;
  ;; The transformation is NESTING, and the write verbs only expressed
  ;; replace-in-place and insert-beside.
  (let [st (store/ingest (store/empty-store) 'w.core
                         "(ns w.core)\n(defn f [r]\n  (swap! r inc)\n  r)\n")]
    (testing "the matched form lands inside the template at $1"
      (let [p (refactor/subform-replace-plan st 'w.core 'f "(swap! r inc)"
                                             "(let [n (count @r)] $1)" true)]
        (is (nil? (:error p)) (pr-str p))
        (is (str/includes? (:new-form-src p) "(let [n (count @r)] (swap! r inc))")
            (:new-form-src p))
        (is (str/includes? (:new-form-src p) "\n  r)")
            "the rest of the form is untouched")))
    (testing "a template with no $1 is REFUSED, not silently a plain replace"
      ;; without the hole the matched form would VANISH, which is a different
      ;; operation than the one asked for
      (let [p (refactor/subform-replace-plan st 'w.core 'f "(swap! r inc)"
                                             "(let [n 1] (swap! r inc))" true)]
        (is (:error p))
        (is (str/includes? (:error p) "$1") (:error p))))
    (testing "without the flag the same call is an ordinary replace"
      (let [p (refactor/subform-replace-plan st 'w.core 'f "(swap! r inc)"
                                             "(reset! r 0)" false)]
        (is (str/includes? (:new-form-src p) "(reset! r 0)"))
        (is (not (str/includes? (:new-form-src p) "swap!")))))
    (testing "a match that is not there refuses exactly as it always did"
      (let [p (refactor/subform-replace-plan st 'w.core 'f "(nope)"
                                             "(let [n 1] $1)" true)]
        (is (:error p))))))

(deftest a-module-row-names-the-moved-var-the-caller-actually-called
  ;; A row targeting the destination recorded only "this var calls to-ns" and
  ;; never WHICH moved var, so everything downstream had to guess. The export
  ;; postcondition looked up a var named nil, found nothing, and reported
  ;; every landed export as unlanded — 39 phantom problems on one move. And
  ;; the export flag could only ever be applied to the whole moved set at
  ;; once, so a move either widened visibility it was not asked to widen or
  ;; refused on a var that was already exported.
  ;;
  ;; The callee is in the reference row the whole time; it was dropped on the
  ;; way into the usage map.
  (let [st (fixture-store)
        p  (refactor/move-plan st 'mv.core '[util mid] 'mv.helpers {})
        to (filter #(= 'mv.helpers (:to %)) (:module-rows p))]
    (is (nil? (:error p)) (pr-str (:error p)))
    (is (seq to) (pr-str (:module-rows p)))
    (testing "every destination row names its callee"
      (is (every? :to-name to) (pr-str to)))
    (testing "and it is the MOVED var, not the caller's own name"
      (is (= '#{mid} (set (map :to-name to))) (pr-str to)))
    (testing "outside callers and the stay-behind alike"
      (is (= '#{[mv.app go] [mv.core-test mid-t] [mv.core entry]}
             (set (map (juxt :from-ns :from-var) to)))
          (pr-str to)))))

(defn- realias-store
  "`ra.dep` is the callee; `ra.user` calls it through the alias `dep` and
  also SHADOWS that spelling with a parameter, which is the case the rewrite
  must not touch."
  []
  (-> (store/empty-store)
      (store/ingest 'ra.dep "(ns ra.dep)\n\n(defn f \"F.\" [x] x)\n\n(defn g \"G.\" [x] x)\n")
      (store/ingest 'ra.user
                    (str "(ns ra.user (:require [ra.dep :as dep] [clojure.string :as str]))\n\n"
                         "(defn one \"O.\" [x] (dep/f x))\n\n"
                         "(defn two \"T.\" [dep] (str/join \",\" [(dep/g dep) dep]))\n"))))

(deftest realias-plan-moves-the-ns-form-and-every-qualified-site
  (let [p (refactor/realias-plan (realias-store) 'ra.user 'dep 'callee)]
    (is (nil? (:error p)))
    (testing "the ns form's :as is rewritten — the alias is DECLARED there, so
              a plan that rewrote only the call sites would leave a namespace
              that does not load, rather than one that merely reads oddly"
      (let [ns-step (first (filter #(= 'ra.user (:name %)) (:steps p)))]
        (is (some? ns-step))
        (is (re-find #"\[ra\.dep :as callee\]" (:source ns-step)))
        (is (not (re-find #":as dep" (:source ns-step))))))
    (testing "every qualified site moves, and the plan says how many"
      (is (= 2 (:sites p)))
      (is (= 3 (count (:steps p)))))))

(deftest realias-plan-leaves-a-bare-occurrence-alone
  ;; `(defn two [dep] … (dep/g dep) … dep)` — the SAME spelling is a parameter
  ;; twice and a qualifier once. An alias is a QUALIFIER, so only `dep/x` means
  ;; it; substituting the symbol wholesale renames the parameter's USES and not
  ;; its binding, which is either an unresolved symbol or, worse, a silent
  ;; capture of something that happens to resolve.
  (let [p   (refactor/realias-plan (realias-store) 'ra.user 'dep 'callee)
        two (first (filter #(= 'two (:name %)) (:steps p)))]
    (is (some? two))
    (is (re-find #"\(callee/g dep\)" (:source two)) "the qualifier moved")
    (is (re-find #"\[dep\]" (:source two)) "the parameter binding did not")
    (is (not (re-find #"\bcallee\b(?!/)" (:source two)))
        "and no bare `callee` was minted anywhere")))

(deftest realias-plan-refuses-a-taken-or-absent-alias-by-name
  (testing "an alias already bound here would merge two libs under one
            qualifier — refused, naming the lib that already holds it, because
            `str` alone does not tell the caller what they collided with"
    (let [p (refactor/realias-plan (realias-store) 'ra.user 'dep 'str)]
      (is (:error p))
      (is (re-find #"clojure\.string" (:error p)))))
  (testing "an alias that names nothing here is refused WITH the ones that do:
            the caller is guessing at a spelling they did not choose, so the
            list is the answer to the question behind the mistake"
    (let [p (refactor/realias-plan (realias-store) 'ra.user 'nope 'callee)]
      (is (:error p))
      (is (re-find #"\bdep\b" (:error p)))
      (is (re-find #"\bstr\b" (:error p))))))

(deftest realias-plan-reports-an-alias-inside-a-string
  ;; A fixture that ingests source TEXT, or a docstring naming `dep/f`, holds
  ;; the alias where no symbol rewriter reaches. Rewriting blind would corrupt
  ;; the fixture; staying silent is exactly how a half-rewritten ns form
  ;; shipped green in phase 2 — both tests asserted nil and never read the
  ;; name back. So: report it, and let the caller decide.
  (let [s (-> (realias-store)
              (store/ingest 'ra.prose
                            (str "(ns ra.prose (:require [ra.dep :as dep]))\n\n"
                                 "(defn three\n  \"Calls dep/f, which is prose.\"\n"
                                 "  [x] (dep/f x))\n")))
        p (refactor/realias-plan s 'ra.prose 'dep 'callee)]
    (is (nil? (:error p)))
    (is (= 1 (:sites p)) "the code site still moves")
    (is (seq (:left-behind p)))
    (is (some #(re-find #"dep/f" (str (:text %))) (:left-behind p)))
    (is (some #(= 'three (:name %)) (:left-behind p))
        "and it says WHICH form, so the caller can go look")))

(defn- stranded-store
  "The store as it stands AFTER `w.old.thing` was renamed `w.new.other` — four
  callers, one per outcome the report has to distinguish."
  []
  (-> (store/empty-store)
      (store/ingest 'w.new.other "(ns w.new.other)\n\n(defn f \"F.\" [x] x)\n")
      ;; derived from the OLD name and from nothing in the new one
      (store/ingest 'w.a "(ns w.a (:require [w.new.other :as thing]))\n\n(defn a \"A.\" [x] (thing/f x))\n")
      ;; the alias the rename did NOT strand: still a suffix of the new name
      (store/ingest 'w.b "(ns w.b (:require [w.new.other :as other]))\n\n(defn b \"B.\" [x] (other/f x))\n")
      ;; stranded, but the alias it would suggest is already spoken for here
      (store/ingest 'w.c (str "(ns w.c (:require [w.new.other :as thing] [clojure.string :as other]))\n\n"
                              "(defn c \"C.\" [x] (other/join \",\" [(thing/f x)]))\n"))
      ;; an abbreviation: derived from nothing the store can see
      (store/ingest 'w.d "(ns w.d (:require [w.new.other :as ot]))\n\n(defn d \"D.\" [x] (ot/f x))\n")))

(deftest stranded-aliases-name-the-callers-still-spelling-the-old-name
  (testing "an alias derived from the OLD name and from nothing in the new one is
            reported, with the alias the naming convention would have produced"
    (let [rows (refactor/stranded-aliases (stranded-store) 'w.old.thing 'w.new.other)
          by-ns (into {} (map (juxt :ns identity)) rows)]
      ;; population first: every assertion below is about membership, and a
      ;; finder that found nothing agrees with all of them
      (is (seq rows) (pr-str rows))
      (is (= 'thing (:alias (by-ns 'w.a))))
      (is (= 'other (:suggest (by-ns 'w.a))))
      (is (= :alias (:via (by-ns 'w.a))))))
  (testing "an alias that is a suffix of BOTH names survived the rename intact —
            reporting it would make the common case (a namespace moving between
            modules under the same last segment) noise on every single rename"
    (let [rows (refactor/stranded-aliases (stranded-store) 'w.old.thing 'w.new.other)]
      (is (some #(= 'w.a (:ns %)) rows) "control: the finder can see this store")
      (is (not-any? #(= 'w.b (:ns %)) rows) (pr-str rows))))
  (testing "an ABBREVIATION is derived from nothing the store can read, so it is
            invisible here and the report says as much rather than guessing"
    (let [rows (refactor/stranded-aliases (stranded-store) 'w.old.thing 'w.new.other)]
      (is (some #(= 'w.a (:ns %)) rows) "control: the finder can see this store")
      (is (not-any? #(= 'w.d (:ns %)) rows) (pr-str rows)))))

(deftest a-suggested-alias-the-caller-already-uses-is-withheld
  (testing "`w.c` is stranded exactly as `w.a` is, and differs only in already
            calling something else `other` — so the suggestion that is right for
            one would be REFUSED for the other. Both rows come from one call, so
            neither absence can be the finder missing the store"
    (let [rows  (refactor/stranded-aliases (stranded-store) 'w.old.thing 'w.new.other)
          by-ns (into {} (map (juxt :ns identity)) rows)]
      (is (contains? by-ns 'w.a))
      (is (contains? by-ns 'w.c))
      (is (= 'thing (:alias (by-ns 'w.c))) "reported, and reported the same way")
      (is (= 'other (:suggest (by-ns 'w.a))) "free here — suggested")
      (is (nil? (:suggest (by-ns 'w.c))) "taken here — withheld")))
  (testing "and the withheld suggestion is withheld because realias-plan would
            in fact refuse it — the two agree by measurement, not by intent"
    (let [p (refactor/realias-plan (stranded-store) 'w.c 'thing 'other)]
      (is (:error p))
      (is (re-find #"clojure\.string" (:error p))))))

(deftest a-dequalified-call-landing-in-a-locals-shadow-is-reported
  ;; move-plan dequalifies `base/dead-ends` -> `dead-ends`, because the target
  ;; ns gets no self-alias. If the moved form also BINDS a local of that name,
  ;; the result is valid Clojure with different behaviour: the local shadows
  ;; the var, so nothing is red until the runtime throws a ClassCastException
  ;; somewhere else. The MIRROR direction (qualifying a bare ref that is a
  ;; local) rewrites the binding vector too and therefore fails at compile —
  ;; honestly. This one does not, which is the whole reason it is reported.
  (let [base      "(ns sh.base)\n\n(defn dead-ends \"D.\" [x] [x])\n"
        moving    (fn [params body]
                    (-> (store/empty-store)
                        (store/ingest 'sh.base base)
                        (store/ingest 'sh.mid
                                      (str "(ns sh.mid (:require [sh.base :as base]))\n\n"
                                           "(defn summarize \"S.\" " params "\n  " body ")\n"))))
        shadowing (moving "[{:keys [dead-ends]}]" "(base/dead-ends dead-ends)")
        clean     (moving "[ends]" "(base/dead-ends ends)")
        p         (refactor/move-plan shadowing 'sh.mid '[summarize] 'sh.base {})
        q         (refactor/move-plan clean 'sh.mid '[summarize] 'sh.base {})]
    (is (nil? (:error p)) (pr-str (:error p)))
    ;; REPORTED, never refused: the detector (edit/local-name?) has no scope
    ;; tracking and over-matches by design, so under a refusal its cost turns
    ;; from a slightly wrong hint into a legitimate move blocked with no way
    ;; through.
    (is (= [{:form 'summarize :was 'base/dead-ends :now 'dead-ends}]
           (:shadowed p)))
    ;; The control, and it carries its own liveness: the SAME dequalification
    ;; runs here and the report is silent, so the empty answer is a judgement
    ;; about locals rather than a rewrite that never happened.
    (let [moved-src (apply str (map str (:append q)))]
      (is (re-find #"\(dead-ends ends\)" moved-src)
          (str "control must dequalify too: " moved-src))
      (is (empty? (:shadowed q))))))

(deftest a-nameless-caller-is-rewritten-because-the-reference-row-has-a-form-id
  ;; A defmethod body defines no var. The store names the form nil and every
  ;; reference row it produces has :from-var nil — so a caller set keyed on
  ;; that name drops it, and the move reports :ok having rewritten some of
  ;; its callers. That is how 2 of 4 went missing on the move that found this.
  ;;
  ;; The row has carried :from-form the whole time. Two shapes, because they
  ;; fail differently: dm.use has a named caller BESIDE the nameless one, so
  ;; the namespace is reached and only the form is skipped; dm.only has
  ;; nothing but the nameless one, so the namespace is never visited at all
  ;; and does not even get the require.
  (let [st (-> (store/empty-store)
               (store/ingest 'dm.core
                             (str "(ns dm.core)\n\n"
                                  "(defn util \"U.\" [x] x)\n\n"
                                  "(defmulti render \"R.\" :kind)\n"))
               (store/ingest 'dm.use
                             (str "(ns dm.use (:require [dm.core :as core]))\n\n"
                                  "(defmethod core/render :a [m] (core/util m))\n\n"
                                  "(defn plain \"P.\" [m] (core/util m))\n"))
               (store/ingest 'dm.only
                             (str "(ns dm.only (:require [dm.core :as core]))\n\n"
                                  "(defmethod core/render :b [m] (core/util m))\n")))
        p  (refactor/move-plan st 'dm.core '[util] 'dm.moved {})
        rw (vals (:rewrites p))]
    (is (nil? (:error p)) (pr-str (:error p)))
    (testing "the nameless caller's namespace is a caller like any other"
      (is (contains? (:require-adds p) 'dm.only)
          (str "no require for a namespace whose only caller is nameless: "
               (pr-str (:require-adds p)))))
    (testing "every caller form is rewritten, named or not"
      (is (= {'dm.only 1 'dm.use 2} (frequencies (map :ns rw)))
          (str "callers rewritten: " (pr-str (map (juxt :ns :name) rw))))
      (is (not-any? #(re-find #"core/util" (:src %)) rw)
          (str "a caller still calling the old address: "
               (pr-str (map :src rw)))))))

(deftest a-nameless-stay-behind-caller-gets-the-require-back-and-the-rewrite
  ;; The mirror of the external half, and it fails one step earlier: the
  ;; stay->moved set is keyed on var NAMES, so a defmethod left behind in
  ;; from-ns contributes nothing to it — from-ns is never even asked for an
  ;; alias, and the moved var's only caller keeps calling a name that has
  ;; left the namespace.
  (let [st (-> (store/empty-store)
               (store/ingest 'sm.base "(ns sm.base)\n\n(defmulti render \"R.\" :kind)\n")
               (store/ingest 'sm.core
                             (str "(ns sm.core (:require [sm.base :as base]))\n\n"
                                  "(defn util \"U.\" [x] x)\n\n"
                                  "(defmethod base/render :a [m] (util m))\n")))
        p  (refactor/move-plan st 'sm.core '[util] 'sm.helpers {})
        rw (vals (:rewrites p))]
    (is (nil? (:error p)) (pr-str (:error p)))
    (is (contains? (:require-adds p) 'sm.core)
        (str "from-ns needs the require back for its nameless caller: "
             (pr-str (:require-adds p))))
    (is (= 1 (count rw))
        (str "the defmethod IS the caller: " (pr-str (map (juxt :ns :name) rw))))
    (is (re-find #"/util" (str (:src (first rw))))
        (str "the bare call must become alias-qualified: "
             (pr-str (:src (first rw)))))))

(deftest a-two-way-split-is-refused-when-the-stay-side-caller-has-no-name
  ;; `util` moves and calls `keeper`, which stays; the defmethod stays and
  ;; calls `util`. That is a require cycle in both directions and the plan
  ;; refuses it — but the check read the var-NAME set, so with the stay-side
  ;; caller nameless it saw one direction, passed, and left the cold-load
  ;; gate to find it later against a namespace that had not moved.
  (let [st (-> (store/empty-store)
               (store/ingest 'tw.base "(ns tw.base)\n\n(defmulti render \"R.\" :kind)\n")
               (store/ingest 'tw.core
                             (str "(ns tw.core (:require [tw.base :as base]))\n\n"
                                  "(defn keeper \"K.\" [x] x)\n\n"
                                  "(defn util \"U.\" [x] (keeper x))\n\n"
                                  "(defmethod base/render :a [m] (util m))\n")))
        p  (refactor/move-plan st 'tw.core '[util] 'tw.helpers {})]
    (is (re-find #"two-way split" (str (:error p))) (pr-str p))
    (is (re-find #"nameless" (str (:error p)))
        (str "and it must account for the caller it cannot name, or the"
             " refusal lists nothing to act on: " (:error p)))))

(deftest a-quoted-carrier-target-keeps-its-qualifier-through-a-move
  ;; Refs INTO the target go bare, because the target ns gets no self-alias.
  ;; That is right for a CALL and wrong for a QUOTED symbol, which is a name
  ;; being passed as data: `(requiring-resolve 'ca.base/hook)` moved into
  ;; ca.base became `(requiring-resolve 'hook)`, which cannot resolve.
  ;;
  ;; Nothing catches it. The form compiles, the moved code loads, and a
  ;; late-bound carrier only resolves at its first CALL — so the write is
  ;; green, the suite is green, and the failure arrives in some later session
  ;; pointing at the carrier rather than at the move.
  (let [st (-> (store/empty-store)
               (store/ingest 'ca.base "(ns ca.base)\n\n(defn hook \"H.\" [x] x)\n")
               (store/ingest 'ca.mid
                             (str "(ns ca.mid (:require [ca.base :as base]))\n\n"
                                  "(defn call \"C.\" [x]\n"
                                  "  [(base/hook x) ((requiring-resolve 'ca.base/hook) x)])\n")))
        p   (refactor/move-plan st 'ca.mid '[call] 'ca.base {})
        src (apply str (map str (:append p)))]
    (is (nil? (:error p)) (pr-str (:error p)))
    (is (re-find #"\(hook x\)" src)
        (str "the CALL still goes bare — the target has no self-alias: " src))
    (is (re-find #"'ca\.base/hook" src)
        (str "and the quoted NAME survives whole, or requiring-resolve has"
             " nothing to resolve: " src))))

(deftest an-external-quoted-carrier-target-takes-the-new-full-name
  ;; The mirror of the dequalify case, one namespace over, and it is the half
  ;; with live sites: slopp.git holds `(store/late-ref
  ;; 'slopp.git.client/fetch-remote!)` and slopp.ops holds one for
  ;; slopp.kernel.boot. Moving either target rewrote the quoted symbol to the
  ;; caller's ALIAS — a name `requiring-resolve` looks up in no namespace at
  ;; all, since aliases are this namespace's private business and a quoted
  ;; symbol is resolved by whoever reads it later.
  (let [st (-> (store/empty-store)
               (store/ingest 'cx.core "(ns cx.core)\n\n(defn util \"U.\" [x] x)\n")
               (store/ingest 'cx.use
                             (str "(ns cx.use (:require [cx.core :as core]))\n\n"
                                  "(defn call \"C.\" [x]\n"
                                  "  [(core/util x) ((requiring-resolve 'cx.core/util) x)])\n")))
        p   (refactor/move-plan st 'cx.core '[util] 'cx.moved {})
        src (str (:src (first (vals (:rewrites p)))))]
    (is (nil? (:error p)) (pr-str (:error p)))
    (is (re-find #"'cx\.moved/util" src)
        (str "a quoted name takes the new home's FULL name: " src))
    (is (not (re-find #"cx\.core" src))
        (str "and nothing still names the old home: " src))
    (is (re-find #"/util x\)" src)
        (str "while the ordinary call still takes the alias: " src))))

(deftest a-quoted-name-is-never-rewritten-to-an-alias
  ;; The rule all three rewrite passes now share, and the reason it is one
  ;; rule rather than three fixes: an alias is the CALLING namespace's
  ;; private business, while a quoted symbol is resolved by whoever reads it
  ;; later — so an alias inside one names nothing anywhere.
  ;;
  ;; This is the qualify direction: the moved form calls a stay-behind, so
  ;; bare refs to it become `from-alias/x`. Correct for the call. For the
  ;; quoted name beside it, it would produce a symbol that resolves in no
  ;; namespace at all, and quoting is exactly what stops the compiler from
  ;; ever noticing.
  (let [st (-> (store/empty-store)
               (store/ingest 'qa.core
                             (str "(ns qa.core)\n\n"
                                  "(defn stayer \"S.\" [x] x)\n\n"
                                  "(defn goer \"G.\" [x]\n"
                                  "  [(stayer x) (list 'stayer)])\n")))
        p   (refactor/move-plan st 'qa.core '[goer] 'qa.moved {})
        src (str (:new-src p))]
    (is (nil? (:error p)) (pr-str (:error p)))
    (is (re-find #"/stayer x\)" src)
        (str "the CALL is qualified back to the old home: " src))
    (is (re-find #"\(list 'stayer\)" src)
        (str "and the quoted symbol is left exactly as written: " src))))
