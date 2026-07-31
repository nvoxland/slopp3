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
            [slopp.api.capabilities :as caps] [slopp.web.client :as client]))

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
  "POST `body` as JSON through `requester` to `url`; the hub's parsed answer, a
  refusal, or nil when it did not answer.

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
  status is what makes a refusal a refusal.

  The transport arrives as a PARAMETER — `slopp.web.client/request` in
  production, a fake in tests. That is what makes the loop above checkable
  without a socket, and it is safe to fake precisely because both adapters pass
  `requester-contract`. Note how little is left here once the transport goes:
  this function is now entirely POLICY, and the policy is the part that was
  ever worth testing."
  [requester url body]
  (let [payload (json/generate-string body)
        ;; The try covers the REQUEST and nothing else, and catches the ONE
        ;; ex-info the port throws for an unreachable far side. It used to wrap
        ;; this whole body in `(catch Exception _ nil)` — so an unencodable
        ;; payload, an NPE, any bug at all came back as nil, and nil is what
        ;; the loop reads as "no hub is running". A project would report itself
        ;; absent forever while the fault sat in its own payload.
        ;;
        ;; That breadth was only possible because the transport threw a bare
        ;; IOException: catching "no server" meant catching everything. The
        ;; narrow catch is what the port's :http/error bought.
        resp    (try
                  (requester {:http/method     :post
                              :http/url        url
                              :http/headers    {"Content-Type" "application/json"}
                              :http/body       payload
                              :http/timeout-ms 2000})
                  (catch clojure.lang.ExceptionInfo e
                    (when-not (= :unreachable (:http/error (ex-data e)))
                      (throw e))))]
    (when resp
      (let [status (:http/status resp)
            parsed (try (json/parse-string (:http/body resp) true)
                        (catch Exception _ nil))]
        (if (< status 400)
          parsed
          {:hub/refused status
           :hub/explain (or parsed
                            (not-empty (str/trim (str (:http/body resp)))))})))))

(defn ^:export start!
  "Begin beating `(payload-fn)` to the hub at `hub-url`, and return a handle
  for [[stop!]]. `on-answer` is called with the hub's reply after every beat.
  `requester` is the transport — [[slopp.web.client/request]] by default, a
  fake in tests.

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
  be held open by a heartbeat.

  The `requester` arity is what makes every paragraph above CHECKABLE. All of
  it — beat now, take the interval from the wire, relay every answer, survive a
  throwing callback — is loop behaviour that has nothing to do with sockets,
  and it used to need a fresh JVM and a real server to observe. The transport
  is covered once, by `requester-contract`, against both adapters; the loop is
  covered here at memory speed."
  ([hub-url payload-fn] (start! hub-url payload-fn (fn [_])))
  ([hub-url payload-fn on-answer] (start! hub-url payload-fn on-answer client/request))
  ([hub-url payload-fn on-answer requester]
   (let [running (atom true)
         thread  (doto (Thread. ^Runnable
                        (fn []
                          (while @running
                            (let [answer (try (post! requester
                                                     (str hub-url "api/register")
                                                     (payload-fn))
                                              ;; The beat must never die — but
                                              ;; surviving is not the same as
                                              ;; reporting that nothing
                                              ;; happened. nil MEANS "no hub";
                                              ;; a bug of ours gets its own
                                              ;; answer, or the loop lies about
                                              ;; the world to cover for itself.
                                              (catch Throwable t
                                                {:hub/error (or (ex-message t)
                                                                (str t))}))]
                              (try (on-answer answer) (catch Throwable _ nil))
                              (try (Thread/sleep (interval-from answer))
                                   (catch InterruptedException _ nil)))))
                        "slopp-ui-heartbeat")
                   (.setDaemon true)
                   (.start))]
     {:thread thread :running running :hub-url hub-url :payload-fn payload-fn
      :requester requester})))

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
      (post! (:requester hb)
             (str (:hub-url hb) "api/deregister")
             {:dir (:dir ((:payload-fn hb)))})
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
