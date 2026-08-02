(ns slopp.api.devserver-test
  "Tests for the framework-managed app server.

  What lives here is the DECIDING — is this a web project, what does it
  serve, on what address — which `serve-plan` answers as pure data from the
  store, so it needs no process. The launching, the done-grain refresh and
  the blue/green swap need a real image and are `^:external`."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.api.devserver :as devserver] [clojure.edn :as edn] [clojure.string :as str] [slopp.web.client :as client] [slopp.web :as web] [clojure.set :as set] [slopp.store.artifacts :as artifacts]))

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
      ;; what http-api.server/derived-port exists to refuse: it "worked for
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
  ;;
  ;; The first cut generated four of serve!'s options — namespaces, host,
  ;; port, adapter, all of which say WHERE to serve — and dropped every one
  ;; that says what the app NEEDS. Measured on a real app: handlers taking
  ;; `:web/deps` got nil, which either 500s or, worse, answers 200 with an
  ;; empty body that a client generator reads as success.
  (let [plan {:enabled? true :mode :dev
              :namespaces ['shop.api 'shop.data]
              :host "127.0.0.1" :port 51234 :adapter :jdk
              :max-body-bytes 2048
              :context-builder 'shop.system/deps}
        form (edn/read-string (devserver/serve-code plan))
        read (nth form 3)                       ; (:port (slopp.web/serve! …))
        call (second read)]
    (testing "the app's CONTEXT is built by the declared builder and passed in"
      ;; not quoted — this one is a CALL, which is why the opts can no longer
      ;; be one flat quoted map
      (is (= '(shop.system/deps) (:web/perform-ctx (last call))) (pr-str call)))
    (testing "and its namespace is required, since nothing else need reach it"
      ;; the builder lives wherever the app's system does — it is not part of
      ;; the served surface and so is not in :web/namespaces
      (is (some #{'(require (quote shop.system))} form) (pr-str form)))
    (testing "the body cap rides too — it has a capability, and the generated
              call ignoring it made that capability describe nothing"
      (is (= 2048 (:web/max-body-bytes (last call)))))
    (testing "the address fields still cross, quoted, because a namespace
              symbol in evaluated position is read as a CLASS name"
      ;; not hypothetical: unquoted, `demo.app` came back from a real app
      ;; image as `Syntax error (ClassNotFoundException) … demo.app`
      (let [opts (last call)]
        (is (= '(quote [shop.api shop.data]) (:web/namespaces opts)))
        (is (= "127.0.0.1" (:web/host opts)))
        (is (= 51234 (:web/port opts)))
        (is (= :jdk (:web/adapter opts)))))
    (testing "an app that declares NO builder passes no context, rather than
              an empty map that would read as one"
      (let [none (edn/read-string (devserver/serve-code (dissoc plan :context-builder)))]
        (is (not (contains? (last (second (nth none 2))) :web/perform-ctx)))))
    (testing "and it still evaluates to the BOUND port — an integer, so a
              throw (which comes back as a string) cannot read as success"
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
  store's own manifest, which for this fixture is empty.

  It echoes `:web/routes` for the same reason it echoes `:web/perform-ctx`:
  the managed server's failures have all been options that never crossed,
  and an option is only observably carried if something on the far side can
  be asked about it."
  (str "(ns slopp.web\n"
       ;; the real framework ships these as one vendored family on the
       ;; child's classpath; here they are store namespaces, so the sibling
       ;; is only loaded if something makes it REACHABLE
       "  (:require [slopp.web.static])\n"
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
       "                        \" ctx=\" (pr-str (:web/perform-ctx opts))\n"
       "                        \" cap=\" (pr-str (:web/max-body-bytes opts))\n"
       "                        \" routes=\" (pr-str (:web/routes opts))\n"
       "                        \" app=\" (when-let [v (resolve 'demo.app/greeting)] (v))))]\n"
       "            (.sendResponseHeaders x 200 (long (alength b)))\n"
       "            (with-open [o (.getResponseBody x)] (.write o b))))))\n"
       "    (.start srv)\n"
       "    {:port (.getPort (.getAddress srv))}))\n"))

(def fake-static-src
  "A stand-in `slopp.web.static` for the app-image test, as store source.

  Faked for the same reason as [[fake-web-src]] — nothing is vendored in a
  checkout — but this one has to do REAL WORK to be worth anything. The
  wiring under test is store bytes → a temp dir the parent writes → a reader
  in a child JVM that has no store, and only a reader that actually opens
  the file proves the chain. So `file-or-resource-reader` here slurps from
  `root` rather than returning a canned value, and `mount-routes` puts what
  it read into the row.

  The real pair is tested in `web-test/static-mounts-serve-raw-bytes`;
  nothing here stands in for routing or for content-type resolution."
  (str "(ns slopp.web.static)\n"
       "\n"
       "(defn file-or-resource-reader \"R.\" [root]\n"
       "  (fn [path]\n"
       "    (let [f (java.io.File. (str root) (str path))]\n"
       "      (when (.isFile f) {:content (slurp f)}))))\n"
       "\n"
       "(defn mount-routes \"M.\" [mounts reader]\n"
       "  (vec (for [[url prefix] mounts]\n"
       "         {:mounted url\n"
       "          :read (:content (reader (str prefix \"/app.css\")))})))\n"))

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
                 (store/ingest 'slopp.web.static fake-static-src)
                 (store/ingest 'demo.app
                               (str "(ns demo.app)\n\n"
                                    "(defn greeting \"G.\" [] \"hello from the store\")\n\n"
                                    "(defn ^{:web/context true} deps \"D.\"\n"
                                    "  [] {:built-by :the-app})\n\n"
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
          (is (str/includes? body "hello from the store")))
        (testing "and the app's declared CONTEXT was BUILT and passed in —
                  the failure that made a managed server useless to the one
                  app that measured it"
          ;; handlers receive this as :web/deps and performers as their first
          ;; argument. nil either 500s or, worse, answers 200 with an empty
          ;; body a client generator reads as success.
          (is (str/includes? body ":built-by :the-app") body))
        (testing "and the body cap, which had a capability describing nothing
                  while the generated call ignored it"
          (is (str/includes? body "cap=1048576") body)))
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
                 (store/ingest 'slopp.web.static fake-static-src)
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
  ;; store we looked at — slopp's own. Its web surface IS the reviewer API,
  ;; which the live session already serves over the LIVE store; a managed
  ;; server there would boot a second image and serve a snapshot of the page
  ;; you are looking at, one done point behind it.
  ;;
  ;; That used to be the `dev.server` capability. It is now DERIVED, because
  ;; the exemption is a fact about the running process and never a project's
  ;; preference — see `the-only-store-that-should-not-be-managed-is-derivable`
  ;; for why a knob only one store should touch is a footgun for everyone.
  ;;
  ;; Deliberately NOT folded into serve-plan: that answers "what would this
  ;; store serve", which production will need too, and a dev-only exemption
  ;; does not belong in it.
  (let [web (-> (store/empty-store)
                (store/ingest 'app.api
                              (str "(ns app.api)\n\n"
                                   "(defn ^{:web/method :get :web/path \"/hi\"\n"
                                   "        :malli/schema [:=> [:cat :map] :map]\n"
                                   "        :web/response :map} hi \"H.\" [req] {:ok true})\n"))
                (#(first (store/record-config-put % "capabilities" :manifest
                                                  "http.enabled" "true"))))]
    (testing "a web project is managed, and the app does not have to ask —
              nothing it can configure turns this off any more"
      (is (devserver/managed? web #{'something.else})))
    (testing "a store this process already serves is exempt, and stays a web
              project while it is"
      (is (not (devserver/managed? web #{'app.api})))
      (is (:enabled? (devserver/serve-plan web "/tmp/x")))
      (testing "and the plan still says where it WOULD serve, because that is
                what production asks"
        (is (pos? (:port (devserver/serve-plan web "/tmp/x"))))))
    (testing "a store that serves no HTTP at all is not managed either"
      (is (not (devserver/managed? (store/empty-store) #{}))))))

(deftest ^:external a-refresh-reports-what-it-cost
  ;; slopp-ui asked "measure app-image boot cost, and let the number pick the
  ;; project" — ~2s means state is the only argument for hot-loading a refresh
  ;; into the RUNNING image, ~15s means it pays on latency alone. But the
  ;; number is a property of the APP: it loads that store's namespaces with
  ;; that store's deps. Measuring slopp's own once answers for slopp once, so
  ;; the boot reports its own cost instead and every app reads its own.
  (let [dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-app"
                   (make-array java.nio.file.attribute.FileAttribute 0)))
        s    (-> (store/empty-store)
                 (store/ingest 'slopp.web fake-web-src)
                 (store/ingest 'slopp.web.static fake-static-src)
                 (store/ingest 'demo.app
                               (str "(ns demo.app)\n\n"
                                    "(defn ^{:web/method :get :web/path \"/hi\"\n"
                                    "        :malli/schema [:=> [:cat :map] :map]\n"
                                    "        :web/response :map} hi \"H.\" [req] {:ok true})\n"))
                 (#(first (store/record-config-put % "capabilities" :manifest
                                                   "http.enabled" "true"))))
        sess (atom {})
        r    (devserver/refresh! sess s dir)]
    (try
      (is (:serving? r) (str "refresh! did not serve: " (:reason r)))
      (testing "the running map carries how long the image took to come up"
        (is (integer? (:boot-ms r)) r)
        (is (pos? (:boot-ms r)) "a JVM launch plus a namespace load is never free"))
      (testing "and it is the BOOT, not the whole refresh — the number that a\n                hot-load would remove, without the bind it would not"
        ;; if this ever measured the bind too, a slow port would read as a
        ;; slow image and the comparison hot-load exists to inform is wrong
        (is (< (:boot-ms r) 120000) r))
      (finally (devserver/stop! r)))))

(deftest the-generated-serve-call-accounts-for-every-option-it-could-carry
  ;; The generalisation of `catalog-covers-every-registered-rule`, which is
  ;; the one completeness test this codebase had and the only reason the new
  ;; write gate could not ship uncataloged.
  ;;
  ;; This is the instance that was MISSING: `serve-code` enumerated four of
  ;; the eight options by hand, and the four it dropped were every option
  ;; describing the APP rather than its address. Nothing compared the two, so
  ;; it took a real app measuring a live server to find it — and the loudest
  ;; symptom was the quiet one, `/api/contracts` answering 200 with an empty
  ;; document that `generate_client` reads as success.
  ;;
  ;; Derived from the ARGLISTS rather than from the malli schemas: the
  ;; destructuring IS the implementation, so it cannot drift from what the
  ;; functions actually read. The schemas can and do — `serve!`'s omits
  ;; :web/routes and :web/max-body-bytes, which `context` destructures.
  (let [opt-keys  (fn [v] (->> (:arglists (meta v)) first first :web/keys
                               (map #(keyword "web" (name %))) set))
        ;; serve! reads the address options and hands the whole map to
        ;; context, which reads the rest. Both, because either alone is half.
        accepted  (into (opt-keys #'web/serve!) (opt-keys #'web/context))
                ;; every option it COULD carry, so the plan has to exercise them all —
        ;; a plan missing :static made :web/routes look ungenerated and let the
        ;; stale "deliberately dropped" entry survive the change that generated it
        plan      {:namespaces ['demo.app] :host "127.0.0.1" :port 1234
                   :adapter :http-kit :max-body-bytes 42
                   :context-builder 'demo.sys/deps
                   :static {"/assets" "public"} :static-dir "/tmp/x"}
        generated (->> (edn/read-string {:default (fn [_ v] v)}
                                        (devserver/serve-code plan))
                       (tree-seq coll? seq)
                       (filter map?)
                       first keys set)
        dropped   (set (keys devserver/unserved-options))]
    (testing "every option is either generated or declared deliberately dropped"
      (is (= accepted (into generated dropped))
          (str "unaccounted for: " (set/difference accepted generated dropped))))
    (testing "and a dropped one says WHY, so the gap is a decision and not an omission"
      ;; the rule `crossings/internal-markers` already follows: a partial
      ;; classification is worse than none, because the one real hole drowns
      ;; in the entries nobody explained
      (is (every? #(and (string? %) (seq %)) (vals devserver/unserved-options))))
    (testing "nothing is BOTH generated and declared dropped"
      ;; The union check above cannot see this: a key in both sets still
      ;; satisfies it, so prose explaining why an option is missing survives
      ;; the write that stops it being missing. Same hand-kept-twin shape
      ;; this test exists to kill, one level up — the classification is
      ;; itself a list that has to track the code.
      (is (empty? (set/intersection generated dropped))
          (str "declared dropped but actually generated: "
               (set/intersection generated dropped))))))

(deftest a-managed-app-serves-the-static-assets-it-declares
  ;; The managed server answered 404 for every static mount, because mounts
  ;; read the store's file manifest and the child image has no store. The
  ;; project that hurt is the one whose whole purpose is to be LOOKED AT: a
  ;; UI's stylesheet and its cljs bundle are the product, so "managed" meant
  ;; an unstyled page with a dead bundle. That project switched dev.server
  ;; off, which reads as "the feature does not apply to me" and is really
  ;; "the feature is broken for me" — the switch hid the bug.
  ;;
  ;; The seam was already right: `mount-routes` takes a READER fn, not a
  ;; store, and `file-or-resource-reader` is the filesystem one. So the fix
  ;; is materialize-then-point, not a new mechanism.
  (let [plan {:namespaces ['demo.app] :host "127.0.0.1" :port 1234
              :adapter :http-kit
              :static {"/assets" "public"}
              :static-dir "/tmp/slopp-static-probe"}
        code (devserver/serve-code plan)]
    (testing "the generated call mounts the prefixes the store declared"
      (is (str/includes? code "mount-routes") "no mount call was generated")
      (is (str/includes? code "/assets"))
      (is (str/includes? code "public")))
    (testing "reading from the dir the parent materialized, since the child has no store"
      (is (str/includes? code "file-or-resource-reader"))
      (is (str/includes? code "/tmp/slopp-static-probe")))
    (testing "and the namespace providing both is REQUIRED in the child"
      ;; Asserting the substring "slopp.web.static" passes on the qualified
      ;; symbol alone, so the first version of this went green while the
      ;; generated code would still have thrown at runtime — the child had
      ;; the symbol and not the namespace. Match the require FORM.
      (is (str/includes? code "(require (quote slopp.web.static))")
          "the child resolves slopp.web.static/mount-routes only if it required it"))
    (testing "a plan with no mounts generates no routes key at all"
      (is (not (str/includes? (devserver/serve-code (dissoc plan :static :static-dir))
                              "mount-routes"))
          "an app without mounts must not pay for the machinery"))))

(deftest static-bytes-are-materialized-for-a-child-that-has-no-store
  ;; The whole reason mounts were unserved. The child image is a separate JVM
  ;; with no store, so the bytes have to be somewhere it can read them —
  ;; which `file-or-resource-reader` already does, given a dir.
  (let [put  (fn [s k v] (first (store/record-config-put s "capabilities" :manifest k v)))
        s    (-> (store/empty-store)
                 (put "http.enabled" "true")
                 (put "http.static./assets" "public"))
        s    (first (store/record-file-put s "public/app.css" "body{}"))
        s    (first (store/record-file-put s "public/deep/x.txt" "hi"))
        s    (first (store/record-file-put s "src/notes.md" "# not mounted"))
        ;; tracked files carry their content inline, so the blob fallback is
        ;; never consulted here — artifacts are the case that needs it
        dir  (devserver/materialize-static! s {"/assets" "public"} (constantly nil))]
    (testing "a mounted file lands under the dir at its manifest path"
      (is (= "body{}" (slurp (str dir "/public/app.css")))))
    (testing "nested paths keep their shape, so one mount serves a TREE"
      (is (= "hi" (slurp (str dir "/public/deep/x.txt")))))
    (testing "a file no mount covers is not copied — the dir is the mount, not the store"
      (is (not (.exists (java.io.File. (str dir "/src/notes.md"))))))
    (testing "no mounts means no dir, so an app without assets allocates nothing"
      (is (nil? (devserver/materialize-static! s {} (constantly nil)))))))

(deftest ^:external a-managed-server-carries-the-apps-assets-all-the-way-into-the-child
  ;; The wiring test. The unit tests prove `serve-code` generates a mount and
  ;; `materialize-static!` writes bytes; neither would have caught the actual
  ;; defect, which was that nothing connected them. The chain is: store
  ;; manifest → a temp dir the parent writes → a reader in a child JVM that
  ;; has no store — three processes' worth of assumptions and no single unit
  ;; that spans them.
  (let [dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-app-static"
                   (make-array java.nio.file.attribute.FileAttribute 0)))
        s    (-> (store/empty-store)
                 (store/ingest 'slopp.web fake-web-src)
                 (store/ingest 'slopp.web.static fake-static-src)
                 (store/ingest 'demo.app
                               (str "(ns demo.app)\n\n"
                                    "(defn greeting \"G.\" [] \"hello\")\n"))
                 (#(first (store/record-config-put % "capabilities" :manifest
                                                   "http.enabled" "true")))
                 (#(first (store/record-config-put % "capabilities" :manifest
                                                   "http.static./assets" "public")))
                 (#(first (store/record-file-put % "public/app.css"
                                                 "body{color:rebeccapurple}"))))
        sess (atom {})
        r    (devserver/start! sess s dir)]
    (try
      (is (:serving? r) (str "start! did not serve: " (:reason r)))
      (let [body (:http/body (client/request {:http/url (:url r)
                                              :http/timeout-ms 5000}))]
        (testing "the mount the store declared reached the child as a route"
          (is (str/includes? body ":mounted \"/assets\"") body))
        (testing "and the child READ the file — store bytes, materialized,
                  opened by a process that cannot see the store"
          ;; the assertion that spans all three. A mount that arrived but
          ;; pointed at an empty dir passes the line above and fails here.
          (is (str/includes? body "rebeccapurple") body)))
      (finally (devserver/stop! r)))))

(deftest the-only-store-that-should-not-be-managed-is-derivable
  ;; `dev.server` existed for exactly one true case: a store whose HTTP
  ;; surface the live session ALREADY serves, where a managed server would
  ;; boot a second image and serve a staler copy of the page in front of
  ;; you. That is slopp's own store, and it is the only one on earth.
  ;;
  ;; A capability that one store should ever set is a footgun for everyone
  ;; else, and it went off within a week: the second project to meet it set
  ;; it false because its ASSETS were 404ing, and the switch then hid that
  ;; bug rather than reporting it. So the question stops being asked and
  ;; starts being computed — this process knows what it serves.
  (let [put  (fn [s k v] (first (store/record-config-put s "capabilities" :manifest k v)))
        web  (-> (store/empty-store)
                 (store/ingest 'app.api
                               (str "(ns app.api)\n\n"
                                    "(defn ^{:web/method :get :web/path \"/hi\"\n"
                                    "        :malli/schema [:=> [:cat :map] :map]\n"
                                    "        :web/response :map} hi \"H.\" [req] {:ok true})\n"))
                 (put "http.enabled" "true"))]
    (testing "an ordinary web project is not self-served, whatever else is running"
      (is (not (devserver/self-served? web #{'some.other.ns}))))
    (testing "a store whose every serving namespace this process already serves IS"
      (is (devserver/self-served? web #{'app.api})))
    (testing "and a store that serves NOTHING is not self-served — two empty
              sets agree, and vacuous truth here would silently stop managing
              every non-web project's server for the wrong reason"
      (is (not (devserver/self-served? (store/empty-store) #{}))))))

(deftest static-bytes-for-an-artifact-come-from-the-on-disk-cache
  ;; Measured against slopp-ui's real store minutes after shipping mounts:
  ;; `/assets/cljs/main.js` still 404ed. Its assets are ARTIFACTS
  ;; (compile_client writes the bundle as one), `store/file-content`
  ;; answered `:content nil`, every path was filtered out, and the
  ;; materialized dir held nothing.
  ;;
  ;; An artifact keeps its bytes OUT OF LINE under
  ;; `<store-dir>/.slopp/artifacts/<sha>`. `:content nil` carries two facts
  ;; at once — there are no bytes, and the bytes are one indirection away —
  ;; and the skip written for the first silently swallowed the second,
  ;; producing precisely the 404 it existed to avoid.
  ;;
  ;; **This uses a REAL artifact deliberately** (slopp-ui's suggestion, and
  ;; they were right): the first version passed a synthetic fetcher, went
  ;; green, and proved only that the plumbing called something — while the
  ;; accessor underneath was the blob table, which does not hold artifacts
  ;; and returned nil for the real sha. A fixture standing in for the thing
  ;; under test cannot fail the way production does. Their store also had an
  ;; EMPTY `:files`, so a fixture with any tracked asset would half-work and
  ;; read as success.
  (let [dir   (str (java.nio.file.Files/createTempDirectory
                    "slopp-artifact-store"
                    (make-array java.nio.file.attribute.FileAttribute 0)))
        bs    (.getBytes "console.log(1)" "UTF-8")
        entry (artifacts/put! dir bs {:kind :cljs}
                              :content-type "application/javascript")
        st    {:artifacts {"public/cljs/main.js" entry} :blobs {}}
        out   (devserver/materialize-static! st {"/assets" "public"} dir)]
    (testing "the artifact's bytes are read from the cache and written out"
      (is (= "console.log(1)" (slurp (str out "/public/cljs/main.js")))))
    (testing "and inline content is NOT required — the store answers nil for it"
      (is (nil? (:content (store/file-content st "public/cljs/main.js")))))
    (testing "a store-dir holding no such artifact skips and does not throw —
              one dead asset must not take down a server serving every other path"
      (let [empty-dir (str (java.nio.file.Files/createTempDirectory
                            "slopp-empty-store"
                            (make-array java.nio.file.attribute.FileAttribute 0)))
            o2 (devserver/materialize-static! st {"/assets" "public"} empty-dir)]
        (is (not (.exists (java.io.File. (str o2 "/public/cljs/main.js")))))))))
