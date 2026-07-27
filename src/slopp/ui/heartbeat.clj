(ns slopp.ui.heartbeat
  "The project half of the UI hub: the loop that tells a hub this project
  exists and is still answering.

  Registering and keeping alive are the SAME call (D-ui-hub), which is what
  removes every piece of state machinery you would otherwise need here — no
  connect, no reconnect, no backoff, no \"have I registered yet\". A hub that
  is down, starts later, or restarts underneath us is one case, and the next
  beat handles it.

  `slopp.ui.hub` is the other end of this wire; `slopp.ui.registry` is the
  shape they agree on."
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [slopp.api.capabilities :as caps]
            [slopp.ui.registry :as reg]))

(defn ^:export hub-url
  "The hub's base url from its `port` — loopback, because a hub registry is a
  list of processes on THIS machine and nothing about it should be reachable
  from another one."
  [port]
  (str "http://127.0.0.1:" port "/"))

(defn ^:export payload
  "The check-in a project sends: who it is, where it answers, and what it is
  doing — [[slopp.ui.contracts/project-beat]].

  The name is `app.name` when the store sets one and the directory's own name
  otherwise, because a project that configured nothing still has to appear in
  the picker as something a human recognises. A blank row would be the
  feature failing at its first use.

  `:status` is a plain string the hub renders without interpreting, and it is
  the seam this design left open: reporting `working` instead of `idle` is a
  change to THIS function, and every hub already displays it."
  [store dir url]
  {:name    (or (caps/effective store "app.name")
                (last (remove str/blank? (str/split (str dir) #"/")))
                "project")
   :dir     (str dir)
   :url     (str url)
   :pid     (.pid (java.lang.ProcessHandle/current))
   :version (caps/effective store "app.version")
   :status  "idle"})

(defn- post!
  "POST `body` as JSON to `url`; true when the hub answered, false when it did
  not. A hub that is not running is the ORDINARY case, not an error — nobody
  has to start one — so a refused connection is a quiet false."
  [url body]
  (try
    (let [client (java.net.http.HttpClient/newHttpClient)
          req    (-> (java.net.http.HttpRequest/newBuilder (java.net.URI. url))
                     (.header "Content-Type" "application/json")
                     (.timeout (java.time.Duration/ofSeconds 2))
                     (.POST (java.net.http.HttpRequest$BodyPublishers/ofString
                             (json/generate-string body)))
                     (.build))]
      (< (.statusCode (.send client req
                             (java.net.http.HttpResponse$BodyHandlers/ofString)))
         400))
    (catch Exception _ false)))

(defn ^:export start!
  "Begin beating `(payload-fn)` to the hub at `hub-url` every `beat-ms`, and
  return a handle for [[stop!]].

  The FIRST beat goes out immediately: a project that only appeared one
  interval after its server started would read as broken for ten seconds
  every single time.

  Registration and keepalive are the same call (D-ui-hub), so this loop needs
  no state machine and no reconnect logic. A hub that is down, starts later,
  or restarts underneath us is all one case — the next beat registers us
  again, and the only cost is up to one interval of absence.

  `payload-fn` is called per beat rather than once, so a project that renames
  itself, moves, or later reports what it is doing needs no restart.

  A DAEMON thread, like every other background loop here: the JVM must never
  be held open by a heartbeat."
  [hub-url payload-fn]
  (let [running (atom true)
        thread  (doto (Thread. ^Runnable
                       (fn []
                         (while @running
                           (try (post! (str hub-url "api/register") (payload-fn))
                                (catch Throwable _ nil))
                           (try (Thread/sleep reg/beat-ms)
                                (catch InterruptedException _ nil))))
                       "slopp-ui-heartbeat")
                  (.setDaemon true)
                  (.start))]
    {:thread thread :running running :hub-url hub-url :payload-fn payload-fn}))

(defn ^:export stop!
  "Stop the beat and tell the hub we are going, returning nil.

  The deregistration is a courtesy, not a guarantee: a killed or crashed
  process never reaches this, which is exactly why the hub ages entries out
  on its own. What it buys is that an ORDERLY shutdown removes the row now
  instead of leaving a project looking alive for half a minute."
  [hb]
  (when hb
    (reset! (:running hb) false)
    (.interrupt ^Thread (:thread hb))
    (try
      (post! (str (:hub-url hb) "api/deregister") {:dir (:dir ((:payload-fn hb)))})
      (catch Throwable _ nil)))
  nil)
