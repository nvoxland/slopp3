(ns slopp.edit.modules-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.edit.modules :as modules]))

(deftest web-gates-guard-the-declared-surface
  (let [src (str "(ns shop.api)\n\n"
                 "(defn ^{:web/method :get :web/path \"/api/users/:id\"\n"
                 "        :web/auth [:group \"admin\"]} get-user \"U.\" [req] req)\n\n"
                 "(defn ^{:web/effect :user/insert} insert-user! \"I.\" [ctx row] row)\n")
        s0  (store/ingest (store/empty-store) 'shop.api src)
        on  (first (store/record-config-put s0 "capabilities" :manifest "http.enabled" "true"))
        land (fn [st form-src]
               (store/ingest st 'shop.more (str "(ns shop.more)\n\n" form-src "\n")))]
    (testing "OFF: no web gate fires while http.enabled is absent"
      (let [s (land s0 "(defn ^{:web/method :get :web/path \"/x\"} bare \"B.\" [req] req)")]
        (is (nil? (modules/web-auth-refusal s 'shop.more 'bare)))))
    (testing "web-auth-refusal: an endpoint with no :web/auth refuses with teaching"
      (let [s (land on "(defn ^{:web/method :get :web/path \"/x\"} bare \"B.\" [req] req)")]
        (is (re-find #":web/auth" (str (modules/web-auth-refusal s 'shop.more 'bare))))
        (testing "a declared :public discharges it — deny is the default, not the ceiling"
          (let [s2 (land on "(defn ^{:web/method :get :web/path \"/x\" :web/auth :public} open \"O.\" [req] req)")]
            (is (nil? (modules/web-auth-refusal s2 'shop.more 'open)))))
        (testing "a non-endpoint never trips it"
          (is (nil? (modules/web-auth-refusal s 'shop.api 'insert-user!))))))
    (testing "web-route-collision: a second claim on method+path refuses; the same form re-landing does not"
      (let [s (land on "(defn ^{:web/method :get :web/path \"/api/users/:id\" :web/auth :public} dupe \"D.\" [req] req)")]
        (is (re-find #"/api/users/:id" (str (modules/web-route-collision s 'shop.more 'dupe))))
        (is (nil? (modules/web-route-collision on 'shop.api 'get-user))
            "a form is never its own collision (the re-land/replace case)")
        (testing "same path, different method, no collision"
          (let [s2 (land on "(defn ^{:web/method :post :web/path \"/api/users/:id\" :web/auth :public} other \"O.\" [req] req)")]
            (is (nil? (modules/web-route-collision s2 'shop.more 'other)))))))
    (testing "web-undeclared-effect: a declared kind needs a marked performer"
      (let [s (land on (str "(defn ^{:web/method :post :web/path \"/y\" :web/auth :public\n"
                            "        :web/effects [:user/insert :email/welcome]} mk \"M.\" [req] req)"))]
        (is (re-find #":email/welcome" (str (modules/web-undeclared-effect s 'shop.more 'mk))))
        (testing "every kind covered → clean"
          (let [s2 (land on (str "(defn ^{:web/method :post :web/path \"/y\" :web/auth :public\n"
                                 "        :web/effects [:user/insert]} mk2 \"M.\" [req] req)"))]
            (is (nil? (modules/web-undeclared-effect s2 'shop.more 'mk2)))))))
    (testing "web-unsafe-get: a GET declaring effect kinds refuses; a POST doing the same is fine"
      (let [s (land on (str "(defn ^{:web/method :get :web/path \"/z\" :web/auth :public\n"
                            "        :web/effects [:user/insert]} gz \"G.\" [req] req)"))]
        (is (re-find #"GET" (str (modules/web-unsafe-get s 'shop.more 'gz))))
        (let [s2 (land on (str "(defn ^{:web/method :post :web/path \"/z\" :web/auth :public\n"
                               "        :web/effects [:user/insert]} pz \"P.\" [req] req)"))]
          (is (nil? (modules/web-unsafe-get s2 'shop.more 'pz))))))
    (testing "web-unsafe-get: a GET whose handler reaches a mutation refuses"
      (let [s (land on (str "(def store-atom (atom {}))\n\n"
                            "(defn ^{:web/method :get :web/path \"/w\" :web/auth :public} gw \"G.\" [req]\n"
                            "  (swap! store-atom assoc :hit req))"))]
        (is (re-find #"mutation" (str (modules/web-unsafe-get s 'shop.more 'gw))))))))

(deftest web-unknown-group-guards-the-policy-vocabulary
  (let [s0 (store/ingest (store/empty-store) 'shop.api "(ns shop.api)\n\n(defn seed \"S.\" [x] x)\n")
        on (-> s0
               (store/record-config-put "capabilities" :manifest "http.enabled" "true") first
               (store/record-config-put "capabilities" :manifest "groups.admin.members" "alice") first)
        land (fn [st form-src]
               (store/ingest st 'shop.more (str "(ns shop.more)\n\n" form-src "\n")))]
    (testing "a policy naming a configured group lands"
      (let [s (land on "(defn ^{:web/method :get :web/path \"/a\" :web/auth [:group \"admin\"]} a \"A.\" [req] req)")]
        (is (nil? (modules/web-unknown-group s 'shop.more 'a)))))
    (testing "a typo'd group refuses with the configured vocabulary in the teaching"
      (let [s (land on "(defn ^{:web/method :get :web/path \"/b\" :web/auth [:group \"admn\"]} b \"B.\" [req] req)")]
        (is (re-find #"admn" (str (modules/web-unknown-group s 'shop.more 'b))))
        (is (re-find #"admin" (str (modules/web-unknown-group s 'shop.more 'b))))))
    (testing "composite policies are walked"
      (let [s (land on "(defn ^{:web/method :get :web/path \"/c\" :web/auth [:any :authenticated [:group \"ghost\"]]} c \"C.\" [req] req)")]
        (is (re-find #"ghost" (str (modules/web-unknown-group s 'shop.more 'c))))))
    (testing "inert until http.enabled"
      (let [s (land s0 "(defn ^{:web/method :get :web/path \"/d\" :web/auth [:group \"ghost\"]} d \"D.\" [req] req)")]
        (is (nil? (modules/web-unknown-group s 'shop.more 'd)))))))
