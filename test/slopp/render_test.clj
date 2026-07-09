(ns slopp.render-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.render :as render]))

(deftest test-namespaces-route-to-a-test-dir
  (testing "test-ns? keys on the -test name suffix (Clojure convention)"
    (is (render/test-ns? 'slopp.semver-test))
    (is (not (render/test-ns? 'slopp.semver)))
    (is (not (render/test-ns? 'slopp.core))))
  (testing "source-path roots production under src/ and tests under test/"
    (is (= "src/slopp/semver.clj" (render/source-path 'slopp.semver)))
    (is (= "test/slopp/semver_test.clj" (render/source-path 'slopp.semver-test)))))

(def corpus
  ["(ns foo)\n\n(defn add [x y]\n  (+ x y))\n\n;; a comment\n(def z 1)\n"
   "(ns bar\n  (:require [clojure.string :as str]))\n\n(def ^:private secret 42)\n"
   ";; leading comment\n(def a 1)(def b 2)\n\n\n"
   "(defn f [x]  x)"])

(deftest render-is-lossless-round-trip
  (testing "render(ingest(src)) == src, whitespace+comments preserved (C1/C6)"
    (doseq [src corpus]
      (let [s (store/ingest (store/empty-store) 'ns src)]
        (is (= src (render/render-ns s 'ns))
            (str "round-trip failed for: " (pr-str src)))))))
