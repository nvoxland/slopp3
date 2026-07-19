(ns slopp.benchmark
  "Progress meter (see .context/dogfooding.md): build sample apps through the
  REAL agent surface (mcp/handle, JSON round-tripped) with deterministic
  scripts, measuring wall time and token cost (chars/4 of the JSON actually
  sent/received). Deliberate red steps are included — debugging is part of
  real usage. Run at milestones: clojure -M -m slopp.benchmark
  Appends rows to benchmarks/results.md (committed).

  Scripts must exercise CURRENT best practice; bump an app's :v when its
  script changes (rows are only comparable within the same :v)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [cheshire.core :as json]
            [slopp.api :as api]
            [slopp.mcp :as mcp])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.time LocalDate]))

;; --- the sample apps ---

(def calculator
  ;; v3: the two-form fix is two REPL-style writes (episodes are inferred)
  {:name "calculator" :v 3 :test-ns "calc.core"
   :steps
   [{:tool "ns_create" :args {:ns "calc.core" :source "(ns calc.core\n  (:require [clojure.test :refer [deftest is]]))\n"}}
    {:tool "edit_add_form" :args {:ns "calc.core" :prompt "tokenizer"
                                  :source "(defn tokenize [s]\n  (mapv (fn [t] (if (re-matches #\"\\d+(\\.\\d+)?\" t) (parse-double t) (keyword t)))\n        (re-seq #\"\\d+(?:\\.\\d+)?|[-+*/()]\" s)))"}}
    {:tool "edit_add_form" :args {:ns "calc.core" :prompt "tokenizer test"
                                  :source "(deftest tokenize-t\n  (is (= [2.0 :+ 3.0] (tokenize \"2+3\")))\n  (is (= [1.5 :* 2.0 :- 1.0] (tokenize \"1.5*2-1\"))))"}}
    {:tool "edit_add_form" :args {:ns "calc.core" :prompt "op dispatch"
                                  :source "(defn apply-op [op a b]\n  (case op :+ (+ a b) :- (- a b) :* (* a b) :/ (/ a b)))"}}
    ;; deliberately buggy tier pass (collapses to a scalar) — realistic debugging
    {:tool "edit_add_form" :args {:ns "calc.core" :prompt "one precedence tier"
                                  :source "(defn eval-pass [ops tokens]\n  (reduce (fn [acc [op v]] (apply-op op acc v))\n          (first tokens)\n          (partition 2 (rest tokens))))"}}
    {:tool "edit_add_form" :args {:ns "calc.core" :prompt "evaluate"
                                  :source "(defn evaluate [s]\n  (->> (tokenize s) (eval-pass #{:* :/}) (eval-pass #{:+ :-})))"}}
    ;; RED lands here; since F1 the failure details come back in this response
    {:tool "edit_add_form" :args {:ns "calc.core" :prompt "precedence tests"
                                  :source "(deftest evaluate-t\n  (is (= 5.0 (evaluate \"2+3\")))\n  (is (= 7.0 (evaluate \"1+2*3\")))\n  (is (= 4.0 (evaluate \"10-3*2\"))))"}}
    ;; the two-form fix as individual REPL-style writes — the interim red
    ;; (stale evaluate) is normal mid-episode state
    {:tool "edit_replace_form"
     :args {:ns "calc.core" :name "eval-pass" :prompt "fix precedence: tier keeps unhandled ops"
            :source "(defn eval-pass [ops tokens]\n  (reduce (fn [acc [op v]]\n            (if (ops op)\n              (conj (pop acc) (apply-op op (peek acc) v))\n              (conj acc op v)))\n          [(first tokens)]\n          (partition 2 (rest tokens))))"}}
    {:tool "edit_replace_form"
     :args {:ns "calc.core" :name "evaluate" :prompt "evaluate unwraps the tier vector"
            :source "(defn evaluate [s]\n  (->> (tokenize s) (eval-pass #{:* :/}) (eval-pass #{:+ :-}) first))"}}
    {:tool "edit_add_form" :args {:ns "calc.core" :prompt "CLI entry"
                                  :source "(defn run-cli [args]\n  (doseq [expr args]\n    (println (str expr \" = \" (evaluate expr)))))"}}
    {:tool "edit_rename" :args {:ns "calc.core" :old "eval-pass" :new "reduce-tier" :prompt "clearer name"}}]})

(def inventory
  {:name "inventory" :v 1 :test-ns "inv.core"
   :steps
   [{:tool "ns_create" :args {:ns "inv.core" :source "(ns inv.core\n  (:require [clojure.test :refer [deftest is]]))\n"}}
    {:tool "edit_add_form" :args {:ns "inv.core" :prompt "store constructor"
                                  :source "(defn make-store [] (atom {}))"}}
    ;; mutating fn deliberately MIS-named (no !) — D6 warning comes back
    {:tool "edit_add_form" :args {:ns "inv.core" :prompt "add stock"
                                  :source "(defn add-item [store sku n]\n  (swap! store update sku (fnil + 0) n))"}}
    {:tool "edit_add_form" :args {:ns "inv.core" :prompt "total units"
                                  :source "(defn total [store] (reduce + 0 (vals @store)))"}}
    {:tool "edit_add_form" :args {:ns "inv.core" :prompt "behavior tests"
                                  :source "(deftest stock-t\n  (let [s (make-store)]\n    (add-item s :widget 3)\n    (add-item s :widget 2)\n    (add-item s :gadget 1)\n    (is (= {:widget 5 :gadget 1} @s))\n    (is (= 6 (total s)))))"}}
    ;; fix the D6 violation with the structural rename
    {:tool "edit_rename" :args {:ns "inv.core" :old "add-item" :new "add-item!" :prompt "fix ! violation"}}]})

