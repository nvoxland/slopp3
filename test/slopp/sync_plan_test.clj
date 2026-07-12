(ns slopp.sync-plan-test
  "Unit tests for the PURE side of git pull: ns-change-plan's form-granular
  3-way (remote wins where we're clean; both-touched → conflict; trivia-only
  → honest noop). The end-to-end pull is covered by the file-based
  slopp.sync-test (spawns sessions)."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.sync :as sync]))

(def base "(ns x.core)\n\n(defn f [x] (+ x 1))\n\n(defn g [x] x)\n")

(deftest noop-when-current-equals-remote
  (is (:noop (sync/ns-change-plan 'x.core base base base))))

(deftest remote-wins-where-we-are-clean
  (let [new (str "(ns x.core)\n\n(defn f [x] (+ x 2))\n\n(defn g [x] x)\n")
        plan (sync/ns-change-plan 'x.core base new base)]
    (testing "one replace step, remote content"
      (is (= [{:action :replace :ns 'x.core :name 'f
               :source "(defn f [x] (+ x 2))"}]
             (:steps plan))))
    (testing "order = the remote file's form order"
      (is (= '[x.core f g] (:order plan))))))

(deftest add-and-delete-flow-through
  (let [new  (str "(ns x.core)\n\n(defn f [x] (+ x 1))\n\n(defn h [x] (* 2 x))\n")
        plan (sync/ns-change-plan 'x.core base new base)]
    (testing "g deleted remotely, h added remotely"
      (is (= #{{:action :delete :ns 'x.core :name 'g}
               {:action :add :ns 'x.core :source "(defn h [x] (* 2 x))"}}
             (set (:steps plan)))))))

(deftest both-edited-is-a-conflict
  (let [new  (str "(ns x.core)\n\n(defn f [x] (+ x 2))\n\n(defn g [x] x)\n")
        cur  (str "(ns x.core)\n\n(defn f [x] (+ x 3))\n\n(defn g [x] x)\n")
        plan (sync/ns-change-plan 'x.core base new cur)]
    (is (:conflict plan))
    (is (re-find #"both sides edited f" (:conflict plan)))))

(deftest already-merged-is-clean
  ;; we already carry the remote's exact change → nothing to do
  (let [new (str "(ns x.core)\n\n(defn f [x] (+ x 2))\n\n(defn g [x] x)\n")]
    (is (:noop (sync/ns-change-plan 'x.core base new new)))))

(deftest anonymous-forms-conflict
  (let [new (str base "(println \"side effect\")\n")]
    (is (:conflict (sync/ns-change-plan 'x.core base new base)))))

(deftest trivia-only-change-is-an-honest-noop
  (let [new (str "(ns x.core)\n\n;; a new comment\n(defn f [x] (+ x 1))\n\n(defn g [x] x)\n")
        plan (sync/ns-change-plan 'x.core base new base)]
    (is (:noop plan))
    (is (:trivia plan))))
