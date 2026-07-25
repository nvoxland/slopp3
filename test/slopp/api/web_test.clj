(ns slopp.api.web-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.api.web :as web] [slopp.api :as api] [slopp.api.external :as external]))

(deftest routes-derive-from-stored-nodes
  (let [src (str "(ns shop.api)\n\n"
                 "(defn ^{:web/method :get :web/path \"/api/users/:id\"\n"
                 "        :web/auth [:group \"admin\"]\n"
                 "        :web/reads {:user [:user/by-id [:path-params :id]]}\n"
                 "        :malli/schema [:=> [:cat :map] :map]\n"
                 "        :web/response :map} get-user \"U.\" [req] req)\n\n"
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
      (testing "with a declared policy and response contract it lands, and the route reports"
        (let [r (api/add-form! sess 'shop.api
                               (str "(defn ^{:web/method :get :web/path \"/api/ping\""
                                    " :web/auth :public :web/response :map} ping \"P.\" [req] req)")
                               :prompt "a public endpoint")]
          (is (nil? (:error r)) (pr-str r))
          (let [rep (web/routes-report (:store @sess))]
            (is (true? (:enabled rep)))
            (is (some #(= "/api/ping" (:path %)) (:routes rep))))))
      (finally (api/close! sess)))))

(deftest ui-route-refs-classify-link-targets
  (let [src (str "(ns shop.ui)\n\n"
                 "(defn nav \"N.\" []\n"
                 "  [:nav [:a {:href \"/store\"} \"s\"]\n"
                 "        [:a {:href \"https://x.example/a\"} \"ext\"]\n"
                 "        [:a {:href \"#top\"} \"anchor\"]])\n\n"
                 "(defn source-link \"S.\" [nsx]\n"
                 "  [:a {:href (str \"/store/source/\" nsx)} \"src\"])\n\n"
                 "(defn todo-form \"F.\" []\n"
                 "  [:form {:action \"/todos\" :method \"post\"} [:button \"go\"]])\n\n"
                 "(defn dyn \"D.\" [req] [:a {:href (:uri req)} \"d\"])\n\n"
                 "(defn ^{:web/external-path \"nginx serves it\"} ext-link \"E.\" []\n"
                 "  [:a {:href \"/behind-nginx\"} \"x\"])\n")
        s    (store/ingest (store/empty-store) 'shop.ui src)
        s    (store/ingest s 'shop.ui-test
                           "(ns shop.ui-test)\n\n(defn fx \"X.\" [] [:a {:href \"/fixture-only\"} \"f\"])\n")
        refs (web/ui-route-refs s)
        of   (fn [kind] (set (map #(select-keys % [:form :attr :method :path])
                                  (filter #(= kind (:kind %)) refs))))]
    (testing "root-relative literals are exact refs; absolute URLs and anchors are skipped"
      (is (= #{{:form 'shop.ui/nav :attr :href :method :get :path "/store"}
               {:form 'shop.ui/todo-form :attr :action :method :post :path "/todos"}}
             (of :exact))))
    (testing "(str \"/literal/\" …) is a prefix ref"
      (is (= #{{:form 'shop.ui/source-link :attr :href :method :get :path "/store/source/"}}
             (of :prefix))))
    (testing "a dynamic value is NAMED, never counted clean"
      (is (= '[shop.ui/dyn] (mapv :form (filter #(= :unresolved (:kind %)) refs)))))
    (testing "^{:web/external-path} discharges the form's refs; test namespaces are fixtures"
      (is (not-any? #(#{'shop.ui/ext-link 'shop.ui-test/fx} (:form %)) refs)))))

(deftest dangling-route-refs-join-declared-routes-and-static-mounts
  (let [src (str "(ns shop.ui)\n\n"
                 "(defn ^{:web/method :get :web/path \"/todos\" :web/auth :public} todos-page \"T.\" [req]\n"
                 "  [:div [:a {:href \"/todos\"} \"self\"]\n"
                 "        [:a {:href \"/nowhere\"} \"bad\"]\n"
                 "        [:a {:href \"/assets/app.css\"} \"css\"]\n"
                 "        [:a {:href \"/assets/missing.css\"} \"gone-file\"]\n"
                 "        [:a {:href (str \"/todo/\" 7)} \"one\"]\n"
                 "        [:a {:href (str \"/gone/\" 7)} \"prefix-bad\"]\n"
                 "        [:a {:href (:uri req)} \"dyn\"]])\n\n"
                 "(defn ^{:web/method :get :web/path \"/todo/:id\" :web/auth :public} todo-page \"O.\" [req] req)\n")
        s (store/ingest (store/empty-store) 'shop.ui src)
        s (first (store/record-config-put s "capabilities" :manifest "http.enabled" "true"))
        s (first (store/record-config-put s "capabilities" :manifest "http.static./assets" "public"))
        s (first (store/record-file-put s "public/app.css" "body{}"))
        {:keys [dangling unresolved]} (web/dangling-route-refs s)]
    (testing "unserved refs: no route, mount without the file, prefix into nothing"
      (is (= #{["/nowhere" :exact] ["/assets/missing.css" :exact] ["/gone/" :prefix]}
             (set (map (juxt :path :kind) dangling)))))
    (testing "dynamic refs are named, not counted clean"
      (is (= '[shop.ui/todos-page] (mapv :form unresolved))))))

(deftest query-routes-carries-rendered-by
  (let [src (str "(ns shop.ui)\n\n"
                 "(defn ^{:web/method :get :web/path \"/todos\" :web/auth :public} todos-page \"T.\" [req]\n"
                 "  [:div [:a {:href \"/todos\"} \"self\"] [:a {:href (str \"/todo/\" 7)} \"one\"]])\n\n"
                 "(defn ^{:web/method :get :web/path \"/todo/:id\" :web/auth :public} todo-page \"O.\" [req]\n"
                 "  [:a {:href \"/todos\"} \"back\"])\n")
        s (store/ingest (store/empty-store) 'shop.ui src)
        s (first (store/record-config-put s "capabilities" :manifest "http.enabled" "true"))
        rows (:routes (web/routes-report s))
        by-path (fn [p] (some #(when (= p (:path %)) %) rows))]
    (testing "exact refs attach through the matcher, prefix refs through the path pattern"
      (is (= '[shop.ui/todo-page shop.ui/todos-page]
             (:rendered-by (by-path "/todos"))))
      (is (= '[shop.ui/todos-page]
             (:rendered-by (by-path "/todo/:id")))))))

(deftest ^:external done-surfaces-dangling-route-refs
  (let [sess (external/open!)]
    (try
      (api/ingest! sess 'ui.core
                   (str "(ns ui.core)\n\n"
                        "(defn ^{:web/method :get :web/path \"/home\" :web/auth :public :web/response :map} home \"H.\" [req]\n"
                        "  [:a {:href \"/nowhere\"} \"x\"])\n"))
      (testing "inert until http.enabled"
        (let [r (external/done! sess :label "pre-optin")]
          (is (empty? (get-in r [:findings :web-dangling-route-refs]))
              (pr-str (:findings r)))))
      (api/config-file! sess "capabilities" :key "http.enabled" :value "true"
                        :prompt "opt into HTTP")
      (testing "a dangling href fires with the form and path"
        (let [r (external/done! sess :label "dangling")]
          (is (= [{:form 'ui.core/home :attr :href :path "/nowhere"}]
                 (mapv #(select-keys % [:form :attr :path])
                       (get-in r [:findings :web-dangling-route-refs])))
              (pr-str (:findings r)))))
      (testing "adding the route discharges"
        (api/add-form! sess 'ui.core
                       "(defn ^{:web/method :get :web/path \"/nowhere\" :web/auth :public :web/response :map} nowhere \"N.\" [req] req)"
                       :prompt "serve the missing route")
        (let [r (external/done! sess :label "served")]
          (is (empty? (get-in r [:findings :web-dangling-route-refs]))
              (pr-str (:findings r)))))
      (finally (api/close! sess)))))

(deftest ^:external react-attr-names-refuse-at-the-write
  (let [sess (external/open!)]
    (try
      (api/ingest! sess 'ui.rx "(ns ui.rx)\n\n(defn seed \"S.\" [x] x)\n")
      (api/config-file! sess "capabilities" :key "http.enabled" :value "true"
                        :prompt "opt into HTTP")
      (testing "a React attribute name in a literal hiccup element refuses, teaching the HTML spelling"
        (let [r (api/add-form! sess 'ui.rx
                               "(defn card \"C.\" [] [:div {:className \"x\"} \"c\"])"
                               :prompt "a React-ism")]
          (is (re-find #":class\b" (str (:error r))) (pr-str r))
          (is (nil? (store/form-named (:store @sess) 'ui.rx 'card)))))
      (testing "the HTML spelling lands"
        (let [r (api/add-form! sess 'ui.rx
                               "(defn card \"C.\" [] [:div {:class \"x\"} \"c\"])"
                               :prompt "correct spelling")]
          (is (nil? (:error r)) (pr-str r))))
      (finally (api/close! sess)))))

(deftest ^:external web-endpoint-schema-gate-requires-a-response-contract
  (let [sess (external/open!)]
    (try
      (api/ingest! sess 'shopc.api "(ns shopc.api)\n\n(defn seed \"S.\" [x] x)\n")
      (testing "before opt-in, an endpoint without :web/response lands (grandfathered)"
        (let [r (api/add-form! sess 'shopc.api
                               "(defn ^{:web/method :get :web/path \"/pre\" :web/auth :public} pre \"P.\" [req] req)"
                               :prompt "pre-optin endpoint")]
          (is (nil? (:error r)) (pr-str r))))
      (api/config-file! sess "capabilities" :key "http.enabled" :value "true"
                        :prompt "opt into HTTP")
      (testing "under opt-in, an endpoint with auth but NO :web/response is refused, never lands"
        (let [r (api/add-form! sess 'shopc.api
                               "(defn ^{:web/method :get :web/path \"/list\" :web/auth :public} list-it \"L.\" [req] req)"
                               :prompt "no response contract")]
          (is (re-find #":web/response" (str (:error r))) (pr-str r))
          (is (nil? (store/form-named (:store @sess) 'shopc.api 'list-it)) "never lands")))
      (testing "declaring :web/response (here inline) lets it land"
        (let [r (api/add-form! sess 'shopc.api
                               (str "(defn ^{:web/method :get :web/path \"/ok\" :web/auth :public"
                                    " :web/response [:map [:n :int]]} ok \"O.\" [req] req)")
                               :prompt "with a response contract")]
          (is (nil? (:error r)) (pr-str r))
          (is (some? (store/form-named (:store @sess) 'shopc.api 'ok)))))
      (finally (api/close! sess)))))

(deftest ^:external web-endpoint-schema-requires-request-on-body-methods
  (let [sess (external/open!)]
    (try
      (api/ingest! sess 'shopr.api "(ns shopr.api)\n\n(defn seed \"S.\" [x] x)\n")
      (api/config-file! sess "capabilities" :key "http.enabled" :value "true"
                        :prompt "opt into HTTP")
      (testing "a POST endpoint with :web/response but NO :web/request is refused"
        (let [r (api/add-form! sess 'shopr.api
                               (str "(defn ^{:web/method :post :web/path \"/orders\" :web/auth :public"
                                    " :web/response [:map [:id :int]]} create \"C.\" [req] req)")
                               :prompt "no request contract")]
          (is (re-find #":web/request" (str (:error r))) (pr-str r))
          (is (nil? (store/form-named (:store @sess) 'shopr.api 'create)))))
      (testing "a GET endpoint needs only :web/response (no request body)"
        (let [r (api/add-form! sess 'shopr.api
                               (str "(defn ^{:web/method :get :web/path \"/orders\" :web/auth :public"
                                    " :web/response [:map]} listing \"L.\" [req] req)")
                               :prompt "get needs only response")]
          (is (nil? (:error r)) (pr-str r))))
      (testing "declaring both contracts lets the POST land"
        (let [r (api/add-form! sess 'shopr.api
                               (str "(defn ^{:web/method :post :web/path \"/orders2\" :web/auth :public"
                                    " :web/request [:map [:item :string]] :web/response [:map [:id :int]]}"
                                    " create2 \"C.\" [req] req)")
                               :prompt "both contracts")]
          (is (nil? (:error r)) (pr-str r))
          (is (some? (store/form-named (:store @sess) 'shopr.api 'create2)))))
      (finally (api/close! sess)))))

(deftest routes-surface-the-declared-contract
  ;; D-web-contracts dogfood finding: the endpoint-schema gate makes
  ;; :web/request / :web/response MANDATORY, but query_routes computed :schema?
  ;; from :malli/schema — a DIFFERENT key — so every contract-carrying endpoint
  ;; reported :schema? false. What query_routes shows must be what the gate
  ;; enforces.
  (let [s   (store/ingest (store/empty-store) 'rc.api
                          (str "(ns rc.api)\n\n"
                               "(defn ^{:web/method :post :web/path \"/o\" :web/auth :public"
                               " :web/request rc.c/new :web/response rc.c/one}"
                               " make \"M.\" [r] r)\n\n"
                               "(defn ^{:web/method :get :web/path \"/bare\" :web/auth :public}"
                               " bare \"B.\" [r] r)\n"))
        by  (into {} (map (juxt :name identity)) (web/endpoints s))]
    (testing "the declared contract rides the route row"
      (is (= 'rc.c/new (:web/request (by 'make))))
      (is (= 'rc.c/one (:web/response (by 'make))))
      (is (true? (:schema? (by 'make)))))
    (testing "an endpoint with no contract reads as unschema'd"
      (is (nil? (:web/response (by 'bare))))
      (is (false? (:schema? (by 'bare)))))))

(deftest route-refs-only-read-hiccup-attribute-position
  (let [src (str "(ns plan.core)\n\n"
                 "(defn steps \"S.\" [x]\n"
                 "  [{:op :add :action :replace}\n"
                 "   {:action \"action\" :method :get}])\n\n"
                 "(defn ^{:web/method :get :web/path \"/p\" :web/auth :public} page \"P.\" [req]\n"
                 "  [:div [:a {:href \"/nowhere\"} \"bad\"]\n"
                 "        [:form {:action (:uri req) :method \"post\"} \"dyn\"]])\n")
        s (store/ingest (store/empty-store) 'plan.core src)
        refs (web/ui-route-refs s)]
    (testing "a data map that happens to carry :action is NOT a route reference"
      (is (= '#{plan.core/page} (set (map :form refs)))
          (pr-str (mapv (juxt :form :attr :value) refs))))
    (testing "genuine hiccup attrs still register, dynamic ones as :unresolved"
      (is (= #{[:href :exact] [:action :unresolved]}
             (set (map (juxt :attr :kind) refs)))))
    (testing "and the method still comes from the same attr map"
      (is (= :post (:method (first (filter #(= :action (:attr %)) refs))))))))

(deftest the-tag-decides-whether-an-attr-is-a-url
  (let [src (str "(ns plan.two)\n\n"
                 "(defn plan \"P.\" [x]\n"
                 "  [:step {:action :replace :name x}])\n\n"
                 "(defn widget \"W.\" [x]\n"
                 "  [:div {:href \"/not-a-link\"} x])\n\n"
                 "(defn ^{:web/method :get :web/path \"/q\" :web/auth :public} page \"P.\" [req]\n"
                 "  [:div [:a.nav#top {:href \"/styled\"} \"sugar\"]\n"
                 "        [:link {:href \"/site.css\"}]\n"
                 "        [:form {:action (:uri req) :method \"post\"} \"dyn\"]])\n")
        s (store/ingest (store/empty-store) 'plan.two src)
        refs (web/ui-route-refs s)]
    (testing ":action on a non-<form> element is data, not a target"
      (is (not (contains? (set (map :form refs)) 'plan.two/plan))
          (pr-str (mapv (juxt :form :attr :value) refs))))
    (testing ":href on a <div> is an attribute the browser ignores, not a link"
      (is (not (contains? (set (map :form refs)) 'plan.two/widget))))
    (testing "the URL-bearing elements register, #id/.class sugar and all"
      (is (= #{"/styled" "/site.css"}
             (set (keep :path refs)))))
    (testing "a <form> action that is code stays :unresolved, with its method"
      (let [dyn (first (filter #(= :unresolved (:kind %)) refs))]
        (is (= [:action :post] [(:attr dyn) (:method dyn)]))))))

(deftest tests-are-joined-to-the-endpoints-whose-paths-they-exercise
  (let [s (store/ingest (store/empty-store) 'shop.api
                        (str "(ns shop.api)\n\n"
                             "(defn ^{:web/method :get :web/path \"/todos\" :web/auth :public} todos \"T.\" [req] req)\n\n"
                             "(defn ^{:web/method :get :web/path \"/todo/:id\" :web/auth :public} one \"O.\" [req] req)\n\n"
                             "(defn ^{:web/method :post :web/path \"/todos\" :web/auth :public} add! \"A.\" [req] req)\n"))
        s (store/ingest s 'shop.api-test
                        (str "(ns shop.api-test)\n\n"
                             "(deftest listing (handle! ctx {:request-method :get :uri \"/todos\"}))\n\n"
                             "(deftest detail (handle! ctx {:request-method :get :uri \"/todo/7\"}))\n\n"
                             "(deftest elsewhere (is (= 1 1)))\n"))
        joined (web/endpoint-test-refs s)]
    (testing "a literal URI in a test resolves through the ROUTER to its endpoint"
      (is (= '#{shop.api-test/listing} (get joined 'shop.api/todos))))
    (testing "a parameterized route matches the concrete path the test uses"
      (is (= '#{shop.api-test/detail} (get joined 'shop.api/one))))
    (testing "method matters — a POST endpoint is not exercised by a GET test"
      (is (nil? (get joined 'shop.api/add!))))
    (testing "a test touching no route joins to nothing"
      (is (not-any? #(contains? % 'shop.api-test/elsewhere) (vals joined))))))

(deftest src-is-a-route-reference-too
  ;; `url-attrs` answers "is this a link" from the HTML spec rather than by
  ;; guessing, which is right — and it listed only :href (a/link/area/base)
  ;; and :action (form). `:src` was simply missing, so a <script> or an <img>
  ;; pointing at a path nothing serves was invisible to the gate built to
  ;; catch exactly that.
  ;;
  ;; Found in anger: slopp's OWN reviewer UI shipped
  ;; `[:script {:src "/assets/cljs/main.js"}]` in the shell of every page,
  ;; served by nothing, 404ing on every request since the wave that added it.
  ;; The gate that should have failed `done` never saw it.
  (let [st (store/ingest (store/empty-store) 'sr.pages
                         (str "(ns sr.pages)\n"
                              "(defn ^{:web/method :get :web/path \"/real\""
                              "        :web/auth :public :web/response :string}\n"
                              "  page [_req]\n"
                              "  [:html [:head\n"
                              "    [:script {:src \"/nowhere/main.js\"}]\n"
                              "    [:script {:src \"/real\"}]\n"
                              "    [:img {:src \"/missing.png\"}]]])\n"))
        refs (web/ui-route-refs st)
        by-path (into {} (map (juxt :path identity)) refs)]
    (testing "a script src is a route reference"
      (is (contains? by-path "/nowhere/main.js") (pr-str refs))
      (is (= :src (:attr (by-path "/nowhere/main.js")))))
    (testing "an img src is one too"
      (is (contains? by-path "/missing.png") (pr-str refs)))
    (testing "and one that IS served does not dangle"
      (let [{:keys [dangling]} (web/dangling-route-refs st)
            paths (set (map :path dangling))]
        (is (contains? paths "/nowhere/main.js"))
        (is (contains? paths "/missing.png"))
        (is (not (contains? paths "/real"))
            "a src pointing at a declared endpoint is served, like any href")))))
