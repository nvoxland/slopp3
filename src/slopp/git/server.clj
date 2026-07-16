(ns slopp.git.server
  (:require [clojure.string :as str]
            [slopp.git :as git])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer] [java.net InetSocketAddress] [java.util.zip GZIPInputStream] [org.eclipse.jgit.lib Repository] [org.eclipse.jgit.transport PacketLineOut RefAdvertiser$PacketLineOutRefAdvertiser UploadPack]))

(defn ^:export status! [^HttpExchange ex code]
  (.sendResponseHeaders ex code -1)
  (.close ex))

(defn ^:export q-params [^HttpExchange ex]
  (into {}
        (keep (fn [kv]
                (let [[k v] (str/split kv #"=" 2)]
                  [k (java.net.URLDecoder/decode (str v) "UTF-8")])))
        (some-> (.getQuery (.getRequestURI ex)) (str/split #"&"))))

(defn ^:export request-body ^java.io.InputStream [^HttpExchange ex]
  (cond-> (.getRequestBody ex)
    (= "gzip" (some-> (.getFirst (.getRequestHeaders ex) "Content-Encoding")
                      str/lower-case))
    (GZIPInputStream.)))

(defn ^:export advertise-refs! [ctx ^HttpExchange ex]
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
              (-> (doto (UploadPack. ^Repository (:repo ctx))
                    (.setBiDirectionalPipe false))
                  (.sendAdvertisedRefs adv))))))))

(defn ^:export upload-pack! [ctx ^HttpExchange ex]
  (doto (.getResponseHeaders ex)
    (.add "Content-Type" "application/x-git-upload-pack-result")
    (.add "Cache-Control" "no-cache"))
  (.sendResponseHeaders ex 200 0)
  (with-open [in (request-body ex)
              os (.getResponseBody ex)]
    (doto (UploadPack. ^Repository (:repo ctx))
      (.setBiDirectionalPipe false)
      (.upload in os nil))))

(defn ^:export git-handler ^HttpHandler [srv]
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
  one is taken (so a shared derived port never blocks startup)."
  ^HttpServer [port]
  (try
    (HttpServer/create (InetSocketAddress. "127.0.0.1" (int port)) 0)
    (catch java.net.BindException _
      (when (zero? (int port)) (throw (java.net.BindException. "no port free")))
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
    (.createContext server "/slopp.git" (git-handler srv))
    (.start server)
    srv))

(defn ^:export stop-server! [{:keys [^HttpServer server ctx]}]
  (.stop server 0)
  (git/close-ctx! ctx)
  nil)

(defn ^:export -main [& [port dir]]
  (let [dir  (or dir (System/getProperty "user.dir"))
        port (if port (Long/parseLong port) (derived-port dir))
        srv  (start-server! port {:dir dir})]
    (println (str "slopp git server: " (:url srv) "  (store: " dir ")"))
    @(promise)))
