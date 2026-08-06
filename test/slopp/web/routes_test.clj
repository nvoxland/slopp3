(ns slopp.web.routes-test
  "Deriving a route TABLE from var metadata — `:web/method`, `:web/path` and
  their neighbours — which is how a slopp app declares its surface without a
  routing DSL. The declaration and the thing declared are one var, so there is
  no table to drift.

  The SPA fallback is here too, and it is the case that needs saying out loud:
  serving deep links under a declared prefix must not swallow a genuine 404.
  That is the same behavioural change `web-spa-consequences` states at the
  done point — the rule tells the author once, and this holds the code to it."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.web.routes :as routes] [slopp.web.router :as router]))

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

(defn ^{:unused-ok "the negative control for route discovery — it exists to be PASSED OVER by the scan, so having no caller is the property under test"} plain "Not an endpoint." [x] x)

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

(deftest spa-fallback-serves-deep-links-without-swallowing-404s
  ;; A client-routed app owns paths the server has no route for: /store/ns/foo
  ;; is real to the browser and meaningless to the router, so a refresh 404s.
  ;; The fix is not a catch-all — a catch-all at the root serves the app
  ;; document for EVERY unmatched path, and an app that can never 404 has no
  ;; way to tell a typo from a page.
  ;;
  ;; So it is DECLARED, per prefix: `:web/spa ["/store"]` says "I am the
  ;; document for client routes under /store", and nothing else changes.
  (let [doc  {:handler :app :method :get :path "/" :auth :public}
        rows (concat [doc
                      {:handler :ns-page :method :get :path "/store/ns/:ns" :auth :public}]
                     (routes/spa-rows doc ["/store" "/change"]))]
    (testing "one catch-all row per declared prefix, same handler"
      (is (= 2 (count (routes/spa-rows doc ["/store" "/change"]))))
      (is (every? #(= :app (:handler %)) (routes/spa-rows doc ["/store" "/change"]))))
    (testing "a real route still wins — the fallback never steals it"
      (is (= :ns-page (:handler (router/match rows :get "/store/ns/demo.core")))))
    (testing "a deep client route the server has no row for gets the document"
      (is (= :app (:handler (router/match rows :get "/store/form/f123/detail")))))
    (testing "and a path outside every declared prefix still 404s"
      ;; THE assertion that matters. A fallback that swallows this is worse
      ;; than no fallback: the app loses its only way to say "no such thing".
      (is (nil? (router/match rows :get "/nonsense")))
      (is (nil? (router/match rows :get "/api/typo"))))
    (testing "the prefix root itself is not swallowed — it is the app's own route"
      ;; /store is a real page here; only paths BELOW it fall back
      (is (nil? (router/match rows :get "/store"))
          "no row declares /store, so it 404s rather than silently rendering the app"))))
