(ns slopp.index.deps-test
  "External dependency support (trust-tiered). M1: the per-store manifest —
  :deps-add/:deps-remove tracked deltas, materialized to a meta row, reaching
  the owned image's classpath and the generated deps.edn."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [slopp.api :as api]
            [slopp.store.build :as build]
            [slopp.index.deps :as deps]
            [slopp.mcp]
            [slopp.store :as store]
            [slopp.store.db :as db] [slopp.store.merge :as merge] [slopp.api.branch :as branch] [clojure.edn :as edn] [clojure.java.io :as io] [slopp.api.external :as external])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir []
  (str (Files/createTempDirectory "slopp-deps-test" (make-array FileAttribute 0))))

;; ---------------------------------------------------------------------------
;; M1a: the delta model (store)
(deftest deps-delta-model
  (let [s0 (store/empty-store)]
    (testing "a fresh store has an empty manifest"
      (is (= {} (:deps s0))))
    (let [[s1 d1] (store/record-deps-add s0 'org.clojure/data.json
                                         {:mvn/version "2.5.0"} :agent "a")]
      (testing "record-deps-add appends a delta and materializes the coord"
        (is (= :deps-add (:op d1)))
        (is (= 'org.clojure/data.json (:lib d1)))
        (is (= {:mvn/version "2.5.0"} (:coord d1)))
        (is (= '*session* (:ns d1)))
        (is (= {'org.clojure/data.json {:mvn/version "2.5.0"}} (:deps s1))))
      (testing "replay reconstructs :deps incrementally (foreign-sync stays cheap)"
        (let [replayed (store/replay-delta s0 d1)]
          (is (some? replayed))
          (is (= {'org.clojure/data.json {:mvn/version "2.5.0"}}
                 (:deps replayed)))))
      (let [[s2 d2] (store/record-deps-remove s1 'org.clojure/data.json
                                              :agent "a")]
        (testing "record-deps-remove drops the coord"
          (is (= :deps-remove (:op d2)))
          (is (= 'org.clojure/data.json (:lib d2)))
          (is (= {} (:deps s2)))
          (is (= {} (:deps (store/replay-delta s1 d2)))))))))

(deftest deps-merge-across-lines
  (testing "different libs from a diverged line land; same-lib divergence conflicts"
    (let [base (store/ingest (store/empty-store) 'x.core "(ns x.core)\n(defn f [] 1)\n")
          [ours _]   (store/record-deps-add base 'a/lib {:mvn/version "1.0"})
          [theirs _] (store/record-deps-add base 'b/lib {:mvn/version "2.0"})
          merged     (merge/merge-logs ours theirs)]
      (is (= {:mvn/version "1.0"} (get-in (:store merged) [:deps 'a/lib])))
      (is (= {:mvn/version "2.0"} (get-in (:store merged) [:deps 'b/lib]))))))

(deftest deps-merge-resolves-version-divergence-to-newer
  ;; same lib pinned to diverging mvn versions auto-resolves to the NEWER
  ;; (numeric, via slopp.semver) with a note — not left as a conflict.
  (let [base (store/ingest (store/empty-store) 'x.core "(ns x.core)\n")]
    (testing "theirs is newer → adopt theirs, note, no conflict"
      (let [[ours _]   (store/record-deps-add base 'a/lib {:mvn/version "1.2.0"})
            [theirs _] (store/record-deps-add base 'a/lib {:mvn/version "1.10.0"})
            m (merge/merge-logs ours theirs)]
        (is (= {:mvn/version "1.10.0"} (get-in (:store m) [:deps 'a/lib])))
        (is (empty? (:conflicts m)))
        (is (some #(and (= :deps (:resolved %)) (= {:mvn/version "1.10.0"} (:kept %)))
                  (:notes m)))))
    (testing "ours is newer → keep ours (still no conflict)"
      (let [[ours _]   (store/record-deps-add base 'a/lib {:mvn/version "1.10.0"})
            [theirs _] (store/record-deps-add base 'a/lib {:mvn/version "1.2.0"})
            m (merge/merge-logs ours theirs)]
        (is (= {:mvn/version "1.10.0"} (get-in (:store m) [:deps 'a/lib])))
        (is (empty? (:conflicts m)))))
    (testing "incomparable coords (mvn vs git) remain a real conflict, ours kept"
      (let [[ours _]   (store/record-deps-add base 'a/lib {:mvn/version "1.2.0"})
            [theirs _] (store/record-deps-add base 'a/lib {:git/sha "abc123"})
            m (merge/merge-logs ours theirs)]
        (is (seq (:conflicts m)))
        (is (= {:mvn/version "1.2.0"} (get-in (:store m) [:deps 'a/lib])))))))

;; ---------------------------------------------------------------------------
;; M1b: persistence (db meta materialization)
(deftest ^:external db-materializes-and-reloads-deps
  (let [dir  (temp-dir)
        conn (db/open! dir)]
    (try
      ;; a non-empty store (next-id gates load-store), then stamp deps
      (let [[s d] (store/record-deps-add
                   (store/ingest (store/empty-store) 'x.core "(ns x.core)\n")
                   'a/lib {:mvn/version "1.0"})]
        (db/persist! conn s d)
        (testing "the manifest survives persist + a fresh load-store"
          (is (= {'a/lib {:mvn/version "1.0"}} (db/deps conn)))
          (is (= {'a/lib {:mvn/version "1.0"}} (:deps (db/load-store conn))))))
      (finally (.close conn)))))

;; ---------------------------------------------------------------------------
;; M1c: the api ops — reaching the live image classpath + durable round-trip
(deftest ^:external deps-add-hot-loads-into-image
  (let [sess (external/open!)]
    (try
      (let [pid0 (.pid ^Process (:process (:image @sess)))
            r    (api/deps-add! sess 'org.clojure/data.json {:mvn/version "2.5.0"})]
        (is (nil? (:error r)) (pr-str r))
        (testing "hot-added — no restart, same image process"
          (is (true? (:hot r)))
          (is (= pid0 (.pid ^Process (:process (:image @sess))))))
        (testing "the dep is on the image classpath now"
          (is (= "{\"a\":1}"
                 (last (api/query-eval
                        sess (str "(require 'clojure.data.json)"
                                  "(clojure.data.json/write-str {:a 1})"))))))
        (testing "deps-list reflects it"
          (is (= {:mvn/version "2.5.0"}
                 (get (api/deps-list sess) 'org.clojure/data.json))))
        (testing "removing restarts the image (jar can't unload)"
          (let [rr (api/deps-remove! sess 'org.clojure/data.json)]
            (is (true? (:restarted rr)))
            (is (not= pid0 (.pid ^Process (:process (:image @sess)))))
            (is (empty? (api/deps-list sess))))))
      (finally (api/close! sess)))))

(deftest ^:external deps-add-validates
  (let [sess (external/open!)]
    (try
      (is (:error (api/deps-add! sess "not-a-symbol" {:mvn/version "1.0"})))
      (is (:error (api/deps-add! sess 'a/b {})))
      (is (:error (api/deps-remove! sess 'never/declared)))
      (finally (api/close! sess)))))

(deftest ^:external deps-durable-round-trip-and-branch-inherit
  (let [dir (temp-dir)]
    (let [sess (external/open! {:slopp.api/dir dir})]
      (try
        (api/deps-add! sess 'org.clojure/data.json {:mvn/version "2.5.0"}
                       :agent "a")
        (testing "a branch created after the add inherits the manifest"
          (branch/branch! sess "feature")
          (is (= {:mvn/version "2.5.0"}
                 (get (api/deps-list sess) 'org.clojure/data.json))))
        (finally (api/close! sess))))
    (testing "a fresh session over the same dir reloads deps AND its image can use them"
      (let [sess2 (external/open! {:slopp.api/dir dir})]
        (try
          (is (= {:mvn/version "2.5.0"}
                 (get (api/deps-list sess2) 'org.clojure/data.json)))
          (is (= "{\"a\":1}"
                 (last (api/query-eval
                        sess2 (str "(require 'clojure.data.json)"
                                   "(clojure.data.json/write-str {:a 1})")))))
          (finally (api/close! sess2)))))))

(deftest build-deps-edn-carries-manifest
  (testing "an empty manifest is byte-identical to the pre-manifest output"
    ;; the build! ours? byte-identity guard depends on this
    (is (= "{:paths [\"src\"]}\n" (build/deps-edn false)))
    (is (= "{:paths [\"src\"]}\n" (build/deps-edn false {})))
    (is (= (build/deps-edn true) (build/deps-edn true {}))))
  (testing "a manifest becomes the generated :deps map"
    (let [s (build/deps-edn false {'org.clojure/data.json {:mvn/version "2.5.0"}})]
      (is (re-find #":deps" s))
      (is (re-find #"org\.clojure/data\.json" s))
      (is (re-find #"2\.5\.0" s)))))

(deftest build-deps-edn-test-alias
  (testing "no test alias by default — byte-identity preserved"
    (is (not (re-find #":test" (build/deps-edn false {} false))))
    (is (= "{:paths [\"src\"]}\n" (build/deps-edn false {} false)))
    (is (= (build/deps-edn true {}) (build/deps-edn true {} false))))
  (testing "test? adds a :test alias putting test/ on a runnable extra-path"
    (let [s (build/deps-edn false {} true)
          m (clojure.edn/read-string s)]
      (is (re-find #":aliases" s))
      (is (= ["test"] (get-in m [:aliases :test :extra-paths])))
      (is (= ["src"] (:paths m)))))
  (testing "test? composes with the native alias and the manifest"
    (let [m (clojure.edn/read-string
             (build/deps-edn true {'org.clojure/data.json {:mvn/version "2.5.0"}} true))]
      (is (= ["test"] (get-in m [:aliases :test :extra-paths])))
      (is (contains? (:aliases m) :native))
      (is (contains? (:deps m) 'org.clojure/data.json)))))

;; ---------------------------------------------------------------------------
;; M4: dependency surface analysis (clj-kondo over the dep's own jars)
(deftest dep-surface-analysis
  (testing "a dep's own jars are external (classpath diff) and analyzed"
    (let [jars (deps/dep-jars 'org.clojure/data.json {:mvn/version "2.5.0"})]
      (is (some #(str/includes? % "data.json") jars))
      (let [s (deps/surface jars)]
        (is (contains? (:namespaces s) 'clojure.data.json))
        (testing "public vars carry arities + docstring"
          (let [wr (get-in s [:vars 'clojure.data.json/write-str])]
            (is (some? wr))
            (is (= 1 (:varargs-min wr)))
            (is (string? (:doc wr)))))))))

(deftest ^:external dep-surface-db-round-trip
  (let [dir (temp-dir) conn (db/open! dir)]
    (try
      (is (nil? (db/get-dep-surface conn "a/b@1.0")))
      (db/put-dep-surface! conn "a/b@1.0"
                           {:namespaces #{'x.y} :vars {'x.y/f {:arities [1]}}})
      (let [s (db/get-dep-surface conn "a/b@1.0")]
        (is (= #{'x.y} (:namespaces s)))
        (is (= [1] (get-in s [:vars 'x.y/f :arities]))))
      (finally (.close conn)))))

(deftest ^:external deps-add-returns-surface
  (let [sess (external/open!)]
    (try
      (let [r (api/deps-add! sess 'org.clojure/data.json {:mvn/version "2.5.0"})]
        (is (some #{'clojure.data.json} (:namespaces r)))
        (is (pos? (:vars r))))
      (finally (api/close! sess)))))

;; ---------------------------------------------------------------------------
;; M6: native-compat gate (GraalVM reachability metadata)
(deftest native-verdict-detects-metadata
  (testing "a dep without reachability metadata → :none (warn, not incompatible)"
    (is (= :none (:verdict (deps/native-verdict
                            (deps/dep-jars 'org.clojure/data.json
                                           {:mvn/version "2.5.0"}))))))
  (testing "a jar shipping META-INF/native-image → :declared"
    (let [jar (str (temp-dir) "/withmeta.jar")]
      (with-open [jos (java.util.jar.JarOutputStream.
                       (clojure.java.io/output-stream jar))]
        (.putNextEntry jos (java.util.jar.JarEntry.
                            "META-INF/native-image/foo/reflect-config.json"))
        (.write jos (.getBytes "[]"))
        (.closeEntry jos))
      (is (= :declared (:verdict (deps/native-verdict [jar])))))))

(deftest ^:external build-native-warns-on-missing-metadata
  (let [dir  (temp-dir)
        sess (external/open! {:slopp.api/dir dir})]
    (try
      (api/ingest! sess 'app.core "(ns app.core)\n\n(defn run [& args] (apply println args))\n")
      (api/deps-add! sess 'org.clojure/data.json {:mvn/version "2.5.0"} :agent "a")
      (let [out (str (temp-dir) "/built")
            r   (external/build! sess out :main 'app.core/run)]
        (is (nil? (:error r)) (pr-str r))
        (testing "the metadata-less dep surfaces as a native warning"
          (is (re-find #"data\.json" (str (get-in r [:native :warnings]))))
          (is (some #{'org.clojure/data.json}
                    (get-in r [:native :metadata-missing])))))
      (finally (api/close! sess)))))

(deftest ^:external deps-ride-the-mcp-surface
  (let [sess (external/open!)]
    (try
      (let [call (fn [tool args]
                   (get-in (slopp.mcp/handle!
                            sess {:id 1 :method "tools/call"
                                  :params {:name tool :arguments args}})
                           [:result :content 0 :text]))]
        (testing "deps_add with a version string"
          (is (re-find #":added"
                       (call "deps_add" {:lib "org.clojure/data.json"
                                         :version "2.5.0" :agent "a"}))))
        (testing "deps_list answers over MCP"
          (is (re-find #"data\.json" (call "deps_list" {})))))
      (finally (api/close! sess)))))

(deftest build-deps-edn-trace-alias
  ;; #121: the external tier is the ONLY tier that runs ^:external tests, so it
  ;; is the only place their trace can come from. When the store carries the
  ;; trace runner, both test aliases route through it instead of straight to
  ;; cognitect — it WRAPS cognitect, so the output the summary parsers read is
  ;; unchanged.
  (testing "without the trace runner nothing changes — cognitect direct"
    (let [s (build/deps-edn false {} true false)]
      (is (re-find #"\"-m\" \"cognitect\.test-runner\"" s))
      (is (not (re-find #"slopp\.testmain" s))))
    (testing "and the no-trace form is byte-identical to the 3-arg one"
      (is (= (build/deps-edn false {} true)
             (build/deps-edn false {} true false)))))
  (testing "with the trace runner BOTH aliases route through it"
    (let [s (build/deps-edn false {} true true)]
      (is (not (re-find #"\"-m\" \"cognitect\.test-runner\"" s))
          "cognitect is delegated to, never invoked directly")
      (is (= 2 (count (re-seq #"\"-m\" \"slopp\.image\.testmain\"" s)))
          ":test AND :test-run — a trace from only one tier-entry has a hole")
      (testing "cognitect stays on the classpath — the runner resolves it there"
        (is (re-find #"io\.github\.cognitect-labs/test-runner" s)))
      (testing ":test keeps its baked -r .* and :test-run still omits it (Q13)"
        (is (re-find #"\"-r\" \"\.\*\"" s))))))

(deftest build-deps-edn-coerces-exclusion-strings-to-symbols
  (testing "an MCP-supplied exclusion (JSON string) projects as a tools.deps symbol"
    (let [s (build/deps-edn false
                            {'garden/garden {:mvn/version "1.3.10"
                                             :exclusions ["com.yahoo.platform.yui/yuicompressor"]}})
          m (edn/read-string s)
          excl (get-in m [:deps 'garden/garden :exclusions])]
      (is (= ['com.yahoo.platform.yui/yuicompressor] excl))
      (is (symbol? (first excl)))
      (is (not (re-find #"\"com\.yahoo" s))))))

(deftest ^:external deps-add-client-is-build-only
  (let [sess (external/open!)]
    (try
      (let [r (api/deps-add! sess 'org.clojure/clojurescript {:mvn/version "1.11.132"}
                             :client true :prompt "the cljs compiler")]
        (testing "a :client dep records to the build-only manifest"
          (is (:client r))
          (is (= {:mvn/version "1.11.132"}
                 (get-in @sess [:store :client-deps 'org.clojure/clojurescript]))))
        (testing "and NEVER enters the runtime :deps that hot-loads and ships"
          (is (nil? (get-in @sess [:store :deps 'org.clojure/clojurescript])))))
      (finally (api/close! sess)))))

(deftest build-deps-edn-cljs-alias
  (testing "no client deps → byte-identical output (the ours? guard depends on it)"
    (is (= (build/deps-edn false {} false false)
           (build/deps-edn false {} false false {}))))
  (testing "client deps → a :cljs alias with the compiler and cljs-src, never in :deps"
    (let [edn (edn/read-string
               (build/deps-edn false {} false false
                               {'org.clojure/clojurescript {:mvn/version "1.11.132"}}))]
      (is (= {:mvn/version "1.11.132"}
             (get-in edn [:aliases :cljs :extra-deps 'org.clojure/clojurescript])))
      (is (= ["cljs-src"] (get-in edn [:aliases :cljs :extra-paths])))
      (is (nil? (get-in edn [:deps 'org.clojure/clojurescript]))))))

(deftest client-compiler-defaults-to-clojurescript
  (testing "defaults to :clojurescript with no config"
    (is (= :clojurescript (build/client-compiler {}))))
  (testing "a project plugs in another backend via the client/compiler config"
    (is (= :cherry (build/client-compiler
                    {:config {"client" {:values {"compiler" "cherry"}}}})))))

(deftest ^:external the-manifest-surface-answers-for-the-host-too
  ;; `deps_list` is the canonical "what does this store depend on" answer, and
  ;; for an inherited store it is the ONLY one anybody reads — the `deps_add`
  ;; report was seen once, by whoever typed it. So a declaration slopp's own
  ;; process overrides has to be visible here too, or the manifest reads as
  ;; wholly in force when part of it is inert.
  ;;
  ;; `api/deps-list` keeps returning the bare manifest: it is the accessor
  ;; other code and tests build on, and the host's classpath is not the
  ;; store's data. The WRAPPING is what the agent-facing surface returns.
  (let [dir (temp-dir)
        sess (external/open! {:slopp.api/dir dir})]
    (try
      (api/deps-add! sess 'org.clojure/data.json {:mvn/version "2.5.0"}
                     :agent "a")
      (let [m (api/deps-manifest sess)]
        (is (= {:mvn/version "2.5.0"} (get (:deps m) 'org.clojure/data.json))
            "the declarations are still all there, under :deps")
        (is (= (api/deps-list sess) (:deps m))
            "and they are exactly what the accessor returns")
        (testing "silence when nothing is overridden"
          ;; this suite runs from a checkout, not a jar, so nothing is bundled
          ;; — the key must be ABSENT rather than present-and-empty, or every
          ;; caller has to distinguish two spellings of nothing
          (is (not (contains? m :host-override)) (pr-str m))))
      (finally (api/close! sess)))))

(deftest a-framework-pin-that-differs-from-the-host-is-NAMED
  ;; The bug this closes actually happened, to the one project built to catch
  ;; exactly this: `slopp-ui` pinned `io.github.nvoxland/slopp-web 0.1.3`, the
  ;; trailing-slash fix in `mount-routes` shipped in slopp's store and was never
  ;; republished, and the app whose bug prompted the fix went on running the
  ;; broken copy. Green everywhere, for a day.
  ;;
  ;; Why nothing could have caught it: there was no fact to compare against.
  ;; `slim-install` took `:version` as an ad-hoc argument (defaulting to
  ;; "0.1.0", so running it bare would DOWNGRADE ~/.m2), and nothing recorded
  ;; which slopp-web release a given slopp's `slopp/web/**` corresponds to.
  ;;
  ;; And `:host-override` structurally cannot cover it, in both directions:
  ;; `bundled-libs.edn` is generated from slopp's dependency BASIS and slopp-web
  ;; is not a dependency of slopp — it is slopp's own source, materialized into
  ;; the jar — and its message ("the host bundles this and cannot displace it")
  ;; is the wrong story anyway. Measured: the DECLARATION wins. The pinned jar is
  ;; what loads, in the oracle image AND in a `java -jar` host process.
  ;;
  ;; So: report on DIFFERENCE, not on ordering. Whichever is newer, the pin is
  ;; what runs, and "a fix in the host has not reached you" is the thing worth
  ;; saying.
  (testing "a pin equal to the host's own framework version says nothing"
    (is (nil? (api/framework-drift {'io.github.nvoxland/slopp-web
                                    {:mvn/version "0.1.4"}}
                                   "0.1.4"))))
  (testing "a pin that differs is named, with both versions and which one RUNS"
    (let [d (api/framework-drift {'io.github.nvoxland/slopp-web
                                  {:mvn/version "0.1.3"}}
                                 "0.1.4")]
      (is (some? d))
      (is (= "0.1.3" (:declared d)) (pr-str d))
      (is (= "0.1.4" (:host d)) (pr-str d))
      (is (re-find #"(?i)declar" (:note d))
          (str "the note must say the declaration is what loads, or the reader"
               " draws the opposite conclusion: " (pr-str d)))))
  (testing "a store that does not depend on the framework at all says nothing"
    (is (nil? (api/framework-drift {'metosin/malli {:mvn/version "0.20.1"}}
                                   "0.1.4"))))
  (testing "and neither does a host that cannot say what it carries — running
            from a checkout rather than a jar is not a finding"
    (is (nil? (api/framework-drift {'io.github.nvoxland/slopp-web
                                    {:mvn/version "0.1.3"}}
                                   nil)))))
