(ns slopp.review.heartbeat
  "The project half of the UI hub: the loop that tells a hub this project
  exists and is still answering.

  Registering and keeping alive are the SAME call (D-ui-hub), which is what
  removes every piece of state machinery you would otherwise need here — no
  connect, no reconnect, no backoff, no \"have I registered yet\". A hub that
  is down, starts later, or restarts underneath us is one case, and the next
  beat handles it.

  `slopp.review.hub` is the other end of this wire; `slopp.review.registry` is the
  shape they agree on."
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [slopp.api.capabilities :as caps]))

(def default-beat-ms
  "How often to check in when the hub has not said otherwise.

  A FALLBACK, not the interval. The hub owns the number — it is the thing
  deciding how long a silent project may go on looking alive — and it sends
  it back with every registration. This is what to do before the first
  answer arrives, and when talking to a hub too old to say."
  10000)

(defn ^:export hub-url
  "The hub's base url from its `port` — loopback, because a hub registry is a
  list of processes on THIS machine and nothing about it should be reachable
  from another one."
  [port]
  (str "http://127.0.0.1:" port "/"))

(defn ^:export payload
  "The check-in a project sends: who it is, where it answers, and what it is
  doing — [[slopp.review.contracts/project-beat]].

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

(defn ^:export refused?
  "Did the hub REFUSE this beat, as opposed to not being there?

  Three outcomes cross this seam and only two used to be distinguishable. A hub
  that is not running is the ORDINARY case — nobody has to start one — and
  answers nil. A hub that answers normally hands back `{:slug … :beat-ms …}`. A
  hub that rejects what we SENT is a bug we own, and it used to answer nil too,
  which made it present as a project that mysteriously never appears in the
  picker.

  It became reachable when the hub started validating the beat: the beat is the
  one contract crossing the split by COPY rather than generation, so a 400 from
  the hub IS the drift signal, and swallowing it leaves no signal at all."
  [answer]
  (boolean (:hub/refused answer)))

(defn interval-from
  "The beat interval `response` asks for, or [[default-beat-ms]].

  The hub answers every registration with `{:slug … :beat-ms …}`, so the two
  processes need no compiled-in agreement about timing — which is just as
  well, since they are separate projects on separate release cycles and a
  shared constant would be a promise neither could check.

  Anything that is not a POSITIVE INTEGER falls back. A hub one version older
  sends no `:beat-ms` at all; a broken one could send a string, or a zero.
  Trusting that would turn the keepalive into a busy loop against a hub
  already misbehaving — the failure mode where the response says to hammer
  the thing that sent it."
  [response]
  (let [ms (:beat-ms response)]
    (if (and (int? ms) (pos? ms)) ms default-beat-ms)))

(defn- post!
  "POST `body` as JSON to `url`; the hub's parsed answer, a refusal, or nil when
  it did not answer.

  It used to return a boolean, which threw away the only thing the hub says
  back. Registration and keepalive are the same call, so this response is the
  ONE place the hub gets to tell a project anything — today that is the
  assigned slug and the beat interval.

  A hub that is not running is the ORDINARY case, not an error — nobody has to
  start one — so a refused connection is a quiet nil.

  **A 4xx is NOT that, and used to be treated as if it were.** Lumping them
  together made \"the hub rejected what I sent\" indistinguishable from \"there
  is no hub\", and only one of those is somebody's bug. It matters because the
  hub validates the beat against its own copy of the contract, and that copy is
  a hand-maintained twin of ours: a 400 here is the drift alarm, and it was
  wired to a silent bulb. So a status the hub answered with comes back as
  `{:hub/refused status :hub/explain …}` — [[refused?]] tests for it, and it
  carries neither `:slug` nor `:beat-ms`, so the address clears and the interval
  falls back without either needing to know about refusals.

  A body that will not parse still degrades to nil rather than throwing; the
  status is what makes a refusal a refusal."
  [url body]
  (try
    (let [client (java.net.http.HttpClient/newHttpClient)
          req    (-> (java.net.http.HttpRequest/newBuilder (java.net.URI. url))
                     (.header "Content-Type" "application/json")
                     (.timeout (java.time.Duration/ofSeconds 2))
                     (.POST (java.net.http.HttpRequest$BodyPublishers/ofString
                             (json/generate-string body)))
                     (.build))
          resp   (.send client req
                        (java.net.http.HttpResponse$BodyHandlers/ofString))
          status (.statusCode resp)
          parsed (try (json/parse-string (.body resp) true)
                      (catch Exception _ nil))]
      (if (< status 400)
        parsed
        {:hub/refused status
         :hub/explain (or parsed (not-empty (str/trim (str (.body resp)))))}))
    (catch Exception _ nil)))

(defn ^:export start!
  "Begin beating `(payload-fn)` to the hub at `hub-url`, and return a handle
  for [[stop!]]. `on-answer` is called with the hub's reply after every beat.

  The FIRST beat goes out immediately: a project that only appeared one
  interval after its server started would read as broken for ten seconds
  every single time.

  Registration and keepalive are the same call (D-ui-hub), so this loop needs
  no state machine and no reconnect logic. A hub that is down, starts later,
  or restarts underneath us is all one case — the next beat registers us
  again, and the only cost is up to one interval of absence.

  The INTERVAL comes from the hub, per beat, through [[interval-from]]. It
  used to be a constant in `slopp.review.registry` that both halves read, which
  stopped being possible when the hub became a separate project: two
  processes on separate release cycles cannot share a compiled-in number.
  Re-read every beat rather than once, for the same reason the payload is —
  a hub that restarts with a different interval is answering the very next
  call, and nothing has to notice.

  `payload-fn` is called per beat rather than once, so a project that renames
  itself, moves, or later reports what it is doing needs no restart.

  `on-answer` is called with the reply EVERY beat, nil included — this loop is
  the only code that ever talks to the hub, so it is the only place that knows
  whether one is there. Whoever cares recomputes from the answer (see
  [[hub-address]]) instead of the beat holding an opinion about sessions, and
  nil arriving is what makes a claim about a departed hub expire rather than
  stand. It must never take the beat down: a callback that throws is the
  caller's problem, not a reason to stop registering.

  A DAEMON thread, like every other background loop here: the JVM must never
  be held open by a heartbeat."
  ([hub-url payload-fn] (start! hub-url payload-fn (fn [_])))
  ([hub-url payload-fn on-answer]
   (let [running (atom true)
         thread  (doto (Thread. ^Runnable
                        (fn []
                          (while @running
                            (let [answer (try (post! (str hub-url "api/register")
                                                     (payload-fn))
                                              (catch Throwable _ nil))]
                              (try (on-answer answer) (catch Throwable _ nil))
                              (try (Thread/sleep (interval-from answer))
                                   (catch InterruptedException _ nil)))))
                        "slopp-ui-heartbeat")
                   (.setDaemon true)
                   (.start))]
     {:thread thread :running running :hub-url hub-url :payload-fn payload-fn})))

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

(defn ^:export hub-address
  "The address to hand a HUMAN for this project — `hub-url` plus the slug the
  hub minted for us — or nil when no hub has answered.

  Registration and keepalive being the same call is what makes this possible:
  every beat's reply carries `:slug`, so a project that is registered right now
  knows its own public page, and one that is not cannot fabricate it. The
  answer is therefore a liveness fact and a deep link at once, which is why
  this takes the reply rather than probing.

  nil is the ANSWER, not a failure. A hub that is not running is the ordinary
  case — nobody has to start one — and reporting the configured url regardless
  is how orientation came to advertise a port with nothing behind it: a
  connection refused where a page was promised."
  [hub-url answer]
  (let [slug (str/trim (str (:slug answer)))]
    (when (seq slug)
      (str hub-url "p/" slug))))
