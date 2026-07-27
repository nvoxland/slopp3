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
            [slopp.store.db :as db] [slopp.sync :as sync] [clojure.edn :as edn] [slopp.mcp.tools :as tools] [slopp.mcp.smells :as smells] [slopp.git.server :as server] [slopp.api.branch :as branch] [slopp.api.query :as query] [slopp.api.review :as review] [slopp.api.external :as external] [slopp.api.cljs :as api.cljs] [slopp.api.rules :as rules] [slopp.ui.server :as ui] [slopp.api.capabilities :as caps] [slopp.api.doctor :as doctor] [slopp.ui.heartbeat :as hb]))

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

(defn- fit-payload
  "Shrink `x` to at most `budget` characters by dropping whole ITEMS from the
  top level, returning `{:body <edn string> :note \"kept of total\"}` — or nil
  when `x` has no items to drop (a scalar/string; the caller falls back).

  Why item-wise: the old trim was `(subs s 0 budget)`, a blind cut through the
  middle of a structure. For a collection response — history rows, changes,
  commits — that yields UNPARSEABLE edn, so the agent's only recovery is
  `query_detail` for the whole payload. Measured in eval9: an 8,367-char
  trimmed history read plus a 21,676-char re-fetch, i.e. the trim cost 30k
  chars where returning the full payload would have cost 21k. A prefix of
  COMPLETE items parses, is immediately usable, and lets the follow-up be
  narrow instead of total."
  [x budget]
  (let [fit (fn [items open close]
              ;; longest prefix of whole items that fits, +2 for the delimiters
              (loop [kept [], used (+ 2 (count open) (count close)), more (seq items)]
                (if-let [it (first more)]
                  (let [s (+ 1 (count (pr-str it)))]
                    (if (> (+ used s) budget)
                      kept
                      (recur (conj kept it) (+ used s) (next more))))
                  kept)))]
    (cond
      (and (sequential? x) (seq x))
      (let [kept (fit x "[" "]")]
        (when (seq kept)
          {:body (pr-str (vec kept))
           :note (str (count kept) " of " (count x) " shown")}))

      (and (map? x) (seq x))
      (let [kept (fit (seq x) "{" "}")]
        (when (seq kept)
          {:body (pr-str (into {} kept))
           :note (str (count kept) " of " (count x) " keys shown")}))

      :else nil)))

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
                    (let [id  (spool! sess full)
                          fit (when (> (count slimmed) 8000)
                                (fit-payload (trim-failure-strings x) 7800))]
                      (cond
                        ;; slimming alone got it under the gate — send it whole
                        (<= (count slimmed) 8000)
                        (str slimmed "\n[trimmed — query_detail {:id \"" id
                             "\"} returns the full response]")

                        ;; drop whole ITEMS: the body stays parseable and usable,
                        ;; so a follow-up can be narrow instead of a full re-fetch
                        fit
                        (str (:body fit) "\n[" (:note fit)
                             " — query_detail {:id \"" id "\"} returns all]")

                        ;; nothing to drop (a single huge string/scalar)
                        :else
                        (str (subs slimmed 0 8000)
                             "\n[trimmed — query_detail {:id \"" id
                             "\"} returns the full response]")))
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
  :summary tag is internal and never rides the wire.

  This path REBUILDS :test from a fixed key list, so anything the layers
  below compute must be added here too or it silently never reaches the
  agent. That has now happened three times — :dry-run's payload, :drift,
  and :external-pending — each looking like a missing feature rather than
  a dropped one. Adding a key below? Add it here in the same edit."
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
          ;; a PREVIEW's payload is the whole point of asking for one —
          ;; dropping it here made dry-run look like a silent no-op
          (:dry-run r)    (assoc :dry-run true
                                 :in-code (:in-code r)
                                 :in-strings (:in-strings r))
          (:note r)       (assoc :note (:note r))
          (:forms r)    (assoc :forms (:forms r))
          (:untested r) (assoc :untested true)
          ;; drift must survive the terse path — it exists precisely to be
          ;; seen on a write the agent would otherwise call done with
          (seq (:drift r)) (assoc :drift (:drift r))
          t             (assoc :test (cond-> {:ran (:test t 0) :pass (:pass t 0)
                                              ;; a run that executed NOTHING is unverified, not green — green must
                                              ;; mean tests ran and passed, or an agent learns to distrust
                                              ;; the status and re-run them by hand
                                              :status (cond
                                                        (red? t)                    :red
                                                        (zero? (:test t 0))         :unverified
                                                        ;; impacted ^:external tests were DEFERRED — whatever
                                                        ;; passed here, it wasn't those. Writing an ^:external
                                                        ;; deftest reported :green off its neighbours in the same
                                                        ;; namespace: a red-first spec reporting success.
                                                        (seq (:external-pending t)) :partial
                                                        :else                       (:status t :green))
                                              :scope (:scope t)}
                                       (:staleness-detected t)  (assoc :staleness-healed true)
                                       (zero? (:test t 0))      ;; name the CAUSE. "no test covers this yet" is the agent's to fix;
                                       ;; "the scope ran nothing" is a slopp bug. Collapsing the
                                       ;; two is how an empty verification fallback hid, looking
                                       ;; like an ordinary untested form.
                                       (assoc :coverage :none
                                              :reason (cond
                                                        ;; nothing ran because everything impacted is
                                                        ;; ^:external — by DESIGN, and the done point
                                                        ;; will run them. Not a gap, not a bug.
                                                        ;; a lower layer already NAMED the reason (e.g. a :cljs write,
                                                        ;; :cljs-deferred-to-compile) — respect it over the generic guesses
                                                        (:reason t)                 (:reason t)
                                                        (seq (:external-pending t)) :all-impacted-external
                                                        (= :all (:affected r))      :no-covering-tests
                                                        :else                       :scope-ran-nothing))
                                       (red? t)                 (assoc :fail (+ (:fail t 0) (:error t 0)))
                                       (seq (:still-red t))     (assoc :still-red (:still-red t))
                                       (seq (:went-green t))    (assoc :went-green (:went-green t))
                                       ;; WHICH tests are deferred, not merely that some are — a
                                       ;; bare :partial an agent cannot act on becomes noise it
                                       ;; learns to skip
                                       (seq (:external-pending t))
                                       (assoc :external-pending (:external-pending t))))
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
              ;; a new ask is a new READER, potentially: /clear and automatic
              ;; compaction both land here and neither is visible any other
              ;; way. `told!` scopes its sent-view hashes to this counter, so
              ;; the first read of a view in a fresh context is always a
              ;; payload. It bumps for READ-ONLY asks too — turns do not, and
              ;; a read-only planning ask is where the withholding was first
              ;; hit.
              (swap! session #(-> %
                                  (assoc :pending-intent prompt :last-intent prompt)
                                  (update ::ask (fnil inc 0)))))))))))

