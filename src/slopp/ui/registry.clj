(ns slopp.ui.registry
  "Which projects have checked in with the UI hub, under what address, and
  how long ago.

  A slopp UI hub is one process per machine that a human starts and points a
  browser at, while every MCP server keeps serving its OWN store over its own
  loopback port — it has to, because warranty and observed examples are
  session-grain and a hub that opened the db itself would render every form as
  covered by nothing (D-ui-hub). What is left for the hub to know is exactly
  what a heartbeat tells it, and this namespace is that knowledge: a plain map
  of dir → entry, with a clock passed in.

  Pure on purpose. `slopp.ui.hub` owns the atom, the routes and the proxy;
  everything about WHEN a project counts as available and WHAT address it
  answers on is decided here, where a test needs no server."
  (:require [clojure.string :as str]))

(def beat-ms
  "How often a project checks in with the hub, in milliseconds.

  Registration and keepalive are the SAME call (`beat`), so this interval is
  also the worst-case time for the hub to learn about a project — including
  after a hub restart, which is why it is short enough to feel instant and
  long enough to be free."
  10000)

(def stale-after-ms
  "How long since a project's last beat before the hub calls it unavailable —
  three missed beats, not one, so an ordinary GC pause or a busy image never
  greys out a project that is perfectly alive."
  (* 3 beat-ms))

(defn- slugify
  "A url-safe address fragment from a project name: lowercase, every run of
  non-alphanumerics collapsed to one hyphen, ends trimmed. Never blank — a
  name of pure punctuation still has to be addressable."
  [s]
  (let [t (-> (str s)
              str/lower-case
              (str/replace #"[^a-z0-9]+" "-")
              (str/replace #"^-+|-+$" ""))]
    (if (str/blank? t) "project" t)))

(defn- mint-slug
  "A slug for `dir` that no OTHER entry in `registry` already holds.

  The plain name comes first, so the common single-project-per-name case gets
  the address you would guess. A collision (~/a/web and ~/b/web on one
  machine is ordinary, not exotic) falls back to the name plus a short hash
  OF THE DIR — stable across restarts, unlike a counter, which would hand the
  same project a different url depending on who booted first."
  [registry name dir]
  (let [base  (slugify name)
        h     (format "%04x" (bit-and (hash (str dir)) 0xffff))
        taken (into #{} (map :slug) (vals registry))]
    (first (remove taken (cons base (map #(str base "-" h (when (pos? %) (str "-" %)))
                                         (range)))))))

(defn beat
  "Record a project's check-in at `now`: upsert `entry` under its `:dir` and
  stamp `:last-seen`. Returns the new registry.

  This is BOTH registration and keepalive, deliberately (D-ui-hub). One call
  means the hub can be started after the projects, or restarted underneath
  them, and every live project re-appears within one `beat-ms` with nobody
  bouncing an MCP server — and there is one code path to get right instead of
  two that must agree.

  The `:dir` is the identity; `:slug` is minted on the FIRST beat and carried
  forward, so an address stays valid even if the project renames itself."
  [registry {:keys [dir] :as entry} now]
  (let [prior (get registry dir)]
    (assoc registry dir
           (assoc entry
                  :dir       dir
                  :slug      (or (:slug prior) (mint-slug registry (:name entry) dir))
                  :last-seen now))))

(defn forget
  "Drop `dir`'s entry — a project's clean-shutdown deregistration, which is
  only the fast path: a project that dies without one goes stale on its own."
  [registry dir]
  (dissoc registry dir))

(defn available?
  "Has `entry` beaten recently enough to count as answering, as of `now`?

  ONE place decides, because two things ask: the list the picker renders and
  the proxy deciding whether to forward a request or serve the page that says
  the project is not running. Those two disagreeing is a project that is
  greyed out and still proxying, or listed and refusing."
  [entry now]
  (<= (- now (:last-seen entry)) stale-after-ms))

(defn projects
  "Every known project as of `now`, sorted by name then dir, each carrying
  `:available?` from [[available?]].

  Availability is DERIVED at read time rather than stored, so nothing has to
  sweep the registry on a timer to keep it honest. A stale project stays in
  the list on purpose: the picker greys it out, because one you were just
  looking at should not vanish the moment its editor closes."
  [registry now]
  (->> (vals registry)
       (sort-by (juxt :name :dir))
       (mapv #(assoc % :available? (available? % now)))))

(defn find-slug
  "The entry `slug` addresses, or nil. The hub's proxy resolves every
  `/p/<slug>/…` request through this."
  [registry slug]
  (first (filter #(= slug (:slug %)) (vals registry))))
