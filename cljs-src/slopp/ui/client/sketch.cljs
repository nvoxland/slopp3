(ns slopp.ui.client.sketch
  "The ONLY place rough.js is touched.

  A vendored library gets exactly one adapter, in our own vocabulary, and
  everything above it stays library-free — the standing advice for an
  external edge, and here also what keeps the boundary auditable: one
  namespace requires `roughjs`, so \"what uses this library?\" has a one-line
  answer instead of being folklore.

  Deliberately tiny. `slopp.ui.graph` computed the geometry and
  `slopp.ui.views` turned it into elements; this only redraws those shapes
  with a hand-drawn stroke. Seeds derive from the module NAME so the sketch
  is stable across renders — rough.js is deterministic given a nonzero seed,
  and a diagram that shimmered on every re-render would be worse than a
  clean one.

  The require resolves because `js_dep` declared the library and
  `slopp.api.cljs/foreign-libs-for` turns that declaration into a
  `:foreign-libs` entry, which compiles `rough/…` to `goog.global[\"rough\"]`.
  No npm, no bundler, no Node.

  Note `clj->js` rather than the `#js` reader literal, which the dialect
  gate refuses — reporting it as a denylisted `read-string`, naming an
  expansion artifact rather than the construct written."
  (:require [roughjs :as rough]))

(defn seed-of
  "A stable nonzero seed for `s`. rough.js treats seed 0 as \"random\", so a
   name that hashed to zero would silently start shimmering."
  [s]
  (let [h (reduce (fn [a c] (bit-and (+ (* 31 a) (.charCodeAt c 0)) 0x7fffffff))
                  7 (seq (str s)))]
    (if (zero? h) 1 h)))

(defn generator
  "A rough.js generator. Needs no DOM — it produces path DATA, not elements,
   which is why the sketch can be computed without touching the page.

   `rough/generator` compiles to a call on `goog.global[\"rough\"]` — the same
   runtime access a bare `js/rough` would make, but visible to the analyzer,
   which is what the `:foreign-libs` declaration buys."
  []
  (rough/generator))

(defn rect-paths
  "Hand-drawn path data for one box: a vector of `{:d :stroke :fill}`."
  [gen {:keys [x y w h seed]}]
  (let [shape (.rectangle gen x y w h
                          (clj->js {:seed seed :roughness 1.05 :bowing 1.2
                                    :fill "none"}))]
    (mapv (fn [p] {:d (.-d p) :stroke (.-stroke p) :fill (.-fill p)})
          (.toPaths gen shape))))

(defn paths-for
  "Hand-drawn path data for every box in `picture`, keyed by module.

  The adapter's whole public surface: a picture goes in, plain path data
  comes out, and nothing above this namespace knows rough.js exists. One
  generator for the pass; the seed comes from the module NAME, so the same
  store sketches identically every time — the property that keeps a
  re-render from making the diagram shimmer."
  [picture]
  (let [gen (generator)]
    (into {}
          (for [n (concat (:nodes picture) (:band picture))]
            [(:module n)
             (rect-paths gen (assoc n :seed (seed-of (:module n))))]))))
