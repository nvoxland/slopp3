(ns slopp.web.contract-test
  "Tests for contract publishing, with endpoint fixtures of its own.

  The fixtures live here rather than in `slopp.web-test` because that
  namespace's facade test asserts its own exact route COUNT — so an endpoint
  added there reds an unrelated passing test, which is how this namespace came
  to exist. A test namespace whose subject is `from-namespaces` traversal needs
  to own its route set."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.web.contract :as contract]))

(defn ^{:web/method :get :web/path "/c/things" :web/auth :public
        :web/response [:map [:things [:sequential :string]]]}
  c-list
  "Fixture: a typed GET — the ordinary case a published contract describes."
  [_req]
  {:status 200 :body {:things []}})

(defn ^{:web/method :post :web/path "/c/things" :web/auth :public
        :web/effectful true
        :web/request [:map [:name :string]]
        :web/response [:map [:id :int]]}
  c-create!
  "Fixture: a body verb — the only shape that carries a :web/request."
  [req]
  {:status 201 :body {:id (count (str (:name (:body req))))}})

(defn ^{:web/method :get :web/path "/c/page" :web/auth :public
        :web/client false :web/response :string}
  c-page
  "Fixture: an HTML page. A :web/path form like any other, and no part of a
  TYPED contract — a fetch wrapper whose (.json resp) runs against HTML is
  nonsense, which is what :web/client false already says at the client
  generator."
  [_req]
  {:status 200 :body "<h1>c</h1>"})

(deftest a-contract-publishes-the-typed-surface-and-nothing-else
  (let [doc     (contract/contract-document ['slopp.web.contract-test])
        by-addr (into {} (map (juxt (juxt :method :path) identity)) (:endpoints doc))]

    (testing "the document names its own version, so a consumer can refuse one it doesn't know"
      (is (= 1 (:slopp/contract-version doc))))

    (testing "an endpoint is addressed by method AND path — one path serves two verbs"
      (is (= #{[:get "/c/things"] [:post "/c/things"]} (set (keys by-addr)))))

    (testing "schemas travel as VALUES, equal to what the var declared"
      ;; var metadata is evaluated, so the schema is already data by the time
      ;; it is published — no store, no source text, no importer.
      (is (= [:map [:things [:sequential :string]]]
             (:response (by-addr [:get "/c/things"]))))
      (is (= [:map [:name :string]]
             (:request (by-addr [:post "/c/things"])))))

    (testing "a verb with no body says so with nil rather than by omitting the key"
      ;; a consumer must be able to tell 'no request body' from 'unknown'.
      (is (contains? (by-addr [:get "/c/things"]) :request))
      (is (nil? (:request (by-addr [:get "/c/things"])))))

    (testing "the endpoint carries its NAME — the consumer names its wrapper from it"
      (is (= 'c-list (:name (by-addr [:get "/c/things"])))))

    (testing ":web/client false opts an endpoint out, exactly as it does at the client generator"
      (is (not (contains? (set (map :path (:endpoints doc))) "/c/page"))))))
