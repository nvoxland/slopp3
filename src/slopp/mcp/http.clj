(ns slopp.mcp.http
  "Tiny localhost JSON-over-HTTP transport over the SAME dispatch as MCP —
  for CLI/scripting access (curl) and harness-driven evals. Binds 127.0.0.1
  only. Since D-web wave 2 the endpoints are DECLARED (:web/* metadata on
  the fns below) and served through the slopp.web facade — the transport is
  the first consumer of the same machinery every slopp web app gets.

    POST /call    {\"name\": tool, \"arguments\": {...}}  -> {\"result\": text}
    POST /mcp     JSON-RPC (native MCP; notifications -> 202)
    GET  /metrics -> {\"calls\": [{:tool :t :in :out} ...]}  (payload sizes)

  Entry: clojure -M -m slopp.mcp.http <port> [project-dir]"
  (:require [slopp.api :as api]
            [slopp.mcp :as mcp]
            [slopp.api.external :as external]
            [slopp.web :as web] [slopp.store :as store] [slopp.store.db :as db] [slopp.web.static :as static] [slopp.review.server :as ui-server]))

(defn ^:export start-server!
  "Start the transport on `port` over a fresh session (`opts` as api/open!):
  the declared endpoints of THIS namespace (/call, /mcp, /metrics) plus any
  `http.static.*` capability mounts, served through the web facade — the
  transport is the first consumer of the same machinery every slopp web app
  gets. Static assets read from the STORE (text or content-addressed
  bytes), so under --live an edited asset serves without a rebuild.
  Returns {:server :session :calls} for stop-server!."
  [port opts]
  (let [session (external/open! opts)
        calls   (atom [])
        mounts  (into {}
                      (keep (fn [[k v]]
                              (when-let [[_ mount] (re-matches #"http\.static\.(.+)"
                                                               (str k))]
                                [mount (str v)])))
                      (get-in (:store @session)
                              [:config "capabilities" :values]))
        reader  (fn [path]
                  (let [st (:store @session)
                        {:keys [content content-type] :as e}
                        (store/file-content st path)]
                    (when e
                      {:content (or content
                                    (some-> (:db @session)
                                            (db/get-blob (:sha e))))
                       :content-type content-type})))
        srv     (web/serve! {:web/namespaces (into ['slopp.mcp.http] ui-server/served-namespaces)
                             :web/routes (static/mount-routes mounts reader)
                             :web/host "127.0.0.1"
                             :web/port port
                             :web/perform-ctx {:session session :calls calls}})]
    {:server srv :session session :calls calls}))

(defn ^:export stop-server!
  "Stop the HTTP transport and close the session it serves, returning nil.
  Takes the map `start-server!` returned — an opaque handle, so its keys are
  read here rather than destructured in the arglist (they are not a contract
  the caller builds). Closing the session is the part that matters: it reaps
  the owned image subprocess."
  [srv]
  (web/stop! (:server srv))
  (api/close! (:session srv))
  nil)

(defn -main "CLI: serve the HTTP transport on `port` (default 7357) over a session at
  `dir`, and block. Enables `:require-turns?` — a real server refuses unrooted
  writes, where an in-process test session does not.
  `clojure -M -m slopp.mcp.http [port] [dir]`"
  [& [port dir]]
  (let [{:keys [session]} (start-server! (Long/parseLong (or port "7357"))
                                         (cond-> {:slopp.api/warm-spare? true}
                                           dir (assoc :slopp.api/dir dir)))]
    (swap! session assoc :require-turns? true))  ; real servers enforce turns
  (println (str "slopp http transport on 127.0.0.1:" (or port "7357")))
  @(promise))

(defn ^{:web/method :post :web/path "/call" :web/auth :public
        :web/effectful true
        :web/client false
        :web/request [:map [:name :string] [:arguments [:maybe :map]]]
        :web/response [:map [:result :string]]}
  call-endpoint!
  "POST /call {name arguments} → {:result <tool text>}: one tool dispatch,
  the curl/scripting transport. :web/effectful — it dispatches into
  `mcp/handle!`, which edits the store; the session and the metrics atom
  arrive as `:web/deps` (a value, never ambient state)."
  [req]
  (let [{:keys [session calls]} (:web/deps req)
        body (:body req)
        resp (mcp/handle! session {:id 1 :method "tools/call"
                                   :params {:name (:name body)
                                            :arguments (:arguments body)}})
        text (get-in resp [:result :content 0 :text] "")]
    (swap! calls conj {:tool (:name body)
                       :t (System/currentTimeMillis)
                       :in (count (pr-str body)) :out (count text)})
    {:status 200 :body {:result text}}))

(defn ^{:web/method :post :web/path "/mcp" :web/auth :public
        :web/effectful true
        :web/client false
        ;; :map, deliberately, and not a tighter shape: JSON-RPC is an OPEN
        ;; envelope by specification — method, params and result vary per
        ;; call and grow with the protocol. A narrower schema here would be
        ;; a contract that refuses valid traffic, which is worse than a wide
        ;; one that tells the truth.
        :web/request [:map [:method {:optional true} :string]]
        :web/response :map}
  mcp-endpoint!
  "POST /mcp: native MCP JSON-RPC over streamable HTTP — N clients share
  this ONE session/store/image (Phase 4 m1). A notification (nil dispatch
  result) answers 202 with no body, per the streamable-HTTP spec."
  [req]
  (let [{:keys [session calls]} (:web/deps req)
        rpc (:body req)
        resp (mcp/handle! session rpc)]
    (when (= "tools/call" (:method rpc))
      (swap! calls conj {:tool (get-in rpc [:params :name])
                         :t (System/currentTimeMillis)
                         :in (count (pr-str rpc))
                         :out (count (str (get-in resp [:result :content 0 :text])))}))
    (if (nil? resp)
      {:status 202}
      {:status 200 :body resp})))

(defn ^{:web/method :get :web/path "/metrics" :web/auth :public
        :web/client false
        :web/response [:map [:calls [:sequential
                                     [:map [:tool [:maybe :string]]
                                      [:t :int] [:in :int] [:out :int]]]]]} metrics-endpoint
  "GET /metrics → {:calls [{:tool :t :in :out} …]}: per-call payload sizes.
  A deref is a READ — no bang, and exactly what web-unsafe-get allows a
  GET to do."
  [req]
  {:status 200 :body {:calls (deref (:calls (:web/deps req)))}})
