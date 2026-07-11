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

(deftest cold-load-breaking-writes-are-refused
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

(deftest declare-satisfies-the-gate
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
