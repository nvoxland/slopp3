(ns slopp.api.capabilities
  "The capability REGISTRY and the readers for it — what a store may declare
  about itself, and what those declarations currently say.

  Capabilities are the store's own configuration surface (`http.enabled`,
  `http.port`, the `auth.*` and `http.static.*` families): typed, defaulted,
  documented in one table, and written only through a gate that validates
  against it. `config_file` refuses an unregistered key, which is what keeps
  this a vocabulary rather than a bag.

  **This namespace is the only place that knows where values live.**
  `effective` parses per the registry type and falls back to the default so a
  registered key never nil-puns; `stored?` answers the question `effective`
  deliberately erases, whether a value was actually SET. Both are exported
  for the same reason: a consumer reaching into
  `[:config \"capabilities\" :values]` itself would be a second place that
  knows the shape, and would skip the parsing and the defaults on the way."
  (:require [clojure.string :as str]))

(def registry
  "The capability registry: one entry per `capabilities` config key —
  `{:key :type :default :doc}`. THE single source the validator
  (`check-value`), the effective-value read (`effective`), and
  `query_capabilities` all derive from, the same declare-once shape as
  `slopp.api.rules.catalog/rule-catalog`.

  `:type` is a small STRUCTURAL vocabulary (`:string` `:boolean` `:int`
  `:enum` `:set-of` `:qualified-symbol` `:csv`) interpreted by plain
  Clojure, not malli — this ns loads in the server/boot JVM, which runs on
  kernel deps only (the two-process split; the `schema-refusal` precedent).
  A `*` in a key is a pattern: trailing `*` matches one-or-more remaining
  segments (`auth.static.*`), a mid `*` exactly one (`groups.*.members`).
  Defaults are chosen so `http.enabled = true` alone yields a working,
  localhost-bound, deny-by-default server."
  [{:key "app.name" :type [:string] :default nil
    :doc "Application name. Unset = the store directory name at build time."}
   {:key "app.version" :type [:string] :default "0.0.0"
    :doc "Application version, carried into build artifacts."}
   {:key "app.main" :type [:qualified-symbol] :default nil
    :doc "The entry fn (app.core/-main). Unset = build's :main arg required."}
   {:key "http.enabled" :type [:boolean] :default false
    :doc "Whether this project serves HTTP. The master opt-in: web rules and query_routes exist only when true."}
   {:key "http.adapter" :type [:enum "http-kit" "jdk"] :default :http-kit
    :doc "Server adapter. http-kit is the production default; jdk (com.sun.net.httpserver) is the zero-dep fallback."}
   {:key "http.host" :type [:string] :default "127.0.0.1"
    :doc "Bind address. Localhost by default; widen deliberately."}
   {:key "http.port" :type [:int {:min 1 :max 65535}] :default nil
    :doc "Port the app's HTTP server binds. Unset = 8080 in production (slopp.web/serve! defaults it, so declaring 8080 here would only resolve \"unset\" a layer too early) and DERIVED from the store dir for the dev server, which is what keeps two projects on one machine from colliding. Set it to pin one address for both."}
   {:key "http.max-body-bytes" :type [:int {:min 1}] :default 1048576
    :doc "Largest accepted request body, bytes."}
   {:key "dev.server" :type [:boolean] :default true
    :doc "Whether slopp runs this project's app server for you while you work — a dedicated image, re-served at each done point, so a live version is always up without the app holding a serve! call. Set false when this project's HTTP surface is already served by something else, since a managed server would then serve a second, staler copy of it. slopp's own store is exactly that case: its web surface is the MCP HTTP transport plus the reviewer API, which the live session already serves."}
   {:key "ui.port" :type [:int {:min 1 :max 65535}] :default nil
    :doc "Port this project's own UI/API listener binds. Unset = DERIVED from the store dir — stable across restarts and collision-free, which a fixed default cannot be on a machine running several projects. Set it only to pin a fixed address."}
   {:key "ui.hub-port" :type [:int {:min 0 :max 65535}] :default 7359
    :doc "The UI hub this project registers with. The hub is a SEPARATE application (it never opens a store), so this is the one number both sides have to agree on by configuration rather than by sharing code — the project beats to it, the hub binds it. Everything else about the beat, including how often, comes back on the registration response. 0 = register with no hub."}
   {:key "http.static.*" :type [:string] :default nil
    :doc "Static mount: the key's tail is the URL prefix, the value a files-manifest path prefix (http.static./assets = public serves public/cljs/main.js at /assets/cljs/main.js). A trailing slash on either is trimmed."}
   {:key "auth.providers" :type [:set-of [:enum "static" "bearer" "proxy-header" "oidc"]] :default #{}
    :doc "Enabled identity providers, comma-separated."}
   {:key "auth.default-policy" :type [:enum "deny" "authenticated" "public"] :default :deny
    :doc "Policy for an endpoint with no :web/auth of its own (reachable only when web-auth-refusal is dialed down)."}
   {:key "auth.session.ttl-seconds" :type [:int {:min 1}] :default 86400
    :doc "Browser session lifetime, seconds."}
   {:key "auth.static.*" :type [:string] :default nil
    :doc "Static-provider settings (auth.static.users.<name> = {:password-hash … :groups […]})."}
   {:key "auth.bearer.*" :type [:string] :default nil
    :doc "Bearer-provider settings (auth.bearer.tokens.<name> = {:secret \"env:NAME\" :groups […]})."}
   {:key "auth.proxy.*" :type [:string] :default nil
    :doc "Trusted-proxy-provider settings (auth.proxy.trusted, auth.proxy.user-header)."}
   {:key "auth.oidc.*" :type [:string] :default nil
    :doc "OIDC-provider settings (auth.oidc.issuer, auth.oidc.client-id, …). Secrets as env:NAME."}
   {:key "groups.*.members" :type [:csv] :default nil
    :doc "Members of a named group, comma-separated (groups.admin.members = alice,bob)."}])

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
  pinned `http.port` but DERIVES one when nobody pinned it, because a fixed
  default collides between two projects on one machine (the reasoning
  `ui-api.server/derived-port` records). \"8080\" typed by hand and 8080
  arriving from the registry have to be told apart to do that.

  Exported for the same reason `effective` is: the config path is this
  namespace's business, and a consumer reaching into
  `[:config \"capabilities\" :values]` to answer this would be the second
  place that knows where values live."
  [store k]
  (some? (get-in store [:config "capabilities" :values (str k)])))

