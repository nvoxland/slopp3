(ns slopp.web.server.httpkit
  (:require [slopp.web.dispatch :as dispatch]
            [org.httpkit.server :as hk]
            [cheshire.core :as json]
            [clojure.string :as str]))

(defn- request-map
  "The request envelope off http-kit's ring map: keep the ring keys, decode
  a JSON body to DATA (a handler never sees an InputStream)."
  [ring-req]
  (let [raw (some-> (:body ring-req) slurp)]
    (assoc (select-keys ring-req [:request-method :uri :query-string :headers :remote-addr])
           :body (when-not (str/blank? (str raw))
                   (try (json/parse-string raw true)
                        (catch Exception _ raw))))))

(defn ^{:export "slopp.web"} start!
  "Serve `ctx` (the dispatch context) via http-kit on {:host :port} — the
  production default adapter (ring-compatible, WebSocket-capable,
  native-image proven). Port 0 binds ephemeral; the returned
  {:server :port} carries the real one. `stop!` takes the return."
  [ctx {:keys [host port] :or {host "127.0.0.1" port 8080}}]
  (let [server (hk/run-server
                (fn [ring-req]
                  (let [resp (try (dispatch/handle! ctx (request-map ring-req))
                                  (catch Exception e
                                    {:status 500 :body {:error (ex-message e)}}))]
                    {:status (or (:status resp) 200)
                     :headers {"Content-Type" "application/json"}
                     :body (json/generate-string (:body resp))}))
                {:ip (str host) :port (int port) :legacy-return-value? false})]
    {:server server :port (hk/server-port server)}))

(defn ^{:export "slopp.web"} stop!
  "Stop a `start!` return. The handle is OPAQUE (a live http-kit server) —
  read in the body, not destructured."
  [srv]
  (hk/server-stop! (:server srv))
  nil)
