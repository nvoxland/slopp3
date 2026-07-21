(ns slopp.refactor-test
  "The move-forms planner: pure move analysis over a store value — external
  callers, dependency direction, selective requires, refusals with teaching.
  The executor (api/move-forms!) is covered end-to-end in surgeon-test; here
  the PLANS are cheap to assert."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.refactor :as refactor]
            [slopp.store :as store] [clojure.string :as str]))
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
