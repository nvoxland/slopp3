(ns slopp.web.dispatch-test
  "The request pipeline with no socket under it — `handle!` takes a request map
  and returns a response map, so everything between those two is checkable
  in-image: routing, auth policy, schema validation, effect interpretation, and
  what happens when a handler blows up.

  That is the whole reason `dispatch` is separate from `web.server.*`. The
  adapters own bytes and ports and need the external tier; the decisions live
  here and cost milliseconds.

  Where a test needs a handler to FAIL, it fails the way real third-party code
  does — including one that throws a bare exception leaking a filesystem path,
  because masking that is the thing being asserted."
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

(deftest dispatch-resolves-identity-from-auth-config
  (let [ctx {:web/routes [{:handler (fn [req] {:status 200
                                               :body {:sub (:web/sub (:web/identity req))}})
                           :method :get :path "/who" :auth :authenticated}]
             :web/auth-config {:auth/providers [:bearer]
                               :auth/bearer {"ci" {:secret "tok-9" :groups ["ci"]}}}}]
    (testing "a bearer header authenticates through the configured providers"
      (let [r (dispatch/handle! ctx {:request-method :get :uri "/who"
                                     :headers {"authorization" "Bearer tok-9"}})]
        (is (= 200 (:status r)) (pr-str r))
        (is (= "ci" (:sub (:body r))))))
    (testing "anonymous stays 401"
      (is (= 401 (:status (dispatch/handle! ctx {:request-method :get :uri "/who"})))))
    (testing "a pre-resolved :web/identity is respected over resolution"
      (is (= "pre" (:sub (:body (dispatch/handle! ctx {:request-method :get :uri "/who"
                                                       :web/identity {:web/sub "pre"}}))))))))

(deftest empty-composite-policies-fail-closed
  ;; review W1: `(every? pred '())` is true, so [:all] (an empty conjunction)
  ;; authorized EVERYONE incl. anonymous — the one degenerate policy that
  ;; failed OPEN while [:any]/[:group]/nil all denied. A composite with no
  ;; sub-policies must deny.
  (testing "[:all] with no sub-policies denies (anonymous and authenticated)"
    (is (not (dispatch/authorized? [:all] nil)))
    (is (not (dispatch/authorized? [:all] {:web/sub "x" :web/groups #{}}))))
  (testing "[:any] with no sub-policies denies"
    (is (not (dispatch/authorized? [:any] nil))))
  (testing "non-empty composites still work"
    (is (dispatch/authorized? [:all :authenticated] {:web/sub "x"}))
    (is (not (dispatch/authorized? [:all :authenticated [:group "admin"]] {:web/sub "x"})))
    (is (dispatch/authorized? [:any [:group "a"] [:group "b"]] {:web/groups #{"b"}}))))

(deftest handler-cannot-emit-an-undeclared-effect-kind
  ;; review W4: run-effects! validated only against the app-wide performer
  ;; set, never the ROUTE's declared :web/effects — so a handler could emit
  ;; any kind a performer provides, incl. a write from a route that declared
  ;; none (or a :get that web-unsafe-get "proved" safe). The static gate
  ;; sees only what it can read in the handler body; the runtime must bound
  ;; effects to the route's declaration.
  (let [performed (atom [])
        ctx {:web/routes [{:handler (fn [_] {:status 200
                                             :web/effects [[:danger/write "pwned"]]})
                           :method :get :path "/x" :auth :public
                           :web/effects nil}]   ; declares NO effects
             :web/effect-performers {:danger/write (fn [_ v] (swap! performed conj v))}}]
    (testing "an effect kind the route did not declare is refused, nothing performed"
      (reset! performed [])
      (let [r (dispatch/handle! ctx {:request-method :get :uri "/x"})]
        (is (= 500 (:status r)) (pr-str r))
        (is (empty? @performed))))))

(deftest ^{:bare-throw-ok "the bare throw IS the subject. This asserts that a handler
              which leaks a filesystem path through a non-ex-info exception has
              that detail MASKED before it reaches a client — so the fixture has
              to throw exactly the kind of exception the rule elsewhere
              forbids. Replacing it with ex-info would test the opposite case."}
  error-responses-do-not-leak-internal-detail
  ;; review W3: the catch returned raw ex-message + the whole ex-data (minus
  ;; :web/status). A 500 disclosed lib exception messages (paths); a handler
  ;; ex-info disclosed whatever it carried. Unexpected errors get a generic
  ;; body; deliberate boundary errors surface their message and ONLY a
  ;; :web/public allowlist.
  (let [ctx {:web/routes
             [{:handler (fn [_] (throw (java.io.FileNotFoundException. "/etc/shadow (nope)")))
               :method :get :path "/boom" :auth :public}
              {:handler (fn [_] (throw (ex-info "bad request"
                                               {:web/status 400
                                                :db/password "hunter2"
                                                :web/public {:field "email"}})))
               :method :get :path "/bad" :auth :public}]}]
    (testing "an UNEXPECTED error is a generic 500 — no message, no data leak"
      (let [r (dispatch/handle! ctx {:request-method :get :uri "/boom"})]
        (is (= 500 (:status r)))
        (is (not (re-find #"shadow|etc" (str (:body r)))) (pr-str r))))
    (testing "a DELIBERATE boundary error surfaces its message + only :web/public"
      (let [r (dispatch/handle! ctx {:request-method :get :uri "/bad"})]
        (is (= 400 (:status r)))
        (is (= "bad request" (:error (:body r))))
        (is (= {:field "email"} (:data (:body r))))
        (is (not (re-find #"hunter2|password" (str (:body r)))) (pr-str r))))))

(deftest bounded-body-caps-the-request-read
  ;; review W8: both adapters slurp the whole body unbounded (JDK → heap/OOM
  ;; DoS; http-kit falls back to its own default), and the configured
  ;; http.max-body-bytes was read by nothing. The shared bounded reader caps
  ;; it and signals overflow so the adapter can answer 413.
  (let [in (fn [s] (java.io.ByteArrayInputStream. (.getBytes (str s) "UTF-8")))]
    (testing "a body within the cap reads through"
      (is (= "hello" (:body (dispatch/bounded-body-string (in "hello") 1024)))))
    (testing "a body over the cap signals :too-large, does not return content"
      (let [r (dispatch/bounded-body-string (in (apply str (repeat 100 "x"))) 10)]
        (is (:too-large r))
        (is (nil? (:body r)))))
    (testing "nil stream is an empty body, never a throw"
      (is (nil? (:body (dispatch/bounded-body-string nil 1024)))))
    (testing "exactly-at-cap is allowed"
      (is (= "12345" (:body (dispatch/bounded-body-string (in "12345") 5)))))))
