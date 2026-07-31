(ns slopp.web.client-test
  "Home of `requester-contract` — the ONE suite every adapter of the HTTP
  client port must pass, and the two runs of it.

  The shape here is the point, and it generalises past HTTP. A fake that only
  ever ran against its own tests proves itself SELF-CONSISTENT, which reads
  identically to proving it FAITHFUL and is not the same claim. So the suite is
  written once against the port and run twice: in-image against the fake, where
  it is free, and `^:external` against the real transport over a real socket,
  where it is the thing that makes the first run mean anything.

  That split is also the clearest case for slopp's two tiers. The same
  assertions cost milliseconds in one and a fresh JVM in the other, and both
  are worth paying for — a reason more principled than `^:external` meaning
  \"the slow ones\"."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [slopp.web.client :as client]
            [slopp.web :as web]))

(defn requester-contract
  "The suite EVERY requester must pass — the real transport and every fake.

  A requester takes `{:http/method :http/url :http/headers :http/body}` and
  answers `{:http/status :http/headers :http/body}`. It holds NO policy: it
  does not decide that a 404 is an error, does not parse the body, does not
  retry. Those are the caller's, and they differ per caller — the heartbeat
  treats a 400 as a drift alarm, `fetch-jwks!` throws, `fetch-contract` reads
  EDN. One transport, three policies, and this suite is about the transport.

  `make` receives a route table `{[method path] handler}` and returns
  `{:requester :base-url :dead-url}` — the fake builds them in memory, the real
  one stands up a server. `:dead-url` is an address where NOTHING is listening,
  which the fake models and the real one gets from a closed port.

  Note the two vocabularies a few lines apart, on purpose: the handlers speak
  ring (`:status`, `:body`), because that is what a far side speaks, and the
  requester speaks `:http/*`, because that is the client port's own.

  The load-bearing assertion is the pair at the end of the list. A far side
  that REFUSES and a far side that is ABSENT must never arrive as the same
  value — status is data, and only a transport failure throws. That is not a
  style preference: the heartbeat shipped with those two conflated, and it
  presented as a project that silently never appeared in the picker. Any fake
  that answers nil for an unreachable host rebuilds that bug inside the tests
  that were supposed to catch it.

  What this suite does NOT pin, said out loud so a green run is not read as
  more than it is: the far side's own body PARSING. A real `slopp.web/serve!`
  decodes JSON before its handler sees it and the fake does not, so the body
  assertion below is deliberately a substring check. A caller whose logic
  depends on the parsed shape is not covered by the fake and needs a real
  server. Naming that is the difference between a fake and a fake that lies."
  [label make]
  (let [routes ;; Every far side here declares `text/plain`, and that is load-bearing rather
        ;; than tidy. A response that declares NO content type gets encoded by
        ;; whatever is serving it — `slopp.web/serve!` JSON-encodes a bare string,
        ;; so "pong" arrives as "\"pong\"" from a real server and as "pong" from
        ;; the fake. That difference is the SERVER's encoding, not the transport's;
        ;; pinning it would turn this suite into a test of `serve!`. Plain text
        ;; passes through both untouched, which is what lets the assertions below
        ;; be exact instead of approximate.
        (let [text (fn [status body]
                     {:web/raw true
                      :status  status
                      :body    body
                      :headers {"Content-Type" "text/plain"}})]
          {[:get  "/ok"]        (fn [_] (text 200 "pong"))
           [:get  "/refused"]   (fn [_] (text 400 "no thanks"))
           [:get  "/broken"]    (fn [_] (text 500 "boom"))
           [:post "/only-post"] (fn [_] (text 200 "posted"))
           [:post "/echo"]
           (fn [req] (text 200 (str "hdr=" (get-in req [:headers "x-probe"])
                                    " body=" (pr-str (:body req)))))})
        {:keys [requester base-url dead-url]} (make routes)
        at  (fn [path] (str base-url (subs path 1)))
        GET (fn [path] (requester {:http/method :get :http/url (at path)}))]
    (testing (str label " — a far side that answers hands back its status and body")
      (let [r (GET "/ok")]
        (is (= 200 (:http/status r)))
        (is (= "pong" (:http/body r)))))
    (testing (str label " — a REFUSING far side is data, never a throw, and its
                  explanation survives the trip")
      (let [r (GET "/refused")]
        (is (= 400 (:http/status r)))
        (is (str/includes? (str (:http/body r)) "no thanks"))))
    (testing (str label " — so is a BROKEN one: the transport reports, it does
                  not judge")
      (is (= 500 (:http/status (GET "/broken")))))
    (testing (str label " — an ABSENT far side THROWS, which is what keeps it
                  distinguishable from one that refused — and the throw CARRIES
                  that fact as data, so a caller can catch this and nothing
                  else. Without it the only way to catch \"no server\" is to
                  catch Exception, which also swallows every bug in the same
                  block; that is not a hypothetical, it is what the heartbeat
                  shipped.")
      (let [e (try (requester {:http/method :get :http/url (str dead-url "ok")})
                   nil
                   (catch clojure.lang.ExceptionInfo ex ex))]
        (is (some? e) "an absent far side must throw, and throw an ex-info")
        (is (= :unreachable (:http/error (ex-data e))) (pr-str (ex-data e)))
        (is (some? (:http/url (ex-data e))) "which url could not be reached")
        (is (instance? Throwable (:http/cause (ex-data e)))
            "the underlying failure is kept, or the diagnosis is lost — DNS,
             refused and timed-out are different problems")))
    (testing (str label " — the METHOD reaches the far side")
      (is (= 200 (:http/status
                  (requester {:http/method :post :http/url (at "/only-post")}))))
      (is (not= 200 (:http/status (GET "/only-post")))
          "a GET to a POST-only route must not succeed, or the method was dropped"))
    (testing (str label " — the BODY and the HEADERS reach the far side")
      (let [r (requester {:http/method  :post
                          :http/url     (at "/echo")
                          :http/headers {"X-Probe" "seen"
                                         "Content-Type" "application/json"}
                          :http/body    "{\"probe\":\"body-made-it\"}"})]
        (is (= 200 (:http/status r)))
        (is (str/includes? (str (:http/body r)) "hdr=seen")
            "a header the caller set never arrived")
        (is (str/includes? (str (:http/body r)) "body-made-it")
            "the request body never arrived")))))

(deftest the-fake-requester-meets-the-requester-contract
  ;; In-image and free: no socket, no port, no server. This is the run that
  ;; makes the fake worth using — a fake nothing has checked is a guess, and a
  ;; guess that agrees with itself is the exact "could not check" wearing the
  ;; face of "checked, fine" that Core 1 exists to prevent.
  (requester-contract
   "fake-requester"
   (fn [routes]
     {:requester (client/fake-requester "http://fake.test/" routes)
      :base-url  "http://fake.test/"
      :dead-url  "http://nobody-is-listening.test/"})))

(deftest ^:external the-jdk-transport-meets-the-requester-contract
  ;; The SAME suite, over a real socket. This is what makes the fake above a
  ;; claim about the world rather than about itself: the two runs share every
  ;; assertion, so a fake that drifts from the transport fails here.
  ;;
  ;; `^:external` for the obvious reason — it binds a port and talks to it. The
  ;; two-tier split earns its keep exactly here: the same contract costs
  ;; milliseconds in-image and a fresh JVM out of it.
  (let [srv (atom nil)]
    (try
      (requester-contract
       "jdk http transport"
       (fn [routes]
         (let [s    (web/serve!
                     {:web/namespaces []
                      :web/routes (vec (for [[[method path] handler] routes]
                                         {:method method :path path
                                          :auth :public :handler handler}))
                      :web/host "127.0.0.1" :web/port 0})
               dead (let [ss (java.net.ServerSocket. 0)
                          p  (.getLocalPort ss)]
                      (.close ss)
                      p)]
           (reset! srv s)
           {:requester client/request
            :base-url  (str "http://127.0.0.1:" (:port s) "/")
            :dead-url  (str "http://127.0.0.1:" dead "/")})))
      (finally (some-> @srv web/stop!)))))
