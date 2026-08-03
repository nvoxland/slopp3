(ns slopp.project.capabilities
  "The capability REGISTRY and the readers for it — what a store may declare
  about itself, and what those declarations currently say.

  Capabilities are the store's own configuration surface (`web.enabled`,
  `web.port`, the `web.auth.*` and `web.static.*` families): typed,
  defaulted, documented in one table, and written only through a gate that
  validates against it. `config_file` refuses an unregistered key, which is
  what keeps this a vocabulary rather than a bag.

  **A key's FIRST SEGMENT names its owner** — `owners` is that vocabulary,
  and it is how this registry stops being one app type's settings under
  generic names (R1/R6).

  **This namespace is the only place that knows where values live.**
  `effective` parses per the registry type and falls back to the default so a
  registered key never nil-puns; `stored?` answers the question `effective`
  deliberately erases, whether a value was actually SET. Both are exported
  for the same reason: a consumer reaching into
  `[:config \"capabilities\" :values]` itself would be a second place that
  knows the shape, and would skip the parsing and the defaults on the way."
  (:require [clojure.string :as str]))

(def owners
  "Who a capability key belongs to, keyed by its FIRST SEGMENT.

  **R1, generalized, and it is the whole of R6's answer for this registry.**
  A capability key's first segment names its owner, so the name carries the
  fact and no second field can drift from it. Three owners today:

  - `slopp` — slopp itself. RESERVED: a project's app can never own a key
    here, which is what makes `slopp.hub.port` unambiguously ours.
  - `app` — any project, whatever kind of application it is.
  - `web` — the WEB app type. Every store carries these; they are inert
    until `web.enabled`.

  **Why a vocabulary and not a convention.** The registry was 74% one app
  type under names that did not say so — `auth.*` and `groups.*` read as
  generic project settings while every reader of them was `slopp.web.auth`
  or a `web-` write gate. R6 says support for an app TYPE lives under that
  type's name and the pattern must be replicable for type #2 without
  renaming type #1. That only holds if a key OUTSIDE the declared owners is
  refused, which is what `every-capability-key-declares-its-owner` pins: app
  type #2 adds its owner here and its keys under that segment, and nothing
  it declares can land in the generic pool by accident.

  The docs are the reader's, not decoration — `report` groups by owner, so
  a store that never enables web sees one named feature it has not turned on
  rather than fourteen unrelated settings it could set."
  {"slopp" "slopp itself — RESERVED, a project's app can never own a key here"
   "app"   "any project, whatever kind of application it is"
   "web"   "the web app type — present in every store, inert until web.enabled"})