(def ^:private env-handlers!
  "call-tool dispatch \u2014 deps/branches/build/help (Q4: the stable dispatch tail lives in\n  per-group handler maps of (fn [session a sym]); call-tool keeps only the\n  hot query/edit clauses)."
  {"deps_add"
   (fn [session a sym]
     (text! (api/deps-add! session (sym :lib)
                          (or (:coord a)
                              (when (:version a)
                                {:mvn/version (:version a)}))
                          :agent (:agent a) :prompt (:prompt a)
                          :client (:client a))))
   "deps_remove"
   (fn [session a sym]
     (text! (api/deps-remove! session (sym :lib)
                                           :agent (:agent a))))
   "deps_list"
   (fn [session _a _sym]
     (text! (api/deps-list session)))
   "store_health"
   (fn [session _a _sym]
     (text! (external/store-health session)))
   "store_doctor"
   (fn [session _a _sym]
     (text! (doctor/diagnose (:store @session))))
   "ui_serve"
   (fn [session a _sym]
     (text! (if (:stop a)
              {:stopped (boolean (ui/stop!))}
              (ui/serve! session (ui/preferred-port (:store @session)
                                                    (:dir @session)
                                                    (:port a))))))
"compile_client"
   (fn [session a _sym]
     (text! (if (:output a)
              (api.cljs/compile-client! session :output (:output a))
              (api.cljs/compile-client! session))))
   "generate_client"
   (fn [session a _sym]
     (text! (if (:ns a)
              (api.cljs/generate-client! session :ns (symbol (:ns a)))
              (api.cljs/generate-client! session))))
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
     (text! (external/build! session (:dir a)
                                    :main (some-> (:main a) symbol)
                                    :name (:name a))))
   "help"
   (fn [_session _a _sym]
     (text! tools/cheat-sheet))})

(def ^:private file-handlers!
  "call-tool dispatch \u2014 tracked files + config (Q4: the stable dispatch tail lives in\n  per-group handler maps of (fn [session a sym]); call-tool keeps only the\n  hot query/edit clauses)."
  {"config"
   (fn [session a _sym]
     (text! (external/config! session (:key a) (:value a))))
   "file_put"
   (fn [session a _sym]
     (text! (api/file-put! session (:path a) (:content a)
                           :prompt (:prompt a) :agent (:agent a)
                           :encoding (:encoding a)
                           :content-type (:content-type a)
                           :source (:source a))))
"js_dep"
   (fn [session a _sym]
     ;; format crosses the wire as a string; keywordize HERE so the verb's
     ;; own check sees a keyword and can name the keywords it wants
     (text! (api/js-dep! session (:name a)
                         {:version    (:version a)
                          :format     (some-> (:format a) not-empty keyword)
                          :global     (:global a)
                          :file       (:file a)
                          ;; registry-anchored provenance: npm versions are
                          ;; immutable, so this is re-fetchable and verifiable
                          ;; where a CDN url only records how the bytes arrived
                          :npm        (:npm a)
                          :npm-path   (:npm-path a)
                          :integrity  (:integrity a)
                          :source-url (:source-url a)
                          :license    (:license a)}
                         :prompt (:prompt a) :agent (:agent a)
                         :remove (:remove a) :source (:source a))))
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
                              :remove (:remove a)
                              :prompt (:prompt a) :agent (:agent a))))
