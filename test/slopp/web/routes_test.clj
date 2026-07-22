(ns slopp.web.routes-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.web.routes :as routes]))

(defn ^{:web/method :get :web/path "/t/users/:id" :web/auth :public
        :web/reads {:user [:user/by-id [:path-params :id]]}}
  t-get
  "Test endpoint."
  [req]
  {:status 200 :body (:web/reads req)})

(defn ^{:web/method :post :web/path "/t/users" :web/auth :authenticated
        :web/effects [:user/insert]}
  t-post
  "Test endpoint."
  [req]
  {:status 201 :web/effects [[:user/insert (:body req)]]})

(defn ^{:web/effect :user/insert} t-insert!
  "Test performer."
  [ctx row]
  (swap! (:db ctx) conj row))

(defn ^{:web/read :user/by-id} t-by-id
  "Test read performer."
  [_ctx id]
  {:user/id id})

(defn ^:unused-ok plain "Not an endpoint." [x] x)

(deftest routes-derive-from-var-metadata
  (let [rows (routes/from-namespaces ['slopp.web.routes-test])]
    (testing "endpoint vars become rows; unmarked vars don't"
      (is (= 2 (count rows)))
      (is (= #{"/t/users/:id" "/t/users"} (set (map :path rows)))))
    (testing "the row carries the contract and the CALLABLE var"
      (let [row (first (filter #(= "/t/users/:id" (:path %)) rows))]
        (is (= :get (:method row)))
        (is (= :public (:auth row)))
        (is (= {:user [:user/by-id [:path-params :id]]} (:web/reads row)))
        (is (var? (:handler row)))
        (is (= 200 (:status ((:handler row) {:web/reads :probe}))))))
    (testing "performers index by kind, var-callable"
      (let [effects (routes/performers-from-namespaces ['slopp.web.routes-test] :web/effect)
            reads   (routes/performers-from-namespaces ['slopp.web.routes-test] :web/read)]
        (is (var? (get effects :user/insert)))
        (is (= {:user/id "7"} ((get reads :user/by-id) {} "7")))))))
