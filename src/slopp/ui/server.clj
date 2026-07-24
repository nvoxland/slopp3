(ns slopp.ui.server
  (:require [slopp.web :as web]
            [slopp.ui.pages]))

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
    (let [srv (web/serve! {:web/namespaces ['slopp.ui.pages]
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
