(ns slopp.coldload-test
  "The cold-load half of the compile gate: index/forward-refs finds same-ns
  var usages that precede the var's first definition/declare — legal against
  a live image, fatal on a fresh namespace load (boot/restart). Found the
  hard way: an edit_group committed a namespace slopp.boot couldn't load."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.index.derive :as derive] [slopp.index.analyze :as analyze]))

(defn- fwd [src ns-sym]
  (derive/forward-refs (analyze/analyze src) ns-sym))

(deftest catches-use-before-def
  (let [v (fwd "(ns x)\n(defn a [] (b))\n(defn b [] 1)\n" 'x)]
    (is (= 1 (count v)))
    (is (= 'b (:symbol (first v))))
    (is (= 'a (:form (first v))))
    (is (= 3 (:def-row (first v))))))

(deftest declare-satisfies
  (is (empty? (fwd "(ns x)\n(declare b)\n(defn a [] (b))\n(defn b [] 1)\n" 'x))))

(deftest ordinary-order-is-clean
  (is (empty? (fwd "(ns x)\n(defn b [] 1)\n(defn a [] (b))\n" 'x))))

(deftest self-recursion-is-clean
  (is (empty? (fwd "(ns x)\n(defn f [n] (if (pos? n) (f (dec n)) n))\n" 'x))))

(deftest same-line-order-breaks-ties-by-col
  (testing "caller before callee on ONE line is still a forward ref"
    (is (seq (fwd "(ns x)\n(defn a [] (b)) (defn b [] 1)\n" 'x))))
  (testing "callee first on the same line is clean"
    (is (empty? (fwd "(ns x)\n(defn b [] 1) (defn a [] (b))\n" 'x)))))

(deftest quoted-symbols-are-not-references
  (is (empty? (fwd "(ns x)\n(defn a [] '(b c))\n(defn b [] 1)\n(defn c [] 2)\n" 'x))))

(deftest value-position-reference-counts
  ;; passing the var's VALUE still resolves the symbol at compile time
  (is (seq (fwd "(ns x)\n(def fns [b])\n(defn b [] 1)\n" 'x))))

(deftest cross-ns-usages-are-ignored
  (is (empty? (fwd "(ns x (:require [clojure.string :as str]))\n(defn a [] (str/upper-case \"z\"))\n" 'x))))