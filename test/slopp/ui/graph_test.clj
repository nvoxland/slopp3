(ns slopp.ui.graph-test
  "Tests for the module-graph analysis, built on two rules.

  First, prefer a REAL fixture: slopp's own production manifest is small
  enough to check by hand and is the only graph whose right answer can be
  verified by inspection. A synthetic three-node graph proves a rule holds;
  it does not prove the rule produces a sensible picture.

  Second, the degradation cases carry more weight than the happy path. This
  view ships to stores that are cyclic, lopsided, or far larger than slopp's
  own, and 'what does it do when there is no foundation to name' is the
  question a user actually hits.

  This namespace is :jvm, not :cljc like its subject, because the composition
  test drives the real layering through `slopp.store/module-layers` — which
  the client never does: layers arrive over the wire already computed."
  (:require [clojure.test :refer [deftest testing is]]
            [slopp.store]
            [slopp.ui.graph :as graph]))

(deftest substrate-bands-sinks-that-many-modules-depend-on
  (testing "a sink two or more modules depend on is foundation"
    (is (= #{"lib"}
           (graph/substrate {"a" ["lib"] "b" ["lib"] "lib" []}))))
  (testing "a sink only ONE module depends on stays an ordinary node"
    (is (= #{}
           (graph/substrate {"a" ["lib"] "lib" []}))))
  (testing "a depended-on module that itself reaches a non-substrate module is not foundation"
    (is (= #{"lib"}
           (graph/substrate {"a" ["mid" "lib"] "b" ["mid"] "mid" ["lib" "a"] "lib" []})))))

(def ^:private slopp-production
  "slopp's own production module manifest as of 2026-07-26 — 14 modules,
   33 edges, 9 layers, no cycles. Small enough to check the answer by hand."
  {"slopp.api"   ["slopp.boot" "slopp.edit" "slopp.image" "slopp.index" "slopp.store" "slopp.web"]
   "slopp.bench" ["slopp.api" "slopp.mcp" "slopp.store"]
   "slopp.boot"  []
   "slopp.cache" []
   "slopp.edit"  ["slopp.cache" "slopp.image" "slopp.index" "slopp.store"]
   "slopp.git"   ["slopp.store"]
   "slopp.image" ["slopp.rt" "slopp.store"]
   "slopp.index" ["slopp.cache" "slopp.image"]
   "slopp.mcp"   ["slopp.api" "slopp.git" "slopp.store" "slopp.sync" "slopp.ui" "slopp.web"]
   "slopp.rt"    []
   "slopp.store" ["slopp.cache"]
   "slopp.sync"  ["slopp.api" "slopp.boot" "slopp.git" "slopp.store"]
   "slopp.ui"    ["slopp.api" "slopp.edit" "slopp.store" "slopp.web"]
   "slopp.web"   []})

