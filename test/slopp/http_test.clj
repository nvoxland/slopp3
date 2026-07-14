(ns slopp.http-test
  (:require [clojure.test :refer [deftest is testing]]
            [cheshire.core :as json]
            [slopp.http :as http])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]))

(defn- post! [port path body]
  (let [client (HttpClient/newHttpClient)
        req (-> (HttpRequest/newBuilder (URI. (str "http://127.0.0.1:" port path)))
                (.POST (HttpRequest$BodyPublishers/ofString (json/generate-string body)))
                (.build))]
    (json/parse-string (.body (.send client req (HttpResponse$BodyHandlers/ofString))) true)))

(defn- get! [port path]
  (let [client (HttpClient/newHttpClient)
        req (.build (HttpRequest/newBuilder (URI. (str "http://127.0.0.1:" port path))))]
    (json/parse-string (.body (.send client req (HttpResponse$BodyHandlers/ofString))) true)))

(deftest ^:isolated http-transport-round-trip
  (let [port 7399
        srv  (http/start-server! port {})]
    (try
      (testing "tool calls over HTTP hit the same dispatch as MCP"
        (is (re-find #":forms 2"
                     (:result (post! port "/call"
                                     {:name "ns_create"
                                      :arguments {:ns "h.core"
                                                  :source "(ns h.core)\n(defn f [x] (* 2 x))\n"}}))))
        (is (re-find #"defn f" (:result (post! port "/call"
                                               {:name "query_source", :arguments {:ns "h.core" :full true}}))))
        (is (re-find #"\b10\b" (:result (post! port "/call"
                                               {:name "query_eval"
                                                :arguments {:code "(h.core/f 5)"}})))))
      (testing "unknown tools surface an error, not a 500 stack"
        (is (re-find #"unknown tool" (:result (post! port "/call"
                                                     {:name "bogus" :arguments {}})))))
      (testing "/metrics records every call with payload sizes"
        (let [m (get! port "/metrics")]
          (is (= 4 (count (:calls m))))
          (is (every? #(and (:tool %) (pos? (:in %)) (pos? (:out %))) (:calls m)))))
      (finally (http/stop-server! srv)))))
