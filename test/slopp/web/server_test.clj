(ns slopp.web.server-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.web.server.jdk :as jdk]
            [cheshire.core :as json]))

(deftest ^:external jdk-adapter-serves-the-pipeline
  (let [ctx {:web/routes [{:handler (fn [req] {:status 200
                                               :body {:got (:web/reads req)}})
                           :method :get :path "/u/:id" :auth :public
                           :web/reads {:user [:user/by-id [:path-params :id]]}}
                          {:handler (fn [_] {:status 201 :body {:ok true}})
                           :method :post :path "/u" :auth :authenticated}]
             :web/read-performers {:user/by-id (fn [_ id] {:user/id id})}}
        srv (jdk/start! ctx {:host "127.0.0.1" :port 0})
        base (str "http://127.0.0.1:" (:port srv))
        http (java.net.http.HttpClient/newHttpClient)
        GET (fn [path]
              (let [resp (.send http
                                (-> (java.net.http.HttpRequest/newBuilder)
                                    (.uri (java.net.URI/create (str base path)))
                                    (.build))
                                (java.net.http.HttpResponse$BodyHandlers/ofString))]
                {:status (.statusCode resp) :body (.body resp)}))]
    (try
      (testing "a declared read flows end to end over the socket"
        (let [r (GET "/u/42")]
          (is (= 200 (:status r)))
          (is (= {:got {:user {:user/id "42"}}}
                 (json/parse-string (:body r) true)))))
      (testing "no route and no identity arrive as statuses, not throws"
        (is (= 404 (:status (GET "/nope"))))
        (let [resp (.send http
                          (-> (java.net.http.HttpRequest/newBuilder)
                              (.uri (java.net.URI/create (str base "/u")))
                              (.POST (java.net.http.HttpRequest$BodyPublishers/ofString "{}"))
                              (.build))
                          (java.net.http.HttpResponse$BodyHandlers/ofString))]
          (is (= 401 (.statusCode resp)))))
      (finally (jdk/stop! srv)))))

(deftest ^:external jdk-adapter-caps-the-request-body
  ;; review W8: an over-cap POST body must get 413, not buffer unbounded.
  (let [ctx {:web/routes [{:handler (fn [_] {:status 201 :body {:ok true}})
                           :method :post :path "/u" :auth :public}]
             :web/max-body-bytes 32}
        srv (jdk/start! ctx {:host "127.0.0.1" :port 0})
        base (str "http://127.0.0.1:" (:port srv))
        http (java.net.http.HttpClient/newHttpClient)
        POST (fn [body]
               (.statusCode
                (.send http
                       (-> (java.net.http.HttpRequest/newBuilder)
                           (.uri (java.net.URI/create (str base "/u")))
                           (.POST (java.net.http.HttpRequest$BodyPublishers/ofString body))
                           (.build))
                       (java.net.http.HttpResponse$BodyHandlers/ofString))))]
    (try
      (testing "a small body is accepted"
        (is (= 201 (POST "{\"a\":1}"))))
      (testing "a body over the cap is 413"
        (is (= 413 (POST (apply str (repeat 200 "x"))))))
      (finally (jdk/stop! srv)))))
