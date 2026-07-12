(ns slopp.coldload-gate-test
  "The compile gate must refuse writes that hot-load into the live image but
  leave a namespace a FRESH load cannot resolve (forward references) — the
  gap found when an edit_group committed a slopp.sync that slopp.boot
  couldn't cold-load. Covers the three write shapes that can create one:
  single-form replace, edit_group replace-before-add, and edit_move."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.api :as api]))

(def seed
  (str "(ns cl.core)\n"
       "(defn early [] 1)\n"
       "(defn late [] 2)\n"
       "(defn tail [] (late))\n"))

(deftest ^:isolated cold-load-breaking-writes-are-refused
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'cl.core seed)

      (testing "replace: an early form referencing a later one is refused"
        (let [r (api/edit-replace! sess 'cl.core 'early "(defn early [] (late))"
                                   :prompt "x" :agent "t")]
          (is (:error r))
          (is (re-find #"cold-load" (str (:error r))))
          (is (re-find #"late" (str (:error r))))))

      (testing "group: replace-before-add forward ref is refused atomically"
        (let [r (api/edit-group! sess
                                 [{:action :replace :ns 'cl.core :name 'early
                                   :source "(defn early [] (brand-new))"}
                                  {:action :add :ns 'cl.core
                                   :source "(defn brand-new [] 42)"}]
                                 :prompt "g" :agent "t")]
          (is (:error r))
          (is (re-find #"cold-load" (str (:error r))))
          (testing "nothing committed"
            (is (= seed (api/query-source sess 'cl.core))))))

      (testing "move: relocating a caller before its callee is refused"
        (let [r (api/move-form! sess 'cl.core 'tail :before 'late
                                :prompt "m" :agent "t")]
          (is (:error r))
          (is (re-find #"cold-load" (str (:error r))))))

      (testing "the legal shapes still land"
        (let [r (api/edit-replace! sess 'cl.core 'tail "(defn tail [] (early))"
                                   :prompt "ok" :agent "t")]
          (is (nil? (:error r)) (pr-str r)))
        (let [r (api/move-form! sess 'cl.core 'late :before 'early
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
      (let [r (api/branch! sess "side")]
        (is (nil? (:error r)) (pr-str r)))
      (let [r (api/edit-replace! sess 'm.core 'g "(defn g [] (h))"
                                 :prompt "g uses h" :agent "t")]
        (is (nil? (:error r)) (pr-str r)))
      ;; main: f drops h, the satisfied declare is tidied away — also legal
      (let [r (api/branch-switch! sess "main")]
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
            r      (api/branch-merge! sess "side")]
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
