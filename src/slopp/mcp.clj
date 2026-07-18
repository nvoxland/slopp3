(ns slopp.mcp
  "Minimal MCP transport (JSON-RPC 2.0 over stdio) exposing `slopp.api` as tools.
  The pure `handle` dispatch is the core (fully testable with plain maps);
  `serve!`/`-main` are the thin newline-delimited-JSON stdio loop.

  Tool names use underscores (MCP restricts names to [A-Za-z0-9_-]). This is the
  agent-facing surface — everything is form-addressed (ns/name), never file+line."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cheshire.core :as json]
            [slopp.api :as api]
            [slopp.db :as db] [slopp.sync :as sync] [clojure.edn :as edn] [slopp.mcp.tools :as tools] [slopp.mcp.smells :as smells] [slopp.git.server :as server] [slopp.api.branch :as branch]))

(def ^:private protocol-version "2024-11-05")

(def ^:private ^:dynamic *hint*
  "Optional one-line workflow hint, attached to map results (item 3)." nil)

(def ^:private ^:dynamic *spool-session*
  "Bound to the session during tools/call so `text` can spool full
  payloads it trims (the headroom pattern: agents get the gist, the full
  version stays retrievable via query_detail for a short while)." nil)
(def ^:private spool-cap 20)
(defn- spool!
  "Keep `full` retrievable for a while; returns its id. Session-scoped,
  FIFO-capped — short-term memory, not history (that's the store)."
  [session full]
  (swap! session update-in [::spool :n] (fnil inc 0))
  (let [n  (get-in @session [::spool :n])
        id (str "r" n)]
    (swap! session update-in [::spool :entries]
           (fn [es]
             (let [es (assoc (or es {}) id full)]
               (if (> (count es) spool-cap)
                 (dissoc es (str "r" (- n spool-cap)))
                 es))))
    id))
(defn- trim-failure-strings
  "Tool-specific heuristic: test-failure :expected/:actual/:message beyond
  700 chars carry their head + a size marker — the diagnosis-relevant part
  is virtually always at the front."
  [x]
  (if-not (and (map? x) (seq (get-in x [:test :failures])))
    x
    (update-in x [:test :failures]
               (fn [fs]
                 (mapv (fn [f]
                         (reduce (fn [f k]
                                   (let [v (get f k)]
                                     (if (and (string? v) (> (count v) 700))
                                       (assoc f k (str (subs v 0 700)
                                                       " …[+" (- (count v) 700) " chars]"))
                                       f)))
                                 f [:expected :actual :message]))
                       fs)))))
(def ^:private ^:ambient-ok strict-boundary?
  "When true, the response boundary (text!) THROWS on any file/line
  coordinate leak — the invariant 'agents never think in files' made
  mechanical. On across the wire test suite (a fixture flips it), off in
  production (zero cost). An atom, not a dynamic var: `binding` is
  dialect-banned, and the flag is process-global test state."
  (atom false))
(defn- boundary-leak
  "The FIRST filesystem-COORDINATE leak in agent-facing response `x` — a
  source `file.clj:line` in any string, or a `:row`/`:col` map key — else
  nil. Agents address by NAME + paste-ready snippet, never file/line, so a
  coordinate crossing the wire is a boundary bug. A bare build path (no
  `:line`) is not a coordinate and passes."
  [x]
  (letfn [(scan [v]
            (cond
              (string? v) (re-find #"[\w./-]*\.clj[cx]?:\d+" v)
              (map? v)    (or (when (some #{:row :col} (keys v))
                                (str "row/col key: "
                                     (pr-str (select-keys v [:row :col]))))
                              (some scan (keys v))
                              (some scan (vals v)))
              (coll? v)   (some scan v)
              :else       nil))]
    (scan x)))
(defn- text! [x]
  (when @strict-boundary?
    (when-let [leak (boundary-leak x)]
      (throw (ex-info (str "boundary leak — a file/line coordinate reached an"
                           " agent response: " leak " (agents address by name +"
                           " snippet, never file:line — anchor it)")
                      {:leak leak}))))
  (let [x       (cond
                  (and *hint* (map? x) (nil? (:hint x))) (assoc x :hint *hint*)
                  (and *hint* (string? x)) (str x "\n\n[hint] " *hint*)
                  :else x)
        full    (if (string? x) x (pr-str x))
        slimmed (let [t (trim-failure-strings x)]
                  (if (string? t) t (pr-str t)))
        out     (if (and (= full slimmed) (<= (count full) 8000))
                  full
                  (if-let [sess *spool-session*]
                    (let [id   (spool! sess full)
                          body (if (<= (count slimmed) 8000)
                                 slimmed
                                 (subs slimmed 0 8000))]
                      (str body "\n[trimmed — query_detail {:id \"" id
                           "\"} returns the full response]"))
                    (if (<= (count slimmed) 8000) slimmed full)))]
    {:content [{:type "text" :text out}]}))

(defn- red? [t]
  (and t (pos? (+ (:fail t 0) (:error t 0)))))

(defn- summarize
  "B1: a green-and-quiet edit result compresses to a terse shape (the Go
  baseline showed slopp's verbose green responses were the token loser).
  :error, NEW red failure detail, or NEW warnings return the full map — a
  red that carries only :still-red names (episode compression) stays
  TERSE. Source echoes are stripped EVERYWHERE (Q1); :untested is a terse
  FLAG; a zero-test verification says :coverage :none (Q8); the :type
  :summary tag is internal and never rides the wire."
  [r verbose?]
  (let [strip (fn [d] (if (map? d) (dissoc d :source :sources :node) d))
        r     (cond-> r
                (:delta r)        (update :delta strip)
                (seq (:deltas r)) (update :deltas (partial mapv strip)))]
    (if (or verbose? (:error r) (seq (:warnings r))
            (and (red? (:test r)) (seq (:failures (:test r)))))
      (update r :test #(if (map? %) (dissoc % :type) %))
      (let [t (:test r)]
        (cond-> {:ok true}
          (:delta r)    (assoc :delta (get-in r [:delta :id]))
          (:group r)    (assoc :group (:group r))
          (:deltas r)   (assoc :deltas (count (:deltas r)))
          (:renamed r)  (assoc :renamed (:renamed r))
          (:mentions r) (assoc :mentions (:mentions r))
          (:renamed-namespaces r) (assoc :renamed-namespaces (:renamed-namespaces r))
          (:forms r)    (assoc :forms (:forms r))
          (:untested r) (assoc :untested true)
          t             (assoc :test (cond-> {:ran (:test t 0) :pass (:pass t 0)
                                              ;; a run that executed NOTHING is unverified, not green — green must
                                              ;; mean tests ran and passed, or an agent learns to distrust
                                              ;; the status and re-run them by hand
                                              :status (cond (red? t)            :red
                                                            (zero? (:test t 0)) :unverified
                                                            :else               (:status t :green))
                                              :scope (:scope t)}
                                       (:staleness-detected t)  (assoc :staleness-healed true)
                                       (zero? (:test t 0))      (assoc :coverage :none)
                                       (red? t)                 (assoc :fail (+ (:fail t 0) (:error t 0)))
                                       (seq (:still-red t))     (assoc :still-red (:still-red t))
                                       (seq (:went-green t))    (assoc :went-green (:went-green t))))
          (:affected r) (assoc :affected (let [a (:affected r)]
                                           (if (= :all a) :all (count a))))
          (:hint r) (assoc :hint (:hint r))
          (:red-first r) (assoc :red-first (:red-first r))
          (:carried-errors r) (assoc :carried-errors (:carried-errors r))
          (:changed-nses r) (assoc :changed-nses (:changed-nses r))
          (:image-healed r) (assoc :image-healed true)
          (:existing-warnings r) (assoc :existing-warnings (:existing-warnings r)))))))

(defn parse-call-args
  "Tool arguments for the one-shot --call CLI: nil/blank → {}; \"@path\"
  reads the file first; the text parses as JSON or EDN (agents emit both)
  and must yield a map."
  [s]
  (let [s (if (and s (str/starts-with? s "@")) (slurp (subs s 1)) s)]
    (if (str/blank? s)
      {}
      (let [v (or (try (json/parse-string s true) (catch Exception _ nil))
                  (try (edn/read-string s) (catch Exception _ nil)))]
        (if (map? v)
          v
          (throw (ex-info (str "--call args must be a JSON or EDN map (or @file): "
                               s)
                          {})))))))
(defn- absorb-pending-intent!
  "Consume <dir>/.slopp/pending-intent when present. The plugin's prompt
  hook writes {\"session-id\": …, \"prompt\": …} (a bare string is
  accepted as prompt-only). The session ADOPTS the harness session id as
  its identity — unless SLOPP_AGENT pinned one — so every delta of one
  Claude session shares a key and concurrent sessions never merge
  episodes; the prompt is stashed as the next auto-turn's intent."
  [session]
  (when-let [dir (:dir @session)]
    (let [f (io/file dir ".slopp" "pending-intent")]
      (when (.exists f)
        (let [raw (slurp f)]
          (.delete f)
          (let [{:keys [sid prompt]}
                (or (try (let [m (json/parse-string raw true)]
                           (when (map? m)
                             {:sid (:session-id m) :prompt (:prompt m)}))
                         (catch Exception _ nil))
                    {:prompt raw})]
            (when (and sid (not (:env-agent? @session)))
              (swap! session assoc :agent-id sid))
            (when-not (str/blank? (or prompt ""))
              (swap! session assoc :pending-intent prompt :last-intent prompt))))))))
(def ^:private env-handlers!
  "call-tool dispatch \u2014 deps/branches/build/help (Q4: the stable dispatch tail lives in\n  per-group handler maps of (fn [session a sym]); call-tool keeps only the\n  hot query/edit clauses)."
  {"deps_add"
   (fn [session a sym]
     (text! (api/deps-add! session (sym :lib)
                                        (or (:coord a)
                                            (when (:version a)
                                              {:mvn/version (:version a)}))
                                        :agent (:agent a))))
   "deps_remove"
   (fn [session a sym]
     (text! (api/deps-remove! session (sym :lib)
                                           :agent (:agent a))))
   "deps_list"
   (fn [session _a _sym]
     (text! (api/deps-list session)))
   "deps_pure"
   (fn [session a sym]
     (text! (if (false? (:pure a))
                           (api/deps-unpure! session (sym :target) :agent (:agent a))
                           (api/deps-pure! session (sym :target) :agent (:agent a)))))
   "branch_create"
   (fn [session a _sym]
     (text! (branch/branch! session (:name a))))
   "branch_switch"
   (fn [session a _sym]
     (text! (branch/branch-switch! session (:name a))))
   "branch_merge"
   (fn [session a _sym]
     (text! (branch/branch-merge! session (:name a))))
   "branch_delete"
   (fn [session a _sym]
     (text! (branch/branch-delete! session (:name a))))
   "query_branches"
   (fn [session _a _sym]
     (text! (branch/query-branches session)))
   "restart"
   (fn [session _a _sym]
     (api/restart! session)
     (text! "restarted"))
   "build"
   (fn [session a _sym]
     (text! (api/build! session (:dir a)
                                    :main (some-> (:main a) symbol)
                                    :name (:name a))))
   "help"
   (fn [_session _a _sym]
     (text! tools/cheat-sheet))})
(def ^:private file-handlers!
  "call-tool dispatch \u2014 tracked files + config (Q4: the stable dispatch tail lives in\n  per-group handler maps of (fn [session a sym]); call-tool keeps only the\n  hot query/edit clauses)."
  {"config"
   (fn [session a _sym]
     (text! (api/config! session (:key a) (:value a))))
   "file_put"
   (fn [session a _sym]
     (text! (api/file-put! session (:path a) (:content a)
                                        :prompt (:prompt a) :agent (:agent a))))
   "file_remove"
   (fn [session a _sym]
     (text! (api/file-remove! session (:path a)
                                           :prompt (:prompt a) :agent (:agent a))))
   "file_list"
   (fn [session _a _sym]
     (text! (api/files-list session)))
   "file_get"
   (fn [session a _sym]
     (text! (api/file-get session (:path a) :at (:at a))))
   "file_history"
   (fn [session a _sym]
     (text! (api/file-history! session (:path a))))
   "config_file"
   (fn [session a _sym]
     (text! (api/config-file! session (:path a)
                                           :key (:key a) :value (:value a)
                                           :unset (:unset a) :format (:format a)
                                           :prompt (:prompt a) :agent (:agent a))))
   "module_dep"
   (fn [session a _sym]
     (text! (api/module-dep! session (:from a) (:to a)
                             :remove (:remove a)
                             :prompt (:prompt a) :agent (:agent a))))
   "module_purity"
   (fn [session a _sym]
     (text! (api/module-tier! session (:module a) (:tier a)
                              :prompt (:prompt a) :agent (:agent a))))})
(def ^:private sync-handlers!
  "call-tool dispatch \u2014 git publish/absorb + remotes (Q4: the stable dispatch tail lives in\n  per-group handler maps of (fn [session a sym]); call-tool keeps only the\n  hot query/edit clauses)."
  {"git_push"
   (fn [session a _sym]
     (text! (if-let [dir (:dir @session)]
              (if (.exists (io/file dir ".git"))
                (sync/mirror-push! dir :url (:url a) :token (:token a)
                                   :branches (or (:branches a)
                                                 (some-> (:branch a) vector)
                                                 [(:branch @session "main")]))
                ;; fileless store: publish the projection directly
                (sync/push! dir :url (:url a) :token (:token a)
                            :branch (:branch @session "main")))
              {:error "git_push needs a durable session (a store dir)"})))
   "git_clone"
   (fn [_session a _sym]
     (text! (sync/clone! (:url a) (:dir a)
                                      :token (:token a) :agent (:agent a))))
   "git_pull"
   (fn [session a _sym]
     (text! (if-let [dir (:dir @session)]
              (let [m (sync/mirror-pull! dir :url (:url a) :token (:token a)
                                         :branches (or (:branches a)
                                                       [(:branch @session "main")]))
                    p (when-not (:error m)
                        (try (sync/pull! session :token (:token a)
                                         :agent (:agent a))
                             (catch Exception e {:error (ex-message e)})))]
                (cond-> m p (assoc :absorbed p)))
              {:error "git_pull needs a durable session (a store dir)"})))
   "git_conflicts"
   (fn [session _a _sym]
     (text! (if-let [dir (:dir @session)]
              {:conflicts (sync/conflicts dir)}
              {:error "git_conflicts needs a durable session"})))
   "git_resolve"
   (fn [session a _sym]
     (text! (if-let [dir (:dir @session)]
                           (sync/resolve! dir (:path a))
                           {:error "git_resolve needs a durable session"})))
   "query_git"
   (fn [session _a _sym]
     (text! (let [ext (when-let [conn (:db @session)]
                           (when-let [r (db/get-meta conn "git-remote")]
                             {:git-remote   r
                              :git-base-sha (db/get-meta conn "git-base-sha")}))]
                       (cond
                         (:git-url @session)
                         (cond-> {:url (:git-url @session)
                                  :remote (str "git remote add slopp " (:git-url @session))
                                  :note (str "milestones (commit_point) are the commits; "
                                             "the local listener is read-only clone/fetch; "
                                             "publish OUT with git_push; "
                                             "wip/<branch> = live un-milestone'd state")}
                           ext (assoc :external ext))

                         ext
                         {:external ext
                          :note "no local listener; git_push publishes to :external"}

                         :else
                         {:error (str "no git listener on this session"
                                      " (ephemeral session, or the port"
                                      " couldn't bind)")}))))
   "query_commits"
   (fn [session _a _sym]
     (text! (let [rows (api/query-commits session)
                  conn (:db @session)
                  al   (when (and conn (:dir @session))
                         (sync/alignment (:dir @session) "."
                                         (str "slopp/" (:branch @session))
                                         rows))]
              (if al
                {:commits rows :alignment al}
                rows))))
   "merge_from"
   (fn [session a _sym]
     (text! (branch/merge! session (:dir a))))})
(def ^:private tail-handlers!
  "Every handler-map entry (Q4) \u2014 call-tool checks here first."
  (merge env-handlers! file-handlers! sync-handlers!))
(defn- told!
  "Knowledge-differential reads: the session keeps a hash of every
  cacheable VIEW it has sent; an identical re-read returns a tiny
  :unchanged stub instead of the payload. Re-fetching becomes FREE, so
  agents never carry views in context 'just in case' — the whole
  don't-hoard stance depends on cheap re-asks. Any store change alters
  the payload, so staleness is impossible by construction."
  [session tool a payload]
  (let [k [tool (select-keys a [:ns :name :targets :since :detail :depth
                                :limit :contains :full :at :collapse :format
                                :on :direction])]
        h (hash payload)]
    (if (and (= h (get-in @session [::told k]))
             (< 130 (count (pr-str payload))))
      {:unchanged true
       :view (str tool (when (:ns a) (str " " (:ns a)))
                  (when (:name a) (str "/" (:name a))))
       :note "identical to what this session already received"}
      (do (swap! session assoc-in [::told k] h)
          payload))))
(defn- call-tool! [session {:keys [name arguments]}]
  (api/sync-with-journal! session)      ; m5b: absorb other servers' commits
  (absorb-pending-intent! session)
  (when (and (:require-turns? @session)
             (contains? tools/write-tools name)
             ;; done/commit_point CLOSE work; always allowed
             (not (#{"done" "commit_point"} name)))
    (let [ag (or (:agent arguments) (:agent-id @session))]
      (when-not (api/turn-open? session ag)
        (if-let [intent (:pending-intent @session)]
          ;; the plugin's prompt hook captured the user's verbatim ask —
          ;; the turn opens itself (zero-ceremony turns)
          (do (swap! session dissoc :pending-intent)
              (api/turn-begin! session :agent ag :intent intent))
          (throw (ex-info (str "no open turn — call turn_begin {intent: "
                               "<the user's verbatim ask>} first")
                          {}))))))
  (let [a   (assoc arguments :agent (or (:agent arguments)
                                        (:agent-id @session)))
        sym (fn [k]
              (if-let [v (get a k)]
                (symbol v)
                (throw (ex-info (str "missing required argument :"
                                     (clojure.core/name k) " for " name)
                                {}))))
        ;; source args are passed raw (not through `sym`), so a misnamed key
        ;; (`new_source` for `source`) silently became nil and fell through to a
        ;; confusing "got 0 forms" parse error. `src` validates a required source
        ;; the way `sym` validates a symbol — and names the alias it caught.
        src (fn [k]
              (let [v (get a k)]
                (if (and v (not (str/blank? (str v))))
                  v
                  (let [alt (some (fn [k2]
                                    (when (and (not= k2 k)
                                               (not (str/blank? (str (get a k2)))))
                                      k2))
                                  [:new_source :new-source :new_src :newsource :src :source-code])]
                    (throw (ex-info (str "missing required argument :"
                                         (clojure.core/name k) " for " name
                                         (if alt
                                           (str " — you passed :" (clojure.core/name alt)
                                                "; the form source goes in :"
                                                (clojure.core/name k))
                                           " (the form source text)")
                                         ".")
                                    {}))))))]
    (if-let [h (tail-handlers! name)]
      (h session a sym)
      (case name
      "ns_create" (text! (api/create-ns! session (sym :ns)
                                                :requires (:requires a)
                                                :source (:source a)
                                                :agent (:agent a)))
      "ns_add_require" (text! (-> (api/add-require! session (sym :ns) (:require a)
                                                      :prompt (:prompt a)
                                                      :agent (:agent a))
                                    (select-keys [:error :warnings :existing-warnings
                                                  :test :affected :delta])
                                    (summarize (:verbose a))))
      "query_project" (text! (told! session name a
                                        (api/query-project session :since (:since a)
                                                          :detail (:detail a))))
      "query_search" (text! (api/query-search session (:pattern a)
                                                  :limit (or (:limit a) 30)))
      "query_source" (text! (told! session name a
                                        (let [full?   (:full a)
                                              gate    (fn [n]
                                                        {:ns n
                                                         :outline (:forms (api/query-outline session n))
                                                         :note (str "outline by default — name the"
                                                                    " forms you need (targets"
                                                                    " [{ns name}]) or pass full:"
                                                                    " true for the whole namespace")})]
                                          (if-let [ts (:targets a)]
                                            (mapv (fn [t]
                                                    (if (or full? (:name t))
                                                      (first (api/query-sources
                                                              session
                                                              [(cond-> {:ns (symbol (:ns t))}
                                                                 (:name t) (assoc :name (symbol (:name t))))]))
                                                      (gate (symbol (:ns t)))))
                                                  ts)
                                            (if full?
                                              (api/query-source session (sym :ns))
                                              (gate (sym :ns)))))))
      "query_detail" (if-let [full (get-in @session [::spool :entries (:id a)])]
                            ;; the retrieval path must NOT re-trim its own payload
                            {:content [{:type "text" :text full}]}
                            (text! {:error (str "no spooled response " (:id a)
                                                " — the spool keeps the last "
                                                spool-cap " trimmed responses")}))
      "query_brief" (text! (told! session name a (api/query-brief session (sym :ns) (sym :name))))
      "query_slice" (text! (told! session name a
                                        (api/query-slice session (sym :ns) (sym :name)
                                                        :depth (or (:depth a) 2)
                                                        :limit (or (:limit a) 8)
                                                        :match (:match a)
                                                        :window (:window a))))
      "query_depends" (text! (told! session name a
                                        (api/query-depends session (:on a)
                                                          :modules (:modules a)
                                                          :direction (if (= "dependencies" (:direction a))
                                                                       :dependencies :dependents))))
      "session_brief" (text! (let [b    (api/session-brief session)
                                       conn (:db @session)
                                       al   (when (and conn (:dir @session))
                                              (sync/alignment
                                               (:dir @session) "."
                                               (str "slopp/" (:branch @session))
                                               (api/query-commits session)))]
                                   (told! session name a (cond-> b al (assoc :alignment al)))))
      "review_scan" (text! (told! session name a
                                            (api/review-scan session
                                                             :ns (:ns a)
                                                             :limit (or (:limit a) 25))))
      "report" (text! (let [r    (api/report session
                                                       :since (:since a)
                                                       :contains (:contains a)
                                                       :limit (or (:limit a) 50))
                                       conn (:db @session)
                                       al   (when (and conn (:dir @session))
                                              (sync/alignment
                                               (:dir @session) "."
                                               (str "slopp/" (:branch @session))
                                               (api/query-commits session)))]
                                   (cond-> r al (assoc :alignment al))))
      "draft_test" (text! (api/draft-test session (sym :ns) (sym :name)
                                                :code (:code a)
                                                :limit (or (:limit a) 5)))
      "turn_begin" (text! (api/turn-begin! session :agent (:agent a)
                                                 :intent (:intent a)
                                                 :user (:user a)))
      "turn_end" (text! (api/turn-end! session :agent (:agent a)
                                               :note (:note a)))
      "query_changes" (text! (api/query-changes session :agent (:agent a)
                                                   :from (:from a) :to (:to a)
                                                   :format (:format a)))
      "episode_revert" (text! (-> (api/revert-episode! session
                                                         :agent (:agent a)
                                                         :prompt (:prompt a))
                                    (select-keys [:error :conflict :reverted
                                                  :skipped-shared :note :test
                                                  :group :affected])
                                    (summarize (:verbose a))))
      "query_history" (text! (told! session name a
                                        (let [nm (:name a)]
                                          (cond
                                            (and nm (:at a))
                                            (assoc (api/query-form-at session (sym :ns) (sym :name)
                                                                     :at (:at a))
                                                   :kind :form-at)

                                            nm
                                            {:kind :form-history
                                             :versions (api/query-form-history session (sym :ns) (sym :name)
                                                                              :format (:format a))}

                                            (:at a)
                                            (assoc (api/query-status-at session :at (:at a))
                                                   :kind :status-at)

                                            (:contains a)
                                            {:kind :prompts
                                             :hits (api/query-search-history session (:contains a)
                                                                            :limit (:limit a))}

                                            :else
                                            (api/query-history session
                                                              :ns (some-> (:ns a) symbol)
                                                              :collapse (:collapse a)
                                                              :format (:format a)
                                                              :limit (or (:limit a) 20))))))
      "query_eval" (text! (api/query-eval session (:code a)))
      "query_call" (text! (apply api/query-call session
                                 (symbol (or (:sym a)
                                             (throw (ex-info "query_call needs :sym (a qualified var name)" {}))))
                                 (:args a)))
      "query_store" (text! (told! session name a
                                  (api/query-store session (:code a)
                                                   :timeout-ms (or (:timeout_ms a) 10000))))
      "query_observe" (text! (let [r (api/query-observe session (sym :ns) (sym :name)
                                                          (:code a)
                                                          :limit (or (:limit a) 10))]
                                 (api/remember-observation! session (sym :ns) (sym :name) r)
                                 r))
      "query_macroexpand" (text! (api/query-macroexpand session (:code a)))
      "query_vocabulary" (text! (told! session name a (api/query-vocabulary session :ns (:ns a))))
      "query_rules" (text! (told! session name a (api/query-rules session)))
      "query_rule_telemetry" (text! (told! session name a (api/query-rule-telemetry session :since (:since a))))
      "edit_replace_form" (text! (-> (api/edit-replace! session (sym :ns) (sym :name)
                                                       (src :source) :prompt (:prompt a)
                                                       :agent (:agent a))
                                    (assoc :forms [(str (sym :ns) "/" (sym :name))])
                                    (select-keys [:error :warnings :existing-warnings :hint :forms
                                                  :untested :image-healed :test :affected :delta
                                                  :red-first :carried-errors :note :advisories])
                                    (summarize (:verbose a))))
      "edit_add_form" (text! (-> (api/add-form! session (sym :ns) (src :source)
                                                   :prompt (:prompt a)
                                                   :agent (:agent a)
                                                   :before (some-> (:before a) symbol))
                                    (select-keys [:error :warnings :existing-warnings :hint
                                                  :untested :image-healed :test :affected :delta
                                                  :red-first :carried-errors :note :advisories])
                                    (summarize (:verbose a))))
      "edit_delete_form" (text! (-> (api/delete-form! session (sym :ns) (sym :name)
                                                      :prompt (:prompt a)
                                                      :agent (:agent a))
                                    (select-keys [:error :test :affected :delta])
                                    (summarize (:verbose a))))
      "edit_rename" (let [old (or (:old a) (:name a) (:from a))
                                new (or (:new a) (:to a))]
                            (when-not (and old new)
                              (throw (ex-info "edit_rename needs :old and :new (aliases: :name/:from, :to)" {})))
                            (text! (-> (api/rename! session (sym :ns) (symbol old)
                                                   (symbol new) :prompt (:prompt a)
                                                   :agent (:agent a))
                                      (select-keys [:error :renamed :test :affected :delta
                                                    :mentions :hint])
                                      (summarize (:verbose a)))))
      "ns_remove_require" (text! (-> (api/remove-require! session (sym :ns) (sym :lib)
                                                         :prompt (:prompt a)
                                                         :agent (:agent a))
                                    (select-keys [:error :test :affected :delta])
                                    (summarize (:verbose a))))
      "rename_sweep" (let [{:keys [from to]} a]
                            (when-not (and from to)
                              (throw (ex-info "rename_sweep needs :from and :to (plain words/segments)" {})))
                            (text! (-> (api/rename-sweep! session from to
                                                         :prompt (:prompt a)
                                                         :agent (:agent a))
                                      (select-keys [:error :source-now :renamed-namespaces :forms
                                                    :group :warnings :existing-warnings
                                                    :changed-nses :test :affected :deltas])
                                      (summarize (:verbose a)))))
      "edit_subform" (let [match (or (:match a) (:from a))
                                src   (or (:source a) (:to a))]
                            (when-not (and (or match (:where a)) src)
                              (throw (ex-info "edit_subform needs :match (exact subform source) OR :where {key value} (the unique map containing it), plus :source" {})))
                            (text! (-> (api/edit-subform! session (sym :ns)
                                            (symbol (or (:form a) (:name a)
                                                        (throw (ex-info "edit_subform needs :form (the containing form's name; :name works too)" {}))))
                                                         match src
                                                         :text (:text a)
                                                         :where (:where a)
                                                         :prompt (:prompt a)
                                                         :agent (:agent a))
                                      (select-keys [:error :source-now :suggestion :conflict
                                                    :warnings :existing-warnings
                                                    :untested :image-healed :test :affected :delta :ms])
                                      (summarize (:verbose a)))))
      "edit_revert" (text! (-> (api/revert-form! session (sym :ns) (sym :name)
                                                      :to (:to a) :prompt (:prompt a)
                                                      :agent (:agent a))
                                    (select-keys [:error :conflict :warnings :test
                                                  :affected :delta :ms])
                                    (summarize (:verbose a))))
      "edit_move" (text! (api/move-form! session (sym :ns) (sym :name)
                                                :before (sym :before)
                                                :prompt (:prompt a)
                                                :agent (:agent a)))
      "edit_trivia" (text! (api/edit-trivia! session (sym :ns)
                                                  (some-> (:before a) symbol)
                                                  (:text a)
                                                  :prompt (:prompt a)
                                                  :agent (:agent a)))
      "edit_extract" (let [subform (or (:form a) (:source a) (:subform a))]
                       (if-not (or subform (:at a))
                         (text! {:error (str "edit_extract needs :form (the exact subform"
                                             " source; aliases :source/:subform accepted)"
                                             " or — better for anything large — :at, an"
                                             " ANCHOR: the subform's first line, which"
                                             " need not parse on its own")})
                         (text! (-> (api/extract! session (sym :ns) (sym :from)
                                                  (sym :name) subform
                                                  :at (:at a)
                                                  :prompt (:prompt a))
                                    (select-keys [:error :source-now :extracted
                                                  :group :test :affected])
                                    (summarize (:verbose a))))))
      "done" (text! (api/done! session :label (:label a)
                                                  :agent (:agent a)))
      "commit_point" (text! (let [r (api/commit-point! session (:description a)
                                                       :agent (:agent a)
                                                       :force (:force a)
                                                       :target (:target a))]
                                    ;; Q10: the mechanical series is the system's job —
                                    ;; a green milestone on a git-configured store
                                    ;; publishes itself; publish trouble rides along
                                    ;; without failing the milestone
                                    (if (and (:commit r) (not= :red (:status r))
                                             (:dir @session))
                                      (if-let [p (try (sync/publish-local!
                                                       (:dir @session)
                                                       (:branch @session))
                                                      (catch Exception e
                                                        {:error (ex-message e)}))]
                                        (assoc r :published
                                               (select-keys p [:pushed :branch
                                                               :error :status]))
                                        r)
                                      r)))
      "test_run" (text!
                       (cond
                         (:isolated a)
                         (api/isolated-test-run! session
                                                 :ns (some-> (:ns a) symbol)
                                                 :affected (:affected a)
                                                 :parallel (some-> (:parallel a) str parse-long)
                                                 :only (some->> (:only a) (mapv symbol)))
                         ;; surgical spot-checks name a target; bare test_run is
                         ;; almost always the redundant "confirm everything" the
                         ;; done-point already does — make ALL explicit, and teach
                         (or (:ns a) (seq (:only a)))
                         (api/test-run! session
                                        (when (:ns a) (sym :ns))
                                        :only (some->> (:only a) (mapv symbol))
                                        :fresh (:fresh a))

                         (:all a)
                         (assoc (api/test-run! session nil :fresh (:fresh a))
                                :note (str "done runs the affected tests for everything"
                                           " you touched — a whole-suite in-image run is"
                                           " rarely needed mid-episode; the merge gate is"
                                           " test_run {isolated true}"))

                         :else
                         {:guidance (str "name what to spot-check: test_run {ns \"x.y-test\"}"
                                         " or {only [\"x.y-test/some-t\"]}. You do NOT need to"
                                         " run tests before done — done runs the affected"
                                         " tests itself. Whole suite in-image: {all true};"
                                         " external merge gate: {isolated true}.")}))
      
      "ns_rename" (text! (api/ns-rename! session (:old a) (:new a)
                                                :prompt (:prompt a)
                                                :agent (:agent a)))
      "cleanup" (text! (if (:all a)
                        (api/cleanup-all! session
                                          :prompt (:prompt a)
                                          :agent (:agent a))
                        (api/cleanup! session (sym :ns)
                                      :prompt (:prompt a)
                                      :agent (:agent a))))
      "undo" (text! (api/undo! session
                               :deltas (:deltas a)
                               :to (:to a)
                               :prompt (:prompt a)
                               :agent (:agent a)))
      "edit_move_forms" (text! (-> (api/move-forms! session (sym :ns)
                                                     (mapv symbol (:forms a))
                                                     (symbol (:to a))
                                                     :export (:export a)
                                                     :prompt (:prompt a)
                                                     :agent (:agent a))
                                    (select-keys [:error :conflict :moved-to
                                                  :moved :rewrote :callers
                                                  :test :group])
                                    (summarize (:verbose a))))
      "change_signature" (text! (-> (api/change-signature! session (sym :ns)
                                                           (sym :name)
                                                           (src :source) (:calls a)
                                                           :prompt (:prompt a)
                                                           :agent (:agent a))
                                    (select-keys [:error :step :group :rewrote :manual
                                                  :warnings :existing-warnings :changed-nses
                                                  :image-healed :test :affected :deltas])
                                    (summarize (:verbose a))))
      (throw (ex-info (str "unknown tool: " name ". Available: "
                           (str/join ", " (map :name tools/tools)))
                      {}))))))

(defn call!
  "One-shot tool invocation against the store at `dir` — the --call CLI's
  engine and the fallback when no MCP connection exists. Opens a durable
  session, dispatches ONE tool call, closes. Returns the wire result map
  ({:content [{:text …}]}; :isError true on tool errors), same as the
  server would send."
  [dir tool arguments]
  (let [session (api/open! {:dir (str dir)})]
    (swap! session assoc :require-turns? true)
    (try
      (try (call-tool! session {:name tool :arguments arguments})
           (catch Exception e
             (assoc (text! (str "error: " (ex-message e))) :isError true)))
      (finally (api/close! session)))))
^:unsafe
(defn ^:entry-point call-main!
  "CLI entry for boot's --call sugar (or --main slopp.mcp/call-main):
  <dir> <tool> [args] — one tool call, result text on stdout, exit 1 on a
  tool error. args is JSON, EDN, or @file (parse-call-args)."
  [& [dir tool args-str]]
  (when (str/blank? tool)
    (binding [*out* *err*]
      (println "usage: --call <tool> [<json/edn args or @file>]"))
    (System/exit 2))
  (let [r (call! (or dir ".") tool (parse-call-args args-str))]
    (println (clojure.string/join "\n" (map :text (:content r))))
    (flush)
    (System/exit (if (:isError r) 1 0))))
^:unsafe (defn handle!
  "Dispatch a JSON-RPC request map; return a response map, or nil for
  notifications. Tool exceptions become an `isError` result (so the agent sees
  the message); protocol errors become JSON-RPC errors."
  [session {:keys [id method params]}]
  (case method
    "initialize" {:jsonrpc "2.0" :id id
                  :result {:protocolVersion protocol-version
                           :capabilities {:tools {:listChanged true}}
                           :serverInfo {:name "slopp" :version "0.1.0"}}}
    "notifications/initialized" nil
    "tools/list" (do (swap! session assoc :slopp.mcp/tools-hash (hash tools/tools))
                     {:jsonrpc "2.0" :id id :result {:tools tools/tools}})
    "tools/call" {:jsonrpc "2.0" :id id
                  :result (binding [*hint* (smells/track-hint! session
                                                        (:name params)
                                                        (:arguments params))
                                    *spool-session* session]
                            (try (call-tool! session params)
                                 (catch Exception e
                                   (assoc (text! (str "error: " (ex-message e)))
                                          :isError true))))}
    "ping" {:jsonrpc "2.0" :id id :result {}}
    (when id
      {:jsonrpc "2.0" :id id
       :error {:code -32601 :message (str "method not found: " method)}})))

(defn- tools-note!
  "The notifications/tools/list_changed message when the tool registry has
  DRIFTED from what this session last advertised (a live reload renamed or
  added a tool — edit_move_forms replaced edit_extract_ns mid-session and no
  client could see it), else nil. Emitting updates the baseline, so each
  drift notifies exactly once. No baseline (tools/list never served) → nil."
  [session]
  (let [h    (hash tools/tools)
        last (:slopp.mcp/tools-hash @session)]
    (when (and last (not= last h))
      (swap! session assoc :slopp.mcp/tools-hash h)
      {:jsonrpc "2.0" :method "notifications/tools/list_changed"})))
(defn serve!
  "Newline-delimited-JSON stdio loop over `in-reader`/`out-writer`."
  [session in-reader out-writer]
  (doseq [line (line-seq in-reader) :when (not (str/blank? line))]
    (when-let [resp (handle! session (json/parse-string line true))]
      (.write out-writer (str (json/generate-string resp) "\n"))
      (.flush out-writer))
    ;; a live reload may have changed the tool registry — tell the client
    ;; to re-list (ordered: same writer, right after the response)
    (when-let [note (tools-note! session)]
      (.write out-writer (str (json/generate-string note) "\n"))
      (.flush out-writer)))
  nil)

^:unsafe
(defn -main
  "Start the stdio MCP server. An optional `dir` argument makes the session
  durable (store at <dir>/.slopp/store.db); without it the session is
  ephemeral. Serving a git checkout that carries a slopp BRANCH with an
  absent/empty store AUTO-IMPORTS it first (zero-ceremony onboarding).
  A durable session ALSO opens an in-process git smart-HTTP listener on a
  dir-derived port (localhost) — a READ-ONLY remote (clone/fetch of
  milestones) any git client can point at with no external daemon;
  `query_git` reports the URL. Publishing to a NORMAL external remote
  (GitHub etc.) goes through `git_push`; `git_clone` rebuilds a fileless
  store from one (slopp.sync)."
  [& [dir]]
  (when dir
    (when-let [r (sync/maybe-auto-import! dir)]
      (binding [*out* *err*]
        (println (str "slopp: auto-imported " (:namespaces r)
                      " namespaces from the repo's slopp branch")))))
  (let [session (api/open! (cond-> {:warm-spare? true}
                             dir (assoc :dir dir)))]
    (swap! session assoc :require-turns? true)   ; real servers enforce turns
    (when dir
      (try
        (let [srv (server/start-server! (server/derived-port dir) {:dir dir})]
          (swap! session assoc :git-server srv :git-url (:url srv))
          ;; stdout is the JSON-RPC channel; banner goes to stderr
          (binding [*out* *err*]
            (println (str "slopp git remote: " (:url srv)))))
        (catch Throwable t                       ; git is optional; MCP must serve
          (binding [*out* *err*]
            (println (str "slopp git remote unavailable: " (.getMessage t)))))))
    (try
      (serve! session (io/reader System/in) (io/writer System/out))
      (finally
        (when-let [srv (:git-server @session)] (server/stop-server! srv))
        (api/close! session)))))
