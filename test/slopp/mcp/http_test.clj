(ns slopp.mcp.http-test
  "The MCP protocol over HTTP, end to end: a real server on a real port,
  answering real JSON-RPC.

  It is the wire counterpart to `slopp.mcp-test`, which exercises the same
  tools in-process. What only this can catch is anything the TRANSPORT does to
  a call — a body that never arrives, a response that does not survive
  serialization, a session that is not the one the caller meant.

  Its two helpers go through `slopp.web.client`, like every other HTTP caller
  in the store. That costs nothing in fidelity: the port's real adapter is what
  makes the call, so these are the same real requests they always were."
  (:require [clojure.test :refer [deftest is testing]]
            [cheshire.core :as json]
            [slopp.mcp.http :as http] [slopp.web-test :as web-test] [slopp.web.client :as client])
)

(defn- post! [port path body]
  (json/parse-string
   (:http/body (client/request {:http/method  :post
                                :http/url     (str "http://127.0.0.1:" port path)
                                :http/headers {"Content-Type" "application/json"}
                                :http/body    (json/generate-string body)}))
   true))

(defn- get! [port path]
  (json/parse-string
   (:http/body (client/request {:http/method :get
                                :http/url    (str "http://127.0.0.1:" port path)}))
   true))

(deftest ^:external http-transport-round-trip
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

(deftest the-store-backed-reader-meets-the-reader-contract
  ;; The RUN lives inside slopp.mcp.* so the contract can reach a
  ;; package-private adapter without it being exported for a test's benefit.
  ;; The contract itself belongs to the port's owner (slopp.web.static), which
  ;; is the only home that does not make it one implementation's test.
  ;;
  ;; In-image and cheap: a store's :files is a plain map, so this adapter needs
  ;; no database, no session and no socket.
  (web-test/reader-contract "store"
                            (fn [files]
                              (http/store-reader (constantly {:files files})
                                                 (constantly nil)))))