(defn config-refusal
  "The `capabilities` config write gate: a teaching error for an unknown
  key, a value that fails its registry type, or a CREDENTIAL-shaped literal
  — nil when the write may land. An unknown key MUST refuse (a typo'd
  capability that silently does nothing is the nil-pun failure this
  registry exists to kill). A secret literal must refuse too: this config
  is tracked and git-projected, so `auth.*` credential positions (a
  `…token…`/`…secret` key, or a `:secret` entry in the value) take
  `env:NAME` indirections only; `password-hash` is exempt — a hash IS the
  safe form."
  [k v]
  (let [k (str k) v (str v)
        credential-key? (and (str/starts-with? k "auth.")
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

            (and (str/starts-with? k "auth.") secret-entry (literal? secret-entry))
            (str k " embeds a literal :secret — this config is tracked and"
                 " git-projected, so secrets go through the environment:"
                 " :secret \"env:SOME_NAME\", and the deployment sets SOME_NAME")))
      (str k " is not a capability — query_capabilities lists every setting"
           " with its type, default, and effective value; known keys/patterns: "
           (str/join ", " (map :key registry))))))

(defn report
  "The `query_capabilities` payload: `{:settings [...] :patterns [...]}`.
  `:settings` = one row per CONCRETE registry key `{:key :effective :default
  :doc}` (+ `:set true :value <raw>` when the store sets it), plus a row for
  every stored key a wildcard pattern governs. `:patterns` = the wildcard
  entries themselves (key + doc) — they name families, they are not
  settable rows. A pure function of the store value, so it is correct on
  any branch and at any revision."
  [store]
  (let [values (get-in store [:config "capabilities" :values] {})
        concrete? #(not (str/includes? (:key %) "*"))
        setting (fn [k entry]
                  (let [v (get values k)]
                    (cond-> {:key k
                             :effective (effective store k)
                             :default (:default entry)
                             :doc (:doc entry)}
                      (some? v) (assoc :set true :value v))))
        rows (mapv #(setting (:key %) %) (filter concrete? registry))
        wild (into []
                   (comp (filter (fn [k] (nil? (some #(when (= (:key %) k) %)
                                                     registry))))
                         (keep (fn [k] (when-let [e (find-entry k)]
                                         (setting k e)))))
                   (sort (keys values)))]
    {:settings (into rows wild)
     :patterns (mapv #(select-keys % [:key :doc])
                     (remove concrete? registry))}))
