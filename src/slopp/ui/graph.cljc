(ns slopp.ui.graph
  "Turns a module manifest into a picture worth looking at.

  The Code view's job is to help someone understand a system they did not
  build, and most systems are not tidy. So everything here is COMPUTED from
  the graph rather than configured: which modules are foundation, which are
  entangled, what collapses when there are too many nodes to draw. A store
  with cycles and one god-module must get an honest picture, not a hairball
  with an apology.

  Pure and :cljc by design — `slopp.store/module-layers` hands over the
  expensive half (SCC-condensed topological layers), this adds the judgement
  and the geometry, and `slopp.ui.views` renders the result as hiccup SVG.
  Keeping the whole chain data-in/data-out is what lets the diagram be an
  ordinary in-image test instead of a screenshot."
  (:require [clojure.set :as set]))

(defn substrate
  "The modules to draw as a FOUNDATION BAND rather than as nodes with edges.

  The band exists to stop drawing edges that carry no information: \"everything
  rests on the store\" reads better as position than as eight arrows. Two ways
  in, both computed, so this generalises to a store nothing like slopp's own:

  - a SINK (nothing in the graph it depends on) that at least TWO modules use.
    One dependent is not enough — that single edge is informative, and banding
    it would move the module away from its only consumer.
  - a HUB whose own dependencies are all banded sinks and whose fan-in reaches
    a quarter of the graph (minimum 3). That is what catches a `store`-shaped
    module: everyone calls it, it calls almost nothing.

  Promotion is ONE level deep on purpose. Cascading walks up the graph and
  swallows real components — on slopp's own manifest an unbounded rule reaches
  `git` and `image`, which are foundation by no reading.

  A graph with no sinks (everything mutually entangled) yields the empty set
  and every edge gets drawn. That degradation is correct: there is no
  foundation to name, and saying so is the honest picture."
  [manifest]
  (let [nodes     (set (keys manifest))
        deps-of   (fn [m] (filter nodes (get manifest m)))
        sinks     (into #{} (filter #(empty? (deps-of %))) nodes)
        fan-in    (frequencies (mapcat deps-of nodes))
        banded    (into #{} (filter #(and (sinks %) (<= 2 (get fan-in % 0)))) nodes)
        threshold (max 3 (quot (+ (count nodes) 3) 4))
        hub?      (fn [m] (and (not (sinks m))
                               (<= threshold (get fan-in m 0))
                               (every? banded (deps-of m))))]
    (set/union banded (into #{} (filter hub?) nodes))))

(def geometry
  "Drawing constants, in user units. One map so the view and the tests agree
   on the canvas without either hardcoding numbers the other cannot see."
  {:node-w 168 :node-h 54 :gap-x 28 :gap-y 46 :band-h 56 :pad 24})

(defn positions
  "Place `:layers` and the foundation `:band` on a canvas.

  Layer 0 goes at the BOTTOM and later layers stack upward, so an edge from a
  module to something it depends on points DOWN — the layer-cake reading.
  (This inverts the order layers arrive in, and the order architecture.md's
  table lists them; reading order and visual foundation disagree, and the
  picture should follow the picture.)

  The band is one strip beneath layer 0. It carries no edges by construction:
  that is the whole point of computing `substrate` — position says 'everything
  rests on these' more legibly than sixteen arrows do.

  Returns {:nodes [{:module :layer :x :y :w :h}] :band [{:module :x :y :w :h}]
  :width :height}. Coordinates only — no colours, no labels, no strokes; the
  view owns all of that."
  [{:keys [layers band]}]
  (let [{:keys [node-w node-h gap-x gap-y band-h pad]} geometry
        depth   (count layers)
        row-w   (fn [n] (+ (* n node-w) (* (max 0 (dec n)) gap-x)))
        widest  (reduce max 0 (map (comp row-w count) layers))
        width   (+ (* 2 pad) (max widest (row-w (count band))))
        height  (+ (* 2 pad) (* depth node-h) (* (max 0 (dec depth)) gap-y)
                   (if (seq band) (+ gap-y band-h) 0))
        row-y   (fn [i] (+ pad (* (- depth 1 i) (+ node-h gap-y))))
        centred (fn [n] (quot (- width (row-w n)) 2))
        place   (fn [ms y h]
                  (let [x0 (centred (count ms))]
                    (map-indexed (fn [i m]
                                   {:module m
                                    :x (+ x0 (* i (+ node-w gap-x)))
                                    :y y :w node-w :h h})
                                 ms)))]
    {:nodes  (vec (mapcat (fn [i ms]
                            (map #(assoc % :layer i) (place ms (row-y i) node-h)))
                          (range) layers))
     :band   (vec (place (vec band) (- height pad band-h) band-h))
     :width  width
     :height height}))

(defn diagram
  "The whole picture as data: placed nodes, the foundation band, and the edges
  actually worth drawing.

  Two decisions live here. First, an edge into the band is DROPPED — that is
  what `substrate` bought, and on slopp's own manifest it removes 16 of 33
  arrows. Second, parallel edges out of one module FAN across its underside
  instead of stacking on a single anchor, so a module with six dependencies
  reads as a fan rather than a smear. Arrivals spread the same way, so a
  heavily-depended-on node does not collect every arrow at one pixel.

  An edge whose target is not placed at all (band member, or absent from the
  manifest) is silently omitted rather than drawn to nowhere.

  Returns `positions`' map plus :edges [{:from :to :x1 :y1 :x2 :y2}]. Still
  only coordinates — colour, stroke, arrowheads and labels are the view's."
  [{:keys [manifest layers band]}]
  (let [band     (set band)
        ;; SORTED for placement: a set's iteration order is unspecified, so
        ;; passing it straight through would let the foundation strip reorder
        ;; between renders of an identical store.
        placed   (positions {:layers layers :band (vec (sort band))})
        by-mod   (into {} (map (juxt :module identity)) (:nodes placed))
        drawable (fn [m] (vec (remove #(or (band %) (nil? (by-mod %)))
                                      (get manifest m))))
        raw      (vec (for [m     (sort (keys manifest))
                            :when (by-mod m)
                            :let  [outs (drawable m)]
                            [i d] (map-indexed vector outs)]
                        {:from m :to d :out i :fan (count outs)}))
        ;; arrival slot per target, assigned by grouping the finished list —
        ;; deterministic, and pure where an accumulator would not have been.
        arrival  (into {} (for [[d es] (group-by :to raw)]
                            [d (into {} (map-indexed (fn [j e] [(:from e) j])) es)]))
        inbound  (frequencies (map :to raw))
        ;; n targets -> n evenly-spaced points, each centred in its own slice
        anchor   (fn [{:keys [x w]} i n] (+ x (quot (* w (inc (* 2 i))) (* 2 n))))]
    (assoc placed :edges
           (let [centre (quot (:width placed) 2)]
             (mapv (fn [{:keys [from to out fan]}]
                     (let [f    (by-mod from)
                           t    (by-mod to)
                           ;; layers are numbered from the bottom, so a source
                           ;; sits ABOVE its target and the span is positive
                           span (- (:layer f) (:layer t))
                           x1   (anchor f out fan)]
                       {:from from :to to
                        :x1 x1
                        :y1 (+ (:y f) (:h f))
                        :x2 (anchor t (get-in arrival [to from]) (get inbound to 1))
                        :y2 (:y t)
                        ;; bow AWAY from the centre so a skip edge clears the
                        ;; boxes it passes; zero for an adjacent hop, which
                        ;; passes nothing and should read as a straight line
                        :bow (if (<= span 1)
                               0
                               (* (if (< x1 centre) -1 1) 52 (dec span)))}))
                   raw)))))
