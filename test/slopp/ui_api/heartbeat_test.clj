(ns slopp.ui-api.heartbeat-test
  "What a check-in must get right: a project always has a name a human
  recognises, the beat matches the contract the hub validates it against, the
  first one is immediate, and an orderly shutdown says goodbye."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [slopp.store :as store]
            [slopp.ui-api.heartbeat :as beat]
            [slopp.ui-api.contracts :as contracts] [slopp.web :as web] [slopp.web.client :as client] [cheshire.core :as json] [clojure.string :as str]))

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
      (let [;; NOT an ephemeral port opened-and-closed: that races. A sibling
            ;; `web/serve!` with {:web/port 0} in the same shard JVM can be
            ;; handed the port we just freed, and the beat then gets a real
            ;; 404 (`{:error "no route"}` — our own server answering) instead
            ;; of a refused connection, which is the OPPOSITE of what this
            ;; asserts. Port 1 is privileged, so nothing in the suite can bind
            ;; it, while CONNECTING needs no privilege and simply refuses —
            ;; the same idiom the `me` fixture above already relies on.
            dead 1
            hb   (beat/start! (str "http://127.0.0.1:" dead "/")
                              (constantly me) #(swap! answers conj %))]
        (try
          (is (wait (fn [] (seq @answers))))
          (is (nil? (last @answers)) (pr-str @answers))
          (finally (beat/stop! hb)))))))

