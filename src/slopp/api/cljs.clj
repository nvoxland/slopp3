(ns slopp.api.cljs
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [slopp.render :as render] [slopp.build :as build] [slopp.api.external :as external] [slopp.api.testrun :as testrun] [slopp.repl :as repl] [slopp.store :as store] [slopp.api.session :as session] [clojure.java.io :as io]))

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
   "            (catch Throwable e (str (.getMessage e) (some->> (.getCause e) (.getMessage) (str \" / \")))))]\n"
   "  (println (str \"" result-marker "\" (pr-str {:warnings @ws :error res}))))\n"))

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

(defn ^:export compile-client!
  "Compile the store's CLIENT namespaces (:cljc + :cljs) to JavaScript with the
  configured backend (build/client-compiler, default :clojurescript) and record
  the output as a served blob — the client wave's compile-error-as-oracle
  (D-web-cljs). Materializes the store (build!) into a throwaway dir whose
  generated deps.edn carries the :cljs alias, shells the compiler in a fresh JVM
  (no Node — real org.clojure/clojurescript on the JVM), then anchors warnings to
  store forms (name-addressed, no file:line) and file_puts the JS. Returns
  {:compiled <ns-count> :warnings [...anchored...] :output <path> :bytes n} on
  success, {:error ... :warnings ...} on a compile failure, or {:note ...} when
  there is no client code. `:output` sets the served path (default
  \"public/cljs/main.js\")."
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
                  {:error error :warnings anchored}
                  (let [js (slurp (io/file dir "out" "main.js"))]
                    (session/commit-appended!
                     session
                     (fn [s] (first (store/record-file-put s output js)))
                     [])
                    {:compiled  (count client)
                     :namespaces (mapv str (sort client))
                     :warnings  anchored
                     :output    output
                     :bytes     (count js)})))))
          (finally
            (letfn [(rm! [f]
                      (when (.isDirectory f) (run! rm! (.listFiles f)))
                      (.delete f))]
              (rm! (io/file dir)))))))))