(deftest substrate-on-a-real-manifest-names-the-foundation-and-nothing-else
  (let [band  (graph/substrate slopp-production)
        edges (for [[m ds] slopp-production d ds] [m d])]
    (testing "the foundation is the three widely-used sinks plus the store hub"
      (is (= #{"slopp.boot" "slopp.cache" "slopp.web" "slopp.store"} band)))
    (testing "store is banded despite an outgoing edge — everyone calls it, it calls almost nothing"
      (is (contains? band "slopp.store")))
    (testing "rt is a sink but NOT foundation: its one edge from image is the informative kind"
      (is (not (contains? band "slopp.rt")))
      (is (some (fn [[m d]] (and (= "slopp.image" m) (= "slopp.rt" d))) edges)
          "and that edge therefore survives into the drawn picture"))
    (testing "promotion stops at one level — these are components, not foundation"
      (is (empty? (filter band ["slopp.git" "slopp.image" "slopp.api" "slopp.edit"]))))
    (testing "banding is what makes the picture readable: 16 of 33 edges stop being drawn"
      (is (= 33 (count edges)))
      (is (= 17 (count (remove (fn [[_ d]] (band d)) edges)))))))

(deftest a-fully-entangled-graph-has-no-foundation-to-name
  (testing "no sinks means no band, and every edge gets drawn — the honest picture"
    (is (= #{} (graph/substrate {"a" ["b"] "b" ["c"] "c" ["a"]}))))
  (testing "a single god-module everything calls is still found"
    (is (= #{"god"}
           (graph/substrate {"a" ["god"] "b" ["god"] "c" ["god"] "god" []})))))

(deftest positions-stack-layers-bottom-up-with-the-foundation-beneath
  (let [{:keys [nodes band width height]}
        (graph/positions {:layers [["rt" "git"] ["image"] ["index" "edit"]]
                          :band   ["cache" "store"]})
        by-module (into {} (map (juxt :module identity)) nodes)
        y-of      #(:y (by-module %))]
    (testing "every module in every layer is placed, and none is invented"
      (is (= #{"rt" "git" "image" "index" "edit"} (set (map :module nodes)))))
    (testing "layer 0 sits BELOW layer 1, which sits below layer 2 — dependencies downward"
      (is (> (y-of "rt") (y-of "image")))
      (is (> (y-of "image") (y-of "index"))))
    (testing "modules in one layer share a baseline and occupy distinct columns"
      (is (= (y-of "rt") (y-of "git")))
      (is (not= (:x (by-module "rt")) (:x (by-module "git")))))
    (testing "the foundation band is beneath everything else"
      (is (seq band))
      (is (every? (fn [b] (every? #(> (:y b) (:y %)) nodes)) band)))
    (testing "the reported canvas contains everything drawn"
      (is (every? #(<= 0 (:x %)) (concat nodes band)))
      (is (every? #(<= (+ (:x %) (:w %)) width) (concat nodes band)))
      (is (every? #(<= (+ (:y %) (:h %)) height) (concat nodes band))))))

(deftest edges-into-the-foundation-are-not-drawn
  (let [picture (graph/diagram {:manifest {"app" ["mid" "lib"] "mid" ["lib"] "lib" []}
                                :layers   [["mid"] ["app"]]
                                :band     #{"lib"}})
        drawn   (set (map (juxt :from :to) (:edges picture)))]
    (testing "only the edge between two placed nodes survives"
      (is (= #{["app" "mid"]} drawn)))
    (testing "edges into the band are omitted — position already says it"
      (is (not-any? (fn [[_ to]] (= "lib" to)) drawn)))
    (testing "each drawn edge starts on its source and ends on its target"
      (let [{:keys [nodes edges]} picture
            by-module (into {} (map (juxt :module identity)) nodes)
            e         (first edges)
            from      (by-module (:from e))
            to        (by-module (:to e))]
        (is (= (+ (:y from) (:h from)) (:y1 e)) "leaves the bottom of the source")
        (is (= (:y to) (:y2 e)) "arrives at the top of the target")
        (is (<= (:x from) (:x1 e) (+ (:x from) (:w from))))
        (is (<= (:x to) (:x2 e) (+ (:x to) (:w to))))))))

(deftest parallel-edges-fan-across-a-module-underside
  (testing "two edges out of one module leave from different points, not one pixel"
    (let [{:keys [edges]} (graph/diagram {:manifest {"app" ["a" "b"] "a" [] "b" []}
                                          :layers   [["a" "b"] ["app"]]
                                          :band     #{}})]
      (is (= 2 (count edges)))
      (is (apply not= (map :x1 edges))))))

(deftest the-assembled-picture-of-a-real-store-is-a-chain-not-a-hairball
  (let [band    (graph/substrate slopp-production)
        reduced (into {} (for [[k v] slopp-production :when (not (band k))]
                           [k (vec (remove band v))]))
        {:keys [layers cycles]} (slopp.store/module-layers reduced)
        picture (graph/diagram {:manifest slopp-production :layers layers :band band})]
    (testing "banding the foundation leaves a near-linear chain"
      (is (= [["slopp.git" "slopp.rt"] ["slopp.image"] ["slopp.index"] ["slopp.edit"]
              ["slopp.api"] ["slopp.sync" "slopp.ui"] ["slopp.mcp"] ["slopp.bench"]]
             layers))
      (is (empty? cycles))
      (is (every? #(<= (count %) 2) layers)
          "no layer is wide enough to need crossing reduction"))
    (testing "the drawn picture is half the edges of the raw manifest"
      (is (= 10 (count (:nodes picture))))
      (is (= 4 (count (:band picture))))
      (is (= 17 (count (:edges picture)))))
    (testing "every drawn edge points DOWN — from a module to what it depends on"
      (is (every? #(< (:y1 %) (:y2 %)) (:edges picture))
          "SVG y grows downward, so downward means y1 < y2"))))

(deftest edges-that-skip-layers-bow-around-the-boxes-between-them
  ;; Found by rendering the real store: slopp.api -> slopp.image was emitted as
  ;; a straight vertical at x=346 spanning three layers, passing through both
  ;; slopp.edit and slopp.index, whose boxes cover x 318-486.
  (let [picture (graph/diagram
                 {:manifest {"top" ["mid" "bottom"] "mid" ["bottom"] "bottom" []}
                  :layers   [["bottom"] ["mid"] ["top"]]
                  :band     #{}})
        bow     (into {} (map (juxt (juxt :from :to) :bow)) (:edges picture))]
    (testing "an adjacent-layer edge stays straight"
      (is (zero? (bow ["top" "mid"])))
      (is (zero? (bow ["mid" "bottom"]))))
    (testing "an edge that skips a layer bows aside to clear what it passes"
      (is (not (zero? (bow ["top" "bottom"])))))
    (testing "the bow grows with the distance spanned"
      (let [deep (graph/diagram
                  {:manifest {"a" ["d"] "b" [] "c" [] "d" []}
                   :layers   [["d"] ["c"] ["b"] ["a"]]
                   :band     #{}})
            far  (:bow (first (:edges deep)))]
        (is (< (abs (bow ["top" "bottom"])) (abs far))
            "three layers apart bows further than two")))))
