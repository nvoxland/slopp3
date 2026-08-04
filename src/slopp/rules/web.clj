(ns slopp.rules.web
  "What the store can SAY about its own web surface, derived from the forms.

  `slopp.web` is the framework an app runs on and knows nothing about stores.
  This is the other direction: reading a store's `:web/*` metadata as data, so
  the route table, the read/effect vocabularies and the rendered link inventory
  are answerable without starting a server. The web write gates and
  `query_routes` are both this, which is deliberate — a rule that refuses at the
  write and a report that lists the surface must agree, and they only can if
  they are one derivation.

  Pure functions of the store value, which is what makes them true on every
  branch and after every merge rather than at whatever moment a server last
  booted.

  **The recurring difficulty is that a rendered path is not a call.** A link is a
  string; nothing resolves it, so a typo and a legitimate handoff are the same
  token. Everything here that classifies one — `:exact`/`:prefix`/`:unresolved`,
  and the `:web/external-path` vs `:web/client-path` split — is an attempt to
  keep those apart, and each distinction was added because collapsing it made a
  report state something false. Prefer adding a category over widening one."
  (:require [slopp.project.capabilities :as capabilities]
            [slopp.web.router :as router] [slopp.store :as store] [slopp.store.render :as render] [clojure.string :as str] [rewrite-clj.node :as n] [slopp.edit.web :as web] [rewrite-clj.parser :as p]))

(defn endpoints
  "Every declared endpoint in the store — a `:web/path` form's route row:
  `{:handler :ns :name :form-id :method :path :auth :web/effects :web/reads
  :schema? :effectful?}` (slopp's own vocabulary keys stay namespaced —
  the same rule the request envelope follows). Built on the SAME traversal
  the write gates check (`modules/web-endpoint-rows`), so what query_routes
  shows is what the gates enforced. A pure function of the store value."
  [store]
  (mapv (fn [{:keys [ns name form-id meta]}]
          {:handler   (symbol (str ns) (str name))
           :ns        ns
           :name      name
           :form-id   form-id
           :method    (:web/method meta)
           :path      (str (:web/path meta))
           :auth      (:web/auth meta)
           :web/effects (:web/effects meta)
           :web/reads   (:web/reads meta)
           ;; the CONTRACT the endpoint-schema gate enforces (D-web-contracts) —
           ;; this used to read :malli/schema, a different key, so every
           ;; contract-carrying endpoint reported :schema? false
           :web/request  (:web/request meta)
           :web/response (:web/response meta)
           ;; the client-route prefixes this document also answers for, when it
           ;; declares any. Surfaced rather than expanded into synthetic
           ;; catch-all rows: query_routes should show what the author
           ;; DECLARED, and three `/store/*spa-path` rows would read as
           ;; surface nobody wrote.
           :web/spa   (:web/spa meta)
           :schema?   (contains? meta :web/response)
           :effectful? (boolean (:web/effectful meta))})
        (web/web-endpoint-rows store)))

(defn performers
  "The app-defined performer vocabulary for `marker-key` (`:web/effect` or
  `:web/read`): {kind → performer qsym}. Delegates to the SAME derivation
  the undeclared-effect gate checks (`modules/web-performers`)."
  [store marker-key]
  (web/web-performers store marker-key))