"module_platform"
   (fn [session a _sym]
     (text! (api/module-platform! session (:module a) (:platform a)
                                  :remove (:remove a)
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
   (fn [session a _sym]
     (text! (if (:commit a)
              ;; the drill-down rung: ONE milestone, full description
              (or (api/query-commits session :commit (:commit a))
                  {:error (str "no milestone " (:commit a))})
              (let [rows (api/query-commits session)
                    conn (:db @session)
                    al   (when (and conn (:dir @session))
                           (sync/alignment (:dir @session) "."
                                           (str "slopp/" (:branch @session))
                                           rows))]
                (if al
                  {:commits rows :alignment al}
                  rows)))))
   "merge_from"
   (fn [session a _sym]
     (text! (branch/merge! session (:dir a))))})

(def ^:private tail-handlers!
  "Every handler-map entry (Q4) \u2014 call-tool checks here first."
  (merge env-handlers! file-handlers! sync-handlers!))

(defn- told!
  "Knowledge-differential reads: the session keeps a hash of every
  cacheable VIEW it has sent, SCOPED TO THE CURRENT ASK; an identical
  re-read within that ask returns a tiny :unchanged stub instead of the
  payload. Re-fetching becomes FREE, so agents never carry views in
  context 'just in case' — the whole don't-hoard stance depends on cheap
  re-asks, and reads are 52% of all output. Any store change alters the
  payload, so staleness is impossible by construction.

  **The ask scope is the correction, and it is about WHOSE knowledge this
  is.** The record lives in the SERVER session, which lasts for the
  process; the claim it makes is about the READER, which resets on
  `/clear`, on automatic compaction, and for every subagent. Unscoped, the
  two diverged and the stub said \"you already know\" to a context that had
  never seen it — measured twice, once mid-plan after a clear and once
  mid-build after an automatic compact. Not staleness: WITHHOLDING, with
  absence-of-payload wearing absence-of-change's clothes.

  The ASK is the boundary and the TURN is not: turns rotate on the
  write-tool gate, so a read-only planning ask never rotates one — and that
  is precisely where this was first hit. `absorb-pending-intent!` bumps
  `::ask` for every prompt the hook records, read-only included.

  `:detail` is the escape for the case the scope cannot cover: a subagent
  shares the session and runs inside the parent's ask, so it can be told
  \"you already know\" about something it has never seen. The payload is
  spooled and the id named, which is the same door `query_detail` already
  opens for trimmed responses — previously the only way to a read-only
  tool's withheld payload was a write-capable tool that prompts for
  permission in plan mode."
  [session tool a payload]
  (let [k [tool (select-keys a [:ns :name :targets :since :detail :depth
                                :limit :contains :full :at :collapse :format
                                :on :direction])]
        h [(get @session ::ask 0) (hash payload)]]
    (if (and (= h (get-in @session [::told k]))
             (< 130 (count (pr-str payload))))
      {:unchanged true
       :view (str tool (when (:ns a) (str " " (:ns a)))
                  (when (:name a) (str "/" (:name a))))
       :detail (spool! session (pr-str payload))
       :note "identical to what this session already received — query_detail {id} if you have not"}
      (do (swap! session assoc-in [::told k] h)
          payload))))

(defn normalize-targets
  "Normalize `query_source`'s `targets` into `[{:ns sym :name sym?} …]`.

  Accepts every UNAMBIGUOUS spelling, because refusing one taught a rule that
  did not need to exist: `{:ns \"a.b\" :name \"c\"}`, the qualified string
  `\"a.b/c\"`, a bare `\"a.b\"`, and the symbol `a.b/c` an agent writing EDN
  reaches for first.

  Previously only the map worked. A string went `(:ns \"a.b/c\")` → nil →
  `(symbol nil)`, and the caller got `no conversion to symbol` — a message
  naming an internal call they never made, with no statement of what WAS
  accepted. Being liberal at the boundary is the better fix than a better
  error message.

  A shape it cannot use is REFUSED rather than dropped: a target that
  silently vanishes reads as \"that form has no source\", which is a different
  and false answer (Core 1)."
  [targets]
  (mapv
   (fn [t]
     (cond
       (map? t)
       (cond-> {:ns (symbol (str (:ns t)))}
         (:name t) (assoc :name (symbol (str (:name t)))))

       (or (string? t) (symbol? t))
       (let [s (str t)
             i (str/index-of s "/")]
         (if (and i (pos? i) (< (inc i) (count s)))
           {:ns (symbol (subs s 0 i)) :name (symbol (subs s (inc i)))}
           {:ns (symbol s)}))

       :else
       (throw (ex-info (str "query_source targets: cannot read " (pr-str t)
                            " — a target is {:ns \"a.b\"} or {:ns \"a.b\" :name \"c\"},"
                            " or the string/symbol form \"a.b/name\" (\"a.b\" alone"
                            " gives that namespace's outline)")
                       {:target t}))))
   targets))

(defn- call-tool! [session {:keys [name arguments]}]
  ;; async-image boot: the store loaded synchronously (this dispatch is live),
  ;; but the image may still be warming on a background thread. Oracle and
  ;; write tools wait for it here; store-value reads serve immediately.
  (when-let [bad (tools/unknown-arg-keys name arguments)]
    (throw (ex-info (str "unknown argument" (when (next bad) "s") " "
                         (str/join ", " (map #(str ":" (clojure.core/name %)) bad))
                         " for " name " — a mistyped or unsupported argument is"
                         " refused, not ignored. accepted: "
                         (str/join " " (sort (map #(str ":" (clojure.core/name %))
                                                  (tools/accepted-arg-keys name)))))
                    {:tool name :unknown (vec bad)})))
  (when-not (contains? tools/image-free-tools name)
    (api/await-image! session))
  (api/sync-with-journal! session)      ; m5b: absorb other servers' commits      ; m5b: absorb other servers' commits
  (absorb-pending-intent! session)
  ;; A NEW ASK IS A NEW TURN. The gate used to open one only when none was
  ;; open, and nothing ever closed one, so a single turn spanned an entire
  ;; session: measured on slopp's own store, five :turn-begin deltas across
  ;; ~15 asks and ZERO :turn-end. Two things were lost by that — the turn's
  ;; wall-clock timing, which rides turn-end and therefore never landed, and
  ;; worse, EVERY ASK AFTER THE FIRST, which never reached the journal at all.
  ;; Rotating costs two marker deltas per ask.
  (when (and (:pending-intent @session)
             (:require-turns? @session)
             (contains? tools/write-tools name)
             (not (#{"done" "commit_point"} name)))
    (let [ag (or (:agent arguments) (:agent-id @session))]
      (when (api/turn-open? session ag)
        (api/turn-end! session :agent ag))))
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
                                                :platform (:platform a)
                                                :prompt (:prompt a)
                                                :agent (:agent a)))
      "ns_add_require" (text! (-> (api/add-require! session (sym :ns) (:require a)
                                                      :prompt (:prompt a)
                                                      :agent (:agent a))
                                    (select-keys tools/wire-keys)
                                    (summarize (:verbose a))))
      "query_project" (text! (told! session name a
                                        (query/query-project session :since (:since a)
                                                          :detail (:detail a))))
      "query_search" (text! (query/query-search session (:pattern a)
                                                  :limit (or (:limit a) 30)))
      "query_source" (text! (told! session name a
                                        (let [full?   (:full a)
                                              gate    (fn [n]
                                                        {:ns n
                                                         :outline (:forms (query/query-outline session n))
                                                         :note (str "outline by default — name the"
                                                                    " forms you need (targets"
                                                                    " [{ns name}]) or pass full:"
                                                                    " true for the whole namespace")})]
                                          (if-let [ts (some-> (:targets a) normalize-targets seq)]
                                            (mapv (fn [t]
                                                    (if (or full? (:name t))
                                                      (first (query/query-sources session [t]))
                                                      (gate (:ns t))))
                                                  ts)
                                            (if full?
                                              (query/query-source session (sym :ns))
                                              (gate (sym :ns)))))))
      "query_detail" (if-let [full (get-in @session [::spool :entries (:id a)])]
                            ;; the retrieval path must NOT re-trim its own payload
                            {:content [{:type "text" :text full}]}
                            (text! {:error (str "no spooled response " (:id a)
                                                " — the spool keeps the last "
                                                spool-cap " trimmed responses")}))
      "query_brief" (text! (told! session name a (query/query-brief session (sym :ns) (sym :name))))
      "query_slice" (text! (told! session name a
                                        (query/query-slice session (sym :ns) (sym :name)
                                                        :depth (or (:depth a) 2)
                                                        :limit (or (:limit a) 8)
                                                        :match (:match a)
                                                        :window (:window a))))
      "query_depends" (text! (told! session name a
                                        (query/query-depends session (:on a)
                                                          :modules (:modules a)
                                                          :detail (:detail a)
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
                                            (review/review-scan session
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
      "query_changes" (text! (query/query-changes session :agent (:agent a)
                                                   :from (:from a) :to (:to a)
                                                   :format (:format a)))
      "episode_revert" (text! (-> (api/revert-episode! session
                                                         :agent (:agent a)
                                                         :prompt (:prompt a))
                                    (select-keys tools/wire-keys)
                                    (summarize (:verbose a))))
      "query_history" (text! (told! session name a
                                        (let [nm (:name a)]
                                          (cond
                                            (and nm (:at a))
                                            (assoc (query/query-form-at session (sym :ns) (sym :name)
                                                                     :at (:at a))
                                                   :kind :form-at)

                                            (and nm (:effort a))
                                            (assoc (query/query-form-history session (sym :ns) (sym :name)
                                                                             :effort true)
                                                   :kind :form-effort)

                                            nm
                                            {:kind :form-history
                                             :versions (query/query-form-history session (sym :ns) (sym :name)
                                                                              :format (:format a))}

                                            (:at a)
                                            (assoc (query/query-status-at session :at (:at a))
                                                   :kind :status-at)

                                            (:contains a)
                                            {:kind :prompts
                                             :hits (query/query-search-history session (:contains a)
                                                                            :limit (:limit a))}

                                            (:dead_ends a)
                                            {:kind :dead-ends
                                             :dead-ends (query/query-history
                                                         session :dead-ends (:dead_ends a))}

                                            :else
                                            (query/query-history session
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
                                  (query/query-store session (:code a)
                                                   :timeout-ms (or (:timeout_ms a) 10000))))
      "query_observe" (text! (let [r (api/query-observe session (sym :ns) (sym :name)
                                                          (:code a)
                                                          :limit (or (:limit a) 10))]
                                 (api/remember-observation! session (sym :ns) (sym :name) r)
                                 r))
      "query_macroexpand" (text! (api/query-macroexpand session (:code a)))
      "query_vocabulary" (text! (told! session name a (query/query-vocabulary session :ns (:ns a))))
      "query_rules" (text! (told! session name a (rules/query-rules session)))
      "query_capabilities" (text! (told! session name a (query/query-capabilities session)))
      "query_routes" (text! (told! session name a (query/query-routes session)))
      "query_rule_telemetry" (text! (told! session name a (query/query-rule-telemetry session :since (:since a))))
      "edit_replace_form" (text! (-> (api/edit-replace! session (sym :ns) (sym :name)
                                                       (src :source) :prompt (:prompt a)
                                                       :agent (:agent a))
                                    (assoc :forms [(str (sym :ns) "/" (sym :name))])
                                    (select-keys tools/wire-keys)
                                    (summarize (:verbose a))))
      "edit_add_form" (text! (-> (api/add-form! session (sym :ns) (src :source)
                                                   :prompt (:prompt a)
                                                   :agent (:agent a)
                                                   :before (some-> (:before a) symbol))
                                    (select-keys tools/wire-keys)
                                    (summarize (:verbose a))))
      "edit_delete_form" (text! (-> (api/delete-form! session (sym :ns) (sym :name)
                                                      :prompt (:prompt a)
                                                      :agent (:agent a))
                                    (select-keys tools/wire-keys)
                                    (summarize (:verbose a))))
      "edit_rename" (let [old (or (:old a) (:name a) (:from a))
                                new (or (:new a) (:to a))]
                            (when-not (and old new)
                              (throw (ex-info "edit_rename needs :old and :new (aliases: :name/:from, :to)" {})))
                            (text! (-> (api/rename! session (sym :ns) (symbol old)
                                                   (symbol new) :prompt (:prompt a)
                                                   :agent (:agent a))
                                      (select-keys tools/wire-keys)
                                      (summarize (:verbose a)))))
      "ns_remove_require" (text! (-> (api/remove-require! session (sym :ns) (sym :lib)
                                                         :prompt (:prompt a)
                                                         :agent (:agent a))
                                    (select-keys tools/wire-keys)
                                    (summarize (:verbose a))))
      "full_check" (text! (external/full-check! session :affected (:affected a)))
      "edit_requalify" (text! (-> (api/requalify-boundary-keys!
                                   session (sym :ns) (sym :name)
                                   :to-ns (or (:to-ns a) (:to_ns a))
                                   :prompt (:prompt a)
                                   :agent (:agent a)
                                   :dry-run (or (:dry-run a) (:dry_run a)))
                                  (select-keys tools/wire-keys)
                                  (summarize (:verbose a))))
      "rename_sweep" (let [{:keys [from to]} a]
                            (when-not (and from to)
                              (throw (ex-info "rename_sweep needs :from and :to (plain words/segments)" {})))
                            (text! (-> (api/rename-sweep! session from to
                                                         :prompt (:prompt a)
                                                         :agent (:agent a)
                                                         :dry-run (or (:dry-run a) (:dry_run a)))
                                      (select-keys tools/wire-keys)
                                      (summarize (:verbose a)))))
      "edit_subform" (let [after  (:after a)
                                anchor (or (:match a) (:from a))
                                ;; :after is a distinct INSERT anchor — combining
                                ;; it with :match/:from/:where composed src on
                                ;; :after's mere PRESENCE while the anchor
                                ;; preferred :match, splicing a DUPLICATE of the
                                ;; neighbor at the match site (review host-F1)
                                _ (when (and after (or anchor (:where a)))
                                    (throw (ex-info "edit_subform: :after is an INSERT anchor — do not combine it with :match/:from/:where (that would duplicate the neighbor). Use one or the other." {})))
                                match (or anchor after)
                                src   (if after
                                        ;; anchor mode: INSERT behind a complete
                                        ;; neighbor — the let-binding splice
                                        ;; without shaping a half-open match
                                        (str after "\n" (or (:source a) (:to a)))
                                        (or (:source a) (:to a)))]
                            (when-not (and (or match (:where a)) src)
                              (throw (ex-info "edit_subform needs :match (exact subform source) OR :where {key value} (the unique map containing it) OR :after (a complete neighboring form to insert behind), plus :source" {})))
                            (text! (-> (api/edit-subform! session (sym :ns)
                                            (symbol (or (:form a) (:name a)
                                                        (throw (ex-info "edit_subform needs :form (the containing form's name; :name works too)" {}))))
                                                         match src
                                                         :text (:text a)
                                                         :wrap (:wrap a)
                                                         :where (:where a)
                                                         :prompt (:prompt a)
                                                         :agent (:agent a))
                                      (select-keys tools/wire-keys)
                                      (summarize (:verbose a)))))
      "edit_revert" (text! (-> (api/revert-form! session (sym :ns) (sym :name)
                                                      :to (:to a) :prompt (:prompt a)
                                                      :agent (:agent a))
                                    (select-keys tools/wire-keys)
                                    (summarize (:verbose a))))
      "edit_move" (text! (api/move-form! session (sym :ns) (sym :name)
                                                :before (sym :before)
                                                :prompt (:prompt a)
                                                :agent (:agent a)))
      "edit_comment" (text! (api/set-comment! session (sym :ns) (sym :name)
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
                                    (select-keys tools/wire-keys)
                                    (summarize (:verbose a))))))
      "done" (text! (external/done! session :label (:label a)
                                                  :agent (:agent a)))
      "commit_point" (text! (let [r (external/commit-point! session (:description a)
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
                         (:external a)
                         (external/external-test-run! session
                                                 :ns (some-> (:ns a) symbol)
                                                 :affected (:affected a)
                                                 :parallel (some-> (:parallel a) str parse-long)
                                                 :only (some->> (:only a) (mapv symbol)))
                         ;; surgical spot-checks name a target; bare test_run is
                         ;; almost always the redundant "confirm everything" the
                         ;; done-point already does — make ALL explicit, and teach
                         (or (:ns a) (seq (:only a)))
                         (external/spot-run! session
                                             :ns (when (:ns a) (sym :ns))
                                             :only (some->> (:only a) (mapv symbol))
                                             :fresh (:fresh a))

                         (:all a)
                         (assoc (api/test-run! session nil :fresh (:fresh a))
                                :note (str "done runs the affected tests for everything"
                                           " you touched — a whole-suite in-image run is"
                                           " rarely needed mid-episode; the merge gate is"
                                           " test_run {external true}"))

                         :else
                         {:guidance (str "name what to spot-check: test_run {ns \"x.y-test\"}"
                                         " or {only [\"x.y-test/some-t\"]}. You do NOT need to"
                                         " run tests before done — done runs the affected"
                                         " tests itself. Whole suite in-image: {all true};"
                                         " external merge gate: {external true}.")}))
      
      "ns_delete" (text! (api/delete-ns! session (sym :ns)
                                              :prompt (:prompt a)
                                              :agent (:agent a)))
      "ns_rename" (text! (api/ns-rename! session (:old a) (:new a)
                                                :prompt (:prompt a)
                                                :agent (:agent a)))
"module_extract" (text! (api/module-extract!
                               session
                               (mapv symbol (:namespaces a))
                               (symbol (str (:to a)))
                               :dry-run (or (:dry-run a) (:dry_run a))
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
                                    (select-keys tools/wire-keys)
                                    (summarize (:verbose a))))
      "change_signature" (text! (-> (api/change-signature! session (sym :ns)
                                                           (sym :name)
                                                           (src :source) (:calls a)
                                                           :prompt (:prompt a)
                                                           :agent (:agent a))
                                    (select-keys tools/wire-keys)
                                    (summarize (:verbose a))))
      (throw (ex-info (str "unknown tool: " name ". Available: "
                           (str/join ", " (map :name tools/tools)))
                      {}))))))

(defn call!
  "One-shot tool invocation against the store at `dir` — the --call CLI's
  engine and the fallback when no MCP connection exists. Opens a durable
  session, dispatches ONE tool call, closes. Returns the wire result map
  ({:content [{:text …}]}; :isError true on tool errors), same as the
  server would send.

  Writes stay TURN-GATED here, deliberately: provenance is not optional just
  because the caller is a script. Turns are DURABLE across one-shot processes,
  so the scripted shape is `--call turn_begin` once, then the writes, then
  `--call turn_end` — not a turn per call. Reads need nothing.

  An unexpected throw reports its CAUSE CHAIN. It does NOT report stack frames:
  everything here flows through `text!`, whose boundary-leak guard refuses a
  file:line coordinate, so emitting frames replaced the real diagnostic with a
  guard exception."
  [dir tool arguments]
  (let [session (external/open! {:slopp.api/dir (str dir)})]
    (swap! session assoc :require-turns? true)
    (try
      (try (call-tool! session {:name tool :arguments arguments})
           (catch Exception e
             (let [chain (take 4 (iterate #(some-> ^Throwable % .getCause) e))
                   msgs  (into [] (comp (take-while some?)
                                        (map #(str (.getSimpleName (class %))
                                                   ": " (ex-message %))))
                               chain)]
               (assoc (text! (str "error: " (str/join " <- " msgs)))
                      :isError true))))
      (finally (api/close! session)))))

^:unsafe
(defn ^:entry-point call-main!
  "CLI entry for boot's --call sugar (or --main slopp.mcp/call-main!):
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

(defn- refusal-text
  "The message a REFUSED call answered with, or nil if it was not a refusal.

  A refusal arrives in two shapes: a thrown exception, which `handle!` has
  already marked `:isError`, or slopp's own refusal-as-data, which `text!`
  pr-strs so the payload opens with `{:error`. This is the whole predicate as
  well as the message — `:refused?` is `(some? (refusal-text r))` — because
  asking the same question at two call sites is how they drift, and the
  failure log has four instances of that shape already.

  It UNDER-counts, deliberately: a result carrying `:error` behind another key
  reads as clean. That is the safe direction for a waste metric — it will
  never invent a problem, only miss one. What it must not do is lose a
  refusal it has already been told about, so an `:isError` with no text still
  answers non-nil."
  [r]
  (let [t (:text (first (:content r)))]
    (cond
      (and t (str/starts-with? t "{:error")) t
      (:isError r) (or t "error")
      :else nil)))

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
    "tools/call"
    ;; BOTH edges of the call, recorded here because this is the only layer
    ;; that sees them. The gap between one answer and the next call is time
    ;; slopp was NOT working — agent reasoning, non-slopp tools, the harness —
    ;; and it had no producer at all: measured over one real session, 78% of
    ;; the wall clock was invisible. turn_end folds the ring onto its delta.
    (let [t0 (System/currentTimeMillis)
          r  (binding [*hint* (smells/track-hint! session
                                                  (:name params)
                                                  (:arguments params))
                       *spool-session* session]
               (try (call-tool! session params)
                    (catch Exception e
                      (assoc (text! (str "error: " (ex-message e)))
                             :isError true))))
          why (refusal-text r)]
      ;; after the call, so a tool that reads the ring (turn_end) never sees
      ;; its own half-finished entry
      (swap! session update :slopp.api.telemetry/calls (fnil conj [])
             {:tool (:name params) :start t0 :end (System/currentTimeMillis)
              ;; A REFUSAL and the reason it gave, from ONE derivation — see
              ;; `refusal-text` for the two shapes it arrives in and for the
              ;; deliberate under-count. The message rides along because a
              ;; count with no cause can only ever support "read that tool's
              ;; contract", which is the guess rather than the finding;
              ;; `call-timing` bounds and truncates what reaches the delta.
              :refused? (some? why)
              :error    why})
      {:jsonrpc "2.0" :id id :result r})
    "ping" {:jsonrpc "2.0" :id id :result {}}
    (when id
      {:jsonrpc "2.0" :id id
       :error {:code -32601 :message (str "method not found: " method)}})))

(defn- tools-note!
  "The notifications/tools/list_changed message when the tool registry has
  DRIFTED from what this session last advertised (a live reload renamed or
  added a tool — edit_move_forms replaced an earlier extract-to-namespace tool mid-session and no
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

^:unsafe (defn start-heartbeat!
  "Start this project checking in with the UI hub, and record the handle on
  the session. Never throws; returns the hub url it beats to, or nil.

  `ui.hub-port` 0 means \"no hub\", and a hub that simply is not running is the
  ordinary case rather than a failure — the beat retries forever and costs
  nothing, so the project appears in the picker within one interval of a hub
  starting later. Registering and keeping alive are the same call, deliberately
  (D-ui-hub).

  The banner names the HUB's address, not this project's derived port, because
  the hub url is the one a human is meant to remember and the derived one is an
  implementation detail they should never have to type."
  [session dir url]
  (try
    (let [port (caps/effective (:store @session) "ui.hub-port")]
      (when (and dir port (pos? (long port)))
        (let [hub    (hb/hub-url port)
              handle (hb/start! hub #(hb/payload (:store @session) dir url))]
          (swap! session assoc :ui-heartbeat handle :ui-hub hub)
          (.println System/err ^String (str "slopp UI hub: " hub
                                            " (open this to switch projects)"))
          hub)))
    (catch Throwable t
      (.println System/err ^String (str "slopp UI hub registration unavailable: "
                                        (.getMessage t)))
      nil)))

^:unsafe (defn start-ui!
  "Bring this project's UI listener up beside the MCP server and start its
  heartbeat to the hub. Returns `ui/serve!`'s map — `{:url :port}`, or
  `{:error …}` — and NEVER throws.

  The listener still serves the LIVE session and still dies with the server,
  because `:test-map` and `:observed` are session-grain and unpersisted; a UI
  served from a fresh session renders every form as covered by no tests. That
  accuracy is what forces the whole hub design (D-ui-hub): a hub cannot answer
  for a store, so every project answers for itself and the hub proxies.

  What changed is the ADDRESS. The port is derived from the store dir instead
  of defaulting to a fixed 7359, so projects on one machine never collide, and
  a taken port falls back to an ephemeral one — the registered url carries
  whatever was actually bound. Nobody needs to know this number; the address a
  human remembers is the hub's.

  Follows the git listener's stance exactly, and for the same reason: the UI is
  OPTIONAL and MCP is not. A busy port, a missing hub, anything at all — it
  reports a sentence on stderr (stdout is the JSON-RPC channel) and the server
  carries on. Nothing about a browser page should be able to stop the thing the
  editor is talking to."
  ([session] (start-ui! session nil))
  ([session explicit-port]
   (let [dir  (:dir @session)
         want (ui/preferred-port (:store @session) dir explicit-port)
         try! (fn [p] (try (ui/serve! session p)
                           (catch Throwable t {:error (or (.getMessage t) (str t))})))
         r0   (try! want)
         ;; a derived port is a PREFERENCE: something else already holding it
         ;; must not cost this project its UI, so fall back to whatever is free.
         r    (if (and (:error r0) (not (zero? (long want)))) (try! 0) r0)]
     ;; ON THE SESSION, the way the git listener carries :git-url. The stderr
     ;; banner below goes to the MCP server's log, which most clients never
     ;; show a human — so autostart without this is a feature nobody can find.
     ;; session_brief surfaces it, which is where an agent looks and how the
     ;; human gets told.
     (when (:url r) (swap! session assoc :ui-url (:url r)))
     (.println System/err
               ^String (if (:url r)
                         (str "slopp UI: " (:url r))
                         (str "slopp UI unavailable: " (:error r))))
     (when (:url r) (start-heartbeat! session dir (:url r)))
     r)))

^:unsafe
(defn -main
  "Start the stdio MCP server. An optional `dir` argument makes the session
  durable (store at <dir>/.slopp/store.db); without it the session is
  ephemeral. Serving a git checkout that carries a slopp BRANCH with an
  absent/empty store AUTO-IMPORTS it first (zero-ceremony onboarding).

  Serving a dir that is NOT slopp-managed writes NOTHING there: the server
  is launched in whatever directory the editor has open, so adoption has to
  be something you do, not something that happens to you. The store is
  created by the first real write (`slopp.api.session/ensure-db!`), and
  until then the git listener stays down too — opening its context would
  create the very store this is avoiding.

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
  (let [session (external/open! (cond-> {:slopp.api/warm-spare? true
                                      ;; boot the image on a background thread
                                      ;; so the MCP handshake completes as soon
                                      ;; as the store loads — a slow/contended
                                      ;; boot no longer races the connect timeout
                                      :slopp.api/async-image? true}
                             dir (assoc :slopp.api/dir dir)))]
    (swap! session assoc :require-turns? true)   ; real servers enforce turns
    (when (and dir (:db @session))
      (try
        (let [srv (server/start-server! (server/derived-port dir) {:dir dir})]
          (swap! session assoc :git-server srv :git-url (:url srv))
          ;; stdout is the JSON-RPC channel; banner goes to stderr
          (binding [*out* *err*]
            (println (str "slopp git remote: " (:url srv)))))
        (catch Throwable t                       ; git is optional; MCP must serve
          (binding [*out* *err*]
            (println (str "slopp git remote unavailable: " (.getMessage t)))))))
    ;; the reviewer UI comes up with the server, always. It serves the LIVE
    ;; session and therefore dies with it — that is the trade that keeps its
    ;; warranty numbers honest — so nothing ever brought it back, and a human
    ;; who wanted it had to know to ask again after every restart.
    ;; start-ui! never throws: MCP must serve even when the UI cannot.
    (start-ui! session)
    (try
      (serve! session (io/reader System/in) (io/writer System/out))
      (finally
        (when-let [srv (:git-server @session)] (server/stop-server! srv))
        ;; deregister BEFORE the listener goes: the hub should learn we are
        ;; leaving from us, not by ageing us out thirty seconds later.
        (hb/stop! (:ui-heartbeat @session))
        (ui/stop!)
        (api/close! session)))))
