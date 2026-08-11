(ns slopp.web.jwks-test
  "Tests for the OIDC key-fetch adapter, served over a REAL socket.

  `^:external` throughout, and that is the point rather than a cost: the whole
  content of `fetch-jwks!` is two round trips and the jwks_uri hop between
  them, so a fake that returns the second document tests nothing that was
  written. slopp ships an HTTP server, so the far side of this adapter can be
  stood up locally in a few lines — which is what makes it cheap here and is
  not true of every adapter — some reach a world no fake can stand in for.

  Neighbours: `slopp.web.auth-test` covers the POLICY these keys feed, using
  static keys and never a network."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.web.jwks :as jwks]
            [slopp.web.server.jdk :as jdk] [slopp.web.client :as web.client] [cheshire.core :as json]))

(deftest ^:external the-two-hop-discovery-lands-on-the-issuers-keys
  ;; fetch-jwks! shipped with zero coverage — ^:unused-ok, because slopp's own
  ;; store configures no OIDC so there is no in-store caller. That is a fair
  ;; reason for no CALLER and not a reason for no TEST: it is vendored into
  ;; every app slopp builds, and the first person to find out whether the two
  ;; hops work should not be someone's production login.
  ;;
  ;; Served over a real socket rather than by redefining the GET. The whole
  ;; content of this adapter is the two round trips and the jwks_uri hop
  ;; between them; a fake that returns the second document skips exactly the
  ;; part being tested.
  (let [jwk  {:kid "k1" :kty "RSA" :n "bXktbW9kdWx1cw" :e "AQAB"}
        base (atom nil)
        ctx  {:web/routes
              [{:method :get :path "/.well-known/openid-configuration" :auth :public
                :handler (fn [_] {:status 200 :body {:jwks_uri (str @base "/keys")}})}
               {:method :get :path "/keys" :auth :public
                :handler (fn [_] {:status 200 :body {:keys [jwk]}})}]}
        srv  (jdk/start! ctx {:host "127.0.0.1" :port 0})]
    (reset! base (str "http://127.0.0.1:" (:port srv)))
    (try
      (testing "discovery names the jwks_uri, and the key set comes back from it"
        (is (= [jwk] (jwks/fetch-jwks! @base))))
      (testing "an issuer that is not there THROWS — a misconfiguration must
                fail at startup, not 401 forever"
        (is (thrown? Exception (jwks/fetch-jwks! "http://127.0.0.1:1"))))
      (finally (jdk/stop! srv)))))

(deftest the-second-hop-is-read-from-the-first-document-not-guessed
  ;; The `^:external` sibling proves this works over a real socket. What it
  ;; cannot do cheaply is prove the two hops are actually CHAINED — that the
  ;; second url comes out of the discovery document rather than being assembled
  ;; from a convention that happens to match. Point the fake's `jwks_uri`
  ;; somewhere a guesser would never look, and only a real chain finds it.
  (let [issuer  "https://idp.test"
        keys-at (fn [path]
                  (web.client/fake-requester
                   issuer
                   {[:get "/.well-known/openid-configuration"]
                    (fn [_] {:status 200
                             :body (json/generate-string
                                    {:jwks_uri (str issuer path)})})
                    [:get path]
                    (fn [_] {:status 200
                             :body (json/generate-string
                                    {:keys [{:kid "k1" :kty "RSA"}]})})}))]
    (testing "the jwks_uri is followed wherever the issuer puts it"
      (is (= [{:kid "k1" :kty "RSA"}]
             (jwks/fetch-jwks! issuer (keys-at "/somewhere/nobody/would/guess")))))
    (testing "a discovery document that 404s fails LOUDLY, carrying the status —
              it used to parse the 404 body into nil, then request the string
              \"null\", so a misconfigured issuer failed three layers from the
              mistake"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"404"
                            (jwks/fetch-jwks! issuer
                                              (web.client/fake-requester issuer {})))))
    (testing "and so does a discovery document naming a jwks_uri the issuer does
              not serve — the failure names the hop that failed"
      (let [dangling (web.client/fake-requester
                      issuer
                      {[:get "/.well-known/openid-configuration"]
                       (fn [_] {:status 200
                                :body (json/generate-string
                                       {:jwks_uri (str issuer "/gone")})})})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"/gone"
                              (jwks/fetch-jwks! issuer dangling)))))))
