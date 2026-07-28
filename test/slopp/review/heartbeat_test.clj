(ns slopp.review.heartbeat-test
  "What a check-in must get right: a project always has a name a human
  recognises, the beat matches the contract the hub validates it against, the
  first one is immediate, and an orderly shutdown says goodbye."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [slopp.store :as store]
            [slopp.review.heartbeat :as beat]
            [slopp.review.contracts :as contracts] [slopp.web :as web]))

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
  ;; Against a STUB hub, not the real one. The hub is a separate project now
  ;; and its code is not here to call — which is the point rather than an
  ;; inconvenience: what a project owes is the CONTRACT (`POST /api/register`,
  ;; `POST /api/deregister`, an answer carrying `:beat-ms`), and a test that
  ;; stood up the real hub would pass just as well if both halves drifted
  ;; together into something no third implementation could talk to.
  ;;
  ;; The stub is also what lets this assert the interval at all: it answers
  ;; with a deliberately tiny `:beat-ms`, and a second beat arriving quickly
  ;; is the observable proof the project took the number from the wire.
  (let [seen (atom [])
        srv  (web/serve!
              {:web/namespaces []
               :web/routes
               [{:method :post :path "/api/register" :auth :public
                 :handler (fn [req]
                            (swap! seen conj [:register (:name (:body req))])
                            {:status 200 :body {:slug "toy" :beat-ms 30}})}
                {:method :post :path "/api/deregister" :auth :public
                 :handler (fn [_]
                            (swap! seen conj [:deregister])
                            {:status 200 :body {:dropped true}})}]
               :web/host "127.0.0.1" :web/port 0})
        url  (str "http://127.0.0.1:" (:port srv) "/")
        me   {:name "toy" :dir "/w/toy" :url "http://127.0.0.1:1/"
              :pid 7 :version "0.1.0" :status "idle"}
        registers (fn [] (count (filter (fn [e] (= :register (first e))) @seen)))
        wait (fn [pred]
               (loop [n 0]
                 (cond (pred) true
                       (> n 100) false
                       :else (do (Thread/sleep 20) (recur (inc n))))))]
    (try
      (let [hb (beat/start! url (constantly me))]
        (testing "the first beat is immediate, so a project is in the picker
                  as soon as its server is up rather than one interval later"
          (is (wait (fn [] (seq @seen))))
          (is (= [:register "toy"] (first @seen))))
        (testing "and the SECOND beat follows at the interval the hub asked
                  for — 30ms here, where the built-in default is ten seconds,
                  so arriving at all is the proof it came from the wire"
          (is (wait (fn [] (<= 2 (registers))))))
        (beat/stop! hb)
        (testing "a clean shutdown deregisters instead of leaving a row to go
                  stale on its own"
          (is (wait (fn [] (some (fn [e] (= [:deregister] e)) @seen))))))
      (finally (web/stop! srv)))))

(deftest the-hub-sets-the-interval-and-the-project-obeys-it
  ;; The split removed the shared constant. `beat-ms` lived in
  ;; `slopp.review.registry` and BOTH halves read it — which worked only while
  ;; both halves were the same codebase. The hub is a separate project now
  ;; and could be a separate release, so a compiled-in agreement about timing
  ;; between two processes is exactly the coupling this split exists to
  ;; remove.
  ;;
  ;; It costs nothing to remove, because the contract already carries it:
  ;; `POST /api/register` answers `{:slug … :beat-ms …}`. The hub was already
  ;; telling us; nobody was listening.
  (testing "a hub that answers with an interval sets ours"
    (is (= 250 (beat/interval-from {:slug "x" :beat-ms 250}))))
  (testing "a hub that says nothing leaves the default standing — an older hub,
            or one that answered with something unexpected, must not turn the
            beat into a busy loop or stop it altogether"
    (is (= beat/default-beat-ms (beat/interval-from {:slug "x"})))
    (is (= beat/default-beat-ms (beat/interval-from nil)))
    (is (= beat/default-beat-ms (beat/interval-from {:beat-ms "soon"})))
    (is (= beat/default-beat-ms (beat/interval-from {:beat-ms 0})))
    (is (= beat/default-beat-ms (beat/interval-from {:beat-ms -5})))))
