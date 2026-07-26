(ns slopp.web.routes)

(defn ^:export spa-rows
  "The catch-all rows a client-routed document contributes — one per declared
  prefix in `:web/spa`, all pointing at the same handler as `row`.

  A client-routed app owns paths the server has no route for: `/store/ns/foo`
  is real to the browser and meaningless to the router, so refreshing it 404s.
  The obvious fix — a catch-all at the root — is worse than the bug: it serves
  the app document for EVERY unmatched path, and an app that can never 404 has
  no way left to distinguish a typo from a page.

  So the fallback is DECLARED and SCOPED. `:web/spa [\"/store\"]` says \"I am
  the document for client routes under /store\", and paths outside every
  declared prefix still 404 exactly as before.

  No new matching rules are needed. The router already ranks a trailing
  catch-all far below both a static segment and a single-segment capture, so
  these rows cannot steal a real route no matter what order they are in —
  `/store/ns/:ns` still wins over `/store/*`.

  Note the prefix ROOT is not covered: `[\"/store\"]` generates `/store/*path`,
  which needs at least one segment below it. If `/store` itself should render
  the app, that is an ordinary route the app declares — an intent, not a
  side effect of a fallback."
  [row prefixes]
  (vec (for [p prefixes]
         (assoc row :path (str p "/*spa-path")))))

(defn ^:export from-namespaces
  "Route rows from the loaded namespaces' public vars carrying `:web/path`
  metadata — the UNIVERSAL route source: a live store, a jar, and a native
  binary all answer from var metadata, the same contract query_routes reads
  off the stored node. A namespace that isn't loaded contributes no rows.
  Rows: {:handler <the var, callable> :method :path :auth :web/effects
  :web/reads :effectful?}.

  A var may also carry `:web/spa` — a vector of path prefixes it serves as the
  client-routed document — and then contributes one extra catch-all row per
  prefix (see `spa-rows`), so a refreshed deep link reaches the app instead of
  a 404. Scoped deliberately: paths outside every declared prefix still 404,
  which is the property a root catch-all would destroy."
  [ns-syms]
  (vec
   (mapcat
    (fn [{:keys [row spa]}] (if (seq spa) (cons row (spa-rows row spa)) [row]))
    (for [ns-sym ns-syms
          :let   [nsx (find-ns (symbol ns-sym))]
          :when  nsx
          v      (vals (ns-publics nsx))
          :let   [m (meta v)]
          :when  (:web/path m)]
      {:spa (:web/spa m)
       :row {:handler   v
             :method    (:web/method m)
             :path      (str (:web/path m))
             :auth      (:web/auth m)
             :web/effects (:web/effects m)
             :web/reads   (:web/reads m)
             :effectful? (boolean (:web/effectful m))}}))))

(defn ^:export performers-from-namespaces
  "The performer vocabulary off loaded var metadata: {kind → the var,
  callable} for `marker-key` (`:web/effect` or `:web/read`) — the runtime
  twin of the store-side derivation the gates check. A namespace that
  isn't loaded contributes nothing."
  [ns-syms marker-key]
  (into {}
        (for [ns-sym ns-syms
              :let   [nsx (find-ns (symbol ns-sym))]
              :when  nsx
              v      (vals (ns-publics nsx))
              :let   [kind (get (meta v) marker-key)]
              :when  kind]
          [kind v])))
