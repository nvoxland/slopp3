(ns slopp.rules.web-test
  "Cover for the web surface's DERIVATIONS — what slopp reads off endpoint
  metadata, as opposed to what happens when a request arrives.

  The runtime is `slopp.web`'s business and is tested portlessly there. Here
  the subject is everything derived BEFORE that: which routes a store
  declares, which URL attributes count as route references, what a contract
  declaration obliges, and what a declaration's consequences are worth saying
  out loud.

  That last one is a genre of its own and worth naming: `:web/spa` changes
  every status code under a prefix from 404 to 200, so the test asserts that
  slopp SAYS so once, and stops. A consequence nobody states is one somebody
  discovers."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.rules.web :as web] [slopp.ops :as ops] [slopp.ops.external :as external] [slopp.web-test :as web-test] [clojure.string :as str]))

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
        on  (first (store/record-config-put s0 "capabilities" :manifest "web.enabled" "true"))]
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
    (testing "routes-report is empty-and-says-why until web.enabled"
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
      (ops/ingest! sess 'shop.api "(ns shop.api)\n\n(defn seed \"S.\" [x] x)\n")
      (testing "before opting in, an endpoint-shaped form lands ungated (the adoption story)"
        (let [r (ops/add-form! sess 'shop.api
                               "(defn ^{:web/method :get :web/path \"/pre\"} pre \"P.\" [req] req)"
                               :prompt "pre-optin endpoint")]
          (is (nil? (:error r)) (pr-str r))))
      (ops/config-file! sess "capabilities" :key "web.enabled" :value "true"
                        :prompt "opt into HTTP")
      (testing "an endpoint with no :web/auth is refused with teaching, and never lands"
        (let [r (ops/add-form! sess 'shop.api
                               "(defn ^{:web/method :get :web/path \"/naked\"} naked \"N.\" [req] req)"
                               :prompt "endpoint without auth")]
          (is (re-find #":web/auth" (str (:error r))) (pr-str r))
          (is (nil? (store/form-named (:store @sess) 'shop.api 'naked)))))
      (testing "with a declared policy and response contract it lands, and the route reports"
        (let [r (ops/add-form! sess 'shop.api
                               (str "(defn ^{:web/method :get :web/path \"/api/ping\""
                                    " :web/auth :public :web/response :map} ping \"P.\" [req] req)")
                               :prompt "a public endpoint")]
          (is (nil? (:error r)) (pr-str r))
          (let [rep (web/routes-report (:store @sess))]
            (is (true? (:enabled rep)))
            (is (some #(= "/api/ping" (:path %)) (:routes rep))))))
      (finally (ops/close! sess)))))

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
        s (first (store/record-config-put s "capabilities" :manifest "web.enabled" "true"))
        s (first (store/record-config-put s "capabilities" :manifest "web.static./assets" "public"))
        s (first (store/record-file-put s "public/app.css" "body{}"))
        {:keys [dangling unresolved]} (web/dangling-route-refs s)]
    (testing "unserved refs: no route, mount without the file, prefix into nothing"
      (is (= #{["/nowhere" :exact] ["/assets/missing.css" :exact] ["/gone/" :prefix]}
             (set (map (juxt :path :kind) dangling)))))
    (testing "dynamic refs are named, not counted clean"
      (is (= '[shop.ui/todos-page] (mapv :form unresolved))))
    (testing "a mount written with a TRAILING SLASH resolves the same way.
              The capability's own doc line showed `web.static./assets =
              public/`, and that form built `public//app.css`, which no
              manifest holds — so following the documentation made every
              asset link in the app read as dangling."
      (let [s2 (first (store/record-config-put s "capabilities" :manifest
                                               "web.static./assets" "public/"))]
        (is (= #{["/nowhere" :exact] ["/assets/missing.css" :exact] ["/gone/" :prefix]}
               (set (map (juxt :path :kind)
                         (:dangling (web/dangling-route-refs s2))))))))
    (testing "an ARTIFACT under a mount is served too. compile_client writes the
              bundle as an artifact — bytes to the content-addressed cache,
              sha to the journal, because inlining it cost 30MB of delta log —
              and then tells you to add an web.static mount. A mount that
              could not see it made that advice impossible to follow: the
              bundle every page loads read as a dangling link."
      (let [src2 (str "(ns shop.doc)\n\n"
                      "(defn ^{:web/method :get :web/path \"/\" :web/auth :public} page \"P.\" [req]\n"
                      "  [:html [:script {:src \"/assets/cljs/main.js\"}]])\n")
            s2 (store/ingest s 'shop.doc src2)
            s2 (first (store/record-artifact
                       s2 "public/cljs/main.js"
                       {:sha "abc123" :bytes 10 :content-type "application/javascript"
                        :recipe {:kind :build :tool "compile_client"}}))]
        (is (not-any? #(= "/assets/cljs/main.js" (:path %))
                      (:dangling (web/dangling-route-refs s2))))))))

(deftest query-routes-carries-rendered-by
  (let [src (str "(ns shop.ui)\n\n"
                 "(defn ^{:web/method :get :web/path \"/todos\" :web/auth :public} todos-page \"T.\" [req]\n"
                 "  [:div [:a {:href \"/todos\"} \"self\"] [:a {:href (str \"/todo/\" 7)} \"one\"]])\n\n"
                 "(defn ^{:web/method :get :web/path \"/todo/:id\" :web/auth :public} todo-page \"O.\" [req]\n"
                 "  [:a {:href \"/todos\"} \"back\"])\n")
        s (store/ingest (store/empty-store) 'shop.ui src)
        s (first (store/record-config-put s "capabilities" :manifest "web.enabled" "true"))
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
      (ops/ingest! sess 'ui.core
                   (str "(ns ui.core)\n\n"
                        "(defn ^{:web/method :get :web/path \"/home\" :web/auth :public :web/response :map} home \"H.\" [req]\n"
                        "  [:a {:href \"/nowhere\"} \"x\"])\n"))
      (testing "inert until web.enabled"
        (let [r (external/done! sess :label "pre-optin")]
          (is (empty? (get-in r [:findings :web-dangling-route-refs]))
              (pr-str (:findings r)))))
      (ops/config-file! sess "capabilities" :key "web.enabled" :value "true"
                        :prompt "opt into HTTP")
      (testing "a dangling href fires with the form and path"
        (let [r (external/done! sess :label "dangling")]
          (is (= [{:form 'ui.core/home :attr :href :path "/nowhere"}]
                 (mapv #(select-keys % [:form :attr :path])
                       (get-in r [:findings :web-dangling-route-refs])))
              (pr-str (:findings r)))))
      (testing "adding the route discharges"
        (ops/add-form! sess 'ui.core
                       "(defn ^{:web/method :get :web/path \"/nowhere\" :web/auth :public :web/response :map} nowhere \"N.\" [req] req)"
                       :prompt "serve the missing route")
        (let [r (external/done! sess :label "served")]
          (is (empty? (get-in r [:findings :web-dangling-route-refs]))
              (pr-str (:findings r)))))
      (finally (ops/close! sess)))))

(deftest ^:external react-attr-names-refuse-at-the-write
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'ui.rx "(ns ui.rx)\n\n(defn seed \"S.\" [x] x)\n")
      (ops/config-file! sess "capabilities" :key "web.enabled" :value "true"
                        :prompt "opt into HTTP")
      (testing "a React attribute name in a literal hiccup element refuses, teaching the HTML spelling"
        (let [r (ops/add-form! sess 'ui.rx
                               "(defn card \"C.\" [] [:div {:className \"x\"} \"c\"])"
                               :prompt "a React-ism")]
          (is (re-find #":class\b" (str (:error r))) (pr-str r))
          (is (nil? (store/form-named (:store @sess) 'ui.rx 'card)))))
      (testing "the HTML spelling lands"
        (let [r (ops/add-form! sess 'ui.rx
                               "(defn card \"C.\" [] [:div {:class \"x\"} \"c\"])"
                               :prompt "correct spelling")]
          (is (nil? (:error r)) (pr-str r))))
      (finally (ops/close! sess)))))

(deftest ^:external web-endpoint-schema-gate-requires-a-response-contract
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'shopc.api "(ns shopc.api)\n\n(defn seed \"S.\" [x] x)\n")
      (testing "before opt-in, an endpoint without :web/response lands (grandfathered)"
        (let [r (ops/add-form! sess 'shopc.api
                               "(defn ^{:web/method :get :web/path \"/pre\" :web/auth :public} pre \"P.\" [req] req)"
                               :prompt "pre-optin endpoint")]
          (is (nil? (:error r)) (pr-str r))))
      (ops/config-file! sess "capabilities" :key "web.enabled" :value "true"
                        :prompt "opt into HTTP")
      (testing "under opt-in, an endpoint with auth but NO :web/response is refused, never lands"
        (let [r (ops/add-form! sess 'shopc.api
                               "(defn ^{:web/method :get :web/path \"/list\" :web/auth :public} list-it \"L.\" [req] req)"
                               :prompt "no response contract")]
          (is (re-find #":web/response" (str (:error r))) (pr-str r))
          (is (nil? (store/form-named (:store @sess) 'shopc.api 'list-it)) "never lands")))
      (testing "declaring :web/response (here inline) lets it land"
        (let [r (ops/add-form! sess 'shopc.api
                               (str "(defn ^{:web/method :get :web/path \"/ok\" :web/auth :public"
                                    " :web/response [:map [:n :int]]} ok \"O.\" [req] req)")
                               :prompt "with a response contract")]
          (is (nil? (:error r)) (pr-str r))
          (is (some? (store/form-named (:store @sess) 'shopc.api 'ok)))))
      (finally (ops/close! sess)))))

(deftest ^:external web-endpoint-schema-requires-request-on-body-methods
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'shopr.api "(ns shopr.api)\n\n(defn seed \"S.\" [x] x)\n")
      (ops/config-file! sess "capabilities" :key "web.enabled" :value "true"
                        :prompt "opt into HTTP")
      (testing "a POST endpoint with :web/response but NO :web/request is refused"
        (let [r (ops/add-form! sess 'shopr.api
                               (str "(defn ^{:web/method :post :web/path \"/orders\" :web/auth :public"
                                    " :web/response [:map [:id :int]]} create \"C.\" [req] req)")
                               :prompt "no request contract")]
          (is (re-find #":web/request" (str (:error r))) (pr-str r))
          (is (nil? (store/form-named (:store @sess) 'shopr.api 'create)))))
      (testing "a GET endpoint needs only :web/response (no request body)"
        (let [r (ops/add-form! sess 'shopr.api
                               (str "(defn ^{:web/method :get :web/path \"/orders\" :web/auth :public"
                                    " :web/response [:map]} listing \"L.\" [req] req)")
                               :prompt "get needs only response")]
          (is (nil? (:error r)) (pr-str r))))
      (testing "declaring both contracts lets the POST land"
        (let [r (ops/add-form! sess 'shopr.api
                               (str "(defn ^{:web/method :post :web/path \"/orders2\" :web/auth :public"
                                    " :web/request [:map [:item :string]] :web/response [:map [:id :int]]}"
                                    " create2 \"C.\" [req] req)")
                               :prompt "both contracts")]
          (is (nil? (:error r)) (pr-str r))
          (is (some? (store/form-named (:store @sess) 'shopr.api 'create2)))))
      (finally (ops/close! sess)))))

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

(deftest client-routes-under-a-declared-spa-prefix-are-served
  ;; `:web/spa` says a document serves client routes under a prefix, so a link
  ;; to /store/form/f1 IS served even though no endpoint declares that path.
  ;; The gate has to know, or every in-app link in a client-routed app reads
  ;; as dangling and the finding becomes noise someone learns to ignore.
  ;;
  ;; The other half matters more: a link OUTSIDE every declared prefix must
  ;; still be flagged. A gate that treats a fallback as "anything goes" has
  ;; given up the only thing it does.
  (let [st (store/ingest (store/empty-store) 'sp.pages
                         (str "(ns sp.pages)\n"
                              "(defn ^{:web/method :get :web/path \"/\""
                              "        :web/auth :public :web/response :string\n"
                              "        :web/spa [\"/store\"]}\n"
                              "  app [_req]\n"
                              "  [:html [:body\n"
                              "    [:a {:href \"/store/form/f1\"} \"a client route\"]\n"
                              "    [:a {:href \"/nope/x\"} \"nothing serves this\"]]])\n"))
        {:keys [dangling]} (web/dangling-route-refs st)
        paths (set (map :path dangling))]
    (testing "the endpoint row carries the declared prefixes, so a reader sees them"
      (is (= ["/store"] (:web/spa (first (web/endpoints st))))))
    (testing "a client route under the prefix is served by the fallback"
      (is (not (contains? paths "/store/form/f1")) (pr-str dangling)))
    (testing "a path outside every prefix still dangles"
      (is (contains? paths "/nope/x") (pr-str dangling)))))

(deftest ^:external declaring-a-spa-prefix-says-what-it-changed
  ;; `:web/spa` is the biggest behavioural change available in one piece of
  ;; metadata: every path under the prefix stops being a 404 and starts being a
  ;; 200, with not-found moving into the client. Nothing said so — the change
  ;; was noticed only because two existing tests asserted the old status.
  ;;
  ;; It fires once, for the episode that DECLARED it, so it cannot decay into a
  ;; standing warning.
  (let [sess (external/open!)]
    (try
      (ops/config-file! sess "capabilities" :key "web.enabled" :value "true"
                        :prompt "the web rules are inert until the store opts in")
      (ops/ingest! sess 'spa.ui
                   (str "(ns spa.ui)\n"
                        "(defn ^{:web/method :get :web/path \"/\" :web/auth :public\n"
                        "        :web/client false :web/response :string}\n"
                        "  doc \"The document.\" [_] {:status 200 :body \"<html></html>\"})\n"))
      (external/done! sess :label "baseline")
      (testing "adding the declaration states the consequence"
        (ops/edit-replace! sess 'spa.ui 'doc
                           (str "(defn ^{:web/method :get :web/path \"/\" :web/auth :public\n"
                                "        :web/client false :web/response :string\n"
                                "        :web/spa [\"/store\"]}\n"
                                "  doc \"The document.\" [_] {:status 200 :body \"<html></html>\"})")
                           :prompt "the client routes /store")
        (let [f (get-in (external/done! sess :label "spa") [:findings :web-spa-consequences])]
          (is (some #(= 'spa.ui/doc (:form %)) f) (pr-str f))
          (is (re-find #"200" (str (:teach (first f)))) (pr-str f))
          (is (re-find #"(?i)not-found" (str (:teach (first f)))) (pr-str f))))
      (testing "it does NOT re-fire while the declaration merely stands"
        (ops/edit-replace! sess 'spa.ui 'doc
                           (str "(defn ^{:web/method :get :web/path \"/\" :web/auth :public\n"
                                "        :web/client false :web/response :string\n"
                                "        :web/spa [\"/store\"]}\n"
                                "  doc \"The document, reworded.\" [_] {:status 200 :body \"<html></html>\"})")
                           :prompt "touch the form without touching the declaration")
        (let [f (get-in (external/done! sess :label "again") [:findings :web-spa-consequences])]
          (is (nil? f) (pr-str f))))
      (finally (ops/close! sess)))))

(deftest a-client-router-path-is-not-somebody-elses-server
  ;; Friction 13, measured on slopp-ui: seven view forms render `/store/ns/foo`
  ;; and `/change/d1..d2`, which no SERVER route matches, so the dangling-route
  ;; check flagged them. The only escape was ^{:web/external-path}, and it
  ;; discharged the check while filing a FALSE statement — the crossings
  ;; inventory then reported those forms as leaving for "somebody else's server".
  ;;
  ;; They are this app's own paths. The literal is a key the CLIENT router
  ;; parses, and `prefix-links` re-addresses it under the mount point before it
  ;; reaches the DOM, so what serves it is this store's own `:web/spa` fallback.
  ;;
  ;; The crossings report is exactly where someone goes to ask what is NOT
  ;; checked here, so a wrong reason there is worse than a missing one. Hence a
  ;; marker of its own rather than widening the old one: same discharge,
  ;; truthful category. Teaching the dangling check to SEE the prefixing was the
  ;; alternative and it cannot be done in general — the base arrives through an
  ;; ordinary function call the checker would have to trace.
  (let [src (str "(ns spa.ui)\n\n"
                 "(defn ^{:web/client-path \"the client router parses it; prefix-links adds the mount point\"}\n"
                 "  ns-link \"N.\" [nsx]\n"
                 "  [:a {:href (str \"/store/ns/\" nsx)} \"ns\"])\n\n"
                 "(defn plain \"P.\" [] [:a {:href \"/served-by-nobody\"} \"x\"])\n")
        s    (store/ingest (store/empty-store) 'spa.ui src)
        refs (web/ui-route-refs s)]
    (testing "the marker discharges the form's refs, exactly as external-path does
              — otherwise it is not an escape and nobody can use it"
      (is (not-any? #(= 'spa.ui/ns-link (:form %)) refs) (pr-str refs)))
    (testing "and an unmarked form in the same namespace is still reported, so
              the marker discharges one form rather than switching the check off"
      (is (some #(= 'spa.ui/plain (:form %)) refs) (pr-str refs)))))

(deftest serving-namespaces-derive-from-the-store-not-a-hand-kept-list
  ;; `:web/namespaces` is the one REQUIRED opt on serve!, and `web/context`'s
  ;; own docstring warns that "a :web/namespaces list missing half the app
  ;; assembles happily and answers". A hand-kept list of what to serve IS
  ;; that defect, held by every app. The store already knows: endpoint rows
  ;; carry :ns, and the performer vocabularies carry qualified syms.
  (let [api   (str "(ns shop.api)\n\n"
                   "(defn ^{:web/method :get :web/path \"/api/users/:id\"\n"
                   "        :web/auth :authenticated\n"
                   "        :web/reads {:user [:user/by-id [:path-params :id]]}\n"
                   "        :malli/schema [:=> [:cat :map] :map]\n"
                   "        :web/response :map} get-user \"U.\" [req] req)\n")
        ;; the performer lives in ANOTHER namespace — this is the one a hand
        ;; list forgets, and omitting it is not a quiet degradation: context
        ;; throws :web/missing-performers because the route above promises a
        ;; read nothing listed can serve.
        data  (str "(ns shop.data)\n\n"
                   "(defn ^{:web/read :user/by-id} user-by-id \"R.\" [ctx id] id)\n"
                   "(defn ^{:web/effect :user/insert} insert! \"I.\" [ctx row] row)\n")
        ui    (str "(ns shop.ui)\n\n"
                   "(defn ^{:web/method :get :web/path \"/users\"\n"
                   "        :web/response :hiccup} users-page \"P.\" [req] [:div])\n")
        plain (str "(ns shop.util)\n\n(defn helper \"H.\" [x] x)\n")
        fixt  (str "(ns shop.api-test)\n\n"
                   "(defn ^{:web/method :get :web/path \"/fixture\"} fx \"F.\" [req] req)\n")
        s     (-> (store/empty-store)
                  (store/ingest 'shop.api api)
                  (store/ingest 'shop.data data)
                  (store/ingest 'shop.ui ui)
                  (store/ingest 'shop.util plain)
                  (store/ingest 'shop.api-test fixt))]
    (testing "every namespace carrying route or performer surface, and no other"
      (is (= ['shop.api 'shop.data 'shop.ui] (web/serving-namespaces s))))
    (testing "sorted, so a build's emitted main is byte-stable across runs"
      (is (= (sort (web/serving-namespaces s)) (web/serving-namespaces s))))
    (testing "a namespace with no web surface is not served"
      (is (not (some #{'shop.util} (web/serving-namespaces s)))))
    (testing "a -test namespace's endpoint-shaped form is a fixture, not surface"
      ;; the same rule routes-report already applies; serving it would mount
      ;; a test's fake endpoint on the real app
      (is (not (some #{'shop.api-test} (web/serving-namespaces s)))))
    (testing "a store with no web surface serves nothing, rather than erroring"
      (is (= [] (web/serving-namespaces (store/empty-store)))))))

(deftest the-store-backed-reader-meets-the-reader-contract
  ;; The RUN lives beside the adapter so the contract can reach a
  ;; package-private implementation without exporting it for a test's benefit.
  ;; The contract itself belongs to the port's owner (slopp.web.static), which
  ;; is the only home that does not make it one implementation's test.
  ;;
  ;; It travelled here from slopp.mcp.http-test when the HTTP MCP transport
  ;; was retired. The adapter was only ever housed there; it answers the same
  ;; port as `static/file-or-resource-reader`, and the two have diverged
  ;; before — a mount prefix written `public/` asks for `public//app.css`,
  ;; which a filesystem normalises away and a manifest lookup does not.
  ;;
  ;; In-image and cheap: a store's :files is a plain map, so this adapter
  ;; needs no database, no session and no socket.
  (web-test/reader-contract "store"
                            (fn [files]
                              (web/store-reader (constantly {:files files})
                                                (constantly nil)))))

(deftest the-app-declares-its-context-builder-with-a-marker
  ;; The managed app server writes the `serve!` call, so it needs to know how
  ;; to build `:web/perform-ctx` — the map a handler receives as `:web/deps`
  ;; and every performer receives as its first argument. It is app-specific
  ;; by definition (a registry, a pool, a database handle), so the app has to
  ;; say, and a MARKER is how everything else in this framework is addressed.
  ;;
  ;; A marker rather than a capability naming a qualified symbol, for a
  ;; reason slopp-ui named: a marker makes a GATE possible. Both halves are
  ;; then visible in the store — handlers that take `:web/deps`, and whether
  ;; anything claims to build it — so "this store takes :web/deps and
  ;; declares no builder" can refuse at the WRITE instead of 500ing in a
  ;; browser. A capability is a string in config, checkable at boot, which is
  ;; later and weaker.
  ;;
  ;; It cannot be a PERFORMER, and that idea is circular rather than merely
  ;; wrong: performers already RECEIVE the perform-ctx as their first
  ;; argument, so the context is strictly upstream of the vocabulary and
  ;; cannot be a member of it. Stated here because it is the obvious
  ;; suggestion.
  (let [ns-src (fn [body] (str "(ns app.system)\n\n" body))
        one    (store/ingest (store/empty-store) 'app.system
                             (ns-src (str "(defn ^{:web/context true} deps \"D.\""
                                          " [] {:registry (atom {})})\n")))]
    (testing "the marked var, fully qualified — the generated serve call has
              to name it from another image"
      (is (= 'app.system/deps (web/context-builder one))))
    (testing "a store that declares none says so plainly, because MOST apps
              need no context and that is not a defect"
      (is (nil? (web/context-builder (store/empty-store)))))
    (testing "TWO builders is a refusal, not a pick — the context is a
              singleton and choosing one silently is how an app ends up
              running on the deps it did not mean"
      (let [two (store/ingest one 'app.other
                              (str "(ns app.other)\n\n"
                                   "(defn ^{:web/context true} deps \"D.\" [] {})\n"))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"(?i)one"
                              (web/context-builder two)))))))

(deftest static-mounts-are-read-once-from-the-capability-family
  ;; The family was parsed privately inside `cljs/served-by-a-mount?`, so the
  ;; managed server could not ask the same question without writing a second
  ;; parser — and two parsers of one config family agree right up until one
  ;; of them learns about trailing slashes. This is the shared one.
  (let [put (fn [s k v] (first (store/record-config-put s "capabilities" :manifest k v)))
        s   (-> (store/empty-store)
                (put "web.enabled" "true")
                (put "web.static./assets" "public")
                (put "web.static./js" "public/cljs/"))]
    (testing "the key tail is the URL prefix, the value the manifest prefix"
      (is (= {"/assets" "public" "/js" "public/cljs"} (web/static-mounts s))))
    (testing "a trailing slash is trimmed, as the capability doc promises"
      ;; not cosmetic: a store-backed reader looks the path up in a manifest
      ;; rather than on a filesystem that would normalise it, so `public/`
      ;; asks for `public//main.js` and gets nothing
      (is (= "public/cljs" (get (web/static-mounts s) "/js"))))
    (testing "non-mount capabilities are not mistaken for mounts"
      (is (nil? (get (web/static-mounts s) "enabled"))))
    (testing "a store with no mounts declares none"
      (is (empty? (web/static-mounts (store/empty-store)))))))

(deftest ^:external a-page-the-jvm-cannot-open-refuses-at-the-write
  ;; The architecture rule, enforced rather than suggested. `^:web/page` marks
  ;; the entry the fake browser opens an app through — and in a :cljs namespace
  ;; that entry cannot be CALLED from a JVM, so every headless test has to
  ;; hand-build a map that RESEMBLES the app.
  ;;
  ;; A resemblance drifts silently, and it drifts in the one direction that
  ;; costs the most: the lookalike keeps passing while the real screen is
  ;; wrong. That is the whole defect the fake browser exists to remove, so
  ;; letting it back in through the entry point is not a small compromise.
  ;;
  ;; A MARKER rather than a capability key, deliberately. A capability naming a
  ;; var is an asserted relation that drifts the day the var is renamed; a
  ;; marker cannot, because it IS the var. And a write gate needs a moment to
  ;; fire — with a marker there is one, and it names the right form.
  (let [sess (external/open!)]
    (try
      (ops/config-file! sess "capabilities" :key "web.enabled" :value "true"
                        :prompt "opt into HTTP")
      (ops/ingest! sess 'ui.shell "(ns ui.shell)\n\n(defn seed \"S.\" [x] x)\n")
      (ops/module-platform! sess "ui.shell" "cljs"
                            :prompt "the browser shell is :cljs by nature")

      (testing "a page marked in a :cljs namespace refuses, and says what it costs"
        (let [r (ops/add-form! sess 'ui.shell
                               "(defn ^:web/page app \"A.\" [] {:state (atom {}) :view (fn [_] [:div])})"
                               :prompt "the entry, in the wrong place")]
          (is (re-find #"(?i)cljs" (str (:error r))) (pr-str r))
          (is (nil? (store/form-named (:store @sess) 'ui.shell 'app))
              "and the form did not land — a refusal that writes anyway teaches nothing")))

      (testing "the same page in a portable namespace lands"
        (ops/ingest! sess 'ui.app "(ns ui.app)\n\n(defn seed \"S.\" [x] x)\n")
        (let [r (ops/add-form! sess 'ui.app
                               "(defn ^:web/page app \"A.\" [] {:state (atom {}) :view (fn [_] [:div])})"
                               :prompt "the entry, where a JVM can call it")]
          (is (nil? (:error r)) (pr-str r))))

      (testing "and an UNMARKED form in a :cljs namespace is none of the gate's business"
        (let [r (ops/add-form! sess 'ui.shell
                               "(defn mount \"M.\" [] :ok)"
                               :prompt "ordinary browser code")]
          (is (nil? (:error r)) (pr-str r))))
      (finally (ops/close! sess)))))

(deftest ^:external a-page-marker-that-cannot-be-opened-refuses
  ;; The gate already refuses a ^:web/page in a :cljs namespace. Two more ways
  ;; to mark one slopp cannot open, both of which would otherwise fail LATER
  ;; and somewhere else.
  (let [sess (external/open!)]
    (try
      (ops/config-file! sess "capabilities" :key "web.enabled" :value "true"
                        :prompt "opt into HTTP")
      (ops/ingest! sess 'ui.core "(ns ui.core)\n\n(defn seed \"S.\" [x] x)\n")

      (testing "the entry must take NO arguments — slopp calls it, so there is nobody to pass one"
        (let [r (ops/add-form! sess 'ui.core
                               "(defn ^:web/page app \"A.\" [opts] {:state (atom {}) :view (fn [_] [:div])})"
                               :prompt "an entry that wants configuring")]
          (is (re-find #"no zero arity" (str (:error r))) (pr-str r))
          (is (nil? (store/form-named (:store @sess) 'ui.core 'app)))))

      (testing "a zero-arg entry lands"
        (let [r (ops/add-form! sess 'ui.core
                               "(defn ^:web/page app \"A.\" [] {:state (atom {}) :view (fn [_] [:div])})"
                               :prompt "the entry")]
          (is (nil? (:error r)) (pr-str r))))

      (testing "a SECOND marker refuses, naming the first"
        ;; the tool scans for the marker and takes what it finds; two of them
        ;; means it answers from whichever the scan reached first, silently,
        ;; and a screen from the wrong app is worse than no screen
        (let [r (ops/add-form! sess 'ui.core
                               "(defn ^:web/page other \"O.\" [] {:state (atom {}) :view (fn [_] [:div])})"
                               :prompt "a second entry")]
          (is (re-find #"ui\.core/app" (str (:error r))) (pr-str r))
          (is (nil? (store/form-named (:store @sess) 'ui.core 'other)))))
      (finally (ops/close! sess)))))

(deftest ^:external a-page-reaching-cljs-cannot-be-opened-and-done-says-so
  ;; The write gate checks the entry's OWN namespace. That is the shallow half:
  ;; an entry can sit in :cljc and reach a :cljs view, passing the gate and
  ;; failing the tool — which is exactly where a real app lands, because the
  ;; entry is small and the views are where the code is.
  ;;
  ;; An ADVISORY rather than a gate, and the reason is structural. The reach
  ;; changes when ANOTHER form moves: declaring some namespace :cljs today can
  ;; strand an entry written last week, and no write to that entry ever
  ;; happens. A per-form write gate cannot see it, however it is written.
  ;;
  ;; Both setup steps carry a control. The first cut of this test guarded only
  ;; the platform declaration and the step that had silently failed was the
  ;; INGEST, a line above it — refused by the module gate, which left the
  ;; advisory reporting nothing over a namespace that had no entry in it. Two
  ;; assertions passed vacuously before either one was doubted.
  (let [sess (external/open!)]
    (try
      (ops/config-file! sess "capabilities" :key "web.enabled" :value "true"
                        :prompt "opt into HTTP")
      (ops/ingest! sess 'demo.app.views
                   "(ns demo.app.views)\n\n(defn page-view \"V.\" [s] [:div (str s)])\n")
      (let [i (ops/ingest! sess 'demo.app
                           (str "(ns demo.app (:require [demo.app.views :as views]))\n\n"
                                "(defn ^:web/page app \"A.\" []"
                                " {:state (atom {}) :view views/page-view})\n"))]
        (is (nil? (:error i)) (pr-str i))
        (is (some #{'app} (map :name (store/forms (:store @sess) 'demo.app)))
            "the entry has to EXIST before anything about its reach means anything"))

      (let [ids (fn [] (mapv :id (store/forms (:store @sess) 'demo.app)))]
        (testing "reaching only portable code is clean"
          (is (empty? (web/web-page-reach-check sess (:store @sess) (ids)))))

        (testing "declaring the VIEWS :cljs strands the entry, and the advisory names both"
          (let [d (ops/module-platform! sess "demo.app.views" "cljs"
                                        :prompt "someone moves the views to the client")]
            (is (nil? (:error d)) (pr-str d))
            (is (= :cljs (store/platform-for (:store @sess) 'demo.app.views))
                "the fixture is only a fixture once the platform actually says :cljs"))
          (let [r (web/web-page-reach-check sess (:store @sess) (ids))]
            (is (seq r) "the entry is now unopenable and no write to it happened")
            (is (= 'demo.app/app (:form (first r))))
            (is (some #{'demo.app.views} (:cljs (first r)))
                "naming the namespace that stranded it is the finding — the entry is fine"))))
      (finally (ops/close! sess)))))

(deftest ^:external the-page-marker-sits-on-a-zero-arg-public-defn
  ;; Review B-F2/F3: the gate graded the shape its author imagined. A
  ;; `(def ^:web/page app 42)` passed ("takes arguments" cannot fire on a def)
  ;; and CCE'd at drive time; a defmethod DISCARDS name metadata at
  ;; macroexpansion so its marker lands on nothing; a `defn-` page passed the
  ;; gate while being invisible to the tool's ns-publics scan — the store
  ;; answering "no ^:web/page" while carrying a gate-approved one is a
  ;; confident wrong answer. And the strict direction was wrong too: a
  ;; multi-arity entry WITH a zero arity was refused for arguments slopp
  ;; never passes.
  (let [sess (external/open!)]
    (try
      (ops/config-file! sess "capabilities" :key "web.enabled" :value "true"
                        :prompt "opt into HTTP")
      (ops/ingest! sess 'ui.core "(ns ui.core)\n\n(defn seed \"S.\" [x] x)\n")

      (testing "a def carrier is refused — its arity cannot be read from the form"
        (let [r (ops/add-form! sess 'ui.core "(def ^:web/page app 42)"
                               :prompt "marker on a def")]
          (is (re-find #"zero-arg" (str (:error r))) (pr-str r))))

      (testing "a defmethod carrier is refused — the marker is discarded at macroexpansion"
        (let [r0 (ops/add-form! sess 'ui.core "(defmulti route \"R.\" :k)"
                                :prompt "a multi to hang the method on")
              r  (ops/add-form! sess 'ui.core "(defmethod ^:web/page route :home [x] x)"
                                :prompt "marker on a defmethod")]
          (is (nil? (:error r0)) (pr-str r0))
          (is (re-find #"defmethod" (str (:error r))) (pr-str r))))

      (testing "a private page is refused — invisible to the tool's scan"
        (let [r (ops/add-form! sess 'ui.core
                               "(defn- ^:web/page hidden \"H.\" [] {:state (atom {}) :view (fn [_] [:div])})"
                               :prompt "marker on a private defn")]
          (is (re-find #"(?i)private" (str (:error r))) (pr-str r))))

      (testing "a multi-arity entry WITH a zero arity lands — slopp can call it with none"
        (let [r (ops/add-form! sess 'ui.core
                               (str "(defn ^:web/page app \"A.\""
                                    " ([] {:state (atom {}) :view (fn [_] [:div])})"
                                    " ([x] x))")
                               :prompt "zero arity exists, so the refusal's rationale does not apply")]
          (is (nil? (:error r)) (pr-str r))))
      (finally (ops/close! sess)))))

(deftest ^:external declaring-cljs-reports-the-pages-it-strands
  ;; Review B-F6: stranding happens with NO write to the page — declaring a
  ;; dependency :cljs changes no form, so the page's done has nothing to hang
  ;; the finding on, and the old prose claimed a coverage the advisory could
  ;; not deliver. The write that does the stranding is the surface that can
  ;; name it at the moment it happens.
  ;;
  ;; The fixture ASSERTS its own construction: ui.app requiring ui.views is a
  ;; cross-MODULE require, and the first cut of this test left that ingest
  ;; refused and unchecked — so the strand report was measured against a
  ;; store where the page never existed, and [] looked like a bug in the
  ;; report. A fixture that failed to build satisfies every absence assertion
  ;; downstream of it.
  (let [sess (external/open!)]
    (try
      (ops/config-file! sess "capabilities" :key "web.enabled" :value "true"
                        :prompt "opt into HTTP")
      (let [r1 (ops/ingest! sess 'ui.views "(ns ui.views)\n\n(defn view \"V.\" [s] [:div])\n")
            _  (ops/module-dep! sess "ui.app" "ui.views" :prompt "the page renders the views")
            r2 (ops/ingest! sess 'ui.app
                            (str "(ns ui.app (:require [ui.views :as v]))\n\n"
                                 "(defn ^:web/page page \"P.\" [] {:state (atom {}) :view v/view})\n"))]
        (is (nil? (:error r1)) (pr-str r1))
        (is (nil? (:error r2)) (pr-str r2)))

      (testing "the declaration that strands a page says so, naming page and culprit"
        (let [r (ops/module-platform! sess "ui.views" "cljs"
                                      :prompt "views to the client — the stranding move")]
          (is (nil? (:error r)) (pr-str r))
          (is (= '[{:page ui.app/page :cljs [ui.views]}] (:stranded-pages r)) (pr-str r))
          (is (str/includes? (str (:warning r)) "unreachable"))))

      (testing "a later unrelated :cljs declaration does not re-report the standing strand"
        (ops/ingest! sess 'ui.other "(ns ui.other)\n\n(defn f \"F.\" [x] x)\n")
        (let [r (ops/module-platform! sess "ui.other" "cljs"
                                      :prompt "unrelated client module")]
          (is (nil? (:error r)) (pr-str r))
          (is (nil? (:stranded-pages r))
              "a standing stranding belongs to the declaration that caused it")))
      (finally (ops/close! sess)))))
