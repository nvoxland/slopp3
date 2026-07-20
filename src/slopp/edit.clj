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
            [slopp.repl :as repl] [slopp.edit.modules :as modules] [slopp.edit.refs :as refs] [clojure.set :as set]))

(def ^:private banned-heads
  "D4 — user macros are banned."
  '#{defmacro})

^:unsafe (def ^:private banned-syms
  "D3 — the analysis-defeater denylist (extensible). The RESOLVERS
  (requiring-resolve, resolve, ns-resolve, find-var, intern) are here per
  the reference-carrier decision: mentions of var names in strings or
  quoted symbols are INERT data, so the gate blocks the moment they could
  BECOME a var — carriers (store/late-ref) or ^:unsafe are the sanctioned
  paths. The METADATA MUTATORS (alter-meta!, reset-meta!) are here because
  slopp reads markers (^:export, ^:unsafe, ^:reads, :malli/schema) straight
  off the STORED node — metadata must be SOURCE-only truth, so mutating a
  reference's metadata at runtime (invisible to analysis) is refused;
  with-meta/vary-meta return NEW values and are unaffected."
  '#{eval alter-var-root binding gen-class definline read-string
     requiring-resolve resolve ns-resolve find-var intern
     alter-meta! reset-meta!})

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

(def ^:private local-binder-heads
  "Heads that introduce LOCAL names. Built partly from strings for the same
  reason `pair-binding-heads` is: naming binding/with-redefs as symbols would
  trip D3 in this very namespace."
  (into '#{defn defn- fn fn* let let* loop loop* doseq for if-let when-let
           if-some when-some with-open}
        (map symbol)
        ["binding" "with-redefs" "with-local-vars"]))

(defn- local-name?
  "Is `sym` bound as a LOCAL name anywhere in sexpr `s` — a parameter vector or
  a let-style binding vector, destructuring included?

  Used ONLY to explain a D3 refusal, never to permit one. A local named
  `binding` cannot invoke `clojure.core/binding` (locals shadow it), so the
  refusal IS a false positive — but permitting on that basis needs real scope
  tracking to be sound, and a denylist with a hole is worse than one with a
  confusing message. So: still refuse, and say why. Over-matching here costs a
  slightly wrong hint and nothing else."
  [s sym]
  (boolean
   (some (fn [node]
           (and (seq? node)
                (contains? local-binder-heads (first node))
                (some (fn [v]
                        (and (vector? v)
                             (some #{sym} (filter symbol? (tree-seq coll? seq v)))))
                      (tree-seq coll? seq node))))
         (tree-seq coll? seq s))))

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
               (cond
                 (local-name? s hit)
                 (str " — you are using it as a LOCAL name, which cannot invoke"
                      " clojure.core/" hit " at all (locals shadow); the gate"
                      " matches symbol NAMES regardless of position, so RENAME"
                      " the local (binding → bnd, eval → ev) — ^:unsafe is the"
                      " wrong tool here")

                 (#{"requiring-resolve" "resolve" "ns-resolve" "find-var"}
                  (name hit))
                 (str " — references resolve through CARRIERS:"
                      " (store/late-ref 'ns/name) for load-cycle late binding,"
                      " #'ns/name for in-process references in data; mark the"
                      " form ^:unsafe only if you truly own the obligation")
                 (#{"alter-meta!" "reset-meta!"} (name hit))
                 (str " — metadata is SOURCE-only truth in slopp: markers"
                      " (^:export, ^:unsafe, ^:reads, :malli/schema) are read"
                      " straight off the stored form, so write the metadata ON"
                      " the form itself; runtime metadata mutation is invisible"
                      " to analysis. with-meta/vary-meta return new values; mark"
                      " the form ^:unsafe only if you truly own the obligation"))))
        :else nil))))

^:unsafe (def ^:private observe-banned
  "query-eval may observe anything (including calling effectful fns) but never
  (re)define code — that would bypass the delta/provenance pipeline (T5)."
  '#{def defn defn- defmacro defonce deftype defrecord defprotocol defmulti
     defmethod in-ns ns ns-unmap ns-unalias alter-var-root intern remove-ns
     create-ns load-file load-string})

