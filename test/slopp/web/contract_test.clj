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
  "Fixture: a typed GET — the ordinary case a published contract describes.

  Deliberately MULTI-LINE with a paragraph break, because a handler docstring
  is multi-line by construction and the continuation lines carry the source's
  own indentation. A document that ships that indentation ships a fact about
  our formatting to every consumer."
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

(defn ^{:web/method :get :web/path "/c/bare" :web/auth :public
        :web/response [:map [:ok :boolean]]}
  c-bare
  [_req]
  {:status 200 :body {:ok true}})

(deftest a-contract-publishes-the-typed-surface-and-nothing-else
  (let [doc     (contract/contract-document ['slopp.web.contract-test])
        by-addr (into {} (map (juxt (juxt :method :path) identity)) (:endpoints doc))]

    (testing "the document names its own version, so a consumer can refuse one it doesn't know"
      (is (= 1 (:slopp/contract-version doc))))

    (testing "an endpoint is addressed by method AND path — one path serves two verbs"
      (is (= #{[:get "/c/things"] [:post "/c/things"] [:get "/c/bare"]}
             (set (keys by-addr)))))

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

(deftest an-endpoint-says-what-it-IS-and-WHERE-it-lives
  ;; Two keys, both asked for by slopp-ui after measuring what the document
  ;; could not answer, and both derived from var METADATA — which is the whole
  ;; constraint this namespace exists under. Neither needs a store, so a jar and
  ;; a native binary publish them identically.
  ;;
  ;; :handler, because :name alone does not resolve. Measured against slopp's
  ;; own nine endpoints: `ns-outline`, `search` and `timeline` each match more
  ;; than one form by simple name — a third of the surface — so a consumer
  ;; linking to "the form called :name" points at the wrong one and looks right
  ;; doing it. (The same measurement, from the other direction, is why
  ;; rules.web's schema resolver goes through the reference graph.)
  ;;
  ;; :doc, because the prose already exists. Every handler here opens with
  ;; `GET <path> — what it is`; it was written for the API's reader and it
  ;; stopped at the process boundary, because a docstring is not a value.
  (let [doc     (contract/contract-document ['slopp.web.contract-test])
        by-addr (into {} (map (juxt (juxt :method :path) identity)) (:endpoints doc))
        things  (by-addr [:get "/c/things"])]

    (testing "the handler's QUALIFIED symbol — a name a consumer can resolve"
      (is (= 'slopp.web.contract-test/c-list (:handler things)))
      (is (= 'slopp.web.contract-test/c-create! (:handler (by-addr [:post "/c/things"])))))

    (testing ":name stays exactly as it was — the client generator names its
              wrapper from it, and this is additive"
      (is (= 'c-list (:name things))))

    (testing "the docstring travels WHOLE. slopp-ui takes the first sentence for
              its index and renders the rest on the endpoint's page: a document
              that ships only a first line cannot be un-truncated by a consumer
              that wants the rest, and the reverse is free"
      (is (re-find #"^Fixture: a typed GET" (:doc things)))
      (is (re-find #"ships a fact about\nour formatting" (:doc things))
          "the tail is present, and the paragraph break with it"))

    (testing "and it arrives DE-INDENTED. A docstring's continuation lines carry
              the source's own indentation, which is an artifact of where the
              form sits in a file — publishing it ships our formatting to every
              consumer, the same trap a schema :doc falls into"
      (is (not (re-find #"\n  \S" (:doc things)))
          (str "no continuation line should start with the source indent: "
               (pr-str (:doc things)))))

    (testing "an endpoint with no docstring says so with nil rather than by
              omitting the key — the same discipline :request already follows"
      (let [bare (by-addr [:get "/c/bare"])]
        (is (contains? bare :doc))
        (is (nil? (:doc bare)))))))
