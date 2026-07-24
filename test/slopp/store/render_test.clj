(ns slopp.store.render-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.store.render :as render]))

(deftest test-namespaces-route-to-a-test-dir
  ;; Synthetic namespaces on purpose. This tests the ROUTING RULE, and an
  ;; earlier version keyed it to a live one (slopp.semver) against hardcoded
  ;; path strings — so extracting slopp.store rewrote the symbol, left the
  ;; string, and reddened a rule that had not changed.
  (testing "test-ns? keys on the -test name suffix (Clojure convention)"
    (is (render/test-ns? 'routing.fixture-test))
    (is (not (render/test-ns? 'routing.fixture)))
    (is (not (render/test-ns? 'slopp.core))))
  (testing "source-path roots production under src/ and tests under test/"
    (is (= "src/routing/fixture.clj" (render/source-path 'routing.fixture)))
    (is (= "test/routing/fixture_test.clj"
           (render/source-path 'routing.fixture-test))))
  (testing "a deep namespace nests, and only the LAST segment carries -test"
    (is (= "src/routing/deep/fixture.clj"
           (render/source-path 'routing.deep.fixture)))
    (is (= "test/routing/deep/fixture_test.clj"
           (render/source-path 'routing.deep.fixture-test)))))

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

(deftest source-path-routes-by-platform
  (testing ":jvm (the arity-2 default) matches legacy .clj under src/"
    (is (= "src/app/core.clj" (render/source-path 'app.core :jvm)))
    (is (= "src/app/core.clj" (render/source-path 'app.core))))
  (testing ":cljc lives under src/ (JVM classpath) with a .cljc extension"
    (is (= "src/app/shared.cljc" (render/source-path 'app.shared :cljc)))
    (is (= "test/app/shared_test.cljc" (render/source-path 'app.shared-test :cljc))))
  (testing ":cljs lives under a separate cljs-src/ tree (off the JVM classpath)"
    (is (= "cljs-src/app/widget.cljs" (render/source-path 'app.widget :cljs)))
    (is (= "cljs-test/app/widget_test.cljs" (render/source-path 'app.widget-test :cljs))))
  (testing "ns-path carries the platform extension"
    (is (= "app/widget.cljs" (render/ns-path 'app.widget :cljs)))
    (is (= "app/shared.cljc" (render/ns-path 'app.shared :cljc)))
    (is (= "app/core.clj" (render/ns-path 'app.core)))))