(defn pure-eval-refusal
  "nil when sexpr `x` looks READ-ONLY, else a teaching error string — the
  gate for the store-value oracle (query_store), which must never write.
  Conservative, quote-pruned symbol walk (refs/walk-pruned) refusing:
  `!`-enders (the effect convention), def-family and redefinition forms,
  java interop (method calls, constructors — arbitrary IO hides there),
  and an explicit denylist of IO/eval/binding entry points. Pure analysis
  needs none of those.

  This list is SELF-SUFFICIENT on purpose. The RESOLVERS
  (requiring-resolve/resolve/ns-resolve/find-var) are the subtle ones: they
  are an arbitrary-code escape — `((requiring-resolve 'clojure.java.shell/sh)
  \"rm\" \"-rf\" \"/\")` is not an effect name, and the dangerous target is a
  QUOTED symbol, which `walk-pruned` prunes, so nothing else here would ever
  see it. They were historically blocked only as a side effect of query_store
  parsing through `parse-form` (the D3 DIALECT gate), whose list exists for a
  different reason entirely — keeping STORED code statically analyzable. A
  security property must not rest on a coincidence in someone else's list, so
  the sandbox now names them itself; see orientation-test/
  sandbox-refuses-resolver-escapes, which fails if that stops being true."
  [x]
  (let [deny (set (str/split (str "spit slurp eval read-string load-string"
                                  " load-file load require use import intern"
                                  ;; resolvers = arbitrary-code escape (above)
                                  " requiring-resolve resolve ns-resolve find-var"
                                  " in-ns remove-ns ns-unmap alter-var-root"
                                  " set! with-redefs with-redefs-fn"
                                  " push-thread-bindings future future-call"
                                  " agent send send-off pmap pcalls promise"
                                  " proxy reify deftype defrecord definterface"
                                  " gen-class definline new def defn defn- defmacro"
                                  " defmethod defmulti defonce defprotocol"
                                  " extend extend-type extend-protocol binding"
                                  " shutdown-agents add-watch remove-watch")
                             #" "))
        deny-ns #{"System" "java.lang.System" "clojure.java.io"
                  "clojure.java.shell" "java.io" "java.nio.file"}
        bad? (fn [f]
               (when (symbol? f)
                 (let [nm (name f)]
                   (when (or (str/ends-with? nm "!")
                             (str/starts-with? nm ".")
                             (str/ends-with? nm ".")
                             (contains? deny nm)
                             (contains? deny-ns (namespace f)))
                     [f]))))]
    (when-let [f (first (refs/walk-pruned bad? x))]
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
  '#{slopp.api/open! slopp.api/restart! slopp.api/external-test-run!
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
               ;; BOTH spellings during the ^:external -> ^:external migration. A live
               ;; gate enforcing a marker cannot be renamed atomically WITH the
               ;; marker: it runs from the OLD compiled code while the group
               ;; rewrites it, so a one-shot sweep is refused at the first test
               ;; it re-tags. Accepting either is what makes the sweep possible.
               ;; ONE spelling. Tolerating the old `^:isolated` too would be WORSE
               ;; than rejecting it: the runner (`test-var-tiers`) reads
               ;; `:external`, so a tolerated old marker would pass this gate
               ;; and then run in-image and recurse — the two checks
               ;; disagreeing, which is this codebase's recurring failure.
               ;;
               ;; Renaming the marker needed a two-phase migration precisely
               ;; because this gate enforces it: a live gate runs from the OLD
               ;; compiled code while a sweep rewrites it, so it must accept
               ;; both for one step, then tighten. The sweep also rewrote the
               ;; comment that said so — prose describing a rename is not
               ;; exempt from the rename.
               (not (:external (meta (second s)))))
      (when-let [hit (some (fn [sym]
                             (let [q (when-let [a (some-> (namespace sym) symbol)]
                                       (when-let [full (aliases a)]
                                         (symbol (str full) (name sym))))]
                               (when (contains? spawning-vars (or q sym)) sym)))
                           (all-symbols node))]
        (str "this test calls " hit " — it spawns slopp images/sessions, and"
             " running it in-image would recurse. Tag it ^:external:"
             " (deftest ^:external " (second s) " …) — external tests run in"
             " the external suite (test_run {:external true})")))))
