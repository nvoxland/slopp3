(ns slopp.render
  "VFS render: project a namespace's current source from the store on demand
  (C1/C6). Lossless — concatenating each element's CST string reproduces the
  ingested source exactly. This is what tools/agents 'read'; nothing is written
  to disk unless an explicit build asks."
  (:require [clojure.string :as str]
            [rewrite-clj.node :as n]
            [slopp.store :as store] [slopp.cache :as cache]))

^:reads (defn render-ns
  "Render `ns-sym`'s current source as a string from the store. Memoized on
  the (immutable) elements vector, through the blessed cache — so a test can
  reset it or bypass it entirely, and the memo is countable in
  `cache/registry` rather than being an invisible atom."
  [store ns-sym]
  (if-let [elements (store/elements store ns-sym)]
    (cache/cached ::render-ns elements
                  (fn [] (apply str (map (comp n/string :node) elements))))
    ""))

(defn ns-path
  "The VFS path of a namespace's rendered file (also used by build! and as the
  source-path for image loads, so stack traces cite VFS coordinates — F6)."
  [ns-sym]
  (str (-> (str ns-sym) (str/replace "-" "_") (str/replace "." "/")) ".clj"))

(defn test-ns?
  "Convention: a namespace whose name ends in `-test` is a test namespace
  (matches cognitect test-runner's default and slopp's own layout), so it
  materializes under `test/` rather than `src/`."
  [ns-sym]
  (str/ends-with? (str ns-sym) "-test"))

(defn source-path
  "The materialized file path for a namespace, rooted by convention: production
  code under `src/`, test namespaces under `test/`. e.g. `slopp.semver` →
  `src/slopp/semver.clj`; `slopp.semver-test` → `test/slopp/semver_test.clj`."
  [ns-sym]
  (str (if (test-ns? ns-sym) "test/" "src/") (ns-path ns-sym)))

(defn element-offsets
  "Start position [row col] (1-based) of each of `ns-sym`'s elements within the
  rendered source — the bridge from index positions (clj-kondo rows/cols against
  `render-ns` output) back to the owning store element."
  [store ns-sym]
  (loop [es (store/elements store ns-sym), row 1, col 1, acc []]
    (if-let [e (first es)]
      (let [s  (n/string (:node e))
            nl (count (filter #(= \newline %) s))]
        (recur (rest es)
               (+ row nl)
               (if (pos? nl)
                 (inc (count (subs s (inc (str/last-index-of s "\n")))))
                 (+ col (count s)))
               (conj acc [row col])))
      acc)))

(defn owner-form
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

