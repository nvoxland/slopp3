(ns slopp.http
  "Tiny localhost JSON-over-HTTP transport over the SAME dispatch as MCP —
  for CLI/scripting access (curl) and harness-driven evals. Binds 127.0.0.1
  only; JDK HttpServer, no added deps.

    POST /call    {\"name\": tool, \"arguments\": {...}}  -> {\"result\": text}
    GET  /metrics -> {\"calls\": [{:tool :t :in :out} ...]}  (payload sizes)

  Entry: clojure -M -m slopp.http <port> [project-dir]"
  (:require [cheshire.core :as json]
            [slopp.api :as api]
            [slopp.mcp :as mcp])
  (:import [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
           [java.net InetSocketAddress]))

(defn- respond! [^HttpExchange ex status ^String body]
  (let [bytes (.getBytes body "UTF-8")]
    (.add (.getResponseHeaders ex) "Content-Type" "application/json")
    (.sendResponseHeaders ex status (alength bytes))
    (doto (.getResponseBody ex) (.write bytes) (.close))))

(defn- handler! ^HttpHandler [f]
  (reify HttpHandler
    (handle [_ ex]
      (try
        (f ex)
        (catch Exception e
          (respond! ex 500 (json/generate-string {:error (ex-message e)})))))))

(defn start-server!
  "Start the transport on `port` over a fresh session (`opts` as api/open!).
  Returns {:server :session :calls} for stop-server!."
  [port opts]
  (let [session (api/open! opts)
        calls   (atom [])
        server  (HttpServer/create (InetSocketAddress. "127.0.0.1" (int port)) 0)]
    (.createContext server "/call"
                    (handler!
                     (fn [^HttpExchange ex]
                       (let [raw  (slurp (.getRequestBody ex))
                             req  (json/parse-string raw true)
                             resp (mcp/handle! session
                                              {:id 1 :method "tools/call"
                                               :params {:name (:name req)
                                                        :arguments (:arguments req)}})
                             text (get-in resp [:result :content 0 :text] "")]
                         (swap! calls conj {:tool (:name req)
                                            :t (System/currentTimeMillis)
                                            :in (count raw) :out (count text)})
                         (respond! ex 200 (json/generate-string {:result text}))))))
    ;; Phase 4 m1: native MCP over streamable HTTP — N MCP clients (Claude
    ;; Code, Codex) share this ONE session/store/image. Single JSON response
    ;; per POST (legal per the streamable-HTTP spec); notifications → 202.
    (.createContext server "/mcp"
                    (handler!
                     (fn [^HttpExchange ex]
                       (if (not= "POST" (.getRequestMethod ex))
                         (respond! ex 405 (json/generate-string
                                           {:error "POST JSON-RPC only"}))
                         (let [raw  (slurp (.getRequestBody ex))
                               req  (json/parse-string raw true)
                               resp (mcp/handle! session req)]
                           (when (= "tools/call" (:method req))
                             (swap! calls conj
                                    {:tool (get-in req [:params :name])
                                     :t (System/currentTimeMillis)
                                     :in (count raw)
                                     :out (count (str (get-in resp [:result :content 0 :text])))}))
                           (if (nil? resp)               ; notification
                             (do (.sendResponseHeaders ex 202 -1)
                                 (.close (.getResponseBody ex)))
                             (respond! ex 200 (json/generate-string resp))))))))
    (.createContext server "/metrics"
                    (handler!
                     (fn [^HttpExchange ex]
                       (respond! ex 200 (json/generate-string {:calls @calls})))))
    (.start server)
    {:server server :session session :calls calls}))

(defn stop-server!
  "Stop the HTTP transport and close the session it serves, returning nil.
  Takes the map `start-server!` returned — an opaque handle, so its keys are
  read here rather than destructured in the arglist (they are not a contract
  the caller builds). Closing the session is the part that matters: it reaps
  the owned image subprocess."
  [srv]
  (.stop ^HttpServer (:server srv) 0)
  (api/close! (:session srv))
  nil)

(defn -main "CLI: serve the HTTP transport on `port` (default 7357) over a session at
  `dir`, and block. Enables `:require-turns?` — a real server refuses unrooted
  writes, where an in-process test session does not.
  `clojure -M -m slopp.http [port] [dir]`"
  [& [port dir]]
  (let [{:keys [session]} (start-server! (Long/parseLong (or port "7357"))
                                         (cond-> {:warm-spare? true}
                                           dir (assoc :dir dir)))]
    (swap! session assoc :require-turns? true))  ; real servers enforce turns
  (println (str "slopp http transport on 127.0.0.1:" (or port "7357")))
  @(promise))
