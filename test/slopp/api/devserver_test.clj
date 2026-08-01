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
