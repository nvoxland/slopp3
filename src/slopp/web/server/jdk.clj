(ns slopp.web.server.jdk
  (:require [slopp.web.dispatch :as dispatch]
            [cheshire.core :as json] [clojure.string :as str])
  (:import [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
           [java.net InetSocketAddress]))

(defn- respond!
  [^HttpExchange ex status ^String body]
  (let [bytes (.getBytes body "UTF-8")]
    (.add (.getResponseHeaders ex) "Content-Type" "application/json")
    (.sendResponseHeaders ex status (alength bytes))
    (doto (.getResponseBody ex) (.write bytes) (.close))))

(defn- request-map
  "The ring-shaped request off an exchange: :request-method (lowercase
  keyword), :uri, :query-string, :headers ({lower-name first-value}), and
  :body — JSON parsed to DATA when present (decode is the framework's job;
  a handler never sees an InputStream)."
  [^HttpExchange ex]
  (let [raw (slurp (.getRequestBody ex))]
    {:request-method (keyword (.toLowerCase (.getRequestMethod ex)))
     :uri (.getPath (.getRequestURI ex))
     :query-string (.getQuery (.getRequestURI ex))
     :headers (into {}
                    (map (fn [[k vs]] [(.toLowerCase (str k)) (first vs)]))
                    (.getRequestHeaders ex))
     :body (when-not (str/blank? raw)
             (try (json/parse-string raw true)
                  (catch Exception _ raw)))}))

(defn ^{:export "slopp.web"} start!
  "Serve `ctx` (the dispatch context — routes + performers) on
  {:host :port}: ONE catch-all handler doing request-map →
  `dispatch/handle!` → JSON. Port 0 binds ephemeral; the returned
  {:server :port} carries the real one. `stop!` takes the return."
  [ctx {:keys [host port] :or {host "127.0.0.1" port 8080}}]
  (let [server (HttpServer/create (InetSocketAddress. (str host) (int port)) 0)]
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ ex]
                        (try
                          (let [resp (dispatch/handle! ctx (request-map ex))]
                            (respond! ex (int (or (:status resp) 200))
                                      (json/generate-string (:body resp))))
                          (catch Exception e
                            (respond! ex 500 (json/generate-string
                                              {:error (ex-message e)})))))))
    (.start server)
    {:server server :port (.getPort (.getAddress server))}))

(defn ^{:export "slopp.web"} stop!
  "Stop a `start!` return immediately. The handle is OPAQUE (a live
  HttpServer) — read in the body, not destructured: it is not a
  schema-shaped boundary map."
  [srv]
  (.stop ^HttpServer (:server srv) 0))