(def registry
  "The capability registry: one entry per `capabilities` config key —
  `{:key :type :default :doc}`. THE single source the validator
  (`check-value`), the effective-value read (`effective`), and
  `query_capabilities` all derive from, the same declare-once shape as
  `slopp.rules.catalog/rule-catalog`.

  **A key's FIRST SEGMENT names its owner**, and the vocabulary is
  `owners` — that is R1 generalized and R6 satisfied for this registry, so
  read it before adding a key.

  `:type` is a small STRUCTURAL vocabulary (`:string` `:boolean` `:int`
  `:enum` `:set-of` `:qualified-symbol` `:csv`) interpreted by plain
  Clojure, not malli — this ns loads in the server/boot JVM, which runs on
  kernel deps only (the two-process split; the `schema-refusal` precedent).
  A `*` in a key is a pattern: trailing `*` matches one-or-more remaining
  segments (`web.auth.static.*`), a mid `*` exactly one
  (`web.auth.groups.*.members`). Defaults are chosen so `web.enabled = true`
  alone yields a working, localhost-bound, deny-by-default server."
  [{:key "app.name" :type [:string] :default nil
    :doc "Application name. Unset = the store directory name at build time."}
   {:key "app.version" :type [:string] :default "0.0.0"
    :doc "Application version, carried into build artifacts."}
   {:key "app.main" :type [:qualified-symbol] :default nil
    :doc "The entry fn (app.core/-main). Unset = build's :main arg required."}
   {:key "web.enabled" :type [:boolean] :default false
    :doc "Whether this project serves HTTP. The master opt-in: web rules and query_routes exist only when true."}
   {:key "web.adapter" :type [:enum "http-kit" "jdk"] :default :http-kit
    :doc "Server adapter. http-kit is the production default; jdk (com.sun.net.httpserver) is the zero-dep fallback."}
   {:key "web.host" :type [:string] :default "127.0.0.1"
    :doc "Bind address. Localhost by default; widen deliberately."}
   {:key "web.port" :type [:int {:min 1 :max 65535}] :default nil
    :doc "Port the app's HTTP server binds. Unset = 8080 in production (slopp.web/serve! defaults it, so declaring 8080 here would only resolve \"unset\" a layer too early) and DERIVED from the store dir for the dev server, which is what keeps two projects on one machine from colliding. Set it to pin one address for both."}
   {:key "web.max-body-bytes" :type [:int {:min 1}] :default 1048576
    :doc "Largest accepted request body, bytes."}

   {:key "slopp.api.port" :type [:int {:min 1 :max 65535}] :default nil
    :doc "Port this project's own UI/API listener binds. Unset = DERIVED from the store dir — stable across restarts and collision-free, which a fixed default cannot be on a machine running several projects. Set it only to pin a fixed address."}
   {:key "slopp.hub.port" :type [:int {:min 0 :max 65535}] :default 7359
    :doc "The hub this project registers with. The hub is a SEPARATE application (it never opens a store), so this is the one number both sides have to agree on by configuration rather than by sharing code — the project beats to it, the hub binds it. Everything else about the beat, including how often, comes back on the registration response. 0 = register with no hub."}
   {:key "web.static.*" :type [:string] :default nil
    :doc "Static mount: the key's tail is the URL prefix, the value a files-manifest path prefix (web.static./assets = public serves public/cljs/main.js at /assets/cljs/main.js). A trailing slash on either is trimmed."}
   {:key "web.auth.providers" :type [:set-of [:enum "static" "bearer" "proxy-header" "oidc"]] :default #{}
    :doc "Enabled identity providers, comma-separated."}
   {:key "web.auth.default-policy" :type [:enum "deny" "authenticated" "public"] :default :deny
    :doc "Policy for an endpoint with no :web/auth of its own (reachable only when web-auth-refusal is dialed down)."}
   {:key "web.auth.session.ttl-seconds" :type [:int {:min 1}] :default 86400
    :doc "Browser session lifetime, seconds."}
   {:key "web.auth.static.*" :type [:string] :default nil
    :doc "Static-provider settings (web.auth.static.users.<name> = {:password-hash … :groups […]})."}
   {:key "web.auth.bearer.*" :type [:string] :default nil
    :doc "Bearer-provider settings (web.auth.bearer.tokens.<name> = {:secret \"env:NAME\" :groups […]})."}
   {:key "web.auth.proxy.*" :type [:string] :default nil
    :doc "Trusted-proxy-provider settings (web.auth.proxy.trusted, web.auth.proxy.user-header)."}
   {:key "web.auth.oidc.*" :type [:string] :default nil
    :doc "OIDC-provider settings (web.auth.oidc.issuer, web.auth.oidc.client-id, …). Secrets as env:NAME."}
   {:key "web.auth.groups.*.members" :type [:csv] :default nil
    :doc "Members of a named group, comma-separated (web.auth.groups.admin.members = alice,bob). A group exists to be named by :web/auth [:group …], which is why it sits under auth rather than beside it."}])

