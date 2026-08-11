(ns slopp.web-test
  "The web facade from the OUTSIDE: `serve!` on a real port, answered by a real
  client, across both server adapters.

  It is also the home of `reader-contract` — the suite both adapters of the
  static-file reader port must pass — which is why a test namespace here is
  required by others rather than being a leaf.

  Everything that binds a socket in here uses its OWN client, declared
  `^{:adapter \"http — …\"}` per test rather than exempted by rule. That is the
  one deliberate exception to \"all HTTP goes through `slopp.web.client`\":
  `requester-contract` uses `serve!` as ITS far side, so routing the server's
  own tests through the client would close the loop and let a symmetric bug —
  client omits a header, server ignores it — pass both suites."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.web :as slopp.web] [slopp.web.static :as static] [slopp.web.router :as router] [clojure.string :as str]))

(defn ^{:web/method :get :web/path "/w/mine/:owner" :web/auth :authenticated}
  t-mine
  "Row-level check inside the handler: only the owner may read."
  [req]
  (slopp.web/enforce (= (:owner (:path-params req))
                        (:web/sub (:web/identity req))))
  {:status 200 :body {:yours true}})

(deftest facade-assembles-and-enforces
  (let [ctx (slopp.web/context {:web/namespaces ['slopp.web-test]})]
    (testing "context derives the route table from var metadata"
      (is (= 1 (count (:web/routes ctx))))
      (is (= "/w/mine/:owner" (:path (first (:web/routes ctx))))))
    (testing "handle! is the portless test surface"
      (let [r (slopp.web/handle! ctx {:request-method :get :uri "/w/mine/ada"
                                :web/identity {:web/sub "ada" :web/groups #{}}})]
        (is (= 200 (:status r)) (pr-str r))))
    (testing "enforce inside the handler maps to 403 response data"
      (let [r (slopp.web/handle! ctx {:request-method :get :uri "/w/mine/ada"
                                :web/identity {:web/sub "eve" :web/groups #{}}})]
        (is (= 403 (:status r)) (pr-str r))))
    (testing "authorized? answers booleans for branching"
      (is (slopp.web/authorized? [:group "admin"] {:web/groups #{"admin"}}))
      (is (not (slopp.web/authorized? [:group "admin"] nil))))))

(deftest ^:external
  ^{:adapter "http — a deliberately INDEPENDENT client. requester-contract's
              real run uses serve! as ITS far side, so routing the server's own
              tests through slopp.web.client would make the two mutually
              circular and let a symmetric bug (client omits a header, server
              ignores it) pass both. The server tests are the one place that
              must not go through the port."}
  serve-round-trips-the-facade
  (let [srv (slopp.web/serve! {:web/namespaces ['slopp.web-test]
                         :web/port 0})
        http (java.net.http.HttpClient/newHttpClient)
        resp (.send http
                    (-> (java.net.http.HttpRequest/newBuilder)
                        (.uri (java.net.URI/create
                               (str "http://127.0.0.1:" (:port srv) "/w/mine/ada")))
                        (.build))
                    (java.net.http.HttpResponse$BodyHandlers/ofString))]
    (try
      (testing "the anonymous request is refused by the declared policy, over the wire"
        (is (= 401 (.statusCode resp))))
      (finally (slopp.web/stop! srv)))))

(deftest ^:external ^{:adapter "http — independent client on purpose; same reason as
              serve-round-trips-the-facade, and doubly so here: this test exists
              to prove a SECOND server adapter behaves like the first, which a
              shared client cannot witness."}
  httpkit-adapter-round-trips-the-facade
  (let [srv (slopp.web/serve! {:web/namespaces ['slopp.web-test]
                         :web/adapter :http-kit
                         :web/port 0})
        http (java.net.http.HttpClient/newHttpClient)
        resp (.send http
                    (-> (java.net.http.HttpRequest/newBuilder)
                        (.uri (java.net.URI/create
                               (str "http://127.0.0.1:" (:port srv) "/w/mine/ada")))
                        (.build))
                    (java.net.http.HttpResponse$BodyHandlers/ofString))]
    (try
      (testing "the declared policy refuses over http-kit exactly as over jdk"
        (is (= 401 (.statusCode resp))))
      (finally (slopp.web/stop! srv)))))

(deftest ^:external ^{:adapter "http — independent client on purpose; same reason as
              serve-round-trips-the-facade. This one sends AUTH headers, which
              is precisely the shape a symmetric client/server bug would hide."}
  auth-round-trips-over-the-wire
  (let [srv (slopp.web/serve! {:web/namespaces ['slopp.web-test]
                         :web/adapter :http-kit
                         :web/port 0
                         :web/auth-config {:auth/providers [:bearer]
                                           :auth/bearer {"ada" {:secret "tok-ada"
                                                                :groups ["dev"]}}}})
        http (java.net.http.HttpClient/newHttpClient)
        GET (fn [path & [token]]
              (let [b (cond-> (java.net.http.HttpRequest/newBuilder)
                        true (.uri (java.net.URI/create
                                    (str "http://127.0.0.1:" (:port srv) path)))
                        token (.header "Authorization" (str "Bearer " token)))]
                (.statusCode (.send http (.build b)
                                    (java.net.http.HttpResponse$BodyHandlers/ofString)))))]
    (try
      (testing "anonymous → 401; wrong token → 401; the right token → 200 (t-mine checks sub=owner)"
        (is (= 401 (GET "/w/mine/ada")))
        (is (= 401 (GET "/w/mine/ada" "wrong")))
        (is (= 200 (GET "/w/mine/ada" "tok-ada")))
        (testing "and enforce still 403s the wrong owner, authenticated or not"
          (is (= 403 (GET "/w/mine/someone-else" "tok-ada")))))
      (finally (slopp.web/stop! srv)))))

(deftest ^:external ^{:adapter "http — independent client on purpose; same reason as
              serve-round-trips-the-facade. Raw BYTES are the case where a
              shared client's own decoding would be indistinguishable from the
              server's encoding."}
  static-mounts-serve-raw-bytes
  (let [png (byte-array [(byte -119) 80 78 71 9 8 7])
        reader (fn [path]
                 (get {"public/logo.png" {:content png :content-type "image/png"}
                       "public/app.css"  {:content "body{}" :content-type "text/css"}}
                      path))
        rows (static/mount-routes {"/assets" "public"} reader)
        srv  (slopp.web/serve! {:web/namespaces []
                          :web/routes rows
                          :web/adapter :http-kit
                          :web/port 0})
        http (java.net.http.HttpClient/newHttpClient)
        GET  (fn [path]
               (let [resp (.send http
                                 (-> (java.net.http.HttpRequest/newBuilder)
                                     (.uri (java.net.URI/create
                                            (str "http://127.0.0.1:" (:port srv) path)))
                                     (.build))
                                 (java.net.http.HttpResponse$BodyHandlers/ofByteArray))]
                 {:status (.statusCode resp)
                  :type (.orElse (.firstValue (.headers resp) "content-type") nil)
                  :body (.body resp)}))]
    (try
      (testing "bytes round-trip with their content type, no JSON wrapping"
        (let [r (GET "/assets/logo.png")]
          (is (= 200 (:status r)))
          (is (= "image/png" (:type r)))
          (is (java.util.Arrays/equals png ^bytes (:body r)))))
      (testing "text assets serve as their own media type"
        (let [r (GET "/assets/app.css")]
          (is (= "text/css" (:type r)))
          (is (= "body{}" (String. ^bytes (:body r) "UTF-8")))))
      (testing "an unknown file is a 404"
        (is (= 404 (:status (GET "/assets/nope.js")))))
      (testing "a path prefix written with a TRAILING SLASH mounts the same
                tree. The handler adds its own separator, so `public/` asked
                the reader for `public//app.css` — and a store-backed reader,
                which looks a path up in a manifest rather than on a
                filesystem that would normalise it, answered nothing."
        (let [srv2 (slopp.web/serve! {:web/namespaces []
                                :web/routes (static/mount-routes {"/assets" "public/"} reader)
                                :web/adapter :http-kit :web/port 0})
              get2 (fn [path]
                     (.statusCode
                      (.send http
                             (-> (java.net.http.HttpRequest/newBuilder)
                                 (.uri (java.net.URI/create
                                        (str "http://127.0.0.1:" (:port srv2) path)))
                                 (.build))
                             (java.net.http.HttpResponse$BodyHandlers/ofByteArray))))]
          (try
            (is (= 200 (get2 "/assets/app.css")))
            (finally (slopp.web/stop! srv2)))))
      (finally (slopp.web/stop! srv)))))

(deftest ^:external built-app-reader-resolves-fs-then-resources
  (let [dir (str (java.nio.file.Files/createTempDirectory
                  "slopp-static" (make-array java.nio.file.attribute.FileAttribute 0)))
        _   (.mkdirs (java.io.File. dir "public"))
        _   (spit (java.io.File. dir "public/app.css") "body{}")
        rdr (static/file-or-resource-reader dir)]
    (testing "a filesystem file resolves with its extension's type"
      (let [{:keys [content content-type]} (rdr "public/app.css")]
        (is (= "text/css" content-type))
        (is (= "body{}" (String. ^bytes content "UTF-8")))))
    (testing "a classpath resource resolves when the file is absent"
      ;; clojure/core.clj is guaranteed on the classpath of any test JVM
      (is (some? (:content (rdr "clojure/version.properties")))))
    (testing "missing everywhere is nil"
      (is (nil? (rdr "public/nope.js"))))))

(deftest ^:external built-app-reader-refuses-path-traversal
  ;; review W5: the reader built File(root, path) with no containment check,
  ;; so `../secret` escaped root. Contained today only by the router's
  ;; single-segment accident — the reader itself must refuse traversal, since
  ;; it is ^:export public surface and the docstring flags the single-segment
  ;; constraint as temporary.
  (let [base (str (java.nio.file.Files/createTempDirectory
                   "slopp-trav" (make-array java.nio.file.attribute.FileAttribute 0)))
        pub  (java.io.File. base "public")
        _    (.mkdirs pub)
        _    (spit (java.io.File. pub "ok.txt") "fine")
        _    (spit (java.io.File. base "secret.txt") "TOP SECRET")
        rdr  (static/file-or-resource-reader (str pub))]
    (testing "an in-root file still serves"
      (is (= "fine" (String. ^bytes (:content (rdr "ok.txt")) "UTF-8"))))
    (testing "a traversal to a file ABOVE root is refused (nil)"
      (is (nil? (rdr "../secret.txt")))
      (is (nil? (rdr "../../etc/hosts")))
      (is (nil? (rdr "sub/../../secret.txt"))))))

(deftest static-mounts-serve-a-tree-and-refuse-traversal
  ;; F6: slopp's own default bundle path (public/cljs/main.js -> /assets/cljs/
  ;; main.js) was unservable because the mount matched one segment only. Now it
  ;; is a catch-all — which REMOVES the accidental containment that was the only
  ;; thing preventing /assets/../../etc/passwd, so the refusal must be explicit
  ;; and the reader must never even be reached.
  (let [seen   (atom [])
        reader (fn [p] (swap! seen conj p) {:content "x" :content-type "text/plain"})
        row    (first (static/mount-routes {"/assets" "public"} reader))
        call   (fn [captured] ((:handler row) {:path-params {:path captured}}))]
    (testing "the mount is a catch-all, so nested assets are reachable"
      (is (= "/assets/*path" (:path row))))
    (testing "a nested path reads under the mount prefix"
      (is (= 200 (:status (call "cljs/main.js"))))
      (is (= "public/cljs/main.js" (last @seen))))
    (testing "traversal is refused, and the reader is never called"
      (reset! seen [])
      (is (= 404 (:status (call "../../etc/passwd"))))
      (is (= 404 (:status (call "cljs/../../../secret"))))
      (is (= 404 (:status (call "/etc/passwd"))))
      (is (empty? @seen) "no traversal attempt may reach the reader"))))

(deftest static-mounts-fall-back-to-extension-content-type
  ;; F7 (dogfood): mount-routes emitted Content-Type ONLY when the reader
  ;; supplied one — and a store-backed reader returns none for a blob, so the
  ;; compiled JS bundle served with NO Content-Type at all. Browsers applying
  ;; strict MIME checking refuse to execute such a script. The extension table
  ;; already existed in this namespace for the built-app reader; the mount now
  ;; uses it as a fallback, so EVERY reader gets a correct type.
  (let [row  (first (static/mount-routes {"/assets" "public"}
                                         (fn [_] {:content "x"})))
        call (fn [p] ((:handler row) {:path-params {:path p}}))]
    (testing "a typeless blob still serves with the right type"
      (is (= "text/javascript" (get-in (call "cljs/main.js") [:headers "Content-Type"])))
      (is (= "text/css" (get-in (call "app.css") [:headers "Content-Type"]))))
    (testing "a reader-supplied type still wins"
      (let [row2 (first (static/mount-routes
                         {"/assets" "public"}
                         (fn [_] {:content "x" :content-type "text/plain"})))]
        (is (= "text/plain" (get-in ((:handler row2) {:path-params {:path "a.js"}})
                                    [:headers "Content-Type"])))))
    (testing "an unknown extension omits the header rather than guessing"
      (is (nil? (get-in (call "thing.zzz") [:headers "Content-Type"]))))))

(deftest query-params-are-parsed-onto-the-request
  ;; Both adapters put :query-string on the request and NOTHING parsed it,
  ;; so the first app that wanted `?view=x` had to write its own splitter —
  ;; and so would the second. Found by building slopp's own UI on this
  ;; framework: a place the app has to reach around slopp.web is a gap in
  ;; slopp.web.
  (testing "the shapes a URL actually arrives in"
    (is (= {} (router/query-params nil)))
    (is (= {} (router/query-params "")))
    (is (= {:view "labeled"} (router/query-params "view=labeled")))
    (is (= {:a "1" :b "2"} (router/query-params "a=1&b=2")))
    (is (= {:flag ""} (router/query-params "flag"))
        "a bare key is present with an empty value — present and empty are not absent"))
  (testing "percent- and plus-encoding, since a value is arbitrary text"
    (is (= {:q "a b"} (router/query-params "q=a+b")))
    (is (= {:q "a/b?c"} (router/query-params "q=a%2Fb%3Fc")))
    (is (= {:ns "demo.core"} (router/query-params "ns=demo.core"))))
  (testing "malformed input is data, never a 500"
    (is (map? (router/query-params "%%%=x&=y&&"))))
  (testing "and malformed text ARRIVES, rather than the pair being dropped"
    ;; changed when decoding moved to slopp.lang. URLDecoder throws on a stray
    ;; `%`, and the old code caught that and dropped the pair — so `?q=100%`,
    ;; a real search typed by a real person, reached the handler as no query
    ;; at all. Losing the parameter is a worse answer than handing over the
    ;; characters that were typed.
    ;; MEASURED against the old implementation rather than asserted about it,
    ;; because these landed green and a green nobody watched fail proves
    ;; nothing. `(URLDecoder/decode "100%" "UTF-8")` throws, the old code
    ;; caught it and returned nil, and a nil key or value dropped the pair —
    ;; so the two assertions below returned `{}` before this change and are
    ;; the two that discriminate. The `café` and `=y` cases passed BEFORE as
    ;; well; they are regression guards, not evidence, and calling all four
    ;; evidence would be the coverage theatre the advisory names.
    (is (= {:q "100%"} (router/query-params "q=100%")))
    (is (= {:q "a%zzb"} (router/query-params "q=a%zzb")))
    (is (= {:q "café"} (router/query-params "q=caf%C3%A9"))
        "and the portable decoder still does real UTF-8")
    (is (= {} (router/query-params "=y"))
        "a pair with no KEY is still dropped — there is nothing to be present under")))

(deftest a-context-cannot-promise-reads-it-cannot-perform
  ;; Reads resolve by VOCABULARY store-wide, so an endpoint in one namespace
  ;; can and should reuse a performer declared in another. Good property —
  ;; but it means a context assembled from HALF the namespaces answers 500,
  ;; not 404, at request time, with the detail server-side and a generic
  ;; error in the body. That is the worst of both: the failure with no check
  ;; is also the failure that is hardest to read.
  ;;
  ;; Every input needed is already in hand at assembly. So assemble-time is
  ;; where it is caught.
  (testing "a route declaring a read nobody performs is refused at assembly"
    (let [e (try (slopp.web/context {:web/namespaces ['slopp.web-test]
                              :web/routes [{:method :get :path "/orphan"
                                            :handler identity
                                            :auth :public
                                            :web/reads {:x [:nobody/serves-this []]}}]})
                 nil
                 (catch clojure.lang.ExceptionInfo ex ex))]
      (is (some? e) "assembling this context has to fail, not defer to a 500")
      (is (re-find #"nobody/serves-this" (ex-message e))
          (str "the message has to name the unservable KIND: " (ex-message e)))
      (is (re-find #"/orphan" (ex-message e))
          (str "and the route that declared it: " (ex-message e)))))
  (testing "a context that can perform every read it declares assembles"
    ;; the guard must not fire on the ordinary case, including a route with
    ;; no declared reads at all
    (is (map? (slopp.web/context {:web/namespaces ['slopp.web-test]})))))

(defn reader-contract
  "Every property a `mount-routes` reader must satisfy, run against whatever
  `make-reader` builds — `{path → content}` in, a reader out.

  The suite may name NOTHING but the port. That is not style: an assertion
  reaching into one adapter would stop running against the other, so the
  constraint is what keeps this from silently becoming one implementation's
  test."
  [label make-reader]
  (let [rdr (make-reader {"public/app.css" "body{}"})]
    (testing (str label ": a path the source holds answers with content")
      (is (some? (:content (rdr "public/app.css")))))
    (testing (str label ": a path nothing holds is nil — MISSING, not a throw")
      (is (nil? (rdr "public/nope.css"))))
    (testing (str label ": a traversal segment is nil, however it is refused")
      ;; the filesystem reader refuses it explicitly; the store-backed one
      ;; simply has no such manifest key. Same answer, different reason —
      ;; which is exactly what a contract is allowed to be indifferent to.
      (is (nil? (rdr "public/../public/app.css"))))
    (testing (str label ": a PREFIX of a real path is not a partial hit")
      (is (nil? (rdr "public/app"))))))

(deftest ^:external the-filesystem-reader-meets-the-reader-contract
  ;; The other side of reader-contract. ^:external because this adapter's whole
  ;; job is the filesystem — the store-backed run of the SAME suite is in-image
  ;; and costs nothing, which is the two-tier split doing what it is for.
  (reader-contract "filesystem"
                   (fn [files]
                     (let [dir (str (java.nio.file.Files/createTempDirectory
                                     "slopp-contract"
                                     (make-array java.nio.file.attribute.FileAttribute 0)))]
                       (doseq [[path content] files]
                         (let [f (java.io.File. dir (str path))]
                           (.mkdirs (.getParentFile f))
                           (spit f content)))
                       (static/file-or-resource-reader dir)))))

(deftest bind-diagnosis-is-the-one-recognizer-for-a-taken-port
  ;; Three listeners answered "the port is taken" three different ways, and
  ;; the plan's founding symptom was that they disagreed. Measured before
  ;; this: `http-api.server/serve!` walked the cause chain and said "port N
  ;; is not available"; `api.devserver/bind-failure` regexed a wire string
  ;; and said "port N is already in use"; `slopp.web/serve!` said nothing at
  ;; all and let a BindException reach the operator.
  ;;
  ;; They differ for ONE honest reason — they hold different things. The
  ;; in-process caller has a Throwable; the dev server has a text blob that
  ;; crossed an nREPL wire. So the recognizer takes either, and the sentence
  ;; is written once. What each caller adds is its own NEXT STEP, which is
  ;; the part that legitimately differs: only the dev server knows the
  ;; failure is fixable with `web.port`.
  (testing "a Throwable carrying a BindException anywhere in its cause chain"
    (is (= "port 8080 is already in use"
           (slopp.web/bind-diagnosis 8080 (java.net.BindException. "Address already in use"))))
    (is (= "port 8080 is already in use"
           (slopp.web/bind-diagnosis 8080 (ex-info "wrapped" {} (java.net.BindException. "nope"))))
        "the cause chain is walked — http-kit wraps"))
  (testing "a text blob that crossed a wire, where the class is gone"
    (is (= "port 7357 is already in use"
           (slopp.web/bind-diagnosis
            7357
            (str "class java.net.BindException: Execution error (BindException) at"
                 " sun.nio.ch.Net/bind0 (Net.java:-2).\nAddress already in use")))))
  (testing "nil for anything it does not recognize — the caller keeps every byte"
    (is (nil? (slopp.web/bind-diagnosis 8080 (java.net.UnknownHostException. "nowhere"))))
    (is (nil? (slopp.web/bind-diagnosis 8080 "Syntax error compiling at (app/core.clj:1:1)")))
    (is (nil? (slopp.web/bind-diagnosis 8080 nil)))))

(deftest ^:external serve-on-a-taken-port-leads-with-the-diagnosis
  ;; The production half of the same rule the dev server already follows. An
  ;; operator starting a built app on a held port got
  ;; `class java.net.BindException: Execution error (BindException) at
  ;; sun.nio.ch.Net/bind0 (Net.java:-2).` and then, after a newline, the one
  ;; clause that matters. Three pieces of noise before the answer.
  ;;
  ;; A clash is an ERROR here and stays one — never a hunt for a free port.
  ;; The url an operator was handed must not quietly stop being the url that
  ;; works, which is the same stance api.server/serve! takes.
  (let [held (slopp.web/serve! {:web/namespaces [] :web/port 0})
        port (:port held)]
    (try
      (let [t (try (slopp.web/serve! {:web/namespaces [] :web/port port})
                   nil
                   (catch Throwable t t))]
        (testing "it still fails — a taken port is never routed around"
          (is (some? t) "binding a held port must not succeed"))
        (testing "the diagnosis leads"
          (is (str/starts-with? (str (ex-message t))
                                (str "port " port " is already in use"))))
        (testing "and the raw failure survives behind it, not squeezed out"
          (is (re-find #"(?i)address already in use" (str (ex-message t)))))
        (testing "the port rides as data, so a caller need not re-parse the sentence"
          (is (= port (:web/port (ex-data t))))))
      (finally (slopp.web/stop! held)))))
