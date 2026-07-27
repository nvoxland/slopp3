(ns slopp.ui.heartbeat-test
  "What a check-in must get right: a project always has a name a human
  recognises, the beat matches the contract the hub validates it against, the
  first one is immediate, and an orderly shutdown says goodbye."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [slopp.store :as store]
            [slopp.ui.heartbeat :as beat]
            [slopp.ui.hub :as hub]
            [slopp.ui.contracts :as contracts]))

(deftest a-beat-says-who-and-where-and-nothing-it-does-not-know
  (let [named (assoc-in (store/empty-store) [:config "capabilities" :values]
                        {"app.name" "Invoices" "app.version" "2.1.0"})]
    (testing "a store that names itself is listed under that name"
      (is (= "Invoices" (:name (beat/payload named "/w/inv" "http://127.0.0.1:1/")))))
    (testing "a store that configured nothing still gets a name a human
              recognises — the directory's own, never a blank row"
      (is (= "inv" (:name (beat/payload (store/empty-store) "/w/inv"
                                        "http://127.0.0.1:1/"))))
      (is (= "inv" (:name (beat/payload (store/empty-store) "/w/inv/"
                                        "http://127.0.0.1:1/")))
          "a trailing slash is not a nameless project"))
    (testing "the beat satisfies the contract the hub validates it against"
      (let [p (beat/payload named "/w/inv" "http://127.0.0.1:1/")]
        (is (m/validate contracts/project-beat p)
            (str "project-beat contract: " (m/explain contracts/project-beat p)))
        (is (= "/w/inv" (:dir p)))
        (is (= "2.1.0" (:version p)))
        (is (pos? (:pid p)) "the pid is what lets a human find the process behind a stuck project")))))

(deftest ^:external a-project-checks-in-on-its-own-and-leaves-when-it-stops
  (let [hub (hub/serve! 0)
        me  {:name "toy" :dir "/w/toy" :url "http://127.0.0.1:1/"
             :pid 7 :version "0.1.0" :status "idle"}
        wait (fn [pred]
               (loop [n 0]
                 (cond (pred) true
                       (> n 100) false
                       :else (do (Thread/sleep 20) (recur (inc n))))))]
    (try
      (let [hb (beat/start! (:url hub) (constantly me))]
        (testing "the first beat is immediate, so a project is in the picker
                  as soon as its server is up rather than one interval later"
          (is (wait #(seq @(:registry hub))))
          (is (= "toy" (:name (first (vals @(:registry hub)))))))
        (beat/stop! hb)
        (testing "a clean shutdown deregisters instead of leaving a row to go
                  stale on its own"
          (is (wait #(empty? @(:registry hub))))))
      (finally (hub/stop! hub)))))
