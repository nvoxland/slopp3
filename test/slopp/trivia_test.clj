(ns slopp.trivia-test
  "Trivia addressability: the comment/blank-line run between forms is
  addressed by its NEIGHBOR (replace the whole gap before form X, or the
  namespace tail). Forms are never touched; foreign replay converges."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.render :as render]
            [slopp.store :as store]))

(def base-src
  "(ns tv.core)\n;; old banner\n;; stale line\n(defn f [] 1)\n(defn g [] 2)\n")

(defn- base [] (store/ingest (store/empty-store) 'tv.core base-src))

(deftest replaces-the-gap-before-a-form
  (let [[st d] (store/replace-trivia (base) 'tv.core 'f ";; fresh banner\n"
                                     :agent "t")]
    (is (= "(ns tv.core)\n;; fresh banner\n(defn f [] 1)\n(defn g [] 2)\n"
           (render/render-ns st 'tv.core)))
    (testing "forms untouched"
      (is (= '[tv.core f g] (into [] (keep :name) (store/forms st 'tv.core)))))
    (testing "the delta anchors on the form-id and carries the text"
      (is (= :trivia (:op d)))
      (is (= (:id (store/form-named (base) 'tv.core 'f)) (:before d))))
    (testing "foreign replay converges"
      (is (= (render/render-ns st 'tv.core)
             (render/render-ns (store/replay-delta (base) d) 'tv.core))))))

(deftest empty-text-deletes-the-gap
  (let [[st _] (store/replace-trivia (base) 'tv.core 'f "" :agent "t")]
    (is (= "(ns tv.core)\n(defn f [] 1)\n(defn g [] 2)\n"
           (render/render-ns st 'tv.core)))))

(deftest nil-anchor-is-the-namespace-tail
  (let [[st d] (store/replace-trivia (base) 'tv.core nil ";; the end")]
    (is (= (str base-src ";; the end\n")
           (render/render-ns st 'tv.core)))
    (is (nil? (:before d)))
    (testing "tail replay converges too"
      (is (= (render/render-ns st 'tv.core)
             (render/render-ns (store/replay-delta (base) d) 'tv.core))))))

(deftest code-in-the-text-is-refused
  (is (:error (store/replace-trivia (base) 'tv.core 'f ";; ok\n(def x 1)\n"))))

(deftest missing-anchor-is-an-error
  (is (:error (store/replace-trivia (base) 'tv.core 'nope ";; hi\n"))))