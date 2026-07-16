(ns slopp.edit
  "The edit pipeline (O1 whole-form replace): parse -> dialect-check (D3/D4) ->
  commit delta (D-store) -> hot-reload into the image (D5) -> return with
  `!`-effect warnings (D6). Verification (affected tests + restart-as-diagnostic)
  is orchestrated one level up, in slopp.api."
  (:require [clojure.string :as str]
            [rewrite-clj.parser :as p]
            [rewrite-clj.node :as n]
            [rewrite-clj.zip :as z]
            [slopp.store :as store]
            [slopp.render :as render]
            [slopp.index :as index]
            [slopp.repl :as repl] [slopp.edit.modules :as modules]))

(def ^:private banned-heads
  "D4 — user macros are banned."
  '#{defmacro})

^:unsafe (def ^:private banned-syms
  "D3 — the analysis-defeater denylist (extensible). The RESOLVERS
  (requiring-resolve, resolve, ns-resolve, find-var, intern) are here per
  the reference-carrier decision: mentions of var names in strings or
  quoted symbols are INERT data, so the gate blocks the moment they could
  BECOME a var — carriers (store/late-ref) or ^:unsafe are the sanctioned
  paths."
  '#{eval alter-var-root binding gen-class definline read-string
     requiring-resolve resolve ns-resolve find-var intern})

(defn- all-symbols [node]
  (filter symbol? (tree-seq coll? seq (n/sexpr node))))

(defn unsafe?
  "Does the top-level form carry `^:unsafe` metadata? The greppable,
  human-discharged opt-out of the dialect ban (the Rust-`unsafe` proof
  obligation — Tier-1 boundary work, e.g. calling into an opaque dep)."
  [node]
  (boolean (:unsafe (meta (n/sexpr node)))))

(defn reads?
  "Does the top-level form carry `^:reads` metadata? The greppable,
  human-discharged override of the D6 `!`-naming rule: the author asserts this
  fn READS external/mutable state (so the effect analysis reaches an anchor —
  a DB SELECT, `deref`, static analysis) but is a read, not a mutation, so it
  takes no bang — matching Clojure's norm (`slurp`, `deref`, `d/q` carry no
  `!`). Orthogonal to `^:unsafe` (dialect) — it suppresses only the effect
  WARNING, and is self-limiting (a mutating form tagged `^:reads` is lying)."
  [node]
  (boolean (:reads (meta (n/sexpr node)))))

(defn dialect-check
  "nil if the form is admissible; an error string otherwise (D3/D4). An
  `^:unsafe` form is admissible by assertion — the author takes on the
  obligation the analyzer can't discharge (it stays greppable via `unsafe?`).
  Shared by the single-form edit gate (`parse-form`) and the whole-namespace
  import gate (`dialect-scan`) so both paths reject identically."
  [node]
  (when-not (unsafe? node) (let [s    (n/sexpr node)
          head (when (seq? s) (first s))]
      (cond
        (contains? banned-heads head)
        (str "dialect (D4): user macros are banned — " head)
        (some banned-syms (all-symbols node))
        (let [hit (first (filter banned-syms (all-symbols node)))]
          (str "dialect (D3): denylisted symbol used — " hit
               (when (#{"requiring-resolve" "resolve" "ns-resolve" "find-var"}
                      (name hit))
                 (str " — references resolve through CARRIERS:"
                      " (store/late-ref 'ns/name) for load-cycle late binding,"
                      " #'ns/name for in-process references in data; mark the"
                      " form ^:unsafe only if you truly own the obligation"))))
        :else nil))))

(defn parse-form
  "Parse `source` as exactly ONE dialect-legal top-level form (the D3/D4 gate
  every write shares). Returns {:node node} or {:error msg} — including for
  unparseable/unbalanced source (F3: never throws)."
  [source]
  (try
    (let [forms (filter n/sexpr-able? (n/children (p/parse-string-all source)))]
      (if (not= 1 (count forms))
        {:error (str "expected exactly one top-level form, got " (count forms))}
        (let [node (first forms)]
          (if-let [err (dialect-check node)]
            {:error err}
            {:node node}))))
    (catch Exception e
      {:error (str "unparseable source (unbalanced?): " (ex-message e))})))

^:unsafe (def ^:private observe-banned
  "query-eval may observe anything (including calling effectful fns) but never
  (re)define code — that would bypass the delta/provenance pipeline (T5)."
  '#{def defn defn- defmacro defonce deftype defrecord defprotocol defmulti
     defmethod in-ns ns ns-unmap ns-unalias alter-var-root intern remove-ns
     create-ns load-file load-string})

