(ns slopp.web.server.httpkit
  (:require [slopp.web.dispatch :as dispatch]
            [org.httpkit.server :as hk]
            [cheshire.core :as json]
            [clojure.string :as str]))

(defn- request-map
  "The request envelope off http-kit's ring map: keep the ring keys, decode
  a JSON body to DATA (a handler never sees an InputStream). Reads at most
  `max-bytes` — over that, returns `::too-large` so the adapter answers 413
  instead of buffering an unbounded body (review W8)."
  [ring-req max-bytes]
  (let [{:keys [body too-large]} (dispatch/bounded-body-string (:body ring-req) max-bytes)]
    (if too-large
      ::too-large
      (assoc (select-keys ring-req [:request-method :uri :query-string :headers :remote-addr])
             :body (when-not (str/blank? (str body))
                     (try (json/parse-string body true)
                          (catch Exception _ body)))))))

(defn ^{:export "slopp.web"} start!
  "Serve `ctx` (the dispatch context) via http-kit on {:host :port} — the
  production default adapter (ring-compatible, WebSocket-capable,
  native-image proven). Port 0 binds ephemeral; the returned
  {:server :port} carries the real one. `stop!` takes the return. Requests
  whose body exceeds `ctx`'s :web/max-body-bytes get a 413 (review W8)."
  [ctx {:keys [host port] :or {host "127.0.0.1" port 8080}}]
  (let [max-body (:web/max-body-bytes ctx 1048576)
        server (hk/run-server
                (fn [ring-req]
                  (let [req (try (request-map ring-req max-body)
                                 (catch Exception _ ::too-large))]
                    (if (= ::too-large req)
                      {:status 413
                       :headers {"Content-Type" "application/json"}
                       :body (json/generate-string {:error "request body too large"})}
                      (let [resp (try (dispatch/handle! ctx req)
                                      (catch Exception _
                                        {:status 500 :body {:error "internal server error"}}))]
                        (if (:web/raw resp)
                          {:status (or (:status resp) 200)
                           :headers (:headers resp {})
                           :body (:body resp)}
                          {:status (or (:status resp) 200)
                           :headers {"Content-Type" "application/json"}
                           :body (json/generate-string (:body resp))})))))
                {:ip (str host) :port (int port) :legacy-return-value? false})]
    {:server server :port (hk/server-port server)}))

(defn ^{:export "slopp.web"} stop!
  "Stop a `start!` return. The handle is OPAQUE (a live http-kit server) —
  read in the body, not destructured."
  [srv]
  (hk/server-stop! (:server srv))
  nil)
