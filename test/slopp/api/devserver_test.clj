(ns slopp.api.devserver-test
  "Tests for the framework-managed app server.

  What lives here is the DECIDING — is this a web project, what does it
  serve, on what address — which `serve-plan` answers as pure data from the
  store, so it needs no process. The launching, the done-grain refresh and
  the blue/green swap need a real image and are `^:external`."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.api.devserver :as devserver] [clojure.edn :as edn] [clojure.string :as str] [slopp.web.client :as client]))

(deftest a-serve-plan-is-derived-from-the-store
  (let [src (str "(ns shop.api)\n\n"
                 "(defn ^{:web/method :get :web/path \"/api/users\"\n"
                 "        :web/auth :authenticated\n"
                 "        :malli/schema [:=> [:cat :map] :map]\n"
                 "        :web/response :map} users \"U.\" [req] req)\n")
        off (store/ingest (store/empty-store) 'shop.api src)
        on  (first (store/record-config-put off "capabilities" :manifest
                                            "http.enabled" "true"))
        put (fn [s k v] (first (store/record-config-put s "capabilities"
                                                        :manifest k v)))]
    (testing "http.enabled is the opt-in, and refusing says how to opt in"
      (let [p (devserver/serve-plan off "/tmp/shop")]
        (is (false? (:enabled? p)))
        (is (re-find #"http\.enabled" (:reason p)))
        (is (nil? (:port p)) "nothing is bound for a store that never opted in")))
    (testing "what to serve comes from the store, never from the caller"
      (is (= ['shop.api] (:namespaces (devserver/serve-plan on "/tmp/shop")))))
    (testing "host and adapter are the declared capabilities"
      (let [p (devserver/serve-plan on "/tmp/shop")]
        (is (= "127.0.0.1" (:host p)))
        (is (= :http-kit (:adapter p)))))
    (testing "an explicitly set http.port WINS — a pinned address stays pinned"
      (is (= 9999 (:port (devserver/serve-plan (put on "http.port" "9999")
                                               "/tmp/shop")))))
    (testing "unset, the port DERIVES from the store dir"
      ;; http.port's registry DEFAULT is 8080, and a fixed default is exactly
      ;; what review.server/derived-port exists to refuse: it "worked for
      ;; exactly one project and collided for the second". Production wants a
      ;; known number, so the default still stands there — but two dev
      ;; sessions on one machine must not fight, so an UNSET port derives.
      (let [a (:port (devserver/serve-plan on "/tmp/shop"))
            b (:port (devserver/serve-plan on "/tmp/other"))]
        (is (not= 8080 a) "the fixed default is not what a dev session binds")
        (is (not= a b) "two projects on one machine derive different ports")
        (is (= a (:port (devserver/serve-plan on "/tmp/shop")))
            "stable across restarts — the url a human bookmarked keeps working")
        (is (< 1024 a 65536))))
    (testing "the plan says it is dev, so nothing reads it as the shipped one"
      ;; the dev server and the built app answer the same routes from
      ;; different stores at different grains — a plan that does not say
      ;; which it is becomes a proxy for the other (Core 9)
      (is (= :dev (:mode (devserver/serve-plan on "/tmp/shop"))))) ))

(deftest the-app-image-loads-the-web-surface-and-what-it-reaches
  (let [s (-> (store/empty-store)
              (store/ingest 'shop.db "(ns shop.db)\n(defn fetch \"F.\" [id] id)\n")
              (store/ingest 'shop.api
                            (str "(ns shop.api (:require [shop.db :as db]))\n\n"
                                 "(defn ^{:web/method :get :web/path \"/api/u/:id\"\n"
                                 "        :web/auth :authenticated\n"
                                 "        :web/reads {:u [:u/by-id [:path-params :id]]}\n"
                                 "        :malli/schema [:=> [:cat :map] :map]\n"
                                 "        :web/response :map} u \"U.\" [req] (db/fetch req))\n"))
              (store/ingest 'shop.data
                            (str "(ns shop.data)\n"
                                 "(defn ^{:web/read :u/by-id} by-id \"R.\" [ctx id] id)\n"))
              ;; nothing in the web surface reaches this
              (store/ingest 'shop.tools "(ns shop.tools)\n(defn cli \"C.\" [x] x)\n")
              (#(first (store/record-config-put % "capabilities" :manifest
                                                "http.enabled" "true"))))
        order (devserver/load-order s)]
    (testing "the web surface and everything it transitively requires"
      (is (= #{'shop.api 'shop.db 'shop.data} (set order))))
    (testing "a namespace the surface cannot reach is not loaded into the app"
      ;; the app image exists to run the APP; loading the whole store would
      ;; make its boot cost grow with the codebase and put code in a serving
      ;; process that nothing serving can call
      (is (not (some #{'shop.tools} order))))
    (testing "dependencies first — the child has no classpath to fall back on"
      (is (< (.indexOf ^java.util.List order 'shop.db)
             (.indexOf ^java.util.List order 'shop.api))))
    (testing "the framework loads from the store when the store is where it lives"
      ;; slopp's own store HOLDS slopp.web; an ordinary app gets it from the
      ;; declared slopp-web coord, already on the child's classpath. Neither
      ;; case may require the app to say which.
      (let [with-fw (store/ingest s 'slopp.web "(ns slopp.web)\n(defn serve! \"S.\" [o] o)\n")]
        (is (some #{'slopp.web} (devserver/load-order with-fw)))))
    (testing "and its absence from the store is not an error"
      (is (not (some #{'slopp.web} order))))
    (testing "a store with no web surface loads nothing"
      (is (= [] (devserver/load-order (store/empty-store)))))))

(deftest the-serve-call-is-built-from-the-plan-not-written-by-the-app
  ;; The whole directive is that an app holds no `serve!` call. So the call
  ;; has to be constructed, and constructing it is pure — which keeps the
  ;; only interesting decisions (what crosses, what comes back) answerable
  ;; without a JVM.
  (let [plan {:enabled? true :mode :dev
              :namespaces ['shop.api 'shop.data]
              :host "127.0.0.1" :port 51234 :adapter :jdk}
        code (devserver/serve-code plan)
        form (edn/read-string code)
        read (nth form 2)                       ; (:port (slopp.web/serve! …))
        call (second read)]                     ; (slopp.web/serve! …)
    (testing "a string, because it crosses an nREPL wire as text"
      (is (string? code)))
    (testing "every field of the plan reaches serve!, and nothing else does"
      (is (= '(do (require (quote slopp.web))
                  (:port (slopp.web/serve! (quote {:web/namespaces [shop.api shop.data]
                                                   :web/host "127.0.0.1"
                                                   :web/port 51234
                                                   :web/adapter :jdk}))))
             form)))
    (testing "the opts are QUOTED — generating a form means every value lands
              in evaluated position, and a namespace symbol there is read as
              a CLASS name"
      ;; not hypothetical: unquoted, `demo.app` came back from a real app
      ;; image as `Syntax error (ClassNotFoundException) … demo.app`, from
      ;; inside a serve call that looked correct. Quoting the whole map
      ;; rather than the vector keeps it true of fields not invented yet.
      (is (= 'quote (first (second call)))))
    (testing "the require is unconditional, so the two framework suppliers are
              ONE call — the store holds slopp.web (slopp's own store) or the
              vendored dir does (an ordinary app), and the app never says which"
      ;; a namespace already loaded through load-ns-into! is marked in
      ;; *loaded-libs*, so this is a no-op there rather than a second load
      (is (= '(require (quote slopp.web)) (nth form 1))))
    (testing "and it evaluates to the BOUND port — an INTEGER, so success
              cannot be confused with failure, which repl/eval! hands back as
              a string"
      ;; Core 1 at the transport: "it bound port 51234" and "it threw" must
      ;; not share a representation. An eval that returns nil for both is how
      ;; a dead app server reads as a live one.
      (is (= :port (first read))))))

(def fake-web-src
  "A stand-in `slopp.web` for the app-image test, as store source.

  It is FAKED for the same reason `api-test/a-built-web-app-RUNS-outside-slopp-entirely`
  fakes it: this suite runs from a checkout where `boot/framework-files` is
  nil, so vendoring supplies nothing, and a test that branched on that would
  assert shape in the only environment it ever executes in.

  The stand-in carries the properties under test — it binds a real socket on
  the address the plan derived, answers over HTTP, reports the BOUND port,
  and reaches the app's own code through `resolve` (so a page can only be
  served if the store's namespaces really loaded into that image). Routing
  is `slopp.web`'s job and is tested in `slopp.web-test`; nothing here
  stands in for it. Zero deps on purpose: the child image carries only the
  store's own manifest, which for this fixture is empty."
  (str "(ns slopp.web\n"
       "  (:import [com.sun.net.httpserver HttpServer HttpHandler]\n"
       "           [java.net InetSocketAddress]))\n"
       "\n"
       "(defn serve! \"Bind and answer.\" [opts]\n"
       "  (let [srv (HttpServer/create\n"
       "             (InetSocketAddress. ^String (:web/host opts)\n"
       "                                 (int (:web/port opts))) 0)]\n"
       "    (.createContext srv \"/\"\n"
       "      (reify HttpHandler\n"
       "        (handle [_ x]\n"
       "          (let [b (.getBytes\n"
       "                   (str \"ns=\" (pr-str (:web/namespaces opts))\n"
       "                        \" app=\" (when-let [v (resolve 'demo.app/greeting)] (v))))]\n"
       "            (.sendResponseHeaders x 200 (long (alength b)))\n"
       "            (with-open [o (.getResponseBody x)] (.write o b))))))\n"
       "    (.start srv)\n"
       "    {:port (.getPort (.getAddress srv))}))\n"))

(deftest ^:external the-app-server-comes-up-and-answers-without-the-app-asking
  ;; The directive this whole namespace exists for: "when we have a web slopp
  ;; project under development, there should always be a live/up-to-date
  ;; version of the server up and going." The store below holds no `serve!`
  ;; call, no namespace list and no port — everything the launch needs is
  ;; derived from what is already there.
  ;;
  ;; Driven through `client/request` rather than a raw slurp. `:direct-http`
  ;; asks for exactly that, and unlike `slopp.web-test`'s round-trips this
  ;; test has no reason to want an INDEPENDENT client: slopp.web.client is
  ;; not what is under test here, so using it is not circular.
  (let [dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-app"
                   (make-array java.nio.file.attribute.FileAttribute 0)))
        s    (-> (store/empty-store)
                 (store/ingest 'slopp.web fake-web-src)
                 (store/ingest 'demo.app
                               (str "(ns demo.app)\n\n"
                                    "(defn greeting \"G.\" [] \"hello from the store\")\n\n"
                                    "(defn ^{:web/method :get :web/path \"/hi\"\n"
                                    "        :malli/schema [:=> [:cat :map] :map]\n"
                                    "        :web/response :map} hi \"H.\" [req] {:ok true})\n"))
                 (#(first (store/record-config-put % "capabilities" :manifest
                                                   "http.enabled" "true"))))
        sess (atom {})
        r    (devserver/start! sess s dir)]
    (try
      (testing "it is up, and the app said nothing to make that happen"
        (is (:serving? r) (str "start! did not serve: " (:reason r))))
      (testing "at the address the plan derived — one answer, not two"
        ;; two derivations of "where does this serve" can disagree, and the
        ;; failure is a url that is reported and a port that is bound
        (is (= (:port (devserver/serve-plan s dir)) (:port r)))
        (is (= (str "http://127.0.0.1:" (:port r) "/") (:url r))))
      (let [body (:http/body (client/request {:http/url (:url r)
                                              :http/timeout-ms 5000}))]
        (testing "the DERIVED namespace list is what crossed into the image"
          (is (str/includes? body "demo.app")))
        (testing "and the store's own code is LOADED there — the page is
                  served by the app, not by something that merely booted"
          ;; this is what makes it an app server rather than a socket: the
          ;; handler reaches demo.app/greeting, which exists only in the
          ;; store and has no classpath to fall back on
          (is (str/includes? body "hello from the store"))))
      (finally (devserver/stop! r)))
    (testing "and stop! takes the whole thing down, because the image IS the
              server — there is no half-stopped state to leak a port"
      (is (= :unreachable
             (:http/error
              (ex-data (try (client/request {:http/url (:url r)
                                             :http/timeout-ms 5000})
                            (catch clojure.lang.ExceptionInfo e e)))))))))

(deftest ^:external a-refresh-swaps-the-app-and-a-red-store-does-not-take-it-down
  ;; "Always up" and "up to date" conflict exactly when a boot fails, and at
  ;; done grain a failing boot is not exotic — mid-episode the store is
  ;; intentionally incomplete, and red-first IS the normal state. So the new
  ;; version is proved to LOAD before the old one is killed.
  (let [dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-refresh"
                   (make-array java.nio.file.attribute.FileAttribute 0)))
        base (-> (store/empty-store)
                 (store/ingest 'slopp.web fake-web-src)
                 (#(first (store/record-config-put % "capabilities" :manifest
                                                   "http.enabled" "true"))))
        app  (fn [greeting]
               (store/ingest base 'demo.app
                             (str "(ns demo.app)\n\n"
                                  "(defn greeting \"G.\" [] \"" greeting "\")\n\n"
                                  "(defn ^{:web/method :get :web/path \"/hi\"\n"
                                  "        :malli/schema [:=> [:cat :map] :map]\n"
                                  "        :web/response :map} hi \"H.\" [req] {:ok true})\n")))
        sess (atom {})
        body (fn [r] (:http/body (client/request {:http/url (:url r)
                                                  :http/timeout-ms 5000})))]
    (try
      (let [v1 (devserver/refresh! sess (app "version one") dir)]
        (testing "the first refresh is just a start"
          (is (:serving? v1) (str "refresh! did not serve: " (:reason v1)))
          (is (str/includes? (body v1) "version one")))
        (testing "and it is held on the session, so the next refresh knows
                  what it is replacing"
          (is (= v1 (:app-server @sess))))
        (let [v2 (devserver/refresh! sess (app "version two") dir)]
          (testing "a second refresh serves the CURRENT store"
            (is (:serving? v2) (str "refresh! did not re-serve: " (:reason v2)))
            (is (str/includes? (body v2) "version two")))
          (testing "on the same url — a stable address is the whole reason to
                    derive a port rather than take a free one"
            (is (= (:url v1) (:url v2))))
          (let [red (store/ingest (app "version three") 'demo.broken
                                  (str "(ns demo.broken (:require [demo.app :as a]))\n\n"
                                       "(defn ^{:web/method :get :web/path \"/b\"\n"
                                       "        :malli/schema [:=> [:cat :map] :map]\n"
                                       "        :web/response :map} b \"B.\"\n"
                                       "  [req] (a/nope-not-a-thing))\n"))
                v3  (devserver/refresh! sess red dir)]
            (testing "a store that will not load does NOT come up"
              (is (not (:serving? v3)))
              (is (str/includes? (str (:reason v3)) "demo.broken")))
            (testing "and the PREVIOUS version is still answering — the app is
                      not down because someone was mid-thought"
              (is (str/includes? (body v2) "version two"))
              (is (= v2 (:app-server @sess)))))))
      (finally (devserver/stop! (:app-server @sess))))))

(deftest whether-slopp-manages-a-dev-server-is-its-own-question
  ;; http.enabled means "this project serves HTTP". It does NOT mean "slopp
  ;; should run that server for you", and the two came apart on the first
  ;; store we looked at — slopp's own. Its web surface IS the MCP HTTP
  ;; transport plus the reviewer API, which the live session already serves
  ;; over the LIVE store; a managed server there would boot a second image
  ;; and serve a snapshot of the page you are looking at, one done point
  ;; behind it.
  ;;
  ;; So the dev lifecycle gets its own key. Deliberately NOT folded into
  ;; serve-plan: that answers "what would this store serve", which
  ;; production will need too, and a dev-only opt-out does not belong in it.
  (let [web  (-> (store/empty-store)
                 (#(first (store/record-config-put % "capabilities" :manifest
                                                   "http.enabled" "true"))))
        off  (first (store/record-config-put web "capabilities" :manifest
                                             "dev.server" "false"))]
    (testing "a web project is managed by default — the whole directive is
              that the app does not have to ask"
      (is (devserver/managed? web)))
    (testing "a project that serves itself opts out, and stays a web project
              while it does"
      (is (not (devserver/managed? off)))
      (is (:enabled? (devserver/serve-plan off "/tmp/x")))
      (testing "and the plan still says where it WOULD serve, because that is
                what production asks"
        (is (pos? (:port (devserver/serve-plan off "/tmp/x"))))))
    (testing "a store that serves no HTTP at all is not managed either"
      (is (not (devserver/managed? (store/empty-store)))))))
