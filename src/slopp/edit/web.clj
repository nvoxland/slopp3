(ns slopp.edit.web
  "The D-web write gates and the store-value primitives they judge against:
  auth policy, route collisions, the effect and context vocabulary, endpoint
  contracts, and the generated-client surface.

  **Store-analysis of `:web/*` metadata — NOT the framework.** `slopp.web` is
  what a user's app runs on, knows nothing about stores, and sits at layer 0;
  this reads a candidate store to decide whether a write may land, at write
  time. They are opposite directions and must not share a prefix.

  It lives under `slopp.edit` rather than in a module of its own for a
  mechanical reason: `module-of` is the first TWO segments, so a namespace's
  module fixes its layer, and these gates run inside the write pipeline. The
  same fact is why the web TOOLING could not stay under `slopp.web` either.

  `slopp.rules.web` is the other consumer of these primitives —
  `web-endpoint-rows`, `web-performers`, `web-context-builders` — and that
  sharing is the point rather than an accident: a rule that REFUSES at the
  write and a report that LISTS the surface have to agree, and they only can
  if they are one derivation. R6: web is app type #1, not the app type, so
  everything app-type-specific carries the qualifier and a second type gets
  its own namespace without renaming this one."
  (:require [clojure.string :as str]
            [rewrite-clj.node :as n]
            [slopp.index.analyze :as analyze]
            [slopp.index.derive :as derive]
            [slopp.store :as store]
            [slopp.store.render :as render]))

(defn web-enabled?
  "The D-web master opt-in, read off the candidate store's `capabilities`
  config: web.enabled = \"true\". Every web gate is inert without it — a
  store that never opts into HTTP is untouched (the adoption story)."
  [candidate]
  (= "true" (get-in candidate [:config "capabilities" :values "web.enabled"])))

(defn ^:export web-name-meta
  "The metadata on a stored form's NAME symbol, read off the node — no eval
  (D3 keeps metadata source-only truth). nil for unnamed/unparseable forms.
  THE reader for the `:web/*` declaration vocabulary; `slopp.rules.web` and
  the web gates both consume it."
  [e]
  (let [s (try (n/sexpr (:node e)) (catch Exception _ nil))]
    (when (and (seq? s) (symbol? (second s)))
      (meta (second s)))))

(defn ^:export web-endpoint-rows
  "Every `:web/path` form in `store`: `{:ns :name :form-id :meta}` rows —
  the single route traversal; the collision gate and `slopp.rules.web` both
  build on it. TEST namespaces are excluded: their endpoint-shaped forms
  are fixtures, not servable surface, and a fixture must neither report in
  query_routes nor claim a path against a production endpoint. A pure
  function of the store value."
  [store]
  (vec
   (for [nsx (sort (keys (:namespaces store)))
         :when (not (render/test-ns? nsx))
         e   (store/forms store nsx)
         :when (:name e)
         :let [m (web-name-meta e)]
         :when (:web/path m)]
     {:ns nsx :name (:name e) :form-id (:id e) :meta m})))

(defn ^:export web-performers
  "The app-declared performer vocabulary for `marker-key` (`:web/effect` or
  `:web/read`): {kind → performer qsym}. slopp interprets no domain
  vocabulary of its own — the store declares it, so this registry is a pure
  function of the forms; the undeclared-effect gate and `slopp.rules.web`
  both consume it."
  [store marker-key]
  (into {}
        (for [nsx (sort (keys (:namespaces store)))
              e   (store/forms store nsx)
              :when (:name e)
              :let [kind (get (web-name-meta e) marker-key)]
              :when kind]
          [kind (symbol (str nsx) (str (:name e)))])))