(defn ambiguous-form-error
  "nil when exactly one element of `ns-sym` bears on `nm`; otherwise the
  refusal every destructive write shares — the sibling of
  `missing-form-error`.

  The hazard is a LEGACY hand-written `(declare nm)` sitting beside
  `(defn nm …)`. Note these do NOT collide in `forms-named`: a declare carries
  no `:name` and contributes no `:names`, so it is invisible to name
  addressing entirely. That invisibility is the bug, not a tie — deleting `nm`
  removes the DEFINITION, silently leaving an orphan declare that keeps the
  var interned and unbound, and which no name-addressed tool can then reach.
  So declares are found by reading their sexpr, not by name lookup.

  A pipeline-owned `^{:auto-declare \"<why>\"}` declare is NOT a hazard and is
  excluded: slopp inserts it deliberately for a genuine cycle, removes it once
  the cycle breaks, and `fix-declares!` must keep being able to edit around
  it. Refusing on those would break the very machinery that retires them."
  [store ns-sym nm]
  (let [named (store/forms-named store ns-sym nm)
        decls (filter (fn [f]
                        (and (nil? (:name f))
                             ;; pipeline-owned declares are managed, not legacy
                             (not (str/includes? (n/string (:node f))
                                                 ":auto-declare"))
                             (let [s (try (n/sexpr (:node f))
                                          (catch Exception _ nil))]
                               (and (seq? s)
                                    (= 'declare (first s))
                                    (contains? (set (rest s)) (symbol (str nm)))))))
                      (store/forms store ns-sym))
        cands (concat named decls)]
    (when (< 1 (count cands))
      {:error (str "ambiguous: " (count cands) " forms in " ns-sym
                   " bear on " nm " (a legacy declare beside its definition)"
                   " — deleting or replacing by name would hit the definition"
                   " and strand the declare. cleanup {ns \"" ns-sym "\"}"
                   " retires the declare and leaves exactly one")
       :candidates (mapv (fn [f]
                           (cond-> {:id (:id f)}
                             (:name f)        (assoc :name (:name f))
                             (nil? (:name f)) (assoc :kind :declare)))
                         cands)})))

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
(defn anchor-error
  "Compile/exception text with VFS coordinates → the anchor agents can act
  on: {:form qsym :at \"snippet\"} — the owning form plus the trimmed
  offending line, paste-ready for edit_subform/query_slice match. nil when
  the text carries no resolvable location (the caller keeps the raw
  message). Agents never consume file:line — reads are name-addressed and
  edits are anchor-addressed; this is the translation, applied once at the
  boundary."
  [store err]
  (when err
    (when-let [[_ path line] (re-find #"\(([\w/._-]+\.clj):(\d+)(?::\d+)?\)"
                                      (str err))]
      (let [nsx (symbol (-> path
                            (str/replace #"\.clj$" "")
                            (str/replace "/" ".")
                            (str/replace "_" "-")))]
        (when (contains? (:namespaces store) nsx)
          (let [row (parse-long line)
                e   (render/owner-form store nsx row 1)
                at  (nth (str/split-lines (render/render-ns store nsx))
                         (dec row) nil)]
            (when (or e at)
              (cond-> {}
                e  (assoc :form (symbol (str nsx) (str (or (:name e) (:id e)))))
                at (assoc :at (str/trim at))))))))))
(defn compile-error
  "The standard compile-failure result every 'failed to compile' surface
  returns: `{:error <prefix + clean message> :form qsym :at snippet}` when
  the error's VFS coordinate resolves against `store`, else just
  `{:error <prefix + clean message>}`. The `(file.clj:line:col)` coordinate
  is ALWAYS stripped from the message — row/col never reaches an agent, even
  as a fallback (a coordinate no tool consumes is noise, not a clue).
  `prefix` is the op label ('rename failed to compile: ')."
  [store err prefix]
  (let [clean (str prefix
                   (str/trim (str/replace (str err)
                                          #"\s*(?:at\s+)?\([\w/._-]+\.clj:\d+(?::\d+)?\)\.?"
                                          "")))]
    (if-let [a (anchor-error store err)]
      (assoc a :error clean)
      {:error clean})))
(defn require-cycles
  "Namespace require CYCLES reachable from `ns-syms`, as a vector of paths
  `[a b a]`. Empty when the require graph is acyclic from those roots.

  A cycle is a cold-load failure Clojure reports as `Cyclic load dependency`,
  and until this existed nothing in slopp could see one:

  - `forward-refs` (what `cold-load-errors` was built on) is INTRA-namespace —
    it finds a form referencing a later form in the same file, not a namespace
    requiring one that requires it back;
  - module-edge cycle detection is between MODULES, so it says nothing about
    two namespaces inside one module.

  Which is exactly the hole a `move_forms` fell into: the move added a require
  to the source namespace for callers it was itself moving, leaving it
  requiring a namespace it referenced nowhere. Hot-loading tolerates that —
  the vars already exist in the image — so in-image verification went GREEN
  over a store no fresh JVM could load.

  Only in-store namespaces are edges; external libs cannot participate."
  [store ns-syms]
  (let [known (set (keys (:namespaces store)))
        edges (fn [n] (filter known (store/ns-requires store n)))
        walk  (fn walk [n seen path]
                (if (contains? seen n)
                  [(conj path n)]
                  (mapcat #(walk % (conj seen n) (conj path n)) (edges n))))]
    (vec (distinct (mapcat #(walk % #{} []) (distinct ns-syms))))))

(defn cold-load-errors
  "The cold-load half of the compile gate: nil when every ns in `ns-syms`
  renders to a namespace a FRESH load can resolve — top-to-bottom AND without
  a require cycle; else one actionable message.

  TWO failure shapes, because there are two ways a fresh load dies:

  - a FORWARD REFERENCE inside a namespace (`index/forward-refs`);
  - a REQUIRE CYCLE between namespaces (`require-cycles`) — Clojure's
    `Cyclic load dependency`.

  Hot-loading into the live image cannot see either: the vars already exist
  there. Without this check a write commits a store that boot/restart cannot
  load. The cycle half was added after a `move_forms` group did exactly that
  and verified GREEN — the gate was there, it simply could not see cycles."
  [store ns-syms]
  (let [cycles   (require-cycles store ns-syms)
        findings (mapcat (fn [ns-sym]
                           (map #(assoc % :ns ns-sym)
                                (index/forward-refs
                                 (index/analyze (render/render-ns store ns-sym))
                                 ns-sym)))
                         (distinct ns-syms))]
    (cond
      (seq cycles)
      (str "would not cold-load — require CYCLE: "
           (str/join "; " (map #(str/join " -> " %) cycles))
           ". A fresh load fails on this even though the live image tolerates"
           " it (the vars are already there). Break the cycle: drop a require"
           " that is no longer referenced, or move the shared code into a"
           " namespace both can depend on.")

      (seq findings)
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
(def write-coherence-lint
  "The kondo finding types that refuse a WRITE. Everything else kondo reports
  is a `done`-grain concern.

  These two dials answer DIFFERENT QUESTIONS, which is why there are two of
  them (contrast the duplicated dead-surface scans, which were two copies of
  ONE question and drifted):

  - `index/kondo-config`'s `:level` — *is this codebase finished and clean?*
    `:error` counts at `done`, `:warning` is listed for the agent to judge.
  - THIS set — *is this form internally incoherent right now?*

  A write is BY DEFINITION mid-work, so almost nothing belongs here. Writing
  `(if x y)` on the way to adding an else branch is normal; refusing it is
  not. The bar is: the form cannot be a step toward anything correct.

  Why these five specifically:
  - `:syntax`, `:unresolved-symbol`, `:unresolved-var` — the form does not
    hold together. Compilation catches most of these too, but lint reaches
    them FIRST and with a far better message: the too-narrow-subform-edit
    hint (a binding without its use, a loop without its recur) fires here and
    is the single most common agent edit mistake, cheap to fix the instant it
    happens and expensive to diagnose later.
  - `:invalid-arity`, `:type-mismatch` — these COMPILE FINE and fail at
    runtime. Two `invalid-arity` findings once dismissed as noise in this
    project were real ArityExceptions in shipped handlers.

  Cross-form staleness is already handled elsewhere: findings in OTHER forms
  ride as `:carried` and are re-checked at `done`, so an incremental
  signature change is never blocked by its own not-yet-updated callers."
  #{:syntax :unresolved-symbol :invalid-arity :type-mismatch})

(defn lint-refusals
  "NEW error-level kondo findings a candidate store would introduce over
  its base. An error IN one of the forms being written (`written-fids`)
  returns {:refuse msg} — your own form must be well-formed. New errors in
  OTHER forms (stale callers after an incremental signature change) don't
  block the REPL flow: they return as {:carried [{:form :type :message}]}
  and the done-point re-checks them hard. nil when clean.

  WHAT blocks here is `write-coherence-lint`, NOT a severity level. A write is
  mid-work by definition, so this asks only whether the FORM is internally
  incoherent right now — whether the CODEBASE is finished is `done`'s
  question, decided by `index/kondo-config`'s `:level`. Gating writes on
  severity instead killed red-first TDD, the module lifecycle and carried-lint
  compression (13 assertions, 7 tests), and refused `(if x y)` written on the
  way to adding an else branch.

  These types are ~never false positives (two 'invalid-arity' errors once
  dismissed as noise were real ArityExceptions in shipped handlers);
  pre-existing findings never block (no deadlock on legacy)."
  [base cand ns-syms written-fids]
  (let [written (set written-fids)
        errs    (fn [store ns-sym]
                  (when (get-in store [:namespaces ns-sym])
                    (->> (index/lint (render/render-ns store ns-sym))
                         (filter #(contains? write-coherence-lint (:type %)))
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
                      " (changing a signature? change_signature rewrites the defn AND its call sites as one intent)")
                    ;; the commonest cause of BOTH types on your own form: the
                    ;; edit was too narrow. A binding and its use, a loop and
                    ;; its recur, an arglist and its body must change together
                    ;; — and they are ONE form, so it is ONE edit. Agents
                    ;; reliably misread this as needing atomicity ACROSS forms
                    ;; and reach for a batch primitive that does not exist.
                    (when (some #(#{:unresolved-symbol :invalid-arity} (:type %)) own)
                      (str " — if this was a targeted subform edit, the change"
                           " spans MORE of this form than you matched (a binding"
                           " and its use, a loop and its recur). Widen the match"
                           " to the enclosing form, or edit_replace_form the"
                           " whole thing: two edits to ONE form is ONE edit.")))}

      (seq carried)
      {:carried (vec (for [f carried]
                       {:form (:form f) :type (:type f) :message (:message f)}))}

      :else nil)))
(defn declare-node
  "Build a `(declare …)` form NODE for `names`, optionally carrying the
  `^{:auto-declare \"<why>\"}` marker (a pipeline-owned declare says why it
  exists — markers-carry-their-why). Built with the RAW parser on purpose:
  `parse-form` BANS hand-written declares (D5), and the pipeline's own
  inserts/rewrites must not trip the gate they enforce on agents."
  [names & {:keys [why]}]
  (first (filter n/sexpr-able?
                 (n/children
                  (p/parse-string-all
                   (str (when why (str "^{:auto-declare \"" why "\"}\n"))
                        "(declare " (str/join " " (map str names)) ")"))))))
(defn resolve-cold-load
  "AUTO-AVOID-DECLARE: make `store`'s `ns-sym` cold-load WITHOUT the agent ever
  writing (declare …). Returns {:store <fixed> …} or nil (already cold-loads).
  Two moves, both silent to the agent (the pipeline OWNS form ordering):
  - **Reorder** (acyclic forward ref): definitions moved above their callers
    — {:store :moved n}. A fresh load then resolves top-to-bottom, no declare.
  - **Auto-declare** (a genuine cycle — mutual recursion, no legal order):
    insert a MARKED `^{:auto-declare \"<why>\"} (declare …)` for the cycle
    members — {:store :declared [names]}. The marker's value is the why
    (markers-carry-their-why); `fix-declares!` removes it once the cycle
    breaks. The write pipeline calls this so agents never hand-write declares."
  [store ns-sym & {:keys [prompt agent]}]
  (when (cold-load-errors store [ns-sym])
    (let [{:keys [order cycle]} (refs/cold-load-order store ns-sym)]
      (if cycle
        (let [names  (mapv #(symbol (name %)) cycle)
              why    (str "mutual recursion: " (str/join ", " (map str names)))
              decl   (declare-node names :why why)
              nameset (set names)
              anchor (some #(when (nameset (:name %)) (:name %))
                           (store/forms store ns-sym))
              [st' _] (store/append-form store ns-sym decl
                                         :before anchor
                                         :prompt (or prompt (str "auto-declare: " why))
                                         :agent agent)]
          (when (and st' (nil? (cold-load-errors st' [ns-sym])))
            {:store st' :declared names}))
        (let [names   (mapv #(:name (store/form-by-id store %)) order)
              [st' n] (store/reorder-to store ns-sym names
                                        :prompt (or prompt "auto-reorder: define before use")
                                        :agent agent)]
          (when (and (pos? n) (nil? (cold-load-errors st' [ns-sym])))
            {:store st' :moved n}))))))
(defn parse-one
  "Parse `source` as exactly ONE top-level form — the RAW parse, NO gate.
  Returns {:node node} or {:error msg}; never throws (F3).

  `parse-form` layers the dialect gate (D3/D4 + D7's declare ban) on top of
  this, for the WRITE paths. Read-only callers that carry their OWN gate use
  this directly — notably `query_store`, whose sandbox is
  `pure-eval-refusal`. The dialect denylist exists to keep STORED code
  statically analyzable; a throwaway analysis query is not stored and nothing
  analyzes it, so borrowing that list there refused the right things for the
  wrong reason (and with nonsense teaching about carriers), and would refuse
  MORE for no reason as D3 grows."
  [source]
  (try
    (let [forms (filter n/sexpr-able? (n/children (p/parse-string-all source)))]
      (if (not= 1 (count forms))
        {:error (str "expected exactly one top-level form, got " (count forms))}
        {:node (first forms)}))
    (catch Exception e
      {:error (str "unparseable source (unbalanced?): " (ex-message e))})))
(defn parse-form
  "Parse `source` as exactly ONE dialect-legal top-level form (the gate every
  WRITE shares): `parse-one` for the raw parse, then D3/D4 via `dialect-check`,
  plus D7 — hand-written `(declare …)` is refused here (the EDIT path only):
  the pipeline OWNS ordering — it reorders definitions above their callers, or
  inserts a marked declare itself for a genuine cycle, so the agent never
  writes one. Imports (`dialect-scan`) keep their declares.
  Returns {:node node} or {:error msg} — never throws (F3)."
  [source]
  (let [r (parse-one source)]
    (if (:error r)
      r
      (try
        (let [node (:node r)
              s    (n/sexpr node)]
          (if (and (seq? s) (= 'declare (first s)))
            {:error (str "(declare …) is managed for you — slopp orders forms"
                         " itself: write your forms in any order (definitions are"
                         " reordered above their callers), and a genuine"
                         " mutual-recursion cycle gets a marked declare inserted"
                         " automatically. Drop the declare and write the real forms.")}
            (if-let [err (dialect-check node)]
              {:error err}
              {:node node})))
        (catch Exception e
          {:error (str "unparseable source (unbalanced?): " (ex-message e))})))))

(defn live-handle-shape-change
  "`{:added #{kw} :removed #{kw}}` when replacing `old-node` with `new-node`
  changes the KEY SHAPE of a `^:live-handle` constructor — otherwise nil.

  A `^:live-handle` fn returns a map the SESSION holds across calls
  (`repl/start!`'s image, `git/open-ctx!`'s ctx, `api/open!`'s session). Those
  are the one piece of state a write cannot reach: a cache keyed on its input
  is safe by construction — every memo in this store is — but a handle is
  keyed on NOTHING. It is a resource built once, under one version of the
  code, and passed back forever after.

  So a write can leave the STORE perfectly consistent (constructor and every
  reader rewritten together) while the value already in memory still has the
  old shape. New code, old value: the reader gets nil. That bricked this
  session twice, unrecoverably, because `undo` and `restart` both work through
  the handle they would have repaired.

  Deliberately over-broad — it compares the form's whole keyword-literal set
  rather than trying to identify the returned map, which is often nested
  (`(inject-rt! {…})`). A false positive costs one image rebuild; a false
  negative costs the session."
  [old-node new-node]
  (let [marked? (fn [nd]
                  (let [s (store/form-sexpr nd)]
                    (boolean (and (seq? s) (symbol? (second s))
                                  (:live-handle (meta (second s)))))))
        kws     (fn [nd]
                  (set (filter keyword?
                               (tree-seq coll? seq (store/form-sexpr nd)))))]
    (when (or (marked? old-node) (marked? new-node))
      (let [o (kws old-node) n (kws new-node)]
        (when (not= o n)
          {:added (set/difference n o) :removed (set/difference o n)})))))

(defn contract-drift
  "What replacing `old-node` with `new-node` changed that the author probably
  did not mean to: `[{:kind :metadata-lost|:docstring-lost|:arity-changed
  :detail …}]`, empty when the contract is intact.

  Reported, never refused — dropping a hint, a docstring or an arity is
  sometimes exactly the intent. The point is that it is never SILENT. A
  refactor here once rebuilt a destructuring from `sexpr` and quietly dropped
  `^Repository`, turning direct interop into reflection: it compiled, passed
  every gate, and reported green. The only way to catch that was to re-read
  the form afterwards, which is the agent doing the write result's job."
  [old-node new-node]
  (let [sx    (fn [nd] (try (n/sexpr nd) (catch Exception _ nil)))
        hints (fn [nd]
                ;; every ^Hint in the form's source, by the symbol it rides
                (into {} (for [zl   (->> (iterate z/next (z/of-string (n/string nd)))
                                         (take-while (complement z/end?)))
                               :let [nd* (z/node zl)]
                               :when (= :meta (n/tag nd*))
                               ;; children include the whitespace between ^Hint and the symbol
                               :let [[m v] (filter n/sexpr-able? (n/children nd*))]
                               :when (symbol? (sx v))]
                           [(sx v) (n/string m)])))
        ;; through the shared accessor — indexing here is the same mistake
        ;; that hid nine documented globals from ambient-state
        docs  store/form-docstring
        arits (fn [nd] (mapv count (modules/fn-arglists (sx nd))))
        lost  (remove (fn [[sym _]] (contains? (hints new-node) sym))
                      (hints old-node))]
    (cond-> []
      (seq lost)
      (conj {:kind :metadata-lost
             :detail (into {} (map (fn [[s m]] [s m])) lost)})

      (and (docs old-node) (not (docs new-node)))
      (conj {:kind :docstring-lost})

      (not= (arits old-node) (arits new-node))
      (conj {:kind :arity-changed
             :detail {:was (arits old-node) :now (arits new-node)}}))))

(defn replace-form
  "Pure edit: validate `new-source` (one dialect-legal form) and replace the form
  named `form-name` in `ns-sym`, keeping its id and appending a `:replace` delta.
  Returns {:store :delta :warnings} (warnings = D6 `!`-effect violations of the
  resulting namespace) or {:error msg}.

  Refuses outright when `form-name` addresses TWO elements — the shared
  chokepoint for that check, so every caller of the pure edit inherits it."
  [store ns-sym form-name new-source & {:keys [prompt agent]}]
  (let [ambiguous (ambiguous-form-error store ns-sym form-name)
        {:keys [node error]} (parse-form new-source)]
    (cond
      ambiguous ambiguous

      error {:error error}

      (isolation-refusal (require-aliases store ns-sym) node)
      {:error (isolation-refusal (require-aliases store ns-sym) node)}

      :else
      (let [old (:node (store/form-named store ns-sym form-name))]
        (if-let [[store' delta] (store/replace-node store ns-sym form-name node
                                                    :prompt prompt :agent agent)]
          (let [{:keys [refuse advisories]}
                (modules/gate-check store' ns-sym (or (store/form-symbol node) form-name))
                drift (when old (contract-drift old node))]
            (if refuse
              {:error refuse}
              (cond-> {:store    store'
                       :delta    delta
                       :warnings (ns-warnings store' ns-sym)}
                (seq advisories) (assoc :advisories advisories)
                (seq drift)      (assoc :drift drift))))
          (missing-form-error store ns-sym form-name))))))

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
        (compile-error (:store r) err "form failed to compile: ")
        {:system   (assoc system :store (:store r))
         :delta    (:delta r)
         :warnings (:warnings r)}))))

