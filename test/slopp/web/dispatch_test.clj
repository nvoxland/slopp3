(ns slopp.web.dispatch-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.web.dispatch :as dispatch]))

(deftest dispatch-runs-the-whole-pipeline-portlessly
  (let [performed (atom [])
        routes [{:handler (fn [req] {:status 200 :body {:got (:web/reads req)}})
                 :method :get :path "/u/:id" :auth :public
                 :web/reads {:user [:user/by-id [:path-params :id]]}}
                {:handler (fn [req] {:status 201 :body {:ok true}
                                     :web/effects [[:user/insert (:body req)]
                                                   [:email/welcome "hi"]]})
                 :method :post :path "/u" :auth [:group "admin"]
                 :web/effects [:user/insert :email/welcome]}
                {:handler (fn [_] {:status 201 :web/effects [[:rogue/kind 1]]})
                 :method :post :path "/rogue" :auth :public
                 :web/effects [:rogue/kind]}]
        ctx {:web/routes routes
             :web/read-performers {:user/by-id (fn [_ id] {:user/id id})}
             :web/effect-performers {:user/insert (fn [_ row] (swap! performed conj [:insert row]))
                                     :email/welcome (fn [_ to] (swap! performed conj [:mail to]))}}]
    (testing "no route → 404 data, never a throw"
      (is (= 404 (:status (dispatch/handle! ctx {:request-method :get :uri "/nope"})))))
    (testing "policy runs BEFORE the handler: :authenticated-shaped policies refuse first"
      (is (= 401 (:status (dispatch/handle! ctx {:request-method :post :uri "/u"}))))
      (is (= 403 (:status (dispatch/handle! ctx {:request-method :post :uri "/u"
                                                :web/identity {:web/sub "eve" :web/groups #{"dev"}}})))))
    (testing "declared reads are fetched before the handler; the handler needs no stub"
      (let [r (dispatch/handle! ctx {:request-method :get :uri "/u/42"})]
        (is (= 200 (:status r)))
        (is (= {:user {:user/id "42"}} (:got (:body r))))))
    (testing "the perform-ctx reaches the handler as :web/deps"
      (let [ctx2 (assoc ctx
                        :web/perform-ctx {:who :deps-probe}
                        :web/routes [{:handler (fn [req] {:status 200
                                                          :body {:deps (:web/deps req)}})
                                      :method :get :path "/deps" :auth :public}])]
        (is (= {:who :deps-probe}
               (:deps (:body (dispatch/handle! ctx2 {:request-method :get :uri "/deps"})))))))
    (testing "returned effects run in order through the performers"
      (reset! performed [])
      (let [r (dispatch/handle! ctx {:request-method :post :uri "/u"
                                    :body {:user/name "ada"}
                                    :web/identity {:web/sub "root" :web/groups #{"admin"}}})]
        (is (= 201 (:status r)))
        (is (= [[:insert {:user/name "ada"}] [:mail "hi"]] @performed))))
    (testing "an effect kind with no performer refuses at runtime, effects-so-far intact"
      (reset! performed [])
      (let [r (dispatch/handle! ctx {:request-method :post :uri "/rogue"})]
        (is (= 500 (:status r)))
        (is (empty? @performed))))))