(defn ^:export ^{:rule/applies-to :production} web-auth-refusal
  "The default-deny auth gate (D-web): a `:web/path` endpoint with NO
  `:web/auth` declaration is refused — `:public` must be typed out, so an
  unsecured route is always a visible decision, never an omission. Inert
  until the store opts into HTTP (`web-enabled?`). Returns a teaching
  string, or nil when clean."
  [candidate ns-sym form-name]
  (when (web-enabled? candidate)
    (when-let [e (store/form-named candidate (symbol (str ns-sym)) (symbol (str form-name)))]
      (let [m (web-name-meta e)]
        (when (and (:web/path m) (not (contains? m :web/auth)))
          (str ns-sym "/" form-name " declares the route " (pr-str (:web/path m))
               " but no :web/auth — every endpoint declares its policy"
               " (default-deny): add :web/auth :public (deliberately open),"
               " :authenticated, or [:group \"<name>\"] to the name metadata;"
               " groups live in the capabilities config (query_capabilities)"))))))

(defn ^:export ^{:rule/applies-to :production} web-route-collision
  "The route-uniqueness gate (D-web): a `:web/path` endpoint whose
  method+path another FORM already claims is refused at the write — a
  duplicate route is impossible by construction, not a startup surprise.
  The same form re-landing (a replace) is not a collision. Inert until
  `web-enabled?`. Returns a teaching string, or nil when clean."
  [candidate ns-sym form-name]
  (when (web-enabled? candidate)
    (when-let [e (store/form-named candidate (symbol (str ns-sym)) (symbol (str form-name)))]
      (let [m (web-name-meta e)]
        (when (:web/path m)
          (let [method (:web/method m)
                path   (str (:web/path m))
                other  (some #(when (and (not= (:form-id %) (:id e))
                                         (= method (:web/method (:meta %)))
                                         (= path (str (:web/path (:meta %)))))
                               %)
                             (web-endpoint-rows candidate))]
            (when other
              (str ns-sym "/" form-name " claims " method " " path
                   " but " (:ns other) "/" (:name other) " already serves it —"
                   " one method+path has one owner: change the path, change the"
                   " method, or extend the existing handler (query_routes lists"
                   " every claim)"))))))))

(defn ^:export web-context-builders
  "Every `^{:web/context true}` fn in the store, as qsyms, sorted — the
  app-declared sources of `:web/perform-ctx`, the map a handler receives as
  `:web/deps` and every performer receives as its first argument.

  PLURAL although exactly one is legal, because the SCAN and the singleton
  POLICY are different jobs and the two callers ask different questions: the
  `web-undeclared-context` write gate asks whether ANY exists,
  `slopp.rules.web/context-builder` asks for THE one and refuses two. Splitting
  them keeps a single definition of who builds the context — the alternative
  is two scans that agree until one gains a case."
  [store]
  (vec (for [nsx (sort (keys (:namespaces store)))
             e   (store/forms store nsx)
             :when (and (:name e) (get (web-name-meta e) :web/context))]
         (symbol (str nsx) (str (:name e))))))

(defn ^:export ^{:rule/applies-to :production} web-undeclared-effect
  "The effect-vocabulary gate (D-web): an endpoint declaring `:web/effects`
  kinds may only name kinds some `^{:web/effect <kind>}` performer provides
  — the dispatcher can only run effects the app defined, and a typo'd kind
  must fail at the write, not at the first request. Inert until
  `web-enabled?`. Returns a teaching string, or nil when clean."
  [candidate ns-sym form-name]
  (when (web-enabled? candidate)
    (when-let [e (store/form-named candidate (symbol (str ns-sym)) (symbol (str form-name)))]
      (let [m (web-name-meta e)
            kinds (seq (:web/effects m))]
        (when (and (:web/path m) kinds)
          (let [known (set (keys (web-performers candidate :web/effect)))
                missing (remove known kinds)]
            (when (seq missing)
              (str ns-sym "/" form-name " declares :web/effects "
                   (pr-str (vec missing)) " but no performer provides "
                   (if (= 1 (count missing)) "it" "them")
                   " — define one per kind: (defn ^{:web/effect "
                   (pr-str (first missing)) "} <name>! [ctx …] …), or reuse an"
                   " existing kind (query_routes lists the vocabulary)"))))))))

