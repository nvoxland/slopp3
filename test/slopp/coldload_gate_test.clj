(ns slopp.coldload-gate-test
  "The compile gate must refuse writes that hot-load into the live image but
  leave a namespace a FRESH load cannot resolve (forward references) — the
  gap found when an edit_group committed a slopp.sync that slopp.boot
  couldn't cold-load. Covers the three write shapes that can create one:
  single-form replace, edit_group replace-before-add, and edit_move."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.api :as api] [slopp.store :as store] [slopp.edit :as edit] [slopp.api.branch :as branch]))

(def seed
  (str "(ns cl.core)\n"
       "(defn early [] 1)\n"
       "(defn late [] 2)\n"
       "(defn tail [] (late))\n"))

(deftest ^:isolated cold-load-gate-reorders-acyclic-declares-cycles
  (let [sess (api/open!)]
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
          (is (re-find #":auto-declare" (api/query-source sess 'cl.cyc))
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

(deftest ^:isolated merge-replay-that-breaks-cold-load-is-refused
  ;; Each line is individually gate-legal, but the MERGE interleaves into a
  ;; forward ref: main deletes the (now-satisfied) declare while the branch
  ;; grows a new forward use of the declared var. The merge door must hold
  ;; the same invariant as every other write door.
  (let [dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-coldload-merge" (make-array java.nio.file.attribute.FileAttribute 0)))
        sess (api/open! {:dir dir})]
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
        (is (not (re-find #"declare" (api/query-source sess 'm.core)))
            "setup: the declare must be gone on main"))
      ;; the merge would land g→(h) with no declare and h defined after g
      (let [before (api/query-source sess 'm.core)
            r      (branch/branch-merge! sess "side")]
        (is (:error r) (pr-str r))
        (is (re-find #"cold-load" (str (:error r))))
        (testing "nothing committed, image intact"
          (is (= before (api/query-source sess 'm.core)))
          (is (= [:indep] (api/query-eval sess "(m.core/f)")))))
      (finally
        (api/close! sess)
        (letfn [(rm [f] (let [f (clojure.java.io/file f)]
                          (when (.isDirectory f) (run! rm (.listFiles f)))
                          (.delete f)))]
          (rm dir))))))

(deftest ^:isolated declare-satisfies-the-gate
  (let [sess (api/open!)]
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