(def wordstats
  {:name "wordstats" :v 1 :test-ns "ws.core"
   :steps
   [{:tool "ns_create" :args {:ns "ws.core" :source "(ns ws.core\n  (:require [clojure.test :refer [deftest is]]\n            [clojure.string :as str]))\n"}}
    {:tool "edit_add_form" :args {:ns "ws.core" :prompt "split words"
                                  :source "(defn words [s]\n  (remove str/blank? (str/split (str/lower-case s) #\"[^a-z0-9']+\")))"}}
    {:tool "edit_add_form" :args {:ns "ws.core" :prompt "frequencies"
                                  :source "(defn word-freqs [s] (frequencies (words s)))"}}
    ;; buggy: forgets the (- n) descending sort — RED on the next test step
    {:tool "edit_add_form" :args {:ns "ws.core" :prompt "top n words"
                                  :source "(defn top-words [s n]\n  (->> (word-freqs s) (sort-by val) (take n) (mapv key)))"}}
    {:tool "edit_add_form" :args {:ns "ws.core" :prompt "behavior tests"
                                  :source "(deftest stats-t\n  (is (= [\"the\" \"cat\"] (words \"The cat\")))\n  (is (= {\"a\" 2 \"b\" 1} (word-freqs \"a b a\")))\n  (is (= [\"a\" \"b\"] (top-words \"a b a a b c\" 2))))"}}
    {:tool "edit_replace_form" :args {:ns "ws.core" :name "top-words" :prompt "sort descending"
                                      :source "(defn top-words [s n]\n  (->> (word-freqs s) (sort-by (comp - val)) (take n) (mapv key)))"}}
    {:tool "edit_rename" :args {:ns "ws.core" :old "words" :new "tokenize" :prompt "clearer name"}}]})

(def apps [calculator inventory wordstats])

;; --- runner ---

(defn- call!
  "One tool call through the real wire shape; returns measured sizes + text."
  [session {:keys [tool args]}]
  (let [in   (json/generate-string {:jsonrpc "2.0" :id 1 :method "tools/call"
                                    :params {:name tool :arguments args}})
        resp (mcp/handle! session (json/parse-string in true))
        out  (json/generate-string resp)]
    {:in (count in) :out (count out)
     :text (get-in resp [:result :content 0 :text])}))

(defn- temp-dir []
  (str (Files/createTempDirectory "slopp-benchmark" (make-array FileAttribute 0))))

(defn- run-app!
  "Run one app's script in a fresh durable session; returns the measurements.

  PRIVATE: `-main` is the only caller. It was public by accident, not design —
  and public surface is what the boundary gates judge, so an accidental
  `defn` makes an internal fixture shape look like a contract."
  [{:keys [name steps test-ns]}]
  (let [session (api/open! {:dir (temp-dir) :warm-spare? true})]
    (try
      (let [t0    (System/nanoTime)
            sizes (mapv #(call! session %) steps)
            final (call! session {:tool "test_run" :args {:ns test-ns}})
            wall  (long (/ (- (System/nanoTime) t0) 1e6))
            summ  (edn/read-string (:text final))]
        (when-not (zero? (+ (:fail summ) (:error summ)))
          (throw (ex-info (str "benchmark app not green at the end: " name)
                          {:app name :summary summ})))
        {:app name :steps (inc (count steps)) :wall-ms wall
         :tok-in  (quot (reduce + (map :in (conj sizes final))) 4)
         :tok-out (quot (reduce + (map :out (conj sizes final))) 4)})
      (finally (api/close! session)))))

(defn- git-sha []
  (str/trim (:out (sh/sh "git" "rev-parse" "--short" "HEAD"))))

(def ^:private results-file "benchmarks/results.md")

(defn- append-results! [rows]
  (io/make-parents results-file)
  (when-not (.exists (io/file results-file))
    (spit results-file
          (str "# Benchmark history\n\n"
               "Wall + token cost of building each sample app through the MCP surface\n"
               "(`clojure -M -m slopp.benchmark`; see `.context/dogfooding.md`).\n"
               "Rows are comparable only within the same script version (v).\n\n"
               "| date | sha | app | v | steps | wall ms | tok in | tok out |\n"
               "|---|---|---|---|---|---|---|---|\n")))
  (let [sha (git-sha) date (str (LocalDate/now))]
    (doseq [{:keys [app v steps wall-ms tok-in tok-out]} rows]
      (spit results-file
            (format "| %s | %s | %s | %s | %d | %d | %d | %d |\n"
                    date sha app v steps wall-ms tok-in tok-out)
            :append true))))

(defn -main "CLI: run every benchmark app, print a row per app, and append the results to
  `benchmarks/results.md`. The tree is fileless, so this runs through the boot
  kernel:
  `clojure -M -m slopp.boot . --snapshot --main slopp.benchmark/-main`"
  [& _]
  (let [rows (doall
              (for [app apps]
                (let [r (assoc (run-app! app) :v (:v app))]
                  (println (format "%-12s v%d  %5dms  in:%d out:%d tokens"
                                   (:app r) (:v r) (:wall-ms r)
                                   (:tok-in r) (:tok-out r)))
                  r)))]
    (append-results! rows)
    (println (str "recorded -> " results-file)))
  (shutdown-agents)
  (System/exit 0))