(defn ^:export ^{:rule/applies-to :production} web-unsafe-get
  "The HTTP-safety gate (D-web): a `:get`/`:head` endpoint must be SAFE in
  the RFC sense — it may neither declare `:web/effects` kinds
  (effects-as-data must not launder a mutating GET) nor reach a mutation
  directly (the D6 mutation set: `effectful-vars` with no external
  boundary, the same read `:internal`'s tier check uses). Inert until
  `web-enabled?`. Returns a teaching string, or nil when clean."
  [candidate ns-sym form-name]
  (when (web-enabled? candidate)
    (when-let [e (store/form-named candidate (symbol (str ns-sym)) (symbol (str form-name)))]
      (let [m (web-name-meta e)]
        (when (and (:web/path m) (#{:get :head} (:web/method m)))
          (cond
            (seq (:web/effects m))
            (str ns-sym "/" form-name " is a GET/HEAD endpoint but declares"
                 " :web/effects " (pr-str (vec (:web/effects m))) " — a safe"
                 " method must not mutate: make it :post/:put/:delete, or drop"
                 " the effects")

            (contains? (derive/effectful-vars
                        (analyze/analyze (render/render-ns candidate ns-sym))
                        nil nil)
                       (symbol (str ns-sym) (str form-name)))
            (str ns-sym "/" form-name " is a GET/HEAD endpoint but reaches a"
                 " mutation — a safe method must not mutate: move the write"
                 " behind a :post/:put/:delete endpoint's :web/effects, or"
                 " return the change as data")))))))

(defn ^:export ^{:rule/applies-to :production} web-unknown-group
  "The policy-vocabulary gate (D-web): an endpoint's `:web/auth` may only
  name groups the `capabilities` config defines
  (`web.auth.groups.<name>.…` keys) — a typo'd group would silently deny
  every request forever, the authz twin of the nil-pun. Walks composite
  policies ([:any …]/[:all …]). Inert until `web-enabled?`. Returns a
  teaching string, or nil when clean."
  [candidate ns-sym form-name]
  (when (web-enabled? candidate)
    (when-let [e (store/form-named candidate (symbol (str ns-sym)) (symbol (str form-name)))]
      (let [m (web-name-meta e)]
        (when (:web/path m)
          (let [known (into #{}
                            (keep #(second (re-matches #"web\.auth\.groups\.([^.]+)\..*" (str %))))
                            (keys (get-in candidate [:config "capabilities" :values] {})))
                named (fn named [p]
                        (cond
                          (and (vector? p) (= :group (first p))) [(second p)]
                          (and (vector? p) (#{:any :all} (first p))) (mapcat named (rest p))
                          :else nil))
                missing (remove known (named (:web/auth m)))]
            (when (seq missing)
              (str ns-sym "/" form-name " grants by group "
                   (pr-str (vec missing)) " but the capabilities config"
                   " defines no such group"
                   (when (seq known)
                     (str " (configured: " (str/join ", " (sort known)) ")"))
                   " — config_file {path \"capabilities\" key \"web.auth.groups."
                   (first missing) ".members\" value \"…\"} defines it, or fix"
                   " the name"))))))))

(defn ^:export web-generated-ns
  "The generated-client protection gate (D-web-contracts part 2): a form marked
  ^{:generated \"<endpoint>\"} is OUTPUT of generate_client and must not be
  hand-edited. Regeneration rewrites the whole client namespace (through
  store/ingest, BELOW this gate layer — so the generator itself is unaffected;
  only edit-tool writes reach here). Returns a teaching string naming the source
  endpoint + generate_client, or nil. To take manual ownership of a generated
  form, strip its ^:generated marker first. Not web-gated — the marker alone
  arms it."
  [candidate ns-sym form-name]
  (when-let [e (store/form-named candidate (symbol (str ns-sym)) (symbol (str form-name)))]
    (when-let [g (:generated (web-name-meta e))]
      (str ns-sym "/" form-name " is GENERATED (from endpoint " g
           ") and must not be hand-edited — generate_client rewrites the whole"
           " client namespace from the endpoint schemas, so an edit here is lost"
           " on the next generate. Change the ENDPOINT's :web/request/:web/response"
           " and re-run generate_client; to take manual ownership, strip the"
           " ^:generated marker first."))))

(defn ^:export client-signature
  "A deterministic fingerprint of the store's web endpoint CONTRACTS — the raw
   {:ns :name :method :path :web/request :web/response} of every endpoint — so a
   done-advisory can tell whether the generated typed client (generate_client) is
   stale WITHOUT re-rendering or parsing it. generate_client records this on the
   `client`/`generated-sig` config at generation; the staleness advisory compares
   the recorded value with the current one. A pure function of the store value."
  [store]
  (str (hash (mapv (fn [{:keys [ns name meta]}]
                     [(str ns) (str name) (:web/method meta) (:web/path meta)
                      (pr-str (:web/request meta)) (pr-str (:web/response meta))])
                   (web-endpoint-rows store)))))

(defn ^:export ^{:rule/applies-to :production} web-endpoint-schema
  "The API-contract gate (D-web-contracts): a `:web/path` endpoint must type out
  its contract so the client validates against the SAME schema. `:web/response`
  is required on EVERY endpoint; `:web/request` is required on a BODY method
  (`:post`/`:put`/`:patch`) — a `:get`/`:delete`/`:head` needs only a response.
  Declare a `.cljc` malli schema VAR (shareable/reusable — `some.contracts/order`)
  or an inline `[:map …]` for a one-off shape. Inert until the store opts into
  HTTP (`web-enabled?`); auth is checked first, so a naked endpoint still refuses
  on `:web/auth` before this. Returns a teaching string, or nil when clean."
  [candidate ns-sym form-name]
  (when (web-enabled? candidate)
    (when-let [e (store/form-named candidate (symbol (str ns-sym)) (symbol (str form-name)))]
      (let [m (web-name-meta e)]
        (when (:web/path m)
          (let [body?   (contains? #{:post :put :patch} (:web/method m))
                missing (cond-> []
                          (not (contains? m :web/response)) (conj :web/response)
                          (and body? (not (contains? m :web/request))) (conj :web/request))]
            (when (seq missing)
              (str ns-sym "/" form-name " declares the route " (pr-str (:web/path m))
                   " but no " (str/join " / " (map str missing))
                   " — every endpoint types out its contract so the client"
                   " validates against the SAME schema (D-web-contracts). Add "
                   (str/join " and " (map str missing))
                   " to the name metadata: a .cljc malli schema VAR"
                   " (shareable/reusable, e.g. some.contracts/order) or an inline"
                   " [:map …] for a one-off shape."))))))))

(defn web-react-attrs
  "Per-form write gate (D-web-html): a literal hiccup element carrying a
  React attribute name — `:className`, `:htmlFor`, an `:onClick`-style
  handler, `:dangerouslySetInnerHTML`. Browsers silently IGNORE unknown
  attributes, so the mistake ships and does nothing. Scoped to maps in
  position 2 of a keyword-tag vector (a JSON-ish payload map is not an
  element); inert until `web-enabled?`. Returns a teaching string, or nil."
  [candidate ns-sym form-name]
  (when (web-enabled? candidate)
    (when-let [e (store/form-named candidate ns-sym form-name)]
      (let [react->fix {:className ":class"
                        :htmlFor ":for"
                        :dangerouslySetInnerHTML "[:html/raw \"…\"] (string literal only)"}
            handler? (fn [k] (re-matches #"on[A-Z].*" (name k)))
            sx (try (n/sexpr (:node e)) (catch Exception _ nil))
            hit (first (for [v (tree-seq coll? seq sx)
                             :when (and (vector? v)
                                        (keyword? (first v))
                                        (map? (second v)))
                             k (keys (second v))
                             :when (and (keyword? k) (nil? (namespace k))
                                        (or (react->fix k) (handler? k)))]
                         k))]
        (when hit
          (str "React attribute " hit " in a hiccup element — "
               (if-let [fix (react->fix hit)]
                 (str "use " fix)
                 (str "server-rendered pages have no event handlers; "
                      "a link or form targeting an endpoint replaces it"))
               ". Browsers silently ignore unknown attributes, so this would "
               "ship and do nothing."))))))

(defn ^:export ^{:rule/applies-to :production} web-undeclared-context
  "The context-SOURCE gate (D-web): an endpoint whose body reads `:web/deps`
  may only do so in a store that declares where those deps come from — one
  `^{:web/context true}` zero-arg fn. The sibling of `web-undeclared-effect`:
  an effect kind needs a marked performer, and the context needs a marked
  builder. Inert until `web-enabled?`. Returns a teaching string, or nil.

  This gate is the reason the context is a MARKER rather than a capability
  naming a qualified symbol. With the declaration in the store, both halves
  are visible statically — the handlers that read `:web/deps`, and whether
  anything claims to build it — so the failure moves to the write that caused
  it. A capability is a string in config, checkable at boot at the earliest,
  which is after the browser has already seen the 500.

  Scoped to `:web/path` ENDPOINTS, not to every form naming the keyword: the
  framework's own dispatcher assigns `:web/deps` onto the request, and gating
  that would refuse writes to slopp's `slopp.web.dispatch/handle!`.

  **The teaching is three clauses and stops** — what, the consequence, and the
  fix as a LITERAL FORM. A cold read (slopp-ui, hitting this unprepared and
  deliberately not opening the SKILL first) reported the literal form as the
  decisive part: the marker spelling, the arity, `defn`-not-`def` and the
  return shape all come off it at once, so no step sends the reader looking.
  Two clauses were CUT on that evidence and should not come back:

  - **\"it cannot be a performer\"** — the right sentence in the wrong room. It
    answers a DESIGN question to a reader in fix-it mode who has already been
    handed the form, and it is the only clause that requires knowing what a
    performer is. It lives in `slopp.rules.web/context-builder`'s docstring and
    the SKILL, where someone deciding meets it.
  - **the lifecycle framed around done points and the managed server** — this
    gate fires on any `web.enabled` store, including one with `dev.server`
    false where no managed server boots at all. Told to that reader it
    asserts a behaviour that does not happen to them: a general truth
    delivered in this store's voice, which is Core 9 one notch down. What is
    left says the same thing unconditionally — anything the builder allocates
    is new each time it RUNS.

  The lifecycle clause STAYS, though, and the test is why: it changes what
  someone writes rather than what they understand. The obvious builder is
  whatever the app's own `serve!` already constructs, moved — which is exactly
  the shape that silently empties."
  [candidate ns-sym form-name]
  (when (web-enabled? candidate)
    (when-let [e (store/form-named candidate (symbol (str ns-sym)) (symbol (str form-name)))]
      (when (and (:web/path (web-name-meta e))
                 (empty? (web-context-builders candidate)))
        (let [sx    (try (n/sexpr (:node e)) (catch Exception _ nil))
              nodes (tree-seq coll? seq sx)]
          (when (or (some #(= :web/deps %) nodes)
                    (some #(and (map? %) (some #{'deps} (:web/keys %))) nodes))
            (str ns-sym "/" form-name " reads :web/deps, but this store declares"
                 " no context builder — so the map would arrive nil, which either"
                 " 500s or, worse, answers 200 with an empty body. Declare exactly"
                 " ONE zero-arg builder: (defn ^{:web/context true} app-context []"
                 " {…}). Anything it allocates is new each time it runs, so keep"
                 " live state outside it.")))))))
