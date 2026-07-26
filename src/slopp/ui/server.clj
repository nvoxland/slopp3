(ns slopp.ui.server
  (:require [slopp.web :as web]
            [slopp.ui.pages] [slopp.ui.api]))

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
  "Every namespace the reviewer UI serves — routes AND read performers.

  ONE list, exported, because there are two servers that mount this UI
  (`ui/serve!` and the MCP http transport) and a literal repeated at both is
  how a namespace ends up served by nobody. That is not hypothetical: the
  compiled client bundle 404'd on every page for two waves behind a 200 for
  the page itself, because one list got a new entry and the other did not.

  Both halves of a request live here. `slopp.ui.api` declares the `/api/*`
  routes; `slopp.ui.pages` declares the `:web/read` performers they resolve
  through. Reads are addressed by VOCABULARY rather than by var, so the API
  reuses the page's reads instead of copying them — but that also means a
  list carrying only one of the two namespaces answers 500 rather than 404,
  which is a much worse way to find out."
  ['slopp.ui.pages 'slopp.ui.api])

(defn ^:export serve!
  "Serve the reviewer UI on `port` over the CALLER's session, and return
  `{:url :port}` — or `{:error :port}` when the port is taken.

  The session is passed in rather than opened here, and that is the whole
  reason this is not the MCP transport: `:test-map` and `:observed` are
  session-grain and never persisted, so a UI served from a fresh session
  renders every form as covered by no tests and exercised by no examples.

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
                           :web/perform-ctx {:session session}})
          p   (:port srv)
          url (str "http://127.0.0.1:" p "/")]
      (reset! current {:server srv :port p :url url})
      {:url url :port p})
    (catch Exception e
      (if (loop [t e]
            (cond (nil? t) false
                  (instance? java.net.BindException t) true
                  :else (recur (.getCause t))))
        {:error (str "port " port " is not available") :port port}
        (throw e)))))
