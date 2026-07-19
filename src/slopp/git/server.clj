(ns slopp.git.server
  (:require [clojure.string :as str]
            [slopp.git :as git])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer] [java.net InetSocketAddress] [java.util.zip GZIPInputStream] [org.eclipse.jgit.lib Repository] [org.eclipse.jgit.transport PacketLineOut RefAdvertiser$PacketLineOutRefAdvertiser UploadPack]))

(defn ^:export status! "Answer `ex` with bare status `code` and no body, and close it."
  [^HttpExchange ex code]
  (.sendResponseHeaders ex code -1)
  (.close ex))

(defn ^:export q-params "The request's query string as a `{\"name\" \"value\"}` map, URL-decoded."
  [^HttpExchange ex]
  (into {}
        (keep (fn [kv]
                (let [[k v] (str/split kv #"=" 2)]
                  [k (java.net.URLDecoder/decode (str v) "UTF-8")])))
        (some-> (.getQuery (.getRequestURI ex)) (str/split #"&"))))

(defn ^:export request-body
  "The request body as a stream, transparently gunzipped when the client sent
  `Content-Encoding: gzip` — git clients routinely do."
  ^java.io.InputStream [^HttpExchange ex]
  (cond-> (.getRequestBody ex)
    (= "gzip" (some-> (.getFirst (.getRequestHeaders ex) "Content-Encoding")
                      str/lower-case))
    (GZIPInputStream.)))

(defn ^:export advertise-refs! "Serve git's ref-advertisement handshake (`GET /info/refs?service=…`) — the
  first request of any clone or fetch.

  Only `git-upload-pack` is answered; `git-receive-pack` gets a 403, because
  this remote is READ-ONLY by design. Edits arrive through slopp's write
  tools, never `git push` to this listener. Projects the journal's milestones
  into the repo first, so a clone always sees current history."
  [ctx ^HttpExchange ex]
  (let [service (get (q-params ex) "service")]
    (if-not (= "git-upload-pack" service)
      (status! ex 403)          ; read-only remote — no receive-pack, no dumb protocol
      (do (git/ensure-projected! ctx)
          (doto (.getResponseHeaders ex)
            (.add "Content-Type" (str "application/x-" service "-advertisement"))
            (.add "Cache-Control" "no-cache"))
          (.sendResponseHeaders ex 200 0)
          (with-open [os (.getResponseBody ex)]
            (let [pck (PacketLineOut. os)
                  adv (RefAdvertiser$PacketLineOutRefAdvertiser. pck)]
              (.writeString pck (str "# service=" service "\n"))
              (.end pck)
              (-> (doto (UploadPack. ^Repository (:slopp.git/repo ctx))
                    (.setBiDirectionalPipe false))
                  (.sendAdvertisedRefs adv))))))))

(defn ^:export upload-pack! "Serve the pack itself (`POST …/git-upload-pack`) — the second half of a
  clone or fetch, streaming objects straight from the in-memory repo to the
  client. Unidirectional: the pipe is one-way over HTTP, not a bidirectional
  git transport."
  [ctx ^HttpExchange ex]
  (doto (.getResponseHeaders ex)
    (.add "Content-Type" "application/x-git-upload-pack-result")
    (.add "Cache-Control" "no-cache"))
  (.sendResponseHeaders ex 200 0)
  (with-open [in (request-body ex)
              os (.getResponseBody ex)]
    (doto (UploadPack. ^Repository (:slopp.git/repo ctx))
      (.setBiDirectionalPipe false)
      (.upload in os nil))))

(defn ^:export git-handler!
  "The smart-HTTP router: `GET …/info/refs` advertises, `POST
  …/git-upload-pack` serves the pack, everything else 404s — the two requests
  a clone or fetch makes, and nothing more.

  Any throwable becomes a 500 rather than a dropped connection, and the
  exchange is closed in a `finally` so a failed request can never leak one."
  ^HttpHandler [srv]
  (reify HttpHandler
    (handle [_ ex]
      (try
        (let [path   (.getPath (.getRequestURI ex))
              method (.getRequestMethod ex)]
          (cond
            (and (= "GET" method) (str/ends-with? path "/info/refs"))
            (advertise-refs! (:ctx srv) ex)

            (and (= "POST" method) (str/ends-with? path "/git-upload-pack"))
            (upload-pack! (:ctx srv) ex)

            :else (status! ex 404)))
        (catch Throwable _
          (try (status! ex 500) (catch Throwable _)))
        (finally (.close ex))))))

(defn ^:export derived-port
  "A localhost port DERIVED from the store dir — stable across restarts, so
  a `git remote` saved against it keeps working next session. In the
  private range [49152, 65535]; a collision (a second server on the same
  dir) falls back to an ephemeral port at bind time (see `start-server!`),
  so this is a preference, not a guarantee."
  [dir]
  (+ 49152 (mod (hash (str dir)) 16384)))

(defn ^:export bind-localhost!
  "HttpServer on 127.0.0.1:port, falling back to an ephemeral port if that
  one is taken (so a shared derived port never blocks startup).

  Only an ephemeral request that ITSELF cannot bind is fatal — there is no
  further fallback — and it throws `ex-info` carrying the port, so a caller
  reading `ex-data` learns what was attempted rather than parsing a message."
  ^HttpServer [port]
  (try
    (HttpServer/create (InetSocketAddress. "127.0.0.1" (int port)) 0)
    (catch java.net.BindException e
      (when (zero? (int port))
        (throw (ex-info "no port free on 127.0.0.1"
                        {:port (int port) :host "127.0.0.1"} e)))
      (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0))))

(defn ^:export start-server!
  "Serve the git smart-HTTP protocol for the store at `:dir` on 127.0.0.1 —
  READ-ONLY (clone/fetch of milestones). Clone with
  `git clone http://127.0.0.1:<port>/slopp.git`. The requested `port` is a
  PREFERENCE — if taken, an ephemeral port is bound; the actual bound port is
  returned as `:port`. Returns {:server :ctx :port :url} for stop-server!."
  [port {:keys [dir]}]
  (when (str/blank? (str dir))
    (throw (ex-info "the git server needs a durable store :dir" {})))
  (let [ctx    (git/open-ctx! dir)
        server (bind-localhost! port)
        actual (.getPort (.getAddress server))
        srv    {:ctx    ctx
                :server server
                :port   actual
                :url    (str "http://127.0.0.1:" actual "/slopp.git")}]
    (.createContext server "/slopp.git" (git-handler! srv))
    (.start server)
    srv))

(defn ^:export stop-server!
  "Stop the git listener and close its context, returning nil. Takes the map
  `start-server!` returned — an opaque handle, so its keys are read here
  rather than destructured in the arglist (they are not a contract the caller
  builds)."
  [srv]
  (.stop ^HttpServer (:server srv) 0)
  (git/close-ctx! (:ctx srv))
  nil)

(defn ^:export -main "CLI: serve the read-only git listener for the store at `dir` (default cwd)
  and block. Port defaults to one derived from `dir`, so a given store keeps a
  stable URL across restarts.
  `clojure -M -m slopp.git.server [port] [dir]`"
  [& [port dir]]
  (let [dir  (or dir (System/getProperty "user.dir"))
        port (if port (Long/parseLong port) (derived-port dir))
        srv  (start-server! port {:dir dir})]
    (println (str "slopp git server: " (:url srv) "  (store: " dir ")"))
    @(promise)))
