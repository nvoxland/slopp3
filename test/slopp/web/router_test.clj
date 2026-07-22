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
