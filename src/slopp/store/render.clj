(ns slopp.store.render
  "VFS render: project a namespace's current source from the store on demand
  (C1/C6). Lossless — concatenating each element's CST string reproduces the
  ingested source exactly. This is what tools/agents 'read'; nothing is written
  to disk unless an explicit build asks."
  (:require [clojure.string :as str]
            [rewrite-clj.node :as n]
            [slopp.store :as store] [slopp.cache :as cache]))

^:reads (defn ^:export render-ns
  "Render `ns-sym`'s current source as a string from the store. Memoized on
  the (immutable) elements vector, through the blessed cache — so a test can
  reset it or bypass it entirely, and the memo is countable in
  `cache/registry` rather than being an invisible atom.

  Forms are joined by ONE BLANK LINE and a form's `:comment` renders directly
  above it. Both separators are supplied here and neither is stored: how code
  is spaced is a rendering decision, and storing it is what made a
  namespace's bytes impossible to reconstruct from the delta log.

  Stored `:sep` elements are IGNORED. That is deliberate and is what lets
  them be deleted — nothing reads them, so nothing depends on what they held.

  It also normalizes. `place-form` gave a tail-appended form a single
  newline, so most of slopp's own forms rendered jammed together;
  `slopp.api.session` alone held 33 single-newline separators against 11
  blank-line ones. One rule everywhere costs 345 bytes across the whole
  store."
  [store ns-sym]
  (if-let [elements (store/elements store ns-sym)]
    (cache/cached ::render-ns elements
                  (fn []
                    (let [forms (filter #(= :form (:kind %)) elements)]
                      (if (empty? forms)
                        ""
                        (str (str/join
                              "\n\n"
                              (map (fn [e]
                                     (if-let [c (:comment e)]
                                       (str c "\n" (n/string (:node e)))
                                       (n/string (:node e))))
                                   forms))
                             "\n")))))
    ""))

(defn ^:export ns-path
  "The VFS path of a namespace's rendered file (also used by build! and as the
  source-path for image loads, so stack traces cite VFS coordinates — F6). The
  arity-2 form takes the namespace's PLATFORM and sets the extension
  accordingly (:jvm→.clj, :cljc→.cljc, :cljs→.cljs, D-web-cljs)."
  ([ns-sym] (ns-path ns-sym :jvm))
  ([ns-sym platform]
   (str (-> (str ns-sym) (str/replace "-" "_") (str/replace "." "/"))
        "." (case platform :cljs "cljs" :cljc "cljc" "clj"))))

(defn ^:export test-ns?
  "Convention: a namespace whose name ends in `-test` is a test namespace
  (matches cognitect test-runner's default and slopp's own layout), so it
  materializes under `test/` rather than `src/`."
  [ns-sym]
  (str/ends-with? (str ns-sym) "-test"))

(defn ^:export source-path
  "The materialized file path for a namespace, rooted by convention: production
  code under `src/`, test namespaces under `test/`. e.g. `app.core` →
  `src/app/core.clj`; `app.core-test` → `test/app/core_test.clj`.
  The arity-2 form takes the namespace's PLATFORM (:jvm/:cljc/:cljs): :cljs roots
  under a separate `cljs-src/` (`cljs-test/`) tree the JVM classpath excludes,
  with a `.cljs` extension; :cljc/:jvm stay under `src/`/`test/` with
  `.cljc`/`.clj` (D-web-cljs)."
  ([ns-sym] (source-path ns-sym :jvm))
  ([ns-sym platform]
   (let [test? (test-ns? ns-sym)
         root  (if (= :cljs platform)
                 (if test? "cljs-test/" "cljs-src/")
                 (if test? "test/" "src/"))]
     (str root (ns-path ns-sym platform)))))

(defn ^:export element-offsets
  "Start position [row col] (1-based) of each of `ns-sym`'s elements within the
  rendered source — the bridge from index positions (clj-kondo rows/cols against
  `render-ns` output) back to the owning store element.

  It must simulate `render-ns` EXACTLY, including the separators the renderer
  synthesizes and the comment it prints above a form. The two drifting apart
  does not fail loudly: positions still resolve, just to the wrong element, so
  a rename finds no call sites and reports a clean plan with nothing in it.

  Separators are not rendered, so a `:sep` element occupies no space and
  reports the cursor as it stands. The returned vector still lines up
  index-for-index with `store/elements`, which is how callers get from a
  position back to the form that owns it."
  [store ns-sym]
  (loop [es (store/elements store ns-sym), row 1, col 1, acc [], seen-form? false]
    (if-let [e (first es)]
      (if (not= :form (:kind e))
        (recur (rest es) row col (conj acc [row col]) seen-form?)
        ;; the blank line between forms, then the comment and its newline —
        ;; both emitted by render-ns, neither stored
        (let [[row col] (if seen-form? [(+ row 2) 1] [row col])
              [row col] (if-let [c (:comment e)]
                          [(+ row (count (filter #(= \newline %) c)) 1) 1]
                          [row col])
              s  (n/string (:node e))
              nl (count (filter #(= \newline %) s))]
          (recur (rest es)
                 (+ row nl)
                 (if (pos? nl)
                   (inc (count (subs s (inc (str/last-index-of s "\n")))))
                   (+ col (count s)))
                 (conj acc [row col])
                 true)))
      acc)))

(defn ^:export owner-form
  "The form element whose rendered span contains position [row col], or nil —
  the bridge from linter/index positions to form addressing."
  [store ns-sym row col]
  (let [elems   (store/elements store ns-sym)
        offsets (element-offsets store ns-sym)
        idx     (dec (count (take-while (fn [[er ec]]
                                          (or (< er row)
                                              (and (= er row) (<= ec col))))
                                        offsets)))]
    (when-not (neg? idx)
      (let [e (nth elems idx)]
        (when (= :form (:kind e)) e)))))
