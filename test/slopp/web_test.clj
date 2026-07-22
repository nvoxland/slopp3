(ns slopp.web-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.web :as web] [slopp.web.static :as static]))

(defn ^{:web/method :get :web/path "/w/mine/:owner" :web/auth :authenticated}
  t-mine
  "Row-level check inside the handler: only the owner may read."
  [req]
  (slopp.web/enforce (= (:owner (:path-params req))
                        (:web/sub (:web/identity req))))
  {:status 200 :body {:yours true}})

(deftest facade-assembles-and-enforces
  (let [ctx (web/context {:web/namespaces ['slopp.web-test]})]
    (testing "context derives the route table from var metadata"
      (is (= 1 (count (:web/routes ctx))))
      (is (= "/w/mine/:owner" (:path (first (:web/routes ctx))))))
    (testing "handle! is the portless test surface"
      (let [r (web/handle! ctx {:request-method :get :uri "/w/mine/ada"
                                :web/identity {:web/sub "ada" :web/groups #{}}})]
        (is (= 200 (:status r)) (pr-str r))))
    (testing "enforce inside the handler maps to 403 response data"
      (let [r (web/handle! ctx {:request-method :get :uri "/w/mine/ada"
                                :web/identity {:web/sub "eve" :web/groups #{}}})]
        (is (= 403 (:status r)) (pr-str r))))
    (testing "authorized? answers booleans for branching"
      (is (web/authorized? [:group "admin"] {:web/groups #{"admin"}}))
      (is (not (web/authorized? [:group "admin"] nil))))))

(deftest ^:external serve-round-trips-the-facade
  (let [srv (web/serve! {:web/namespaces ['slopp.web-test]
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
      (finally (web/stop! srv)))))

(deftest ^:external httpkit-adapter-round-trips-the-facade
  (let [srv (web/serve! {:web/namespaces ['slopp.web-test]
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
      (finally (web/stop! srv)))))

(deftest ^:external auth-round-trips-over-the-wire
  (let [srv (web/serve! {:web/namespaces ['slopp.web-test]
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
      (finally (web/stop! srv)))))

(deftest ^:external static-mounts-serve-raw-bytes
  (let [png (byte-array [(byte -119) 80 78 71 9 8 7])
        reader (fn [path]
                 (get {"public/logo.png" {:content png :content-type "image/png"}
                       "public/app.css"  {:content "body{}" :content-type "text/css"}}
                      path))
        rows (static/mount-routes {"/assets" "public"} reader)
        srv  (web/serve! {:web/namespaces []
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
      (finally (web/stop! srv)))))

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
