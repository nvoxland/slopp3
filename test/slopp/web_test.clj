(ns slopp.web-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.web :as web]))

(defn ^{:web/method :get :web/path "/w/mine/:owner" :web/auth :authenticated}
  t-mine
  "Row-level check inside the handler: only the owner may read."
  [req]
  (slopp.web/enforce (= (:owner (:path-params req))
                        (:web/sub (:web/identity req))))
  {:status 200 :body {:yours true}})

(deftest facade-assembles-and-enforces
  (let [ctx (web/context {:web/namespaces ['slopp.web-test]})]
    (testing "context derives the route table from var metadata"
      (is (= 1 (count (:web/routes ctx))))
      (is (= "/w/mine/:owner" (:path (first (:web/routes ctx))))))
    (testing "handle! is the portless test surface"
      (let [r (web/handle! ctx {:request-method :get :uri "/w/mine/ada"
                                :web/identity {:web/sub "ada" :web/groups #{}}})]
        (is (= 200 (:status r)) (pr-str r))))
    (testing "enforce inside the handler maps to 403 response data"
      (let [r (web/handle! ctx {:request-method :get :uri "/w/mine/ada"
                                :web/identity {:web/sub "eve" :web/groups #{}}})]
        (is (= 403 (:status r)) (pr-str r))))
    (testing "authorized? answers booleans for branching"
      (is (web/authorized? [:group "admin"] {:web/groups #{"admin"}}))
      (is (not (web/authorized? [:group "admin"] nil))))))

(deftest ^:external serve-round-trips-the-facade
  (let [srv (web/serve! {:web/namespaces ['slopp.web-test]
                         :web/port 0})
        http (java.net.http.HttpClient/newHttpClient)
        resp (.send http
                    (-> (java.net.http.HttpRequest/newBuilder)
                        (.uri (java.net.URI/create
                               (str "http://127.0.0.1:" (:port srv) "/w/mine/ada")))
                        (.build))
                    (java.net.http.HttpResponse$BodyHandlers/ofString))]
    (try
      (testing "the anonymous request is refused by the declared policy, over the wire"
        (is (= 401 (.statusCode resp))))
      (finally (web/stop! srv)))))

(deftest ^:external httpkit-adapter-round-trips-the-facade
  (let [srv (web/serve! {:web/namespaces ['slopp.web-test]
                         :web/adapter :http-kit
                         :web/port 0})
        http (java.net.http.HttpClient/newHttpClient)
        resp (.send http
                    (-> (java.net.http.HttpRequest/newBuilder)
                        (.uri (java.net.URI/create
                               (str "http://127.0.0.1:" (:port srv) "/w/mine/ada")))
                        (.build))
                    (java.net.http.HttpResponse$BodyHandlers/ofString))]
    (try
      (testing "the declared policy refuses over http-kit exactly as over jdk"
        (is (= 401 (.statusCode resp))))
      (finally (web/stop! srv)))))

(deftest ^:external auth-round-trips-over-the-wire
  (let [srv (web/serve! {:web/namespaces ['slopp.web-test]
                         :web/adapter :http-kit
                         :web/port 0
                         :web/auth-config {:auth/providers [:bearer]
                                           :auth/bearer {"ada" {:secret "tok-ada"
                                                                :groups ["dev"]}}}})
        http (java.net.http.HttpClient/newHttpClient)
        GET (fn [path & [token]]
              (let [b (cond-> (java.net.http.HttpRequest/newBuilder)
                        true (.uri (java.net.URI/create
                                    (str "http://127.0.0.1:" (:port srv) path)))
                        token (.header "Authorization" (str "Bearer " token)))]
                (.statusCode (.send http (.build b)
                                    (java.net.http.HttpResponse$BodyHandlers/ofString)))))]
    (try
      (testing "anonymous → 401; wrong token → 401; the right token → 200 (t-mine checks sub=owner)"
        (is (= 401 (GET "/w/mine/ada")))
        (is (= 401 (GET "/w/mine/ada" "wrong")))
        (is (= 200 (GET "/w/mine/ada" "tok-ada")))
        (testing "and enforce still 403s the wrong owner, authenticated or not"
          (is (= 403 (GET "/w/mine/someone-else" "tok-ada")))))
      (finally (web/stop! srv)))))