(deftest the-beat-loop-is-checkable-without-a-network
  ;; Everything this asserts was already asserted — in three `^:external` tests
  ;; that each stand up a real server in a fresh JVM. The behaviour under test
  ;; was never the SOCKET; it was the loop: beat immediately, take the interval
  ;; from the wire, hand every answer outward, say goodbye on the way out.
  ;;
  ;; Injecting the transport is what separates those. The socket is covered
  ;; once, by `client-test/requester-contract`, against BOTH adapters — so the
  ;; fake here is a claim about the world rather than about itself, and the
  ;; loop can be checked at memory speed.
  ;;
  ;; The last case is the one no test ever made: `start!` has documented since
  ;; it was written that a callback which THROWS must not take the beat down,
  ;; and nothing checked it. It is cheap to check only because the transport
  ;; moved out.
  (let [seen    (atom [])
        answers (atom [])
        routes  {[:post "/api/register"]
                 (fn [req]
                   (swap! seen conj [:register (str (:body req))])
                   {:status 200 :body (json/generate-string {:slug "toy" :beat-ms 20})})
                 [:post "/api/deregister"]
                 (fn [_] (swap! seen conj [:deregister]) {:status 200 :body "{}"})}
        hub     "http://hub.test/"
        me      {:name "toy" :dir "/w/toy" :url "http://127.0.0.1:1/"}
        beats   (fn [] (count (filter (fn [e] (= :register (first e))) @seen)))
        wait    (fn [pred]
                  (loop [n 0]
                    (cond (pred) true
                          (> n 200) false
                          :else (do (Thread/sleep 5) (recur (inc n))))))]
    (testing "the first beat is immediate and the second arrives at the interval
              the hub asked for — 20ms, where the compiled-in default is ten
              seconds, so a second beat at all is proof it came from the wire"
      (let [hb (beat/start! hub (constantly me) #(swap! answers conj %)
                            (client/fake-requester hub routes))]
        (try
          (is (wait (fn [] (<= 2 (beats)))))
          (is (str/includes? (second (first @seen)) "toy")
              "the payload has to actually reach the hub")
          (is (every? (fn [a] (= "toy" (:slug a))) @answers) (pr-str @answers))
          (finally (beat/stop! hb))))
      (testing "and an orderly stop says goodbye rather than leaving a row to
                age out on its own"
        (is (wait (fn [] (some (fn [e] (= [:deregister] e)) @seen))))))
    (testing "a hub that REFUSES is not a hub that is absent — the distinction
              the whole port exists to keep, checked here without a socket"
      (let [refusing (client/fake-requester
                      hub {[:post "/api/register"]
                           (fn [_] {:status 400
                                    :body (json/generate-string
                                           {:explain {:dir ["missing required key"]}})})})
            got (atom [])
            hb  (beat/start! hub (constantly me) #(swap! got conj %) refusing)]
        (try
          (is (wait (fn [] (seq @got))))
          (let [a (last @got)]
            (is (some? a) "nil is what made a refusal look like an absent hub")
            (is (beat/refused? a) (pr-str a))
            (is (str/includes? (pr-str a) "missing required key")
                "the hub's explanation must survive, or drift is undiagnosable"))
          (is (nil? (beat/hub-address hub (last @got)))
              "a refused beat registered nothing, so there is no page to offer")
          (finally (beat/stop! hb)))))
    (testing "an ABSENT hub is a quiet nil — nobody has to run one"
      (let [got (atom [])
            hb  (beat/start! hub (constantly me) #(swap! got conj %)
                             (client/fake-requester "http://elsewhere.test/" {}))]
        (try
          (is (wait (fn [] (seq @got))))
          (is (nil? (last @got)) (pr-str @got))
          (finally (beat/stop! hb)))))
    (testing "a callback that THROWS must not take the beat down — documented
              since the loop was written, and never once checked"
      (reset! seen [])
      (let [hb (beat/start! hub (constantly me)
                            (fn [_] (throw (ex-info "callers break" {})))
                            (client/fake-requester hub routes))]
        (try
          (is (wait (fn [] (<= 2 (beats))))
              "the loop kept registering despite the callback throwing every time")
          (finally (beat/stop! hb)))))))

(deftest a-bug-we-own-is-not-a-hub-that-is-absent
  ;; The third collapse in the same function, and the one nothing caught.
  ;;
  ;; `post!` fixed refused-vs-absent and then wrapped its ENTIRE body — the
  ;; request, the JSON encode, the parse, the comparison — in
  ;; `(catch Exception _ nil)`. So a payload that cannot be serialised, an NPE,
  ;; or any bug at all came back as nil, and nil is what the loop reads as
  ;; "no hub is running". A project would report itself absent forever while
  ;; the actual fault sat in its own payload.
  ;;
  ;; That breadth was only possible BECAUSE the transport threw a bare
  ;; IOException: catching "no server" meant catching everything. Now that the
  ;; port throws an ex-info carrying :http/error, the catch can be narrow, and
  ;; three different facts get three different answers — nil for absent,
  ;; :hub/refused for rejected, :hub/error for a bug we own.
  (let [got   (atom [])
        hub   "http://hub.test/"
        routes {[:post "/api/register"]
                (fn [_] {:status 200
                         :body (json/generate-string {:slug "toy" :beat-ms 20})})
                [:post "/api/deregister"] (fn [_] {:status 200 :body "{}"})}
        wait  (fn [pred]
                (loop [n 0]
                  (cond (pred) true
                        (> n 200) false
                        :else (do (Thread/sleep 5) (recur (inc n))))))
        ;; a function value is not JSON, so generate-string throws before the
        ;; request is ever made — a bug entirely on our side of the wire
        hb    (beat/start! hub (constantly {:name (fn [] :not-serialisable)})
                           #(swap! got conj %)
                           (client/fake-requester hub routes))]
    (try
      (is (wait (fn [] (seq @got))))
      (let [a (last @got)]
        (is (some? a)
            "nil here is the bug: it is indistinguishable from an absent hub")
        (is (:hub/error a) (pr-str a))
        (is (not (beat/refused? a)) "a bug of ours is not the hub refusing us")
        (is (nil? (beat/hub-address hub a))
            "and it registered nothing, so there is no page to offer")
        (is (= beat/default-beat-ms (beat/interval-from a))
            "nor does it change the interval"))
      (testing "and the loop SURVIVES it — a broken payload must not stop the
                beat, or one bad field takes the project off the picker for
                good. Asserted on the THREAD rather than on a second beat:
                an errored answer falls back to the ten-second default
                interval, so counting beats here would measure the clock"
        (is (.isAlive ^Thread (:thread hb))))
      (finally (beat/stop! hb)))))
