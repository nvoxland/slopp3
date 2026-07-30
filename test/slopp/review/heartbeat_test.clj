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

(deftest a-project-can-name-its-own-page-only-once-a-hub-has-answered
  ;; Orientation advertised `:ui-hub` from the moment the beat STARTED, so a
  ;; machine with no hub running was handed a url that refuses the connection
  ;; — "I did not check" printed as "checked and fine". The fix is not a probe:
  ;; the hub already answers every beat with the slug it minted, and that
  ;; answer is both a liveness fact and the deep link. `post!` was reading it
  ;; and throwing everything but `:beat-ms` away.
  (testing "an answered beat yields the project's own page on the hub"
    (is (= "http://127.0.0.1:7359/p/slopp2"
           (beat/hub-address "http://127.0.0.1:7359/" {:slug "slopp2" :beat-ms 10}))))
  (testing "no answer, or an answer with no slug, yields NOTHING — a hub that
            is not running is the ordinary case, and the honest report of it is
            absence, not the configured address"
    (is (nil? (beat/hub-address "http://127.0.0.1:7359/" nil)))
    (is (nil? (beat/hub-address "http://127.0.0.1:7359/" {:beat-ms 10})))
    (is (nil? (beat/hub-address "http://127.0.0.1:7359/" {:slug ""}))
        "a blank slug is not an address")
    (is (nil? (beat/hub-address "http://127.0.0.1:7359/" {:slug "   "})))))

(deftest ^:external the-beat-hands-every-answer-back-so-the-address-tracks-the-hub
  ;; `hub-address` is only useful if something feeds it the CURRENT answer. The
  ;; beat is the one thing that knows: it is the only code that ever talks to
  ;; the hub, and it does so forever. So the loop reports each answer outward
  ;; and whoever cares recomputes — rather than the beat holding an opinion
  ;; about sessions, or a session probing a hub it has no business calling.
  ;;
  ;; Every beat, not just the first: a hub that goes away must make the address
  ;; go away too, or this is the same stale-claim bug one layer along.
  (let [answers (atom [])
        srv (web/serve!
             {:web/namespaces []
              :web/routes
              [{:method :post :path "/api/register" :auth :public
                :handler (fn [_] {:status 200 :body {:slug "toy" :beat-ms 30}})}
               {:method :post :path "/api/deregister" :auth :public
                :handler (fn [_] {:status 200 :body {:dropped true}})}]
              :web/host "127.0.0.1" :web/port 0})
        url (str "http://127.0.0.1:" (:port srv) "/")
        me  {:name "toy" :dir "/w/toy" :url "http://127.0.0.1:1/"}
        wait (fn [pred]
               (loop [n 0]
                 (cond (pred) true
                       (> n 100) false
                       :else (do (Thread/sleep 20) (recur (inc n))))))]
    (try
      (let [hb (beat/start! url (constantly me) #(swap! answers conj %))]
        (try
          (testing "the answer arrives, repeatedly, and carries the slug"
            (is (wait (fn [] (<= 2 (count @answers)))))
            (is (every? #(= "toy" (:slug %)) @answers) (pr-str @answers)))
          (testing "which is exactly what hub-address needs"
            (is (= (str url "p/toy") (beat/hub-address url (last @answers)))))
          (finally (beat/stop! hb))))
      (testing "a hub that stops answering reports nil, so the address it
                supported stops being claimed"
        (web/stop! srv)
        (reset! answers [])
        (let [hb (beat/start! url (constantly me) #(swap! answers conj %))]
          (try
            (is (wait (fn [] (seq @answers))))
            (is (every? nil? @answers) (pr-str @answers))
            (is (nil? (beat/hub-address url (last @answers))))
            (finally (beat/stop! hb)))))
      (finally (web/stop! srv)))))

(deftest ^:external a-hub-that-REFUSES-the-beat-is-not-a-hub-that-is-absent
  ;; `post!` returned nil for a refused connection AND for a 4xx, so "no hub is
  ;; running" and "the hub rejected what I sent" were the same answer. A hub
  ;; that is not running is the ordinary case and costs nothing; a hub that
  ;; REFUSES us is a bug we own, and it presents identically — as a project that
  ;; never appears in the picker.
  ;;
  ;; This became reachable the moment the hub started validating the beat
  ;; (`slopp-ui.hub/register!`), which it does because the beat is the one
  ;; contract crossing the split by COPY: `contracts/project-beat` here is a
  ;; hand-maintained twin of the hub's, and neither store can read the other. So
  ;; the whole drift-detection story is "the hub 400s and we say so" — and if we
  ;; swallow the 400, there is no story at all.
  (let [refuse (fn [status body]
                 (web/serve!
                  {:web/namespaces []
                   :web/routes [{:method :post :path "/api/register" :auth :public
                                 :handler (fn [_] {:status status :body body})}]
                   :web/host "127.0.0.1" :web/port 0}))
        answers (atom [])
        wait (fn [pred]
               (loop [n 0]
                 (cond (pred) true
                       (> n 100) false
                       :else (do (Thread/sleep 20) (recur (inc n))))))
        me {:name "toy" :dir "/w/toy" :url "http://127.0.0.1:1/"}]
    (testing "a REFUSED beat is reported as a refusal, carrying what the hub said"
      (let [srv (refuse 400 {:error "this beat does not satisfy the contract"
                             :explain {:dir ["missing required key"]}})
            url (str "http://127.0.0.1:" (:port srv) "/")]
        (try
          (let [hb (beat/start! url (constantly me) #(swap! answers conj %))]
            (try
              (is (wait (fn [] (seq @answers))))
              (let [a (last @answers)]
                (is (some? a)
                    "nil is what made a refusal look like an absent hub")
                (is (beat/refused? a) (pr-str a))
                (is (re-find #"missing required key" (pr-str a))
                    (str "the hub's explain must survive the trip, or drift is"
                         " undiagnosable from this side: " (pr-str a))))
              (finally (beat/stop! hb))))
          (finally (web/stop! srv)))))
    (testing "and a refusal yields NO address — we are not registered, so there
              is no page to hand anyone"
      (is (nil? (beat/hub-address "http://127.0.0.1:7359/" (last @answers)))))
    (testing "nor does it change the beat interval: a hub refusing us must not
              also become a hub we hammer"
      (is (= beat/default-beat-ms (beat/interval-from (last @answers)))))
    (testing "an ABSENT hub is still a quiet nil — nobody has to run one"
      (reset! answers [])
      (let [dead (let [s (java.net.ServerSocket. 0) p (.getLocalPort s)]
                   (.close s) p)
            hb   (beat/start! (str "http://127.0.0.1:" dead "/")
                              (constantly me) #(swap! answers conj %))]
        (try
          (is (wait (fn [] (seq @answers))))
          (is (nil? (last @answers)) (pr-str @answers))
          (finally (beat/stop! hb)))))))
