(ns slopp.web.server.httpkit
  "The production adapter (D-web §9): http-kit — ring-compatible,
  WebSocket-capable, proven under native-image.

  An adapter owns exactly two things — **the socket, and the wire encoding** —
  and it is the only place in the framework allowed to know about either. It
  turns a live request into the ring-shaped map `dispatch/handle!` expects
  (JSON body decoded to DATA, because no handler should ever see an
  InputStream), calls it, and encodes what comes back: JSON, unless the
  response says `:web/raw`, in which case the body is written verbatim with
  the headers it carries.

  Everything ABOVE this line is pure and testable without a port; everything
  below is somebody else's library. That is what makes the adapter a VALUE —
  `serve!` picks one by keyword, so the server library is a config choice
  rather than a rewrite. `slopp.web.server.jdk` is the same contract with no
  dependency, and the two agreeing is the point.

  Body size is capped from the context (`:web/max-body-bytes`, 1 MiB by
  default) and answered 413, because an unbounded slurp is bounded only by
  heap — the same guard, from the same shared reader, in both adapters."
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