(def ^:private url-attrs
  "Hiccup tag → the attributes that name a URL ON THAT ELEMENT, per HTML.
   `:href` is a URL on <a>, <link>, <area> and <base>; `:action` on <form>;
   `:src` on the elements that FETCH one — script, img, iframe, source,
   track, embed, audio, video. Everywhere else these are inert attributes the
   browser ignores, so a map carrying one is not a route reference — it is
   ordinary data that happens to share a key name. This table is why
   `link-refs` needs no heuristics: the question 'is this a link' is answered
   by the HTML spec, not by guessing.

   `:src` was missing until 2026-07-25, and the omission was not academic:
   slopp's OWN reviewer UI carried `[:script {:src \"/assets/cljs/main.js\"}]`
   in the shell of every page, served by nothing, 404ing on every request
   since the wave that added it — and `web-dangling-route-refs`, the gate
   built to fail `done` on exactly that, could not see it. A fetched URL is
   as dangling as a clicked one; the browser just fails more quietly."
  {:a #{:href} :link #{:href} :area #{:href} :base #{:href}
   :form #{:action}
   :script #{:src} :img #{:src} :iframe #{:src} :source #{:src}
   :track #{:src} :embed #{:src} :audio #{:src} :video #{:src}})

(defn- hiccup-tag
  "The ELEMENT of a hiccup tag keyword, with hiccup's `#id` / `.class` sugar
   stripped — `:a#main.big` and `:a.nav` are both `:a`. nil for anything that
   isn't a keyword, so a non-element vector answers no element at all."
  [x]
  (when (keyword? x)
    (keyword (first (str/split (name x) #"[#.]")))))

(defn- link-refs
  "Route references in one form's SEXPR: the URL-bearing attribute of a hiccup
  element that HAS one. Root-relative string values are :exact; (str \"/lit\" …)
  with a root-relative literal first arg is :prefix; other dynamic values are
  :unresolved. Absolute URLs (scheme or //), anchors, and non-root-relative
  strings are not route references at all. :action takes its method from the
  same map's :method attr (default :get); :href is always :get.

  THE TAG DECIDES — see `url-attrs`. Reading instead \"any map in this form with
  an :href key\" was a coincidence test: it made `{:op :add :action :replace}` a
  route reference, 16 of them store-wide, none dischargeable by anyone. The
  attribute map must also sit in hiccup ATTRIBUTE position (second element,
  after the tag), which is what an attr map IS. Grounding in the HTML spec is
  not a tighter heuristic, it is the actual question, so there is no residue
  left to shave. A map assembled elsewhere and passed in by name is missed —
  the right side to err on: a missed ref costs a 404 nobody was told about, a
  false one costs every reader of every done."
  [sexpr]
  (for [v     (filter vector? (tree-seq coll? seq sexpr))
        :when (map? (second v))
        :let  [m (second v)]
        attr  (url-attrs (hiccup-tag (first v)))
        :when (contains? m attr)
        :let [val (get m attr)
              method (if (= :action attr)
                       (let [mv (get m :method "get")]
                         (keyword (str/lower-case (if (keyword? mv) (name mv) (str mv)))))
                       :get)
              ref (cond
                    (string? val)
                    (when (and (str/starts-with? val "/")
                               (not (str/starts-with? val "//")))
                      {:kind :exact :path val})

                    (and (seq? val) (= 'str (first val)) (string? (second val))
                         (str/starts-with? (second val) "/")
                         (not (str/starts-with? (second val) "//")))
                    {:kind :prefix :path (second val)}

                    (nil? val) nil

                    :else {:kind :unresolved :value (pr-str val)})]
        :when ref]
    (assoc ref :attr attr :method method)))

(defn ui-route-refs
  "Every route REFERENCE the store's forms render: literal :href/:action
  attrs classified :exact / :prefix / :unresolved, each row carrying the
  qualified :form. A pure function of the forms (the keyword-inventory
  property) — correct on every branch, after every merge, at any revision.
  Test namespaces are fixtures.

  TWO markers skip a form whole, and the difference between them is the whole
  point of having two:

  - `^{:web/external-path \"why\"}` — the target is served by something OUTSIDE
    this store (nginx, another service). A genuine crossing, honest about it.
  - `^{:web/client-path \"why\"}` — the target is THIS app's own path, and the
    literal is a key the CLIENT router parses. Nothing serves it as written:
    the render adds the mount point first, and a `:web/spa` fallback answers it.

  One marker used to serve both, and the crossings inventory then reported an
  SPA's own screens as leaving for \"somebody else's server\" — seven forms of
  false statement in the one report someone reads to find out what is NOT
  checked here. Widening the old marker's meaning would have kept the lie;
  teaching the check to SEE the prefixing is not possible in general, because
  the base arrives through an ordinary function call."
  [store]
  (vec
   (for [nsx (sort (keys (:namespaces store)))
         :when (not (render/test-ns? nsx))
         e (store/forms store nsx)
         :when (:name e)
         :let [sx (try (n/sexpr (:node e)) (catch Exception _ nil))
               mt (when (seq? sx) (meta (second sx)))]
         :when (and sx
                    (not (:web/external-path mt))
                    (not (:web/client-path mt)))
         ref (link-refs sx)]
     (assoc ref :form (symbol (str nsx) (str (:name e)))))))

(defn ^:export routes-report
  "The `query_routes` payload. `web.enabled` false → `{:enabled false
  :routes [] :note …}` — a store that never opted into HTTP has no web
  surface and no web rules (the adoption story). Enabled → every endpoint
  row (`endpoints`), each carrying `:rendered-by` (the forms whose
  `ui-route-refs` target it — exact refs through the router's matcher,
  prefix refs through the path pattern) when any do, plus the derived
  performer vocabularies (`:effect-kinds` / `:read-kinds`)."
  [store]
  (if-not (capabilities/effective store "web.enabled")
    {:enabled false :routes []
     :note (str "web.enabled is false — config_file {path \"capabilities\" "
                "key \"web.enabled\" value \"true\"} opts this store into HTTP")}
    (let [refs    (ui-route-refs store)
          renders (fn [row]
                    (->> refs
                         (filter (fn [{:keys [kind method path]}]
                                   (case kind
                                     :exact  (some? (router/match [row] method path))
                                     :prefix (str/starts-with? (str (:path row)) path)
                                     false)))
                         (map :form) distinct sort vec not-empty))]
      {:enabled true
       :routes (mapv #(if-let [r (renders %)] (assoc % :rendered-by r) %)
                     (endpoints store))
       :effect-kinds (set (keys (performers store :web/effect)))
       :read-kinds (set (keys (performers store :web/read)))})))

(defn dangling-route-refs
  "`ui-route-refs` joined against what the store actually serves: declared
  endpoints (through the router's matcher, so parameterized paths match),
  `web.static.*` mounts (an :exact path must map to a file that EXISTS on
  the manifest), and route/mount prefixes for :prefix refs. Returns
  `{:dangling [ref …] :unresolved [ref …]}` — dynamic refs are NAMED, never
  counted clean."
  [store]
  (let [refs   (ui-route-refs store)
        routes (endpoints store)
        ;; a trailing slash on the value is trimmed, because the join below adds its
        ;; own: `public/` would build `public//app.css`, which no manifest holds,
        ;; and every asset link in the app would read as dangling
        mounts (into {}
                     (keep (fn [[k v]]
                             (when-let [[_ m] (re-matches #"web\.static\.(.+)" (str k))]
                               [m (str/replace (str v) #"/+$" "")])))
                     (get-in store [:config "capabilities" :values]))
        static-file? (fn [path]
                       (some (fn [[url-prefix file-prefix]]
                               (and (str/starts-with? path (str url-prefix "/"))
                                    (some? (store/file-content
                                            store
                                            (str file-prefix "/"
                                                 (subs path (inc (count url-prefix))))))))
                             mounts))
        ;; a document declaring :web/spa answers for client routes BELOW each
        ;; prefix, so a link to /store/form/f1 is served even though no
        ;; endpoint declares that path. Scoped, exactly as the fallback rows
        ;; are: a path outside every prefix still dangles, which is the half
        ;; of this that keeps the gate worth having.
        spa-prefixes (into #{} (mapcat :web/spa) routes)
        client-route? (fn [path]
                        (some #(str/starts-with? path (str % "/")) spa-prefixes))
        served? (fn [{:keys [kind method path]}]
                  (case kind
                    :exact  (boolean (or (router/match routes method path)
                                         (static-file? path)
                                         (client-route? path)))
                    :prefix (boolean
                             (or (some #(str/starts-with? (str (:path %)) path) routes)
                                 (some (fn [[url-prefix _]]
                                         (str/starts-with? path (str url-prefix "/")))
                                       mounts)
                                 (client-route? path)))
                    false))]
    {:dangling   (vec (remove served? (remove #(= :unresolved (:kind %)) refs)))
     :unresolved (filterv #(= :unresolved (:kind %)) refs)}))

(defn- request-literals
  "The literal ring REQUESTS in one form's sexpr: maps carrying a string `:uri`,
   as `{:method :uri}`. `:request-method` gives the method (`:get` when a test
   omits it, matching ring). A map with a non-literal uri names no particular
   route and is skipped — there is nothing to join it to."
  [sexpr]
  (for [m (filter map? (tree-seq coll? seq sexpr))
        :let [uri (:uri m)]
        :when (and (string? uri) (str/starts-with? uri "/"))]
    {:uri uri
     :method (let [mv (get m :request-method :get)]
               (if (keyword? mv) mv (keyword (str/lower-case (str mv)))))}))

(defn ^:export endpoint-test-refs
  "`{qualified-endpoint-form #{qualified-test-form}}` — which tests exercise
   which declared endpoint, joined through the ROUTER over the literal ring
   requests test forms contain (`{:request-method :get :uri \"/todo/7\"}`).

   The tracer cannot see this edge: a test reaches a handler through
   `web/handle!`'s runtime route scan, so there is no static reference and no
   recorded evidence until the test has run once. Every endpoint write therefore
   reported `:no-covering-tests` while a red test aimed at exactly that route sat
   in the store. The route table IS static and `router/match` is the same matcher
   the server uses, so this join needs no heuristic.

   Erring toward INCLUSION is correct here, and is the opposite of the bar a RULE
   must clear (D-rule-grounding): this feeds test SELECTION, where an extra test
   costs seconds and a missed one costs a false green."
  [store]
  (let [routes (endpoints store)
        owner  (fn [{:keys [method uri]}]
                 (when-let [r (router/match routes method uri)]
                   (when (and (:ns r) (:name r))
                     (symbol (str (:ns r)) (str (:name r))))))]
    (reduce
     (fn [acc [test-sym reqs]]
       (reduce (fn [a req]
                 (if-let [e (owner req)] (update a e (fnil conj #{}) test-sym) a))
               acc reqs))
     {}
     (for [nsx  (sort (keys (:namespaces store)))
           :when (render/test-ns? nsx)
           e     (store/forms store nsx)
           :when (:name e)
           :let  [sx (try (n/sexpr (:node e)) (catch Exception _ nil))]
           :when sx]
       [(symbol (str nsx) (str (:name e))) (request-literals sx)]))))

(defn ^:export context-builder
  "The qsym of this store's `^{:web/context true}` fn — the zero-arg builder
  of `:web/perform-ctx` — or nil when the app declares none.

  The managed app server WRITES the `serve!` call, so it has to know how to
  build the context a handler receives as `:web/deps` and every performer
  receives as its first argument. That map is app-specific by definition (a
  registry, a pool, a connection), so the app must say — and a marker is how
  everything else in this framework is addressed.

  **A marker rather than a capability naming a qualified symbol.** A marker
  makes a GATE possible: both halves are then visible in the store — handlers
  destructuring `:web/deps`, and whether anything claims to build it — so
  \"this store takes `:web/deps` and declares no builder\" refuses at the WRITE
  rather than 500ing in a browser. That gate is
  `slopp.edit.web/web-undeclared-context`, and it is why this is a marker;
  a capability is a string in config, checkable for resolvability at boot,
  which is later and weaker, and it splits the declaration from the thing
  declared.

  The SCAN lives in `slopp.edit.web/web-context-builders` — shared with
  the gate, which asks whether ANY builder exists where this asks for THE one.
  What is here is the singleton POLICY, and only that.

  **It cannot be a performer**, and the idea is circular rather than merely
  wrong: performers already RECEIVE the perform-ctx as their first argument,
  so the context is strictly upstream of that vocabulary and cannot be a
  member of it. Written down because it is the obvious suggestion.

  **A SINGLETON**, unlike performers, which are keyed by kind because there
  are many. So two declarations is a refusal rather than a pick: choosing
  silently is how an app ends up running on deps it did not mean, and the
  failure would surface as a missing key three layers away."
  [store]
  (let [found (web/web-context-builders store)]
    (when (seq found)
      (when (next found)
        (throw (ex-info (str "a store declares exactly ONE ^{:web/context true}"
                             " builder; this one has " (count found) ": "
                             (str/join ", " found))
                        {:web/context-builders (vec found)})))
      (first found))))

(defn ^:export serving-namespaces
  "Every namespace that must be scanned to serve this store's web surface —
  the derived answer to `serve!`'s `:web/namespaces`, sorted.

  The union of two things the store already knows: the namespaces owning
  endpoint rows (`endpoints`), and the namespaces of the performer vars
  behind the effect/read vocabularies (`performers`). `-test` namespaces are
  excluded on both sides, the same rule `routes-report` applies — a test's
  endpoint-shaped form is a fixture, and serving it would mount a fake
  endpoint on the real app.

  Why derived rather than declared: `:web/namespaces` is the one REQUIRED
  opt on `serve!`, and `web/context`'s own docstring warns that \"a
  `:web/namespaces` list missing half the app assembles happily and
  answers\". A hand-kept list of what to serve IS that defect, held by every
  app that serves. The forgettable entry is a PERFORMER-only namespace: a
  route promising `:web/reads {:user [:user/by-id …]}` whose performer lives
  next door assembles into a context that throws `:web/missing-performers`,
  and the list is the only place that could have been wrong.

  Store-side on purpose. `slopp.web` requires nothing but `slopp.web.*` and
  must stay that way — it is what gets vendored into an app. So this is
  computed HERE and handed to the framework as data: directly by the dev
  server, and baked into the main `build!` emits."
  [store]
  (->> (concat (map :ns (endpoints store))
               (->> [:web/effect :web/read]
                    (mapcat #(vals (performers store %)))
                    (keep namespace)
                    (map symbol)))
       (remove nil?)
       (remove #(str/ends-with? (str %) "-test"))
       distinct
       sort
       vec))

(defn ^:export static-mounts
  "This store's `web.static.*` mounts as `{url-prefix manifest-prefix}`.

  The key's tail is the URL prefix and the value a files-manifest path
  prefix, so `web.static./assets = public` serves `public/logo.png` at
  `/assets/logo.png`. Feeds `slopp.web.static/mount-routes`, which pairs it
  with a reader — [[store-reader]] for a live store, and
  `slopp.web.static/file-or-resource-reader` for the managed dev server,
  whose child image has no store and reads materialized bytes instead.

  **Trailing slashes are trimmed on both sides, and that is not cosmetic.**
  `mount-routes` adds its own separator, so `public/` asks the reader for
  `public//main.js` — which a filesystem quietly normalises and a manifest
  lookup does not, making the same config work or 404 depending on which
  reader is behind it. Pinned by `web-test/static-mounts-serve-raw-bytes`.

  Exists because `cljs/served-by-a-mount?` had parsed this family privately,
  which left the managed server no way to ask the same question without a
  second parser of one config family."
  [store]
  (into {}
        (for [[k v] (get-in store [:config "capabilities" :values])
              :when (re-matches #"web\.static\..+" (str k))]
          [(str/replace (subs (str k) (count "web.static.")) #"/$" "")
           (str/replace (str v) #"/$" "")])))

(defn store-reader
  "The LIVE-store reader for `static/mount-routes`: resolve `path` through the
  store's manifest (text) or its content-addressed artifacts (bytes), falling
  back to `get-blob` for a sha the in-memory cache does not hold.

  `get-store` is a THUNK, not a store value, and that is the point of the
  seam: it is re-read per request, so under `--live` an edited asset serves
  without a rebuild. A store value captured once would freeze the tree at
  server start.

  The counterpart adapter is `static/file-or-resource-reader`, and the two
  answer the same port. They have diverged before — a mount prefix written
  `public/` asks for `public//app.css`, which a filesystem normalises away and
  a manifest lookup does not — so both are held to one suite,
  `slopp.web-test/reader-contract`. This existed as an anonymous fn inside
  `start-server!` and was therefore reachable only by booting a session and
  binding a port, which is why it had never been tested at all."
  [get-store get-blob]
  (fn [path]
    (let [{:keys [content content-type sha]} (store/file-content (get-store) path)]
      (when (or content sha)
        {:content (or content (get-blob sha))
         :content-type content-type}))))

(defn web-public-mutation-check
  "Done-advisory (D-web): a CHANGED endpoint whose policy is :public and
   which declares `:web/effects` kinds — a publicly-writable surface should
   be a decision someone made, not an omission. Fires per form with the
   declared kinds; inert until the store opts into HTTP (web.enabled).
   v1 reads the DECLARATION; a public endpoint mutating without declaring
   is web-unsafe-get's (GET) or the effects-vocabulary's territory."
  [_session st* changed]
  (when (= "true" (get-in st* [:config "capabilities" :values "web.enabled"]))
    (vec (keep (fn [fid]
                 (when-let [e (store/form-by-id st* fid)]
                   (let [m (web/web-name-meta e)]
                     (when (and (:web/path m)
                                (= :public (:web/auth m))
                                (seq (:web/effects m)))
                       {:form (symbol (str (store/ns-of-form-id st* fid))
                                      (str (:name e)))
                        :web/effects (vec (:web/effects m))}))))
               changed))))

(defn web-dangling-route-refs-check
  "Done-advisory (D-web-html): rendered links/forms targeting a path no
   declared route or static mount serves — the UI nil-pun: it ships and
   404s. Fires STORE-WIDE, like dead surface, because deleting a route
   dangles an UNCHANGED form's link. Inert until web.enabled. The
   `^{:web/external-path \\\"why\\\"}` marker on the rendering form discharges.

   Dynamic (`:unresolved`) refs ride along as `:severity :info` findings:
   listed at done, never status-flipping. They used to be omitted entirely
   — the only way to keep them from flipping an `:error` rule red — which
   hid the one part of this check a human has to judge."
  [_session st* _changed]
  (when (= "true" (get-in st* [:config "capabilities" :values "web.enabled"]))
    (let [{:keys [dangling unresolved]} (dangling-route-refs st*)]
      (vec (concat dangling
                   (map #(assoc % :severity :info) unresolved))))))

(defn web-stale-client-check
  "Done-advisory (D-web-contracts part 2): the generated typed client
   (generate_client) is STALE — an endpoint or its :web/request/:web/response
   changed since the client was last generated. Fires only once a client has been
   generated (a `client`/`generated-sig` is on record), so it never nags a store
   that has not opted into a generated client. Regenerating re-records the
   signature and clears it."
  [_session store _changed]
  (let [recorded (get-in store [:config "client" :values "generated-sig"])]
    (when (and recorded (not= recorded (web/client-signature store)))
      [{:web-stale-client true
        :teach (str "the generated typed client is out of date — an endpoint or its"
                    " :web/request/:web/response changed since generate_client last"
                    " ran. Re-run generate_client to re-derive the wrappers.")}])))

(defn web-inline-schema-dup-check
  "Done-advisory (D-web-contracts part 2): 2+ endpoints declare the SAME
   structured inline :web/request/:web/response schema — the DRY nudge toward the
   paved road. A shared shape should be a named .cljc schema VAR so server and
   client validate against ONE definition and a change lands in one place. Only
   structured (vector) inline schemas count — a bare keyword like :map is too
   trivial to extract. Fires once per duplicated shape."
  [_session store _changed]
  (let [inlines (for [{:keys [ns name meta]} (web/web-endpoint-rows store)
                      k     [:web/request :web/response]
                      :let  [v (get meta k)]
                      :when (vector? v)]
                  {:endpoint (symbol (str ns) (str name)) :schema v})
        dups    (->> inlines
                     (group-by :schema)
                     (keep (fn [[schema es]]
                             (let [eps (distinct (map :endpoint es))]
                               (when (>= (count eps) 2) [schema (vec eps)])))))]
    (for [[schema eps] dups]
      {:duplicate-inline-schema (pr-str schema)
       :endpoints eps
       :teach (str (count eps) " endpoints declare the identical inline schema "
                   (pr-str schema) " — extract it to a named .cljc schema var so"
                   " the server and the generated client validate against ONE"
                   " definition and a change lands once.")})))

(defn web-spa-consequences-check
  "Done-advisory: an endpoint gained `:web/spa` this episode — state what that
   changed, once.

   Declaring a client-routed prefix is the single biggest behavioural change
   available in one piece of metadata, and nothing said so. Before: a bad deep
   link under the prefix was a 404, resolved and refused by the server. After:
   the server serves the document (it cannot know the path is bad), the client
   fetches, gets its own 404, and renders a not-found screen. **The HTTP status
   for every path under that prefix changed from 404 to 200.**

   That is correct — it is what `:web/spa` is FOR — but it is a real semantic
   change that only surfaced here because two existing tests happened to assert
   the old status.

   Fires only for the episode that ADDED the declaration, like
   `shell-widening`: it asks once, while the reason is still in context, and
   cannot decay into a standing warning to scroll past. It teaches rather than
   checks, and the boundary inventory still reports `:spa/client-routing` as an
   UNCHECKED exit — nothing compares the client's route table to the server's,
   and a teach is not a check."
  [_session st* changed]
  (let [ds       (store/deltas st*)
        baseline (->> ds (filter #(= :done (:op %))) last :id)
        old-srcs (when baseline (store/sources-at st* baseline))
        spa?     (fn [form] (when (and (seq? form) (symbol? (second form)))
                              (:web/spa (meta (second form)))))]
    (vec (for [fid changed
               :let [e (store/form-by-id st* fid)]
               :when (and e (:name e))
               :let [new (store/form-sexpr (:node e))
                     old (some-> (get old-srcs fid) p/parse-string store/form-sexpr)
                     ps  (spa? new)]
               ;; only when the declaration is NEW: either the form is new, or
               ;; its previous version did not carry one
               :when (and ps (not (spa? old)))]
           {:form  (symbol (str (store/ns-of-form-id st* fid)) (str (:name e)))
            :teach (str "every path under " (pr-str ps) " now answers 200, not 404 —"
                        " the server serves this document for any path below the"
                        " prefix and NOT-FOUND moves into the client. Make sure the"
                        " client renders a not-found screen for a path its own"
                        " router does not know, or a bad deep link shows a blank"
                        " pane at a URL that looks valid. The prefix ROOT is not"
                        " covered by the fallback and still needs its own route")}))))
