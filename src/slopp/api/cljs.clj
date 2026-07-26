(ns slopp.api.cljs
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [slopp.store.render :as render] [slopp.store.build :as build] [slopp.api.external :as external] [slopp.api.testrun :as testrun] [slopp.image.repl :as repl] [slopp.store :as store] [slopp.api.session :as session] [clojure.java.io :as io] [slopp.edit.modules :as edit.modules] [slopp.edit :as edit]))

(def result-marker
  "The line prefix the cljs compile runner prints its EDN summary behind, so the
  parent can extract a machine-readable result from arbitrary compiler chatter."
  "SLOPP-CLJS-RESULT ")

(defn parse-result
  "Extract the cljs compile runner's EDN summary from its captured `output` — the
  text after the `result-marker` line. Returns {:warnings [...] :error ...}, or
  nil when the marker is absent (the runner JVM died before printing, which the
  caller reports as a compile failure)."
  [output]
  (some->> (str/split-lines (str output))
           (some #(when (str/starts-with? % result-marker)
                    (subs % (count result-marker))))
           edn/read-string))

(defn anchor-warnings
  "Anchor cljs compile findings to store FORMS: each `{:ns :line :message ...}`
  becomes `{:form qsym :at snippet :type ... :message ...}` via
  render/owner-form — so a cljs warning reads like a clj compile error
  (name-addressed, no file:line; the anchor discipline). A finding whose ns/line
  don't resolve keeps its message but carries no form anchor."
  [store warnings]
  (vec
   (for [w warnings
         :let [nsx  (some-> (:ns w) symbol)
               row  (:line w)
               ok?  (boolean (and nsx row (contains? (:namespaces store) nsx)))
               e    (when ok? (render/owner-form store nsx row 1))
               at   (when ok? (nth (str/split-lines (render/render-ns store nsx))
                                   (dec row) nil))]]
     (cond-> {:type (:type w) :message (:message w)}
       e  (assoc :form (symbol (str nsx) (str (or (:name e) (:id e)))))
       at (assoc :at (str/trim at))))))

(defn runner-src
  "The self-contained Clojure program (a `-e` string) run in the :cljs-alias JVM
  to compile the materialized client namespaces. It binds a warning handler that
  collects analyzer warnings ({:type :line :ns :message}), catches hard errors,
  and prints an EDN summary behind `result-marker` that parse-result reads. The
  cljs compiler reads whatever .cljs/.cljc it finds under `src/` and `cljs-src/`
  (its .clj siblings are invisible to it). :simple yields one self-contained JS."
  [{:keys [output-to output-dir optimizations]
    :or {output-to "out/main.js" output-dir "out" optimizations :simple}}]
  (str
   "(require '[cljs.build.api :as b] '[cljs.analyzer :as ana])\n"
   "(def ws (atom []))\n"
   "(def dirs (filterv #(.exists (java.io.File. ^String %)) [\"src\" \"cljs-src\"]))\n"
   "(let [res (try\n"
   "            (binding [ana/*cljs-warning-handlers*\n"
   "                      [(fn [wt env extra]\n"
   "                         (when (get ana/*cljs-warnings* wt)\n"
   "                           (swap! ws conj {:type wt :line (:line env)\n"
   "                                           :ns (str (or (get-in env [:ns :name]) ana/*cljs-ns*))\n"
   "                                           :message (str \"WARNING: \" (try (ana/error-message wt extra) (catch Throwable _ (name wt))))})))]]\n"
   "              (b/build (apply b/inputs dirs) {:output-to \"" output-to "\" :output-dir \"" output-dir "\" :optimizations " optimizations "})\n"
   "              nil)\n"
   ;; the WHOLE cause chain plus the ex-data LOCATION. The outermost
   ;; message is usually "failed compiling file:…" and the reason is in a
   ;; cause; the file and line live in ex-data. Keeping only .getMessage
   ;; threw both away, which is why a hard failure used to reach the agent
   ;; as a bare temp-dir path (anchor-error consumes :error-at).
   "            (catch Throwable e\n"
   "              (let [chain (->> (iterate #(.getCause ^Throwable %) e)\n"
   "                               (take-while some?) (take 8))\n"
   ;; the first ex-data carrying a LINE, not merely the first carrying
   ;; ex-data: the outer "failed compiling" exception has ex-data with no
   ;; location, so taking it anchors nothing while looking like it worked.
   "                    d     (first (filter :line (keep ex-data chain)))]\n"
   ;; the messages as a VECTOR, joined by the consumer. A separator string
   ;; literal here sits three levels of escaping deep — source → -e argument
   ;; → generated program — and one level too many turns " / " into a
   ;; character literal, which is exactly how this line first shipped broken.
   ;; Sending data instead of a formatted string removes the nesting.
   "                {:msgs (vec (distinct (keep #(.getMessage ^Throwable %) chain)))\n"
   ;; coerced, because this crosses a PROCESS boundary as EDN: ex-data's
   ;; :file is an object, and pr-str of it comes back as #object[…] which
   ;; the reader on this side refuses. Only readable scalars may cross.
   "                 :at  (when d {:file (str (:file d))\n"
   "                               :line (when (number? (:line d)) (:line d))\n"
   "                               :column (when (number? (:column d)) (:column d))})})))]\n"
   "  (println (str \"" result-marker "\" (pr-str {:warnings @ws :error res}))))\n"))

