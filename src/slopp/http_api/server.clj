(ns slopp.http-api.server
  "The listener a project serves its OWN reviewer UI on.

  One per MCP process, over the live session — which is the whole reason this
  is not the MCP transport. Warranty and observed examples ARE persisted
  (`session/persist-trace!` at every verified run, reloaded by `open!`), so a
  fresh session is not blank; it is BEHIND. It sees the last snapshot rather
  than what the agent is working against right now, and it would boot a second
  image to see even that.

  Its address is derived rather than configured (D-hub). The fixed
  `slopp.api.port` default worked for one project on a machine and collided for the
  second; now nobody needs to know this port at all, because the address a
  human remembers belongs to the HUB, which is its own project (`slopp-ui`)
  and proxies here."
  (:require [slopp.project.capabilities :as caps]
            [slopp.web :as web]
            [slopp.http-api.reads] [slopp.http-api.endpoints]))

(defonce ^:private current
  ;; defonce, not def: under --live this namespace reloads on every edit,
  ;; and a plain def would drop the handle of a server still holding a port.
  (atom nil))

(defn ^:export running
  "The live UI server as `{:port :url}`, or nil when none is."
  []
  (some-> @current (select-keys [:port :url])))

(defn ^:export stop!
  "Stop the UI server if one is running; true when it stopped something.
  Idempotent, because eviction and an explicit stop are the same act."
  []
  (when-let [srv (:server @current)]
    (web/stop! srv)
    (reset! current nil)
    true))

(def ^:export served-namespaces
  "Every namespace this project's API serves — endpoints AND read performers.

  ONE list, exported. It had two mounts — `serve!` here and the MCP http
  transport — and a literal repeated at both is how a namespace ends up
  served by nobody: the compiled client bundle 404'd on every page for two
  waves behind a 200 for the page itself, because one list got a new entry
  and the other did not.

  The transport is retired (D-mcp-stdio-only), so `serve!` is the only mount
  now, and that is the POINT rather than an excuse to inline it: mounting the
  reviewer API anywhere else is what the decision rules out. This API is a
  distinct custom API for the UI, not part of what counts as a web project,
  and one exported list is what makes a second mount a visible choice rather
  than a copied literal.

  Both halves of a request live here. `slopp.http-api.api` declares the `/api/*`
  routes; `slopp.http-api.reads` declares the `:web/read` performers they resolve
  through. Reads are addressed by VOCABULARY rather than by var, so an
  endpoint names a KIND and the performer for it is found — but that also
  means a list carrying only one of the two namespaces answers 500 rather
  than 404, which is a much worse way to find out.

  It is a short list now and stays that way: a project serves JSON and the
  EDN contract, nothing else. The pages a human looks at belong to the hub,
  which is a separate application (D-hub part 4)."
  ['slopp.http-api.reads 'slopp.http-api.endpoints])

(defn ^:export derived-port
  "A localhost port DERIVED from the store dir for this project's own UI
  listener — stable across restarts, and different for every project on the
  machine.

  This is what replaced a fixed `slopp.api.port` default (D-hub). One well-known
  port worked for exactly one project and collided for the second; deriving
  makes the collision structurally impossible instead of configured away, and
  nobody needs to know the number, because the address a human remembers is
  the hub's.

  SALTED — originally to keep it off the git listener's port for the same
  dir, since one MCP process bound both and an unsalted formula would have
  had every project collide with itself. That listener is gone and the salt
  now distinguishes this from nothing. It STAYS anyway, and the reason is
  the only one that matters here: the formula IS the address. Changing it
  relocates every project's UI on every machine, and anything holding a
  saved url points at a dead port. A vestigial salt is cheaper than that.

  A preference, not a guarantee: a taken port falls back to an ephemeral one
  at bind time, and the registered url carries whatever was actually bound."
  [dir]
  (+ 49152 (mod (hash (str "slopp-ui:" dir)) 16384)))

(defn ^:export preferred-port
  "Which port this project's UI listener should try: an explicit request
  first, then the configured `slopp.api.port`, then [[derived-port]] for `dir`, then
  0 (ephemeral) when there is no dir to derive from.

  ONE resolution, because two callers ask — the autostart in `slopp.mcp` and
  the `ui_serve` tool. Two copies of this ladder disagreeing would put the UI
  on an address neither of them reported."
  [store dir explicit]
  (or explicit
      (caps/effective store "slopp.api.port")
      (some-> dir derived-port)
      0))

(defn ^:export serve!
  "Serve the reviewer UI on `port` over the CALLER's session, and return
  `{:url :port}` — or `{:error :port}` when the port is taken.

  The session is passed in rather than opened here, and that is the whole
  reason this listener exists separately at all. The reviewer API is a
  CUSTOM API for the UI — it reuses the web machinery, and it is deliberately
  not part of what counts as this project's web app (D-http-api-distinct).
  Not because the warranty is unwritable
  — `session/persist-trace!` writes `:test-map` to store meta at every verified
  run and `open!` loads it back, so a fresh session is not blank. Because what
  it loads is the last SNAPSHOT: the trace an agent is working against mid-
  episode is ahead of the persisted one, `:observed` the same, and opening a
  session to find out would boot a second image of code this process already
  has. A page that showed the warranty as of the last write instead of as of
  now would be wrong exactly when someone is watching it change.

  Two stances, both learned by Clerk the hard way. Serving again EVICTS the
  running server instead of hunting for a free port — a url you were handed
  should not quietly stop being the url that works. And a port someone else
  holds is reported as a sentence, because `BindException` at an agent is a
  stack trace where an instruction belongs.

  `port` 0 binds an ephemeral port; the BOUND port is what comes back."
  [session port]
  (stop!)
  (try
    (let [srv (web/serve! {:web/namespaces served-namespaces
                           :web/host "127.0.0.1"
                           :web/port port
                           :web/perform-ctx {:session session
                                           :served-namespaces served-namespaces}})
          p   (:port srv)
          url (str "http://127.0.0.1:" p "/")]
      (reset! current {:server srv :port p :url url})
      {:url url :port p})
    (catch Exception e
      ;; the recognition AND the sentence come from slopp.web — this used to
      ;; walk its own cause chain and phrase its own answer, one of three
      ;; listeners doing that differently. What stays here is the part that is
      ;; genuinely this listener's: it REPORTS rather than throws, because the
      ;; caller is a tool result.
      (if-let [d (web/bind-diagnosis port e)]
        {:error d :port port}
        (throw e)))))