(defn- match-pattern?
  "Does dotted key `k` match registry `pattern`? A trailing `*` matches one
  or more remaining segments; a mid-pattern `*` exactly one."
  [pattern k]
  (loop [ps (str/split (str pattern) #"\.")
         ks (str/split (str k) #"\.")]
    (cond
      (empty? ps) (empty? ks)
      (and (= "*" (first ps)) (empty? (rest ps))) (boolean (seq ks))
      (empty? ks) false
      (or (= "*" (first ps)) (= (first ps) (first ks))) (recur (rest ps) (rest ks))
      :else false)))

(defn find-entry
  "The registry entry governing concrete key `k` — exact match first, then
  wildcard patterns. nil = no such capability (the unknown-key refusal
  signal; a typo'd key must never silently do nothing)."
  [k]
  (let [k (str k)]
    (or (some #(when (= (:key %) k) %) registry)
        (some #(when (and (str/includes? (:key %) "*")
                          (match-pattern? (:key %) k))
                 %)
              registry))))

(defn check-value
  "Validate config string `v` against `entry`'s declared `:type`. nil when
  the value suits; else a TEACHING string — the key, what it takes, what
  arrived — surfaced verbatim by config_file's refusal. Write-time
  validation is the point: a bad value is refused at the write, not
  discovered when the server fails to boot."
  [entry v]
  (when entry
    (let [v (str v)
          [t opt] (:type entry)
          bad (fn [wants]
                (str (:key entry) " takes " wants ", got " (pr-str v)))]
      (case t
        :string nil
        :boolean (when-not (#{"true" "false"} v) (bad "true or false"))
        :int (let [n (try (Long/parseLong v) (catch NumberFormatException _ nil))]
               (cond
                 (nil? n) (bad "an integer")
                 (and (:min opt) (< n (:min opt))) (bad (str "an integer ≥ " (:min opt)))
                 (and (:max opt) (> n (:max opt))) (bad (str "an integer ≤ " (:max opt)))))
        :enum (let [members (rest (:type entry))]
                (when-not (some #(= % v) members)
                  (bad (str "one of " (str/join ", " members)))))
        :set-of (let [members (rest (second (:type entry)))
                      vals* (map str/trim (str/split v #","))]
                  (when-let [alien (some #(when-not (some (fn [m] (= m %)) members) %)
                                         vals*)]
                    (bad (str "a comma-separated subset of " (str/join ", " members)
                              " — " (pr-str alien) " is not one"))))
        :qualified-symbol (when-not (re-matches #"[^\s/]+/[^\s/]+" v)
                            (bad "a qualified symbol (app.core/-main)"))
        :csv (when (str/blank? v) (bad "a comma-separated list"))))))

(defn ^:export effective
  "The effective value of capability `k` for this store: the stored
  `capabilities` config value parsed per its registry type, else the
  entry's `:default` — so a registered key with a default never nil-puns.
  Unknown key → nil. A stored value failing its check (reachable only via
  a foreign merge; the write gate refuses it) falls back to the default
  rather than throwing at serve time.

  Exported: it is THE reader for a capability value, and a consumer
  outside this module reaching into `[:config \"capabilities\" :values]`
  would skip both the type parsing and the default."
  [store k]
  (let [k (str k)
        entry (find-entry k)
        v (get-in store [:config "capabilities" :values k])
        parse (fn [entry v]
                (case (first (:type entry))
                  :string v
                  :boolean (= "true" v)
                  :int (Long/parseLong v)
                  :enum (keyword v)
                  :set-of (into #{} (map (comp keyword str/trim))
                                (str/split v #","))
                  :qualified-symbol (symbol v)
                  :csv (into #{} (map str/trim) (str/split v #","))))]
    (cond
      (nil? entry) nil
      (and v (nil? (check-value entry v))) (parse entry v)
      :else (:default entry))))

(defn ^:export stored?
  "Whether capability `k` was explicitly SET in this store, as opposed to
  carrying its registry default.

  `effective` deliberately erases that distinction so a registered key never
  nil-puns. Some callers need it back: the dev server binds an explicitly
  pinned `web.port` but DERIVES one when nobody pinned it, because a fixed
  default collides between two projects on one machine (the reasoning
  `http-api.server/derived-port` records). \"8080\" typed by hand and 8080
  arriving from the registry have to be told apart to do that.

  Exported for the same reason `effective` is: the config path is this
  namespace's business, and a consumer reaching into
  `[:config \"capabilities\" :values]` to answer this would be the second
  place that knows where values live."
  [store k]
  (some? (get-in store [:config "capabilities" :values (str k)])))

(defn ^:export config-refusal
  "The `capabilities` config write gate: a teaching error for an unknown
  key, a value that fails its registry type, or a CREDENTIAL-shaped literal
  — nil when the write may land. An unknown key MUST refuse (a typo'd
  capability that silently does nothing is the nil-pun failure this
  registry exists to kill). A secret literal must refuse too: this config
  is tracked and git-projected, so `web.auth.*` credential positions (a
  `…token…`/`…secret` key, or a `:secret` entry in the value) take
  `env:NAME` indirections only; `password-hash` is exempt — a hash IS the
  safe form."
  [k v]
  (let [k (str k) v (str v)
        credential-key? (and (str/starts-with? k "web.auth.")
                             (re-find #"(token|secret)s?(\.|$)" k))
        secret-entry (second (re-find #":secret\s+\"([^\"]*)\"" v))
        literal? (fn [s] (and (seq (str s))
                              (not (str/starts-with? (str s) "env:"))))]
    (if-let [entry (find-entry k)]
      (or (check-value entry v)
          (cond
            (and credential-key? (nil? secret-entry) (not (str/includes? v ":"))
                 (literal? v))
            (str k " holds a literal credential — this config is tracked and"
                 " git-projected, so secrets go through the environment:"
                 " value \"env:SOME_NAME\", and the deployment sets SOME_NAME")

            (and (str/starts-with? k "web.auth.") secret-entry (literal? secret-entry))
            (str k " embeds a literal :secret — this config is tracked and"
                 " git-projected, so secrets go through the environment:"
                 " :secret \"env:SOME_NAME\", and the deployment sets SOME_NAME")))
      (str k " is not a capability — query_capabilities lists every setting"
           " with its type, default, and effective value; known keys/patterns: "
           (str/join ", " (map :key registry))))))

(defn ^:export report
  "The `query_capabilities` payload: `{:settings [...] :patterns [...]
  :owners {...}}`, plus `:orphaned` when the store has stored keys this build
  does not recognise. `:settings` = one row per CONCRETE registry key
  `{:key :owner :effective :default :doc}` (+ `:set true :value <raw>` when
  the store sets it), plus a row for every stored key a wildcard pattern
  governs. `:patterns` = the wildcard entries themselves (key + owner + doc)
  — they name families, they are not settable rows. A pure function of the
  store value, so it is correct on any branch and at any revision.

  **`:owner` is DERIVED from the key's first segment**, never stored beside
  it, so the label and the name cannot disagree; `:owners` is the vocabulary
  those labels come from. It exists because every project is shown every
  key, and fourteen of nineteen belong to one app type — a store that will
  never serve HTTP still reads `web.auth.oidc.*` as something it could set.
  Filtering them out would be the wrong fix: `web.enabled` is itself a web
  key, so hiding web keys until web is on hides the switch that turns it on.
  Attribution is what makes fourteen settings read as one feature.

  **`:orphaned` is the rename path, and it used to be invisible.** This is a
  JOIN of the registry against the stored config, and a stored key with no
  registry row simply fell off it. So a store carrying three settings under
  retired names reported ZERO `:set true` and said nothing at all — the tool
  whose job is *what is configured here* describing an unconfigured store,
  while the reason its app server would not start sat in the config it
  declined to mention. UNSET and SET-UNDER-A-NAME-I-NO-LONGER-KNOW shared one
  representation at the exact moment the difference IS the diagnosis.

  The rows carry the VALUE, not just the key, because that makes the answer a
  migration instruction rather than a prompt to go and look. Absent when there
  are none, the way the module manifest's `:debt` is — this always computes,
  so absence unambiguously means none.

  Found by the first store to cross a capability rename. With
  `no-backwards-compatibility` standing policy that path is common rather than
  rare, so the report has to survive it."
  [store]
  (let [values (get-in store [:config "capabilities" :values] {})
        concrete? #(not (str/includes? (:key %) "*"))
        owner-of (fn [k] (first (str/split (str k) #"\.")))
        setting (fn [k entry]
                  (let [v (get values k)]
                    (cond-> {:key k
                             :owner (owner-of k)
                             :effective (effective store k)
                             :default (:default entry)
                             :doc (:doc entry)}
                      (some? v) (assoc :set true :value v))))
        rows (mapv #(setting (:key %) %) (filter concrete? registry))
        exact? (fn [k] (some #(when (= (:key %) k) %) registry))
        ;; every stored key the concrete rows above did not already cover:
        ;; some are governed by a wildcard pattern, and the rest are governed
        ;; by nothing, which is the case this used to drop on the floor.
        loose (remove exact? (sort (keys values)))
        {governed true orphans false} (group-by #(some? (find-entry %)) loose)
        wild (mapv #(setting % (find-entry %)) governed)
        orphaned (mapv (fn [k] {:key k :value (get values k)}) orphans)]
    (cond-> {:settings (into rows wild)
             :patterns (mapv #(assoc (select-keys % [:key :doc]) :owner (owner-of (:key %)))
                             (remove concrete? registry))
             ;; the vocabulary rides along rather than being looked up: an
             ;; owner label on a row is only useful beside what the label
             ;; MEANS, and a reader of this payload has no other way to it.
             :owners owners}
      (seq orphaned)
      (assoc :orphaned orphaned
             :orphaned-note
             (str "stored under names this slopp does not know — nothing reads"
                  " them. They are usually a capability RENAME you have not"
                  " migrated: set the current key (query_capabilities lists"
                  " them all) and then config_file {path \"capabilities\" key"
                  " <old> unset true}")))))