(defn anchor-error
  "Anchor a hard cljs compile FAILURE to a store form, the way
  [[anchor-warnings]] anchors an analyzer warning: `{:error msg}` plus
  `:form` and `:at` when the location resolves.

  `at` is the compiler's `{:file :line}` — `file` being a path inside the
  throwaway materialization dir, which is precisely why it must not reach the
  agent. slopp's standing invariant is that no `file:line` is ever handed
  out; warnings honoured it and errors did not, because the runner kept only
  `.getMessage` and dropped the `ex-data` carrying the location.

  The path→namespace step is real work, not a string replace: ClojureScript
  munges `-` to `_` in file names, so `app/my_view.cljs` is `app.my-view`.

  A location that does not resolve to a store namespace anchors NOTHING and
  says so by omission rather than guessing — an anchor pointing at the wrong
  form is worse than no anchor (Core 1)."
  [store msg {:keys [file line]}]
  (let [nsx (when file
              (some-> (re-matches #"(?:file:)?(?:.*?/)?((?:[^/]+/)*[^/]+)\.clj[sc]$"
                                  (str file))
                      second
                      (str/replace "/" ".")
                      (str/replace "_" "-")
                      symbol))
        ok? (boolean (and nsx line (contains? (:namespaces store) nsx)))
        e   (when ok? (render/owner-form store nsx line 1))
        at  (when ok? (nth (str/split-lines (render/render-ns store nsx))
                           (dec line) nil))]
    (cond-> {:error (-> (str msg)
                        ;; the compiler names the materialization dir, often
                        ;; more than once. :form and :at carry the location
                        ;; properly now, so the path is both redundant and a
                        ;; breach of the no-file:line invariant — and it
                        ;; points into a temp dir the agent never created.
                        (str/replace #"\s*at line \d+(?::\d+)?" "")
                        (str/replace #"(?:file:)?\S*\.clj[sc]\b" "")
                        (str/replace #"\s*/\s*/\s*" " / ")
                        (str/replace #"\s{2,}" " ")
                        (str/replace #"^[\s/]+|[\s/]+$" ""))}
      e  (assoc :form (symbol (str nsx) (str (or (:name e) (:id e)))))
      at (assoc :at (str/trim at)))))

(defmulti compile-client*
  "Compile the client namespaces materialized under `dir` with a specific
  backend, returning the runner's parsed `{:warnings [...] :error ...}` (or an
  `{:error ...}` for an unknown backend). Dispatches on the compiler keyword so a
  future cherry/squint backend adds a `defmethod` without touching callers
  (D-web-cljs, the pluggable-compiler decision)."
  (fn [compiler _dir] compiler))

(defmethod compile-client* :clojurescript
  [_ dir]
  (let [{:keys [out exit]} (testrun/run-cmd!
                            [repl/clojure-bin "-M:cljs" "-e" (runner-src {})]
                            dir 300000)]
    (or (parse-result out)
        {:warnings []
         :error (str "cljs compile produced no result (exit " exit ") — "
                     (->> (str/split-lines (str out)) (remove str/blank?)
                          (take-last 8) (str/join "\n")))})))

(defmethod compile-client* :default
  [compiler _dir]
  {:warnings []
   :error (str "no client-compiler backend for " compiler
               " — only :clojurescript is implemented today (cherry/squint are"
               " planned). Set the backend with config_file {path \"client\""
               " key \"compiler\" value \"clojurescript\"}.")})

(defn ^:private resolve-schema-ref
  "Resolve a :web/request/:web/response value into a client-usable schema
   reference. A schema VAR symbol (alias- or fully-qualified) →
   {:kind :var :sym <fq> :ns <schema-ns>} when it lives in a :cljc namespace so
   it compiles into BOTH the server oracle and the client bundle; a var that
   can't ship to the client is tagged instead: {:kind :not-cljc …} (wrong
   platform) or {:kind :missing …} (no such var). An inline malli form (vector
   or keyword) → {:kind :inline :schema raw}; nil → {:kind :none}. Alias
   resolution uses the ENDPOINT ns's requires (edit/require-aliases)."
  [store endpoint-ns raw]
  (cond
    (nil? raw)     {:kind :none}
    (symbol? raw)  (let [aliases (edit/require-aliases store endpoint-ns)
                         sns     (some-> (namespace raw) symbol)
                         full-ns (if sns (get aliases sns sns) endpoint-ns)
                         nm      (symbol (clojure.core/name raw))
                         fq      (symbol (str full-ns) (str nm))]
                     (cond
                       (nil? (store/form-named store full-ns nm))
                       {:kind :missing :sym fq :ns full-ns}
                       (not= :cljc (store/platform-for store full-ns))
                       {:kind :not-cljc :sym fq :ns full-ns
                        :platform (store/platform-for store full-ns)}
                       :else {:kind :var :sym fq :ns full-ns}))
    :else          {:kind :inline :schema raw}))

(defn ^:export client-wrapper-specs
  "The generated-client plan (D-web-contracts part 2): one wrapper SPEC per web
   endpoint (`edit.modules/web-endpoint-rows`), with its request/response schemas
   resolved to shippable :cljc vars. Returns {:wrappers [spec …] :problems [p …]}.
   A spec is {:fn-name :method :path :endpoint :request :response} — :fn-name gets
   a ! on a mutating verb (post/put/patch/delete), :request/:response are
   resolve-schema-ref results (only a body verb carries a request). An endpoint
   whose schema can't ship to the client (non-:cljc, or a missing var) is SKIPPED
   and reported as a problem {:endpoint :schema-ref :ns :issue :platform} so the
   generated namespace always compiles. Pure function of the store value."
  [store]
  (reduce
   (fn [acc {:keys [ns name meta]}]
     ;; ^{:web/client false} opts an endpoint OUT of client generation. An HTML
     ;; page is a :web/path form like any other, but a typed fetch wrapper whose
     ;; (.json resp) runs against HTML is nonsense. Declared, never sniffed:
     ;; :string is a legitimate JSON response, so the response schema cannot
     ;; decide this — only the endpoint can.
     (if (false? (:web/client meta))
       acc
       (let [endpoint (symbol (str ns) (str name))
             method   (:web/method meta)
             req      (resolve-schema-ref store ns (:web/request meta))
             resp     (resolve-schema-ref store ns (:web/response meta))
             bad      (vals (into {} (map (juxt :sym identity))
                                 (filter (comp #{:not-cljc :missing} :kind) [req resp])))]
         (if (seq bad)
           (update acc :problems into
                   (for [b bad] {:endpoint endpoint :schema-ref (:sym b)
                                 :ns (:ns b) :issue (:kind b) :platform (:platform b)}))
           (update acc :wrappers conj
                   {:fn-name  (symbol (str name
                                       ;; not a second bang: a mutating endpoint
                                       ;; named with one is already following the
                                       ;; dialect's convention, and `pay!!` is the
                                       ;; generator fighting the house style
                                       (when (and (#{:post :put :patch :delete} method)
                                                  (not (.endsWith (str name) "!")))
                                         "!")))
                    :method   method
                    :path     (:web/path meta)
                    :endpoint endpoint
                    :request  (if (#{:post :put :patch} method) req {:kind :none})
                    :response resp})))))
   {:wrappers [] :problems []}
   (edit.modules/web-endpoint-rows store)))

(defn ^:private schema-form
  "The cljs code for a resolved schema ref: the fully-qualified var symbol (a
   :var) or the inline malli form pr-str'd (an :inline); nil for :none."
  [resolved]
  (case (:kind resolved)
    :var    (str (:sym resolved))
    :inline (pr-str (:schema resolved))
    nil))

(defn ^:private path-expr
  "The cljs path expression for a route: the literal path string, or a (str …)
   that substitutes each :segment with (:segment params)."
  [path]
  (let [segs (str/split path #"/" -1)]
    (if (not-any? #(str/starts-with? % ":") segs)
      (pr-str path)
      (let [parts     (mapcat (fn [s]
                                (if (str/starts-with? s ":")
                                  [(list (keyword (subs s 1)) 'params)]
                                  [s]))
                              segs)
            joined    (interpose "/" parts)
            collapsed (reduce (fn [acc x]
                                (if (and (string? x) (string? (peek acc)))
                                  (conj (pop acc) (str (peek acc) x))
                                  (conj acc x)))
                              [] joined)]
        (pr-str (cons 'str collapsed))))))

(defn ^:private render-wrapper
  "One typed fetch wrapper defn (a source string) for a client-wrapper-specs
   spec: marked ^{:generated <endpoint>} (provenance + edit-protection) and
   ^:export (client-API surface), validating the request against its schema
   before send and the response after receive (malli + json-transformer at the
   JSON boundary). A body verb takes/validates a params map; path :segments are
   interpolated from that map."
  [{:keys [fn-name method path endpoint request response]}]
  (let [verb      (str/upper-case (clojure.core/name method))
        req-code  (schema-form request)
        resp-code (schema-form response)
        params?   (boolean (or req-code (some #(str/starts-with? % ":")
                                              (str/split path #"/"))))
        arglist   (if params? "[params]" "[]")
        opts      (if req-code
                    (str "(clj->js {:method \"" verb "\" "
                         ":headers {\"Content-Type\" \"application/json\"} "
                         ":body (js/JSON.stringify (clj->js (m/encode " req-code
                         " params (mt/json-transformer))))})")
                    (str "(clj->js {:method \"" verb "\"})"))
        validate  (when req-code
                    (str "  (when-not (m/validate " req-code " params)\n"
                         "    (throw (ex-info \"" fn-name " request failed validation\"\n"
                         "                    {:errors (m/explain " req-code " params)})))\n"))
        handle    (if resp-code
                    (str "      (.then (fn [body]\n"
                         "               (let [data (m/decode " resp-code
                         " (js->clj body :keywordize-keys true) (mt/json-transformer))]\n"
                         "                 (when-not (m/validate " resp-code " data)\n"
                         "                   (throw (ex-info \"" fn-name " response failed validation\"\n"
                         "                                   {:errors (m/explain " resp-code " data)})))\n"
                         "                 data))))")
                    "      (.then (fn [body] body)))")]
    (str "(defn ^{:generated \"" endpoint "\"} ^:export " fn-name "\n"
         "  \"" verb " " path " — generated client wrapper (D-web-contracts).\"\n"
         "  " arglist "\n"
         (or validate "")
         "  (-> (js/fetch " (path-expr path) " " opts ")\n"
         "      (.then (fn [resp] (.json resp)))\n"
         handle ")")))

(defn ^:export render-client-ns
  "Render the generated typed-client namespace SOURCE (a string) from wrapper
   specs (client-wrapper-specs :wrappers): a ns form requiring malli + every
   schema's :cljc contracts namespace, then one typed fetch wrapper per endpoint.
   The whole namespace is generate_client's output — every form is ^:generated
   (edit-protected + inspection-exempt), and the schema references are real store
   references so 'edit a schema → every affected client call' falls out of the
   graph. Pure."
  [ns-sym wrappers]
  (let [schema-nses (->> wrappers
                         (mapcat (juxt #(get-in % [:request :ns])
                                       #(get-in % [:response :ns])))
                         (remove nil?) distinct sort)
        requires    (str "(:require [malli.core :as m]\n"
                         "            [malli.transform :as mt]"
                         (apply str (for [n schema-nses] (str "\n            " n)))
                         ")")]
    (str "(ns " ns-sym "\n  " requires ")\n\n"
         (str/join "\n\n" (map render-wrapper wrappers)))))

(defn- served-by-a-mount?
  "Whether any `http.static.*` mount would serve `path`.

  A mount key's tail is the URL prefix and its value a files-manifest path
  prefix (`http.static./js = public/cljs` serves `public/cljs/main.js` at
  `/js/main.js`), so the question is just whether some mount's value is a
  prefix of the written path.

  NOT the same question as \"is this file reachable over HTTP\", and the
  caller's message must not say it is. An ENDPOINT that reads the file serves
  it just as well — slopp's own reviewer UI does exactly that, and this
  predicate reported the bundle unserved while `/js/main.js` was returning it
  with a 200. Detecting that case generically means scanning source for the
  path as a string, which a docstring mentioning it would trip; saying what
  was actually checked is both cheaper and honest."
  [store path]
  (boolean
   (some (fn [[k v]]
           (and (re-matches #"http\.static\..+" (str k))
                (str/starts-with? (str path) (str v))))
         (get-in store [:config "capabilities" :values]))))

(defn ^:export compile-client!
  "Compile the store's CLIENT namespaces (:cljc + :cljs) to JavaScript with the
  configured backend (build/client-compiler, default :clojurescript) and record
  the output as a served blob — the client wave's compile-error-as-oracle
  (D-web-cljs). Materializes the store (build!) into a throwaway dir whose
  generated deps.edn carries the :cljs alias — build! injects slopp's OWN
  toolchain there (the compiler + malli), so the agent never hand-adds slopp's
  plumbing. Shells the compiler in a fresh JVM (no Node — real
  org.clojure/clojurescript on the JVM), then anchors warnings to store forms
  (name-addressed, no file:line) and stores the JS (file_put). Returns {:compiled
  <ns-count> :warnings [...anchored...] :output <path> :bytes n} on success,
  {:error ... :warnings ...} on a compile failure, or {:note ...} when there is
  no client code. `:output` sets the served path (default \"public/cljs/main.js\")."
  [session & {:keys [output] :or {output "public/cljs/main.js"}}]
  (let [st       (:store @session)
        compiler (build/client-compiler st)
        client   (filterv #(#{:cljc :cljs} (store/platform-for st %))
                          (keys (:namespaces st)))]
    (if (empty? client)
      {:note (str "no client namespaces to compile — declare one :cljc or :cljs"
                  " via module_platform")}
      (let [dir (str (java.nio.file.Files/createTempDirectory
                      "slopp-cljs"
                      (make-array java.nio.file.attribute.FileAttribute 0)))]
        (try
          (let [b (external/build! session dir)]
            (if (:error b)
              b
              (let [{:keys [warnings error]} (compile-client* compiler dir)
                    anchored (anchor-warnings st (or warnings []))]
                (if error
                  ;; the runner sends {:msg :at}; a legacy/fallback string still
                  ;; works, it simply anchors nothing
                  (merge (if (map? error)
                           (anchor-error st (str/join " / " (:msgs error)) (:at error))
                           (anchor-error st (str error) nil))
                         {:warnings anchored})
                  (let [js (slurp (io/file dir "out" "main.js"))]
                    (session/commit-appended!
                     session
                     (fn [s] (first (store/record-file-put s output js)))
                     [])
                    ;; A bundle nothing serves is the failure this just had: slopp's own sat
                    ;; in the manifest for two waves while every page 404'd on it,
                    ;; because serving it needs an http.static.* mount and nothing
                    ;; said so. Serving it IS one line — the gap was
                    ;; discoverability, so the tool that wrote the file names the
                    ;; line, and goes quiet once a mount covers the path.
                    (cond-> {:compiled  (count client)
                             :namespaces (mapv str (sort client))
                             :warnings  anchored
                             :output    output
                             :bytes     (count js)}
                      (not (served-by-a-mount? (:store @session) output))
                      (assoc :serve-with
                             (let [dir (or (second (re-matches #"(.*)/[^/]+" output))
                                           output)]
                               (str "no http.static mount serves " output
                                    " — config_file {path \"capabilities\" key"
                                    " \"http.static./js\" value \"" dir "\"} mounts it"
                                    " at /js/. (An endpoint that reads the file"
                                    " serves it too; this checked mounts only.)")))))))))
          (finally
            (letfn [(rm! [f]
                      (when (.isDirectory f) (run! rm! (.listFiles f)))
                      (.delete f))]
              (rm! (io/file dir)))))))))

(defn ^:export generate-client!
  "Generate the typed client (D-web-contracts part 2): read every web endpoint's
   contract (client-wrapper-specs) and write a stored, edit-PROTECTED :cljs
   namespace of typed fetch wrappers — one per endpoint, validating the request
   out and the response in against the SAME shared :cljc schema the server
   enforces. The target namespace defaults to the `client`/`generated-ns` config,
   else app.client.api; `:ns` overrides. The write goes through store/ingest
   (BELOW the per-form gates — so regeneration overwrites wholesale and is the
   only writer) and marks the module :cljs, so `compile_client` picks it up; when
   `client`/`auto-compile` is on the write schedules a background recompile.
   Endpoints whose schema can't ship to the client (non-:cljc, or a missing var)
   are SKIPPED and surfaced in :problems so the namespace always compiles. An
   EXPLICIT step (mirrors compile_client); a done-advisory nudges regeneration
   when endpoints drift. slopp provisions malli itself (build! injects it — the
   generated code requires it), so the agent never hand-adds slopp's plumbing.
   Returns {:generated :wrappers :endpoints :platform :delta} (+ :problems,
   recompile keys) — or a :note when there is nothing to generate."
  [session & {:keys [ns]}]
  (let [st0    (:store @session)
        target (symbol (str (or ns
                                (get-in st0 [:config "client" :values "generated-ns"])
                                'app.client.api)))
        {:keys [wrappers problems]} (client-wrapper-specs st0)]
    (if (empty? wrappers)
      (cond-> {:generated target :wrappers [] :endpoints 0
               :note "no shippable endpoints — nothing generated"}
        (seq problems) (assoc :problems problems))
      (let [src (render-client-ns target wrappers)]
        (session/commit-appended!
         session
         (fn [s]
           (let [s1 (first (store/record-module-platform s (str target) :cljs))
                 s2 (store/ingest s1 target src)]
             ;; record the contract fingerprint so the done-advisory can detect
             ;; endpoint drift and nudge a regenerate (the "explicit" safety net)
             (first (store/record-config-put s2 "client" :manifest "generated-sig"
                                             (edit.modules/client-signature st0)))))
         [target])
        (let [recompiled (session/maybe-recompile-client! session target)]
          (cond-> {:generated target
                   :wrappers  (mapv (comp str :fn-name) wrappers)
                   :endpoints (count wrappers)
                   :platform  :cljs
                   :delta     (:id (last (:deltas (:store @session))))}
            (seq problems) (assoc :problems problems)
            recompiled     (merge recompiled)))))))
