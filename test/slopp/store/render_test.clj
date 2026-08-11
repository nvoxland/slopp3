(ns slopp.store.render-test
  "Tests for turning stored forms back into source — where a namespace stops
  being data and becomes a file.

  Two things meet here. WHERE that file goes: `source-path` routes production
  and test namespaces to different trees and carries the platform's
  extension, and getting it wrong misfiles code rather than corrupting it.
  And WHAT it contains: `render-ns` supplies the space between forms and the
  comment above one, neither of which is stored.

  That second contract used to be byte-exact — render(ingest(src)) == src —
  and holding it meant storing whitespace, which is what kept a namespace
  from being reconstructible out of the delta log. It is now content-lossless
  and spacing-normalized, and the property worth defending is IDEMPOTENCE:
  render twice, get the same bytes, so a no-op push shows no diff."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.store.render :as store.render]))

(deftest test-namespaces-route-to-a-test-dir
  ;; Synthetic namespaces on purpose. This tests the ROUTING RULE, and an
  ;; earlier version keyed it to a live one (slopp.semver) against hardcoded
  ;; path strings — so extracting slopp.store rewrote the symbol, left the
  ;; string, and reddened a rule that had not changed.
  (testing "test-ns? keys on the -test name suffix (Clojure convention)"
    (is (store.render/test-ns? 'routing.fixture-test))
    (is (not (store.render/test-ns? 'routing.fixture)))
    (is (not (store.render/test-ns? 'slopp.core))))
  (testing "source-path roots production under src/ and tests under test/"
    (is (= "src/routing/fixture.clj" (store.render/source-path 'routing.fixture)))
    (is (= "test/routing/fixture_test.clj"
           (store.render/source-path 'routing.fixture-test))))
  (testing "a deep namespace nests, and only the LAST segment carries -test"
    (is (= "src/routing/deep/fixture.clj"
           (store.render/source-path 'routing.deep.fixture)))
    (is (= "test/routing/deep/fixture_test.clj"
           (store.render/source-path 'routing.deep.fixture-test)))))

(def corpus
  ["(ns foo)\n\n(defn add [x y]\n  (+ x y))\n\n;; a comment\n(def z 1)\n"
   "(ns bar\n  (:require [clojure.string :as str]))\n\n(def ^:private secret 42)\n"
   ";; leading comment\n(def a 1)(def b 2)\n\n\n"
   "(defn f [x]  x)"])

(deftest render-round-trips-content-and-normalizes-spacing
  ;; The old contract was byte-identical: render(ingest(src)) == src. Holding
  ;; it meant STORING the whitespace between forms, and storing it is what
  ;; made a namespace impossible to reconstruct from the delta log — a
  ;; comment lived in a `:sep` no delta recorded.
  ;;
  ;; The contract now splits. CONTENT — every form, every comment — round
  ;; trips exactly. SPACING is the renderer's decision and gets normalized to
  ;; one blank line between forms. Byte-exactness against arbitrary input was
  ;; never the property anything needed; STABILITY is, because that is what
  ;; keeps a no-op push from showing a diff.
  (let [content (fn [s]
                  (mapv (juxt :name :comment)
                        (filter #(= :form (:kind %)) (store/elements s 'ns))))]
    (doseq [src corpus]
      (let [s   (store/ingest (store/empty-store) 'ns src)
            out (store.render/render-ns s 'ns)
            s2  (store/ingest (store/empty-store) 'ns out)]
        (testing (str "every form AND every comment survives: " (pr-str src))
          (is (= (content s) (content s2))))
        (testing (str "rendering is IDEMPOTENT — a no-op push must not diff: " (pr-str src))
          (is (= out (store.render/render-ns s2 'ns))))
        (testing (str "and it ends with exactly one newline: " (pr-str src))
          (is (re-find #"[^\n]\n\z" out) (pr-str out)))))))

(deftest source-path-routes-an-instrument-out-of-src
  (testing "the default role is :product, and arity-2 keeps meaning what it meant"
    (is (= "src/app/core.clj" (store.render/source-path 'app.core :jvm)))
    (is (= "src/app/core.clj" (store.render/source-path 'app.core :jvm :product))))
  (testing "an :instrument roots under instruments/ — outside src/, so anything that jars src/ excludes it"
    (is (= "instruments/app/bench.clj" (store.render/source-path 'app.bench :jvm :instrument)))
    (is (= "instruments/app/deep/bench.clj"
           (store.render/source-path 'app.deep.bench :jvm :instrument))))
  (testing "a TEST of an instrument is still a test — test/ wins, because a test does not ship either way"
    (is (= "test/app/bench_test.clj"
           (store.render/source-path 'app.bench-test :jvm :instrument))))
  (testing "the platform still decides the extension"
    (is (= "instruments/app/bench.cljc" (store.render/source-path 'app.bench :cljc :instrument)))))

(deftest source-path-routes-by-platform
  (testing ":jvm (the arity-2 default) matches legacy .clj under src/"
    (is (= "src/app/core.clj" (store.render/source-path 'app.core :jvm)))
    (is (= "src/app/core.clj" (store.render/source-path 'app.core))))
  (testing ":cljc lives under src/ (JVM classpath) with a .cljc extension"
    (is (= "src/app/shared.cljc" (store.render/source-path 'app.shared :cljc)))
    (is (= "test/app/shared_test.cljc" (store.render/source-path 'app.shared-test :cljc))))
  (testing ":cljs lives under a separate cljs-src/ tree (off the JVM classpath)"
    (is (= "cljs-src/app/widget.cljs" (store.render/source-path 'app.widget :cljs)))
    (is (= "cljs-test/app/widget_test.cljs" (store.render/source-path 'app.widget-test :cljs))))
  (testing "ns-path carries the platform extension"
    (is (= "app/widget.cljs" (store.render/ns-path 'app.widget :cljs)))
    (is (= "app/shared.cljc" (store.render/ns-path 'app.shared :cljc)))
    (is (= "app/core.clj" (store.render/ns-path 'app.core)))))
