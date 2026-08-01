(ns slopp.api.devserver-test
  "Tests for the framework-managed app server.

  What lives here is the DECIDING — is this a web project, what does it
  serve, on what address — which `serve-plan` answers as pure data from the
  store, so it needs no process. The launching, the done-grain refresh and
  the blue/green swap need a real image and are `^:external`."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.api.devserver :as devserver]))

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
