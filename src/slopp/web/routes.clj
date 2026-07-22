(ns slopp.web.routes)

(defn ^:export from-namespaces
  "Route rows from the loaded namespaces' public vars carrying `:web/path`
  metadata — the UNIVERSAL route source: a live store, a jar, and a native
  binary all answer from var metadata, the same contract query_routes reads
  off the stored node. A namespace that isn't loaded contributes no rows.
  Rows: {:handler <the var, callable> :method :path :auth :web/effects
  :web/reads :effectful?}."
  [ns-syms]
  (vec
   (for [ns-sym ns-syms
         :let   [nsx (find-ns (symbol ns-sym))]
         :when  nsx
         v      (vals (ns-publics nsx))
         :let   [m (meta v)]
         :when  (:web/path m)]
     {:handler   v
      :method    (:web/method m)
      :path      (str (:web/path m))
      :auth      (:web/auth m)
      :web/effects (:web/effects m)
      :web/reads   (:web/reads m)
      :effectful? (boolean (:web/effectful m))})))

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
