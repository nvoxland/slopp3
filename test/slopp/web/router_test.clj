(ns slopp.web.router-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.web.router :as router]))

(deftest routes-match-methods-paths-and-params
  (let [routes [{:method :get :path "/api/users/:id" :handler 'a/get-user}
                {:method :post :path "/api/users" :handler 'a/create-user}
                {:method :get :path "/api/users/me" :handler 'a/me}
                {:method :get :path "/health" :handler 'a/health}]]
    (testing "params capture into :path-params"
      (let [m (router/match routes :get "/api/users/42")]
        (is (= 'a/get-user (:handler m)))
        (is (= {:id "42"} (:path-params m)))))
    (testing "a static segment beats a param capture"
      (is (= 'a/me (:handler (router/match routes :get "/api/users/me")))))
    (testing "the method discriminates"
      (is (= 'a/create-user (:handler (router/match routes :post "/api/users"))))
      (is (nil? (router/match routes :delete "/api/users"))))
    (testing "no match is nil, never a throw"
      (is (nil? (router/match routes :get "/nope")))
      (is (nil? (router/match routes :get ""))))
    (testing "a trailing slash is tolerated"
      (is (= 'a/health (:handler (router/match routes :get "/health/")))))))

(deftest catch-all-segment-matches-a-nested-remainder
  ;; F6: a static mount must be able to serve a TREE (/assets/cljs/main.js),
  ;; which the exact-segment-count router could not express — so slopp's own
  ;; default bundle path 404'd. A trailing *name captures one or more remaining
  ;; segments, matching the capabilities pattern convention (a trailing * is a
  ;; prefix). It must rank BELOW static and single-segment captures so existing
  ;; precedence is untouched.
  (let [routes [{:method :get :path "/assets/*path"       :handler 'a/asset}
                {:method :get :path "/assets/favicon.ico" :handler 'a/favicon}
                {:method :get :path "/assets/:one"        :handler 'a/one}]]
    (testing "a catch-all captures the whole remainder, slash-joined"
      (let [m (router/match routes :get "/assets/cljs/main.js")]
        (is (= 'a/asset (:handler m)))
        (is (= "cljs/main.js" (:path (:path-params m))))))
    (testing "a static segment still beats the catch-all"
      (is (= 'a/favicon (:handler (router/match routes :get "/assets/favicon.ico")))))
    (testing "a single-segment capture still beats the catch-all"
      (is (= 'a/one (:handler (router/match routes :get "/assets/app.css")))))
    (testing "a catch-all needs at least one segment"
      (is (nil? (router/match [{:method :get :path "/assets/*path" :handler 'a/asset}]
                              :get "/assets"))))))