(defn pure-eval-refusal
  "nil when sexpr `x` looks READ-ONLY, else a teaching error string — the
  gate for the store-value oracle (query_store), which must never write.
  Conservative, quote-pruned symbol walk refusing: `!`-enders (the effect
  convention), def-family and redefinition forms, java interop (method
  calls, constructors — arbitrary IO hides there), and an explicit denylist
  of IO/eval/binding entry points. Pure analysis needs none of those."
  [x]
  (let [deny (set (str/split (str "spit slurp eval read-string load-string"
                                  " load-file load require use import intern"
                                  " in-ns remove-ns ns-unmap alter-var-root"
                                  " set! with-redefs with-redefs-fn"
                                  " push-thread-bindings future future-call"
                                  " agent send send-off pmap pcalls promise"
                                  " proxy reify deftype defrecord definterface"
                                  " gen-class new def defn defn- defmacro"
                                  " defmethod defmulti defonce defprotocol"
                                  " extend extend-type extend-protocol binding"
                                  " shutdown-agents add-watch remove-watch")
                             #" "))
        deny-ns #{"System" "java.lang.System" "clojure.java.io"
                  "clojure.java.shell" "java.io" "java.nio.file"}
        bad (fn bad [f]
              (cond
                (and (seq? f) (= 'quote (first f))) nil
                (symbol? f)
                (let [nm (name f)]
                  (when (or (str/ends-with? nm "!")
                            (str/starts-with? nm ".")
                            (str/ends-with? nm ".")
                            (contains? deny nm)
                            (contains? deny-ns (namespace f)))
                    f))
                (map-entry? f) (or (bad (key f)) (bad (val f)))
                (coll? f) (some bad f)
                :else nil))]
    (when-let [f (bad x)]
      (str "query_store is READ-ONLY analysis over the immutable store value — "
           f " is refused (no effects, no defs, no interop, no IO/eval). "
           "Pure clojure.core plus slopp's pure fns (slopp.store/forms, "
           "slopp.render/render-ns, slopp.index/analyze ...) cover the "
           "analysis space"))))
(defn observe-gate
  "nil if `code` is observation-only; an error string if it (re)defines
  code or doesn't parse. POSITION-aware: banned symbols count anywhere
  they could act (operator or value position — `apply` smuggling included),
  but QUOTED data is inert, so a read-only census like `'#{defn defmacro}`
  passes."
  [code]
  (try
    (letfn [(scan [form]
              (cond
                (and (seq? form) (= 'quote (first form))) nil
                (symbol? form) (when (observe-banned form) form)
                (map? form)    (some scan (mapcat identity form))
                (coll? form)   (some scan form)
                :else nil))]
      (let [forms (keep #(try (n/sexpr %) (catch Exception _ nil))
                        (filter n/sexpr-able?
                                (n/children (p/parse-string-all code))))]
        (when-let [bad (some scan forms)]
          (str "query-eval is observe-only; `" bad
               "` (re)defines code — use the edit tools (quoted `" bad
               "` as data is fine)"))))
    (catch Exception e
      (str "unparseable code: " (ex-message e)))))

(defn strip-image-reload
  "Remove `:reload`/`:reload-all` flags from every `require`/`use`/`require-macros`
  form in `code`. The owned image has NO source files — store namespaces are
  loaded via `load-ns!` (they aren't on the classpath) and deps are jars — so a
  muscle-memory `(require 'the.ns :reload)` throws FileNotFoundException instead
  of the intended no-op. Stripping makes it the no-op. Only flags that are
  direct children of a require/use form are touched (a `:reload` elsewhere is
  data and survives). Output is for eval, not storage, so leftover whitespace is
  fine. Returns `code` unchanged if it doesn't parse."
  [code]
  (letfn [(reload-flag? [nd]
            (and (n/sexpr-able? nd)
                 (contains? #{:reload :reload-all} (n/sexpr nd))))
          (strip [node]
            (if (n/inner? node)
              (let [kids (n/children node)
                    head (some-> (first (filter n/sexpr-able? kids)) n/sexpr)
                    kids (if (contains? '#{require use require-macros} head)
                           (remove reload-flag? kids)
                           kids)]
                (n/replace-children node (mapv strip kids)))
              node))]
    (try
      (n/string (strip (p/parse-string-all code)))
      (catch Exception _ code))))

(defn add-require-source
  "F5: structurally add one require clause (`require-str`, e.g.
  \"[clojure.string :as str]\") to an ns form's source. Returns {:src new-src}
  or {:error msg} (bad clause / already required)."
  [ns-source require-str]
  (try
    (let [req  (n/sexpr (p/parse-string require-str))
          lib  (if (vector? req) (first req) req)
          spec (n/sexpr (p/parse-string ns-source))
          libs (for [clause spec
                     :when (and (seq? clause) (= :require (first clause)))
                     r (rest clause)]
                 (if (vector? r) (first r) r))]
      (if (some #{lib} libs)
        {:error (str "already required: " lib)}
        (let [zloc (z/of-string ns-source)
              rq   (z/find-value zloc z/next :require)]
          {:src (if rq
                  (-> rq z/up (z/append-child req) z/root-string)
                  (-> zloc (z/append-child (list :require req)) z/root-string))})))
    (catch Exception e
      {:error (str "bad require clause: " (ex-message e))})))

(defn ns-warnings
  "D6 `!`-effect violations for `ns-sym`'s current state. The external-dep
  boundary (M3): a call into any namespace provided by a manifest dependency
  (`:dep-ns`) is an effect anchor unless the var is in `:dep-pure`. A form tagged
  `^:reads` is EXEMPT — the author asserts it reads external/mutable state but is
  intentionally not `!`-named (Clojure's read-takes-no-bang norm)."
  [store ns-sym]
  (let [dep-nses (into #{} (mapcat identity) (vals (:dep-ns store)))
        exempt   (into #{}
                       (keep (fn [e]
                               (when (and (:name e) (reads? (:node e)))
                                 (symbol (str ns-sym) (str (:name e))))))
                       (store/forms store ns-sym))]
    (remove #(contains? exempt (:var %))
            (index/effect-violations (index/analyze (render/render-ns store ns-sym))
                                     dep-nses (:dep-pure store)))))

(defn dialect-scan
  "Run the D3/D4 dialect gate (the SAME check `parse-form` applies per form) over
  every form of `ns-sym` already in `store`. The import path parses a whole
  namespace at once, so it can't gate through `parse-form` — this closes the
  hole. Returns an error string naming EVERY offending form (a whole-ns import
  otherwise has to be re-sent once per host form, discovering them one rejection
  at a time), or nil if all are admissible. `^:unsafe` forms pass exactly as on
  the edit path: a host form can only ENTER the store already marked, so it is
  never frozen (un-editable) against a later edit of its own body."
  [store ns-sym]
  (let [violations (keep (fn [e]
                           (when-let [err (dialect-check (:node e))]
                             (str "  " (or (:name e) "?") ": " err)))
                         (store/forms store ns-sym))]
    (when (seq violations)
      (str (if (= 1 (count violations))
             "1 form uses a denylisted symbol — mark it ^:unsafe"
             (str (count violations) " forms use denylisted symbols — mark each ^:unsafe"))
           " if the boundary code is intentional:\n"
           (str/join "\n" violations)))))

(def spawning-vars
  "Vars whose call spawns slopp images/sessions/JVMs. A deftest that touches
  one CANNOT run in-image — per-write verification runs tests in the image,
  and these vars spawn images: the run would recurse (Q7). Resolution is
  alias-based (see require-aliases); fully-qualified calls hit directly."
  '#{slopp.api/open! slopp.api/restart! slopp.api/isolated-test-run!
     slopp.repl/start! slopp.git/start-server!
     slopp.sync/clone! slopp.sync/import! slopp.sync/pull!
     slopp.sync/maybe-auto-import!
     slopp.mcp/call! slopp.mcp/call-main! slopp.mcp/serve! slopp.mcp/-main
     slopp.boot/-main slopp.benchmark/-main})
(defn require-aliases
  "{alias full-ns} (plus identity entries for the full names) from `ns-sym`'s
  ns-form :require clauses — the resolution context for gates that need to
  know WHAT a form calls (isolation-refusal). :refer'd bare names are not
  resolved — alias-style requires are the store convention."
  [store ns-sym]
  (let [ns-form (some #(let [s (try (n/sexpr (:node %)) (catch Exception _ nil))]
                         (when (and (seq? s) (= 'ns (first s))) s))
                      (store/elements store ns-sym))]
    (into {}
          (for [clause (drop 2 (or ns-form ()))
                :when  (and (seq? clause) (= :require (first clause)))
                spec   (rest clause)
                :let   [[lib alias] (cond
                                      (vector? spec) [(first spec)
                                                      (second (drop-while #(not= :as %) spec))]
                                      (symbol? spec) [spec nil])]
                :when  lib
                entry  (cond-> [[lib lib]] alias (conj [alias lib]))]
            entry))))
(defn isolation-refusal
  "Q7 gate — nil when `node` may run in-image; the refusal string (naming the
  fix) when it's an untagged deftest that calls a spawning var and would
  recurse under in-image verification. `aliases` = require-aliases of the
  target ns; fully-qualified calls resolve through the identity entries."
  [aliases node]
  (let [s (try (n/sexpr node) (catch Exception _ nil))]
    (when (and (seq? s)
               (contains? '#{deftest clojure.test/deftest} (first s))
               (symbol? (second s))
               (not (:isolated (meta (second s)))))
      (when-let [hit (some (fn [sym]
                             (let [q (when-let [a (some-> (namespace sym) symbol)]
                                       (when-let [full (aliases a)]
                                         (symbol (str full) (name sym))))]
                               (when (contains? spawning-vars (or q sym)) sym)))
                           (all-symbols node))]
        (str "this test calls " hit " — it spawns slopp images/sessions, and"
             " running it in-image would recurse. Tag it ^:isolated:"
             " (deftest ^:isolated " (second s) " …) — isolated tests run in"
             " the external suite (test_run {:isolated true})")))))
(defn missing-form-error
  "Q9: a 'no form named X' that TEACHES — names near-miss forms in the ns (or
  points at query_outline) so the next call succeeds instead of guessing.
  Returns the whole {:error msg} map; every no-such-form site shares it."
  [store ns-sym nm]
  (let [names (keep :name (store/forms store ns-sym))
        q     (str/lower-case (str nm))
        near  (->> names
                   (filter #(let [s (str/lower-case (str %))]
                              (or (str/includes? s q) (str/includes? q s))))
                   (take 5)
                   seq)]
    {:error (str "no form named " nm " in " ns-sym
                 (if near
                   (str " — nearest: " (str/join ", " near))
                   (str " — query_source {ns " ns-sym "} lists what exists")))}))
(defn replace-form
  "Pure edit: validate `new-source` (one dialect-legal form) and replace the form
  named `form-name` in `ns-sym`, keeping its id and appending a `:replace` delta.
  Returns {:store :delta :warnings} (warnings = D6 `!`-effect violations of the
  resulting namespace) or {:error msg}."
  [store ns-sym form-name new-source & {:keys [prompt agent]}]
  (let [{:keys [node error]} (parse-form new-source)]
    (cond
      error {:error error}

      (isolation-refusal (require-aliases store ns-sym) node)
      {:error (isolation-refusal (require-aliases store ns-sym) node)}

      :else
      (if-let [[store' delta] (store/replace-node store ns-sym form-name node
                                                  :prompt prompt :agent agent)]
        (if-let [merr (modules/module-refusal store' ns-sym
                                      (or (store/form-symbol node) form-name))]
          {:error merr}
          {:store    store'
           :delta    delta
           :warnings (ns-warnings store' ns-sym)})
        (missing-form-error store ns-sym form-name)))))

(defn hot-load-form!
  "Hot-reload one form (from a store VALUE — commit only on success, S1) into
  the image, padded with newlines to its VFS row and attributed to its VFS
  path so stack traces cite the exact lines `query-source` shows (F6).
  Returns nil on success, or the compile/load error message."
  [image store form-id]
  (let [ns-sym  (store/ns-of-form-id store form-id)
        elems   (store/elements store ns-sym)
        idx     (first (keep-indexed
                        (fn [i e] (when (= form-id (:id e)) i)) elems))
        [row _] (nth (render/element-offsets store ns-sym) idx)
        src     (n/string (:node (nth elems idx)))
        padded  (if (>= row 2)
                  (str "(in-ns '" ns-sym ")\n"
                       (apply str (repeat (- row 2) "\n")) src)
                  (str "(in-ns '" ns-sym ") " src))]
    (:err (repl/load-checked! image padded (render/ns-path ns-sym)))))

(defn apply-replace!
  "Pipeline through hot-reload over `system` {:store store :image handle}:
  `replace-form`, then redefine the form in the live image (D5) — a form that
  fails to COMPILE rejects the whole edit (S1; nothing to commit). Returns
  {:system {:store ...} :delta :warnings} or {:error msg}."
  [system ns-sym form-name new-source & {:keys [prompt]}]
  (let [r (replace-form (:store system) ns-sym form-name new-source :prompt prompt)]
    (cond
      (:error r) r

      :else
      (if-let [err (hot-load-form! (:image system) (:store r)
                                   (:form-id (:delta r)))]
        {:error (str "form failed to compile: " err)}
        {:system   (assoc system :store (:store r))
         :delta    (:delta r)
         :warnings (:warnings r)}))))

(defn remove-require-source
  "Symmetric counterpart of add-require-source: structurally remove the
  require spec for `lib` from an ns form's source. Returns {:src new-src} or
  {:error msg}."
  [ns-source lib]
  (try
    (let [zloc (z/of-string ns-source)
          rq   (z/find-value zloc z/next :require)]
      (if-not rq
        {:error "no :require clause"}
        (let [spec (->> (z/right rq)
                        (iterate z/right)
                        (take-while some?)
                        (filter #(let [s (z/sexpr %)]
                                   (or (= lib s)
                                       (and (vector? s) (= lib (first s))))))
                        first)]
          (if-not spec
            {:error (str lib " is not required")}
            (let [root   (z/root-string (z/remove spec))
                  zloc2  (z/of-string root)
                  clause (z/up (z/find-value zloc2 z/next :require))
                  vals*  (remove #(or (n/whitespace? %) (n/comment? %))
                                 (n/children (z/node clause)))]
              ;; drop the whole clause if only `:require` itself remains
              {:src (if (= 1 (count vals*))
                      (z/root-string (z/remove clause))
                      root)})))))
    (catch Exception e
      {:error (str "remove-require failed: " (ex-message e))})))
(defn cold-load-errors
  "The cold-load half of the compile gate: nil when every ns in `ns-syms`
  renders to a namespace a FRESH load can resolve top-to-bottom; else one
  actionable message naming each forward reference. Hot-loading into the
  live image cannot see these (the vars already exist there) — without this
  check a write can commit a store that boot/restart cannot load."
  [store ns-syms]
  (let [findings (mapcat (fn [ns-sym]
                           (map #(assoc % :ns ns-sym)
                                (index/forward-refs
                                 (index/analyze (render/render-ns store ns-sym))
                                 ns-sym)))
                         (distinct ns-syms))]
    (when (seq findings)
      (str "would not cold-load (a fresh namespace load fails): "
           (str/join "; "
                     (map (fn [{:keys [ns form symbol row def-row]}]
                            (str ns "/" (or form "<top-level>") " (line " row
                                 ") references " symbol
                                 " defined later (line " def-row ") — fix: edit_move {ns "
                                 ns " name " (name symbol) " before "
                                 (or form "<the referencing form>") "}"))
                          findings))
           " — or add (declare ...)"))))
(defn lint-refusals
  "NEW error-level kondo findings a candidate store would introduce over
  its base. An error IN one of the forms being written (`written-fids`)
  returns {:refuse msg} — your own form must be well-formed. New errors in
  OTHER forms (stale callers after an incremental signature change) don't
  block the REPL flow: they return as {:carried [{:form :type :message}]}
  and the done-point re-checks them hard. nil when clean. Error-level
  lint is ~never a false positive (two 'invalid-arity' errors once
  dismissed as noise were real ArityExceptions in shipped handlers);
  pre-existing errors never block (no deadlock on legacy)."
  [base cand ns-syms written-fids]
  (let [written (set written-fids)
        errs    (fn [store ns-sym]
                  (when (get-in store [:namespaces ns-sym])
                    (->> (index/lint (render/render-ns store ns-sym))
                         (filter #(= :error (:level %)))
                         (map #(assoc % :ns ns-sym)))))
        key*    (juxt :ns :type :message)
        news    (mapcat (fn [ns-sym]
                          (let [old (set (map key* (errs base ns-sym)))]
                            (remove #(old (key* %)) (errs cand ns-sym))))
                        (distinct ns-syms))
        located (map (fn [f]
                       (let [e (render/owner-form cand (:ns f) (:row f) (:col f))]
                         (assoc f :form-id (:id e)
                                :form (when e
                                        (symbol (str (:ns f))
                                                (str (or (:name e) (:id e))))))))
                     news)
        [own carried] ((juxt filter remove) #(contains? written (:form-id %)) located)]
    (cond
      (seq own)
      {:refuse (str "lint ERROR in the form you are writing: "
                    (str/join "; " (map #(str (:ns %) ": " (name (:type %))
                                              " — " (:message %))
                                        own))
                    " — error-level kondo findings are almost never false"
                    " positives; fix the form before sending it"
                    (when (some #(= :invalid-arity (:type %)) own)
                      " (changing a signature? change_signature rewrites the defn AND its call sites as one intent)"))}

      (seq carried)
      {:carried (vec (for [f carried]
                       {:form (:form f) :type (:type f) :message (:message f)}))}

      :else nil)))
