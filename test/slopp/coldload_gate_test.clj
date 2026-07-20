(ns slopp.coldload-gate-test
  "The compile gate must refuse writes that hot-load into the live image but
  leave a namespace a FRESH load cannot resolve (forward references) — the
  gap found when an edit_group committed a slopp.sync that slopp.boot
  couldn't cold-load. Covers the three write shapes that can create one:
  single-form replace, edit_group replace-before-add, and edit_move."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.api :as api] [slopp.store :as store] [slopp.edit :as edit] [slopp.api.branch :as branch] [clojure.java.io :as io] [slopp.api.query :as query] [slopp.api.external :as external]))

(def seed
  (str "(ns cl.core)\n"
       "(defn early [] 1)\n"
       "(defn late [] 2)\n"
       "(defn tail [] (late))\n"))

(deftest ^:external cold-load-gate-reorders-acyclic-declares-cycles
  (let [sess (external/open!)]
    (try
      (testing "replace: an early form referencing a later one AUTO-REORDERS (silent)"
        (api/ingest! sess 'cl.core seed)
        (let [r (api/edit-replace! sess 'cl.core 'early "(defn early [] (late))"
                                   :prompt "x" :agent "t")]
          (is (nil? (:error r)) (pr-str r))
          (is (nil? (:reordered r)) "no ordering key leaks to the agent")
          (is (= '[cl.core late early tail]
                 (mapv :name (store/forms (:store @sess) 'cl.core)))
              "the callee was moved above its caller")
          (is (nil? (edit/cold-load-errors (:store @sess) '[cl.core])))))

      (testing "group: add a helper + a caller referencing it AUTO-REORDERS atomically"
        (api/ingest! sess 'cl.grp "(ns cl.grp)\n(defn early [] 1)\n")
        (let [r (api/edit-group! sess
                                 [{:action :replace :ns 'cl.grp :name 'early
                                   :source "(defn early [] (brand-new))"}
                                  {:action :add :ns 'cl.grp
                                   :source "(defn brand-new [] 42)"}]
                                 :prompt "g" :agent "t")]
          (is (nil? (:error r)) (pr-str r))
          (is (= '[cl.grp brand-new early]
                 (mapv :name (store/forms (:store @sess) 'cl.grp)))
              "the added helper was ordered above its caller")
          (is (nil? (edit/cold-load-errors (:store @sess) '[cl.grp])))))

      (testing "genuine CYCLE (mutual recursion) AUTO-DECLARES — the pipeline owns it"
        (api/ingest! sess 'cl.cyc "(ns cl.cyc)\n(defn ping [n] n)\n(defn pong [n] (ping n))\n")
        (let [r (api/edit-replace! sess 'cl.cyc 'ping "(defn ping [n] (pong n))"
                                   :prompt "cyc" :agent "t")]
          (is (nil? (:error r)) (pr-str r))
          (is (nil? (:declared r)) "silent — no declare key leaks to the agent")
          (is (nil? (edit/cold-load-errors (:store @sess) '[cl.cyc]))
              "the inserted declare makes it cold-load")
          (is (re-find #":auto-declare" (query/query-source sess 'cl.cyc))
              "a MARKED declare was inserted by the pipeline")))

      (testing "move: relocating a caller before its callee is still REFUSED (explicit order)"
        (api/ingest! sess 'cl.mv "(ns cl.mv)\n(defn early [] 1)\n(defn late [] 2)\n(defn tail [] (late))\n")
        (let [r (api/move-form! sess 'cl.mv 'tail :before 'late
                                :prompt "m" :agent "t")]
          (is (:error r))
          (is (re-find #"cold-load" (str (:error r))))))

      (testing "legal writes still land"
        (let [r (api/edit-replace! sess 'cl.core 'tail "(defn tail [] (early))"
                                   :prompt "ok" :agent "t")]
          (is (nil? (:error r)) (pr-str r))))
      (finally (api/close! sess)))))

(deftest ^:external merge-replay-that-breaks-cold-load-is-refused
  ;; Each line is individually gate-legal, but the MERGE interleaves into a
  ;; forward ref: main deletes the (now-satisfied) declare while the branch
  ;; grows a new forward use of the declared var. The merge door must hold
  ;; the same invariant as every other write door.
  (let [dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-coldload-merge" (make-array java.nio.file.attribute.FileAttribute 0)))
        sess (external/open! {:slopp.api/dir dir})]
    (try
      (api/ingest! sess 'm.core
                   (str "(ns m.core)\n"
                        "(declare h)\n"
                        "(defn f [] (h))\n"
                        "(defn g [] 1)\n"
                        "(defn h [] 2)\n"))
      ;; branch: g starts calling h — legal, the declare is above
      (let [r (branch/branch! sess "side")]
        (is (nil? (:error r)) (pr-str r)))
      (let [r (api/edit-replace! sess 'm.core 'g "(defn g [] (h))"
                                 :prompt "g uses h" :agent "t")]
        (is (nil? (:error r)) (pr-str r)))
      ;; main: f drops h, the satisfied declare is tidied away — also legal
      (let [r (branch/branch-switch! sess "main")]
        (is (nil? (:error r)) (pr-str r)))
      (let [r (api/edit-replace! sess 'm.core 'f "(defn f [] :indep)"
                                 :prompt "f drops h" :agent "t")]
        (is (nil? (:error r)) (pr-str r)))
      (let [r (api/fix-declares! sess 'm.core :agent "t")]
        (is (nil? (:error r)) (pr-str r))
        (is (not (re-find #"declare" (query/query-source sess 'm.core)))
            "setup: the declare must be gone on main"))
      ;; the merge would land g→(h) with no declare and h defined after g
      (let [before (query/query-source sess 'm.core)
            r      (branch/branch-merge! sess "side")]
        (is (:error r) (pr-str r))
        (is (re-find #"cold-load" (str (:error r))))
        (testing "nothing committed, image intact"
          (is (= before (query/query-source sess 'm.core)))
          (is (= [:indep] (api/query-eval sess "(m.core/f)")))))
      (finally
        (api/close! sess)
        (letfn [(rm [f] (let [f (clojure.java.io/file f)]
                          (when (.isDirectory f) (run! rm (.listFiles f)))
                          (.delete f)))]
          (rm dir))))))

(deftest ^:external declare-satisfies-the-gate
  (let [sess (external/open!)]
    (try
      (api/ingest! sess 'cl.two
                   (str "(ns cl.two)\n"
                        "(declare lazy)\n"
                        "(defn user [] 7)\n"
                        "(defn lazy [] 8)\n"))
      (let [r (api/edit-replace! sess 'cl.two 'user "(defn user [] (lazy))"
                                 :prompt "declared forward use" :agent "t")]
        (is (nil? (:error r)) (pr-str r)))
      (finally (api/close! sess)))))
(deftest registrations-and-the-cold-load-gate
  ;; Registrations are ANONYMOUS (D8), and the reorder machinery is name-keyed:
  ;; cold-load-order's :order drops them, reorder-to cannot move them. That is
  ;; SAFE only because of two facts pinned here, found by probing (2026-07-17):
  ;; the gate's forward-ref scan reads kondo rows directly, so it sees a
  ;; defmethod body's reference to its multi (nil :from-var and all); and
  ;; resolve-cold-load re-checks its own output, so a reorder that left a
  ;; method above its multi cannot land — it degrades to the explicit refusal.
  ;; If either fact rots, a multimethod ns could silently load wrong.
  (let [good (store/ingest (store/empty-store) 'cl.ok
                           (str "(ns cl.ok)\n\n(defmulti area :shape)\n\n"
                                "(defmethod area :square [s] 1)\n"))
        bad  (store/ingest (store/empty-store) 'cl.bad
                           (str "(ns cl.bad)\n\n(defmethod area :square [s] 1)\n\n"
                                "(defmulti area :shape)\n"))]
    (testing "a well-ordered multimethod ns cold-loads — no false refusal"
      (is (nil? (edit/cold-load-errors good '[cl.ok]))))
    (testing "a method ABOVE its multi is a forward ref the gate SEES —
              the method body's usage arrives without a :from-var, and the
              scan must not filter it out"
      (is (some? (edit/cold-load-errors bad '[cl.bad]))))
    (testing "auto-reorder cannot fix it (registrations are not orderable by
              name) and must say so, not return a still-broken store"
      (is (nil? (edit/resolve-cold-load bad 'cl.bad))))))

(deftest require-cycles-are-a-cold-load-failure
  ;; The gap that let a move_forms group commit a store no fresh JVM could
  ;; load: slopp.api required slopp.api.external while referencing nothing in
  ;; it, and slopp.api.external required slopp.api.
  ;;
  ;;   Cyclic load dependency: [slopp/api] -> slopp/api/external -> [slopp/api]
  ;;
  ;; forward-refs is INTRA-namespace, so cold-load-errors could not see it, and
  ;; module-edge cycle detection does not apply because both namespaces are in
  ;; ONE module. Hot-load tolerates the cycle; a cold load does not.
  (let [cyclic (-> (store/empty-store)
                   (store/ingest 'cy.core
                                 (str "(ns cy.core (:require [cy.core.edge :as edge]))\n"
                                      "(defn f \"F.\" [x] (edge/g x))\n"))
                   (store/ingest 'cy.core.edge
                                 (str "(ns cy.core.edge (:require [cy.core :as core]))\n"
                                      "(defn g \"G.\" [x] (core/f x))\n")))
        clean  (-> (store/empty-store)
                   (store/ingest 'ok.core.edge "(ns ok.core.edge)\n(defn g \"G.\" [x] x)\n")
                   (store/ingest 'ok.core
                                 (str "(ns ok.core (:require [ok.core.edge :as edge]))\n"
                                      "(defn f \"F.\" [x] (edge/g x))\n")))]
    (testing "a require cycle is reported, naming the namespaces in it"
      (let [msg (str (edit/cold-load-errors cyclic '[cy.core cy.core.edge]))]
        (is (re-find #"(?i)cycl" msg) msg)
        (is (re-find #"cy\.core" msg) msg)
        (is (re-find #"cy\.core\.edge" msg) msg)))
    (testing "reporting it from EITHER end — a move touches only one side"
      (is (re-find #"(?i)cycl" (str (edit/cold-load-errors cyclic '[cy.core])))))
    (testing "a one-way require is clean"
      (is (nil? (edit/cold-load-errors clean '[ok.core ok.core.edge]))))))
