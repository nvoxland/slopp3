(ns slopp.webdev.screen-test
  "End-to-end cover for the `screen` tool: a real store, a real image, the
  app's own handlers, and a readable answer.

  Everything below `slopp.web.screen` is unit-tested against hiccup literals,
  which is right and proves nothing about the part that actually breaks — that
  the entry is findable, that the framework is on the image's classpath, and
  that a script survives crossing into another JVM as data. Those are the
  seams, so this test ingests a small app and drives it.

  It is `^:external` for the plainest of reasons: it starts an image."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [slopp.ops :as ops]
            [slopp.ops.external :as external]
            [slopp.webdev.screen :as scr] [clojure.java.io :as io] [slopp.kernel.boot :as boot]))

(deftest ^:external an-agent-can-look-at-a-screen-without-writing-code
  ;; The end of the loop this feature exists to close: a real store, a real
  ;; image, the app's own handlers, and a readable answer — no test written by
  ;; the reader and no browser opened.
  ;;
  ;; TWO environment facts make this look stranger than it is, and both are
  ;; properties of the external TIER rather than of the tool:
  ;;
  ;; (1) `framework-files` reads META-INF/slopp/framework-files.edn, which
  ;; build.clj generates INTO THE JAR. A test JVM running from a materialized
  ;; tree has no such resource, so the real fn answers nil and nothing would
  ;; ever vendor. `a-framework-using-store-survives-a-RESTART` redefs it for
  ;; the same reason; this one supplies the REAL slopp/web sources, DERIVED
  ;; from the classpath rather than hand-listed — a hand-kept file list goes
  ;; stale the first time a namespace is added, which this store learned twice
  ;; this week.
  ;;
  ;; (2) Vendoring must precede process start — a JVM caches a relative
  ;; classpath dir that did not exist at launch — so the app is ingested and
  ;; THEN the image is restarted. A real session gets that ordering for free,
  ;; because the store already holds the app when it opens.
  (let [web-clj (io/file (.toURI (io/resource "slopp/web.clj")))
        web-dir (io/file (.getParentFile web-clj) "web")
        prefix  (inc (count (.getPath web-dir)))
        fw      (into {"slopp/web.clj" (slurp web-clj)}
                      (for [f (file-seq web-dir)
                            :when (and (.isFile f) (.endsWith (.getName f) ".clj"))]
                        [(str "slopp/web/" (subs (.getPath f) prefix)) (slurp f)]))]
    (is (contains? fw "slopp/web/screen.clj")
        "the derivation found the framework — an empty file map vendors nothing and every assertion below would fail for the wrong reason")
    ;; and its DEPS, for the same reason and with the same cause: vendoring
    ;; copies SOURCE and discards the pom, so the framework's own requires have
    ;; to arrive separately. Production derives this list at build time into
    ;; framework-deps.edn; here it is spelled out, and a drift announces itself
    ;; as "Could not locate <lib>" rather than hiding.
    (with-redefs [boot/framework-files (constantly fw)
                  boot/framework-deps  (constantly '{cheshire/cheshire {:mvn/version "5.13.0"}
                                                     hiccup/hiccup     {:mvn/version "2.0.0"}
                                                     garden/garden     {:mvn/version "1.3.10"}
                                                     http-kit/http-kit {:mvn/version "2.8.0"}})]
      (let [sess (external/open!)]
        (try
          (ops/ingest! sess 'demo.app
                       (str "(ns demo.app)\n\n"
                            "(def state (atom {:q \"\" :n 0}))\n\n"
                            "(defn view \"V.\" [s]\n"
                            "  [:main {:data-region \"main\"}\n"
                            "   [:h1 \"Demo\"]\n"
                            "   [:input {:placeholder \"Filter\" :value (:q s)\n"
                            "            :on-change #(swap! state assoc :q (:value %))}]\n"
                            "   [:button {:on-click #(swap! state update :n inc)} \"Add\"]\n"
                            "   [:p (str \"q=\" (:q s) \" n=\" (:n s))]])\n\n"
                            "(defn ^:web/page page \"P.\" [] {:state state :view view})\n"))
          ;; the MARKER is what makes this store a framework user: its own code
          ;; requires nothing from slopp.web, and slopp opens it with
          ;; slopp.web.screen on its behalf
          (ops/restart! sess)

          (testing "a bare look renders the screen, and names the entry it used"
            (let [r (scr/screen! sess)]
              (is (nil? (:error r)) (pr-str r))
              (is (str/includes? (str (:screen r)) "# Demo"))
              (is (str/includes? (str (:screen r)) "Filter [fill]")
                  "a field is VISIBLE and says typing changes something")
              (is (str/includes? (str (:screen r)) "Add [click]")
                  "and a button says it can be clicked")
              (is (str/includes? (str (:entry r)) "demo.app/page")
                  "a screen is only as trustworthy as the app it came from")))

          (testing "a script drives the app's OWN handlers, in order"
            (let [r (scr/screen! sess
                                 :steps [{:fill "Filter" :value "web"}
                                         {:click "Add"}
                                         {:click "Add"}]
                                 :region "main" :detail "prose")]
              (is (nil? (:error r)) (pr-str r))
              (is (str/includes? (str (:screen r)) "q=web n=2")
                  "typed, clicked twice, and the state is the app's own")
              (is (not (str/includes? (str (:screen r)) "[click]"))
                  "prose was asked for and prose is what came back")))

          (testing "a store with no marked page says what to do about it"
            (let [s2 (external/open!)]
              (try
                (ops/ingest! s2 'plain.core "(ns plain.core)\n\n(defn f \"F.\" [x] x)\n")
                (is (str/includes? (str (:error (scr/screen! s2))) "no ^:web/page")
                    "an ERROR, not an empty screen — a blank answer reads as a broken app")
                (finally (ops/close! s2)))))
          (finally (ops/close! sess)))))))
