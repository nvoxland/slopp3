(ns slopp.api.web-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.api.web :as web] [slopp.api :as api] [slopp.api.external :as external]))

(deftest routes-derive-from-stored-nodes
  (let [src (str "(ns shop.api)\n\n"
                 "(defn ^{:web/method :get :web/path \"/api/users/:id\"\n"
                 "        :web/auth [:group \"admin\"]\n"
                 "        :web/reads {:user [:user/by-id [:path-params :id]]}\n"
                 "        :malli/schema [:=> [:cat :map] :map]} get-user \"U.\" [req] req)\n\n"
                 "(defn ^{:web/method :post :web/path \"/api/users\"\n"
                 "        :web/auth :authenticated\n"
                 "        :web/effects [:user/insert]} create-user \"C.\" [req] req)\n\n"
                 "(defn ^{:web/effect :user/insert} insert-user! \"I.\" [ctx row] row)\n\n"
                 "(defn ^{:web/read :user/by-id} user-by-id \"R.\" [ctx id] id)\n\n"
                 "(defn plain \"P.\" [x] x)\n")
        s0  (store/ingest (store/empty-store) 'shop.api src)
        on  (first (store/record-config-put s0 "capabilities" :manifest "http.enabled" "true"))]
    (testing "endpoints: every :web/path form, read off the stored node"
      (let [eps (web/endpoints s0)
            by-path (fn [p] (some #(when (= p (:path %)) %) eps))]
        (is (= 2 (count eps)))
        (let [e (by-path "/api/users/:id")]
          (is (= :get (:method e)))
          (is (= 'shop.api/get-user (:handler e)))
          (is (= [:group "admin"] (:auth e)))
          (is (= {:user [:user/by-id [:path-params :id]]} (:web/reads e)))
          (is (true? (:schema? e))))
        (let [e (by-path "/api/users")]
          (is (= :post (:method e)))
          (is (= [:user/insert] (:web/effects e)))
          (is (not (:schema? e))))))
    (testing "performers: the app-defined effect/read vocabulary"
      (is (= {:user/insert 'shop.api/insert-user!} (web/performers s0 :web/effect)))
      (is (= {:user/by-id 'shop.api/user-by-id} (web/performers s0 :web/read))))
    (testing "routes-report is empty-and-says-why until http.enabled"
      (is (false? (:enabled (web/routes-report s0))))
      (is (empty? (:routes (web/routes-report s0)))))
    (testing "routes-report with the capability on"
      (let [rep (web/routes-report on)]
        (is (true? (:enabled rep)))
        (is (= 2 (count (:routes rep))))
        (is (= #{:user/insert} (:effect-kinds rep)))
        (is (= #{:user/by-id} (:read-kinds rep)))))
    (testing "a test namespace's endpoint-shaped form is a fixture, not surface"
      (let [s2 (store/ingest on 'shop.api-test
                             (str "(ns shop.api-test)\n\n"
                                  "(defn ^{:web/method :get :web/path \"/fixture\"} fx \"F.\" [req] req)\n"))]
        (is (= 2 (count (:routes (web/routes-report s2)))))))))

(deftest ^:external web-gates-ride-the-write-path
  (let [sess (external/open!)]
    (try
      (api/ingest! sess 'shop.api "(ns shop.api)\n\n(defn seed \"S.\" [x] x)\n")
      (testing "before opting in, an endpoint-shaped form lands ungated (the adoption story)"
        (let [r (api/add-form! sess 'shop.api
                               "(defn ^{:web/method :get :web/path \"/pre\"} pre \"P.\" [req] req)"
                               :prompt "pre-optin endpoint")]
          (is (nil? (:error r)) (pr-str r))))
      (api/config-file! sess "capabilities" :key "http.enabled" :value "true"
                        :prompt "opt into HTTP")
      (testing "an endpoint with no :web/auth is refused with teaching, and never lands"
        (let [r (api/add-form! sess 'shop.api
                               "(defn ^{:web/method :get :web/path \"/naked\"} naked \"N.\" [req] req)"
                               :prompt "endpoint without auth")]
          (is (re-find #":web/auth" (str (:error r))) (pr-str r))
          (is (nil? (store/form-named (:store @sess) 'shop.api 'naked)))))
      (testing "with a declared policy it lands, and the route reports"
        (let [r (api/add-form! sess 'shop.api
                               (str "(defn ^{:web/method :get :web/path \"/api/ping\""
                                    " :web/auth :public} ping \"P.\" [req] req)")
                               :prompt "a public endpoint")]
          (is (nil? (:error r)) (pr-str r))
          (let [rep (web/routes-report (:store @sess))]
            (is (true? (:enabled rep)))
            (is (some #(= "/api/ping" (:path %)) (:routes rep))))))
      (finally (api/close! sess)))))
