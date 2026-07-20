(ns slopp.mcp-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.edn :as edn]
            [cheshire.core :as json]
            [slopp.api :as api]
            [slopp.mcp :as mcp] [clojure.java.io :as io] [slopp.store :as store] [slopp.db :as db] [clojure.java.shell :as sh] [slopp.sync :as sync] [clojure.string :as str] [slopp.mcp.tools :as tools] [slopp.api.query :as query] [slopp.api.review :as review] [slopp.api.external :as external]))

(deftest ^:external protocol-handshake
  (let [sess (atom {})]
    (testing "initialize returns serverInfo named slopp"
      (let [r (mcp/handle! sess {:jsonrpc "2.0" :id 1 :method "initialize" :params {}})]
        (is (= "slopp" (get-in r [:result :serverInfo :name])))
        (is (contains? (:result r) :protocolVersion))))
    (testing "tools/list returns schemas with MCP-legal names"
      (let [tools (get-in (mcp/handle! sess {:id 2 :method "tools/list"}) [:result :tools])]
        (is (seq tools))
        (is (every? #(re-matches #"[a-zA-Z0-9_-]+" (:name %)) tools))
        (is (contains? (set (map :name tools)) "query_source"))
        (is (contains? (set (map :name tools)) "edit_replace_form"))))
    (testing "notifications (no id) produce no response"
      (is (nil? (mcp/handle! sess {:method "notifications/initialized"}))))
    (testing "unknown method -> JSON-RPC error"
      (is (= -32601 (get-in (mcp/handle! sess {:id 9 :method "bogus"}) [:error :code]))))))

(defn- call! [sess tool args]
  (get-in (mcp/handle! sess {:id 1 :method "tools/call"
                            :params {:name tool :arguments args}})
          [:result :content 0 :text]))

(deftest ^:external tools-call-end-to-end
  (let [sess (api/open!)]
    (try
      (call! sess "ns_create" {:ns "demo" :source "(ns demo)\n(defn add [x y] (+ x y))\n"})
      (testing "query_source (VFS read)"
        (is (re-find #"defn add" (call! sess "query_source" {:ns "demo" :full true}))))
      (testing "query_eval hits the oracle"
        (is (re-find #"\b5\b" (call! sess "query_eval" {:code "(demo/add 2 3)"}))))
      (testing "edit_replace_form over the wire (JSON round-trip) hot-reloads"
        (let [wire-req (json/generate-string
                        {:jsonrpc "2.0" :id 4 :method "tools/call"
                         :params {:name "edit_replace_form"
                                  :arguments {:ns "demo" :name "add"
                                              :source "(defn add [x y] (* x y))"
                                              :prompt "mul"}}})
              resp (mcp/handle! sess (json/parse-string wire-req true))
              wire-resp (json/parse-string (json/generate-string resp) true)]
          (is (nil? (:error wire-resp)))
          (is (re-find #"\b6\b" (call! sess "query_eval" {:code "(demo/add 2 3)"})))))
      (finally (api/close! sess)))))

(deftest ^:external help-and-hints                                ; item 3: weak-model guidance
  (let [sess (api/open!)]
    (try
      (testing "the help tool exists (agents invented the name twice)"
        (let [h (call! sess "help" {})]
          (is (re-find #"edit_replace_form" h))
          (is (re-find #"query_project" h))))
      (call! sess "ns_create" {:ns "hint" :source "(ns hint (:require [clojure.test :refer [deftest is]]))\n(defn f [x] x)\n(deftest f-t (is (= 1 (f 1))))\n"})
      (testing "redundant test_runs earn a hint; a write resets the counter"
        (call! sess "test_run" {:ns "hint"})
        (call! sess "test_run" {:ns "hint"})
        (let [r3 (call! sess "test_run" {:ns "hint"})]
          (is (re-find #"rarely needed" r3)))
        (call! sess "edit_replace_form" {:ns "hint" :name "f" :source "(defn f [x] (identity x))"})
        (is (not (re-find #"rarely needed" (call! sess "test_run" {:ns "hint"})))))
      (testing "a hint fires ONCE per session — a fresh streak stays quiet"
        (call! sess "test_run" {:ns "hint"})
        (call! sess "test_run" {:ns "hint"})
        (let [r6 (call! sess "test_run" {:ns "hint"})]
          (is (not (re-find #"rarely needed" r6)))))
      (testing "a write between test_run and done keeps done QUIET (spot-check flow)"
        (call! sess "edit_replace_form" {:ns "hint" :name "f" :source "(defn f [x] x)"})
        (is (not (re-find #"pre-flight" (call! sess "done" {:label "quiet"})))))
      (testing "an ISOLATED run before done stays quiet — it is the milestone gate"
        (call! sess "test_run" {:ns "hint" :external true})
        (is (not (re-find #"pre-flight" (call! sess "done" {:label "gated"})))))
      (testing "an in-image test_run immediately before done earns the redundancy hint"
        (call! sess "test_run" {:ns "hint"})
        (is (re-find #"pre-flight" (call! sess "done" {:label "noisy"}))))
      (finally (api/close! sess)))))

(deftest ^:external rename-arg-forgiveness                        ; from the symmetric eval
  (let [sess (api/open!)]
    (try
      (call! sess "ns_create" {:ns "ra" :source "(ns ra)\n(defn f [x] x)\n(defn g [x] (f x))\n"})
      (testing "the aliases every eval run guessed first now just work"
        (let [r (edn/read-string (call! sess "edit_rename"
                                       {:ns "ra" :name "f" :to "h"}))]
          (is (nil? (:error r)))))
      (testing "missing args produce a clear message, not a raw conversion error"
        (is (re-find #"needs :old and :new"
                     (call! sess "edit_rename" {:ns "ra"})))
        (is (re-find #"missing required argument :ns"
                     (call! sess "query_source" {}))))
      (finally (api/close! sess)))))

(deftest ^:external write-op-arg-forgiveness                      ; eval round 2
  (let [sess (api/open!)]
    (try
      (call! sess "ns_create" {:ns "wa" :source "(ns wa)\n(defn f [x] (+ x x 1))\n(defn g [x] (f x))\n"})
      (testing "edit_extract accepts :source for :form; missing gets a real message"
        (let [r (edn/read-string (call! sess "edit_extract"
                                       {:ns "wa" :from "f" :name "doubled"
                                        :source "(+ x x 1)"}))]
          (is (nil? (:error r))))
        (is (re-find #"needs :form" (call! sess "edit_extract"
                                          {:ns "wa" :from "f" :name "z"}))))
      (finally (api/close! sess)))))

(deftest ^:external green-responses-are-terse                     ; B1
  (let [sess (api/open!)]
    (try
      (call! sess "ns_create" {:ns "b1" :source "(ns b1 (:require [clojure.test :refer [deftest is]]))\n(defn f [x] x)\n(deftest f-t (is (= 1 (f 1))))\n"})
      (call! sess "test_run" {:ns "b1"})
      (testing "a quiet green edit returns the terse shape"
        (let [r (edn/read-string (call! sess "edit_replace_form"
                                       {:ns "b1" :name "f"
                                        :source "(defn f [x] (identity x))"}))]
          (is (true? (:ok r)))
          (is (nil? (:failures r)))
          (is (< (count (pr-str r)) 120) (pr-str r))))
      (testing ":verbose true forces the full shape"
        (let [r (edn/read-string (call! sess "edit_replace_form"
                                       {:ns "b1" :name "f"
                                        :source "(defn f [x] x)" :verbose true}))]
          (is (map? (:delta r)))
          (is (map? (:test r)))))
      (testing "a red edit returns full detail incl. :failures"
        (let [r (edn/read-string (call! sess "edit_replace_form"
                                       {:ns "b1" :name "f"
                                        :source "(defn f [x] (inc x))"}))]
          (is (seq (get-in r [:test :failures])))))
      (finally (api/close! sess)))))
(deftest parse-call-args-shapes
  (testing "nil/blank → {}"
    (is (= {} (mcp/parse-call-args nil)))
    (is (= {} (mcp/parse-call-args "  "))))
  (testing "JSON and EDN both parse, keys keywordized"
    (is (= {:ns "demo" :limit 5} (mcp/parse-call-args "{\"ns\":\"demo\",\"limit\":5}")))
    (is (= {:ns "demo" :limit 5} (mcp/parse-call-args "{:ns \"demo\" :limit 5}"))))
  (testing "@file reads the file first"
    (let [f (java.io.File/createTempFile "callargs" ".json")]
      (spit f "{\"ns\":\"demo\"}")
      (is (= {:ns "demo"} (mcp/parse-call-args (str "@" f))))))
  (testing "non-map input is a clear error"
    (is (thrown-with-msg? Exception #"JSON or EDN map"
                          (mcp/parse-call-args "[1 2 3]")))))
(deftest ^:external one-shot-call
  (let [dir (str (java.nio.file.Files/createTempDirectory
                  "slopp-call" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (testing "a query works with no MCP connection and returns the wire shape"
      (let [r (mcp/call! dir "query_project" {})]
        (is (not (:isError r)))
        (is (string? (get-in r [:content 0 :text])))))
    (testing "writes stay turn-gated: no open turn → tool error, not a write"
      (let [r (mcp/call! dir "ns_create" {:ns "demo" :source "(ns demo)"
                                          :agent "probe"})]
        (is (:isError r))
        (is (re-find #"turn" (get-in r [:content 0 :text])))))
    (testing "turn_begin in one call!, the write in the NEXT (turns are durable)"
      (mcp/call! dir "turn_begin" {:agent "probe" :intent "one-shot test"})
      (let [r (mcp/call! dir "ns_create" {:ns "demo"
                                          :source "(ns demo)\n(defn f [x] x)\n"
                                          :agent "probe"})]
        (is (not (:isError r)) (get-in r [:content 0 :text]))))))
(deftest ^:external pending-intent-opens-the-turn
  ;; the plugin's UserPromptSubmit hook drops {session-id, prompt} JSON in
  ;; .slopp/pending-intent; the turn gate opens the turn from it and the
  ;; session ADOPTS the harness session id as its identity — no agent
  ;; field anywhere on the wire
  (let [dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-turnhook"
                   (make-array java.nio.file.attribute.FileAttribute 0)))
        sess (api/open! {:slopp.api/dir dir})]
    (try
      (swap! sess assoc :require-turns? true)
      (spit (io/file dir ".slopp" "pending-intent")
            "{\"session-id\":\"sess-abc123\",\"prompt\":\"add a widget feature\"}")
      (let [r (edn/read-string
               (call! sess "ns_create" {:ns "pi.core"
                                       :source "(ns pi.core)\n(defn f [] 1)\n"}))]
        (is (nil? (:error r)) (pr-str r)))
      (is (false? (.exists (io/file dir ".slopp" "pending-intent"))))
      (testing "the session adopted the harness id; the write is stamped with it"
        (is (= "sess-abc123" (:agent-id @sess)))
        (is (= "sess-abc123"
               (->> (store/deltas (:store @sess))
                    (filter #(= :ingest (:op %)))
                    first :agent))))
      (testing "the turn carries the verbatim prompt"
        (is (seq (query/query-search-history sess "add a widget feature"))))
      (testing "with no pending intent and no turn, the gate still refuses"
        (call! sess "turn_end" {})
        (let [r (call! sess "edit_add_form" {:ns "pi.core"
                                            :source "(defn g [] 2)"})]
          (is (re-find #"no open turn" r))))
      (finally (api/close! sess)))))
(deftest ^:external two-sessions-never-merge-episodes
  ;; the P4 invariant, now free of wire labels: two sessions on ONE store
  ;; get DISTINCT generated identities, so episode_revert scopes to the
  ;; session that calls it (under a constant label these episodes MERGED)
  (let [dir (str (java.nio.file.Files/createTempDirectory
                  "slopp-iso"
                  (make-array java.nio.file.attribute.FileAttribute 0)))
        sa  (api/open! {:slopp.api/dir dir})
        sb  (api/open! {:slopp.api/dir dir})]
    (try
      (is (not= (:agent-id @sa) (:agent-id @sb)))
      (call! sa "ns_create" {:ns "iso.a" :source "(ns iso.a)\n(defn fa [] 1)\n"})
      (call! sb "ns_create" {:ns "iso.b" :source "(ns iso.b)\n(defn fb [] 2)\n"})
      (let [r (edn/read-string (call! sb "episode_revert" {}))]
        (is (nil? (:error r)) (pr-str r)))
      (testing "B's work is gone, A's survives"
        (is (not (re-find #"defn fb" (call! sb "query_source" {:targets [{:ns "iso.b" :name "fb"}]}))))
        (is (re-find #"defn fa" (call! sa "query_source" {:targets [{:ns "iso.a" :name "fa"}]}))))
      (finally (api/close! sa) (api/close! sb)))))
(deftest ^:external terse-results-carry-forms
  (let [sess (api/open!)]
    (try
      (call! sess "ns_create" {:ns "tf.core" :source "(ns tf.core)\n(defn f [x] x)\n"})
      (testing "replace names its form"
        (is (re-find #":forms \[\"tf.core/f\"\]"
                     (call! sess "edit_replace_form" {:ns "tf.core" :name "f"
                                                     :source "(defn f [x] (identity x))"}))))
      (finally (api/close! sess)))))
(deftest ^:external trimmed-responses-spool-the-full-version
  (let [sess (api/open!)]
    (try
      (call! sess "ns_create" {:ns "sp.core" :source "(ns sp.core)\n(defn f [] 1)\n"})
      (testing "a response over the size gate is trimmed and retrievable"
        (let [r  (call! sess "query_eval" {:code "(apply str (repeat 9000 \"x\"))"})
              id (second (re-find #"query_detail \{:id \"(r\d+)\"\}" r))]
          (is (some? id) r)
          (let [full (call! sess "query_detail" {:id id})]
            (is (>= (count full) 8000))
            (is (not (re-find #"query_detail \{:id" full))))))
      (testing "giant failure strings never reach the agent whole
                (upstream capture truncates actuals; the text! heuristic
                covers the other fields)"
        (let [r (call! sess "ns_create"
                      {:ns "sp.red"
                       :source "(ns sp.red (:require [clojure.test :refer [deftest is]]))\n(deftest big-t (is (= \"a\" (apply str (repeat 3000 \"z\")))))\n"})]
          (is (re-find #"fail 1" r))
          (is (not (re-find #"z{1000}" r)))))
      (testing "an unknown id is an honest error"
        (is (re-find #"no spooled response" (call! sess "query_detail" {:id "r999"}))))
      (finally (api/close! sess)))))
(deftest ^:external untested-writes-stay-terse-and-honest
  (let [sess (api/open!)]
    (try
      (call! sess "ns_create" {:ns "ut.core" :source "(ns ut.core)\n(defn f [x] x)\n(defn g [x] x)\n"})
      (call! sess "ns_create" {:ns "ut.core-test" :source "(ns ut.core-test (:require [clojure.test :refer [deftest is]] [ut.core :as c]))\n(deftest f-t (is (= 1 (c/f 1))))\n"})
      (call! sess "test_run" {:ns "ut.core-test"})
      (testing "an untested green write is terse — flagged, no source echo"
        (let [r (call! sess "edit_replace_form" {:ns "ut.core" :name "g"
                                                :source "(defn g [x] (identity x))"})]
          (is (re-find #":ok true" r) r)
          (is (re-find #":untested true" r) r)
          (is (not (re-find #"identity" r)) r)
          (testing "…but the covering namespace's suite still RUNS (graph fallback)"
            ;; no test covers `g` specifically, so :untested holds. It used to
            ;; also verify NOTHING, because the fallback ran tests in ut.core
            ;; — a production ns holding none. It now runs ut.core-test, found
            ;; through the require graph. Emptiness itself is asserted by
            ;; unverified-says-why-it-verified-nothing.
            (is (re-find #":status :green" r) r)
            (is (not (re-find #":coverage :none" r)) r))))
      (testing "a new deftest is not 'untested' — it IS a test"
        (call! sess "edit_add_form" {:ns "ut.core-test"
                                    :source "(deftest g-t (is (= 2 (c/g 2))))"})
        (let [r (call! sess "edit_replace_form" {:ns "ut.core-test" :name "g-t"
                                                :source "(deftest g-t (is (= 3 (c/g 3))))"})]
          (is (not (re-find #":untested" r)) r)))
      (finally (api/close! sess)))))
(deftest ^:external rename-names-leftover-prose-mentions
  (let [sess (api/open!)]
    (try
      (call! sess "ns_create"
            {:ns "pm.core"
             :source (str "(ns pm.core)\n"
                          "(defn bulk-rate [n] (if (>= n 10) 0.1 0.0))\n"
                          "(defn describe\n  \"Applies the bulk-rate tier.\"\n  [n]\n"
                          "  (str \"bulk-rate applies: \" (bulk-rate n)))\n")})
      (testing "the rename result points at docstring/string mentions of the old name (Q11)"
        (let [r (call! sess "edit_rename" {:ns "pm.core" :old "bulk-rate" :new "volume-rate"})]
          (is (re-find #":mentions" r) r)
          (is (re-find #":ns pm.core, :form describe" r) r)))
      (testing "a clean rename carries no :mentions"
        (call! sess "edit_replace_form"
              {:ns "pm.core" :name "describe"
               :source "(defn describe [n] (str \"tier: \" (volume-rate n)))"})
        (let [r (call! sess "edit_rename" {:ns "pm.core" :old "volume-rate" :new "tier-rate"})]
          (is (not (re-find #":mentions" r)) r)))
      (finally (api/close! sess)))))
(deftest ^:external milestones-publish-themselves
  (let [dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-pub" (make-array java.nio.file.attribute.FileAttribute 0)))
        _    (sh/sh "git" "init" dir)
        _    (sh/sh "git" "-C" dir "-c" "user.name=t" "-c" "user.email=t@t"
                    "commit" "--allow-empty" "-m" "root")
        sess (api/open! {:slopp.api/dir dir})]
    (try
      (call! sess "ns_create" {:ns "pub.core" :source "(ns pub.core)\n(defn ^:unused-ok f [x] x)\n"})
      (testing "a milestone mirrors into LOCAL git as slopp/<store-branch> (user decision 2026-07-14)"
        (let [r (call! sess "commit_point" {:description "first"})]
          (is (re-find #":published" r) r)
          (is (re-find #"slopp/main" r) r))
        (let [head (:out (sh/sh "git" "-C" dir "rev-parse" "refs/heads/slopp/main"))]
          (is (= 40 (count (clojure.string/trim head))) head)))
      (testing "no REMOTE is touched or saved — remote publishing stays explicit"
        (is (nil? (db/get-meta (:db @sess) "git-remote"))))
      (finally (api/close! sess)))))
(deftest ^:external commits-prove-git-alignment
  (let [dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-align" (make-array java.nio.file.attribute.FileAttribute 0)))
        _    (sh/sh "git" "init" dir)
        _    (sh/sh "git" "-C" dir "-c" "user.name=t" "-c" "user.email=t@t"
                    "commit" "--allow-empty" "-m" "root")
        sess (api/open! {:slopp.api/dir dir})]
    (try
      (call! sess "ns_create" {:ns "al.core" :source "(ns al.core)\n(defn ^:unused-ok f [x] x)\n"})
      (call! sess "commit_point" {:description "first"})
      (testing "query_commits carries the alignment PROOF against the local mirror (Q12)"
        (let [r (call! sess "query_commits" {})]
          (is (re-find #":aligned true" r) r)
          (is (re-find #":branch-head" r) r)
          (is (re-find #"no worktree" r) r)))
      (finally (api/close! sess)))))
(deftest ^:external whole-ns-source-is-outline-by-default
  (let [sess (api/open!)]
    (try
      (call! sess "ns_create" {:ns "gt.core" :source "(ns gt.core)\n(defn f [x] (* x 2))\n(defn g [x] (+ x 1))\n"})
      (testing "a bare {ns} read returns the outline + the way in, NOT the dump"
        (let [r (call! sess "query_source" {:ns "gt.core"})]
          (is (not (re-find #"\(\* x 2\)" r)) r)
          (is (re-find #"f" r) r)
          (is (re-find #"full" r) r)))
      (testing "named targets stay a cheap direct read"
        (is (re-find #"\(\* x 2\)"
                     (call! sess "query_source" {:targets [{:ns "gt.core" :name "f"}]}))))
      (testing "full: true is the explicit whole-namespace dump"
        (is (re-find #"\(\* x 2\)" (call! sess "query_source" {:ns "gt.core" :full true}))))
      (finally (api/close! sess)))))
(deftest ^:external rename-sweep-is-one-intent
  (let [sess (api/open!)]
    (try
      (call! sess "ns_create"
            {:ns "sw.zone"
             :source (str "(ns sw.zone (:require [clojure.test :refer [deftest is]]))\n"
                          "(def zone-fees {1 500, 2 900})\n"
                          "(defn zone-fee \"The zone fee table lookup.\" [z] (get zone-fees z 0))\n"
                          "(deftest zone-t (is (= 500 (zone-fee 1))))\n")})
      (call! sess "module_dep" {:from "sw.core" :to "sw.zone" :prompt "fixture edge"})
      (call! sess "ns_create"
            {:ns "sw.core"
             :source (str "(ns sw.core (:require [clojure.test :refer [deftest is]] [sw.zone :as zone]))\n"
                          "(defn total \"Base plus the zone fee.\" [z] (+ 100 (zone/zone-fee z)))\n"
                          "(deftest total-t (is (= 600 (total 1))))\n")})
      (testing "one call sweeps namespaces, vars, keys, and prose (Q14)"
        (let [r (call! sess "rename_sweep" {:from "zone" :to "region"})]
          (is (re-find #":renamed-namespaces" r) r)
          (is (not (re-find #":error" r)) r)))
      (testing "the sweep is total"
        (is (= "[]" (call! sess "query_search" {:pattern "zone"}))))
      (testing "behavior survives under the new names"
        (is (re-find #"600" (call! sess "query_eval" {:code "(sw.core/total 1)"})))
        (is (re-find #"500" (call! sess "query_eval" {:code "(sw.region/region-fee 1)"}))))
      (finally (api/close! sess)))))
(deftest ^:external repeated-reads-are-free
  (let [sess (api/open!)]
    (try
      (call! sess "ns_create"
            {:ns "tk.core"
             :source (apply str "(ns tk.core)\n"
                            (for [i (range 1 6)]
                              (str "(defn f" i " [x] (+ x " i "))\n")))})
      (testing "an identical re-read returns an :unchanged stub, not the payload (told-tracking)"
        (let [a (call! sess "query_source" {:ns "tk.core"})
              b (call! sess "query_source" {:ns "tk.core"})]
          (is (re-find #":outline" a) a)
          (is (re-find #":unchanged true" b) b)
          (is (< (count b) (count a)))))
      (testing "a body edit leaves the OUTLINE honestly unchanged"
        (call! sess "edit_replace_form" {:ns "tk.core" :name "f1"
                                        :source "(defn f1 [x] (* x 9))"})
        (is (re-find #":unchanged true" (call! sess "query_source" {:ns "tk.core"}))))
      (testing "a change the view can SEE invalidates it"
        (call! sess "edit_add_form" {:ns "tk.core" :source "(defn g [x] x)"})
        (is (re-find #":outline" (call! sess "query_source" {:ns "tk.core"}))))
      (finally (api/close! sess)))))
(deftest ^:external usage-smells-hint-once
  (let [sess (api/open!)]
    (try
      (call! sess "ns_create" {:ns "sm.a" :source "(ns sm.a)\n(defn f [x] x)\n(defn g [x] x)\n"})
      (call! sess "ns_create" {:ns "sm.b" :source "(ns sm.b)\n(defn h [x] x)\n"})
      (testing "a second whole-namespace dump earns the slice hint, ONCE"
        (call! sess "query_source" {:ns "sm.a" :full true})
        (let [r2 (call! sess "query_source" {:ns "sm.b" :full true})]
          (is (re-find #"query_slice" r2) r2))
        (call! sess "ns_create" {:ns "sm.c" :source "(ns sm.c)\n(defn i [x] x)\n"})
        (let [r3 (call! sess "query_source" {:ns "sm.c" :full true})]
          (is (not (re-find #"query_slice" r3)) r3)))
      (testing "a rename streak earns the sweep hint"
        (call! sess "edit_rename" {:ns "sm.a" :old "f" :new "f2"})
        (let [r (call! sess "edit_rename" {:ns "sm.a" :old "g" :new "g2"})]
          (is (re-find #"rename_sweep" r) r)))
      (finally (api/close! sess)))))
(deftest ^:external one-off-pushes-keep-the-default-remote
  (let [dir   (str (java.nio.file.Files/createTempDirectory
                    "slopp-oop" (make-array java.nio.file.attribute.FileAttribute 0)))
        barea (str (java.nio.file.Files/createTempDirectory
                    "slopp-oop-a" (make-array java.nio.file.attribute.FileAttribute 0)))
        bareb (str (java.nio.file.Files/createTempDirectory
                    "slopp-oop-b" (make-array java.nio.file.attribute.FileAttribute 0)))
        _     (sh/sh "git" "init" "--bare" barea)
        _     (sh/sh "git" "init" "--bare" bareb)
        sess  (api/open! {:slopp.api/dir dir})]
    (try
      (api/ingest! sess 'pr.core "(ns pr.core)\n(defn ^:unused-ok f [x] x)\n")
      (external/commit-point! sess "seed" :agent "t")
      (testing "the FIRST url is saved as the default"
        (is (nil? (:error (sync/push! dir :url barea))))
        (is (= barea (db/get-meta (:db @sess) "git-remote"))))
      (testing "a one-off push elsewhere succeeds but the default STAYS (user regression)"
        (let [r (sync/push! dir :url bareb)]
          (is (nil? (:error r)) (pr-str r))
          (is (= barea (db/get-meta (:db @sess) "git-remote")))
          (is (= barea (str (:default-remote r))) (pr-str r))))
      (finally (api/close! sess)))))
(deftest ^:external mirror-push-and-pull-sync-slopp-branches
  (let [dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-mir" (make-array java.nio.file.attribute.FileAttribute 0)))
        bare (str (java.nio.file.Files/createTempDirectory
                   "slopp-mir-remote" (make-array java.nio.file.attribute.FileAttribute 0)))
        _    (sh/sh "git" "init" dir)
        _    (sh/sh "git" "-C" dir "-c" "user.name=t" "-c" "user.email=t@t"
                    "commit" "--allow-empty" "-m" "root")
        _    (sh/sh "git" "init" "--bare" bare)
        sess (api/open! {:slopp.api/dir dir})]
    (try
      (call! sess "ns_create" {:ns "mr.core" :source "(ns mr.core)\n(defn ^:unused-ok f [x] x)\n"})
      (call! sess "commit_point" {:description "first"})
      (testing "git_push mirrors local slopp/* to the remote (and saves the first url)"
        (let [r (call! sess "git_push" {:branches ["main"] :url bare})]
          (is (re-find #":mirrored" r) r))
        (is (re-find #"refs/heads/slopp/main"
                     (:out (sh/sh "git" "ls-remote" "--heads" bare))))
        (is (= bare (db/get-meta (:db @sess) "git-remote"))))
      (testing "git_pull brings the mirror into another clone (auto-import keys on slopp/*)"
        (let [dir2 (str (java.nio.file.Files/createTempDirectory
                         "slopp-mir2" (make-array java.nio.file.attribute.FileAttribute 0)))]
          (sh/sh "git" "clone" bare dir2)
          (sh/sh "git" "-C" dir2 "checkout" "-q" "-b" "work")
          (is (some? (sync/maybe-auto-import! dir2)) "marker must accept slopp/main")
          (let [s2 (api/open! {:slopp.api/dir dir2})]
            (try
              (call! s2 "ns_create" {:ns "mr.extra" :source "(ns mr.extra)\n(defn ^:unused-ok g [x] x)\n"})
              (let [r (call! s2 "git_pull" {:branches ["main"] :url bare})]
                (is (re-find #":pulled" r) r)
                (is (re-find #"slopp/main" r) r))
              (finally (api/close! s2))))))
      (finally (api/close! sess)))
    (testing "a FILELESS store (no .git) still publishes — the projection goes directly"
      (let [d2   (str (java.nio.file.Files/createTempDirectory
                       "slopp-nogit" (make-array java.nio.file.attribute.FileAttribute 0)))
            bare2 (str (java.nio.file.Files/createTempDirectory
                        "slopp-nogit-remote" (make-array java.nio.file.attribute.FileAttribute 0)))
            _    (sh/sh "git" "init" "--bare" bare2)
            s3   (api/open! {:slopp.api/dir d2})]
        (try
          (call! s3 "ns_create" {:ns "ng.core" :source "(ns ng.core)\n(defn ^:unused-ok f [x] x)\n"})
          (let [r (call! s3 "commit_point" {:description "no git here"})]
            (is (re-find #":commit" r) r)
            (is (not (re-find #":published" r)) r))
          (is (re-find #"url" (call! s3 "git_push" {})) "no remote: helpful error names :url")
          (let [r (call! s3 "git_push" {:url bare2})]
            (is (re-find #":pushed" r) r))
          (is (re-find #"refs/heads/slopp/main"
                       (:out (sh/sh "git" "ls-remote" "--heads" bare2))))
          (finally (api/close! s3)))))))
(deftest ^:external red-first-rides-the-wire
  ;; the api carried :red-first but the wire's select-keys dropped it —
  ;; an agent would never have seen WHY its spec landed red
  (let [sess (api/open!)]
    (try
      (call! sess "ns_create" {:ns "rw.core"
                              :source "(ns rw.core)\n(defn seed \"S.\" [x] x)\n"})
      (call! sess "ns_create"
            {:ns "rw.core-test"
             :source (str "(ns rw.core-test (:require [rw.core :as c]\n"
                          "                           [clojure.test :refer [deftest is]]))\n"
                          "(deftest seed-t (is (= 1 (c/seed 1))))\n")})
      (let [r (call! sess "edit_add_form"
                    {:ns "rw.core-test"
                     :source "(deftest dbl-t (is (= 4 (c/dbl 2))))"
                     :prompt "red first over the wire"})]
        (is (re-find #":red-first \[rw\.core/dbl\]" r) r)
        (is (re-find #"stubbed in-image" r) r))
      (finally (api/close! sess)))))
(deftest ^:external read-tools-declare-readonly-on-the-wire
  ;; MCP readOnlyHint: without it, plan-mode clients must treat every tool
  ;; as potentially mutating and prompt — even for query_source
  (let [sess (api/open!)]
    (try
      (let [tools   (get-in (mcp/handle! sess {:id 2 :method "tools/list"})
                            [:result :tools])
            by-name (into {} (map (juxt :name identity)) tools)]
        (is (true? (get-in by-name ["query_source" :annotations :readOnlyHint])))
        (is (true? (get-in by-name ["query_eval" :annotations :readOnlyHint]))
            "the oracle is observe-only by gate — clients may trust it")
        (is (true? (get-in by-name ["session_brief" :annotations :readOnlyHint])))
        (is (nil? (get-in by-name ["edit_replace_form" :annotations]))
            "writes carry NO read-only claim")
        (is (nil? (get-in by-name ["module_dep" :annotations]))))
      (finally (api/close! sess)))))
(deftest ^:external the-wire-speaks-done-not-groups
  (let [sess (api/open!)]
    (try
      (let [names (into #{} (map :name)
                        (get-in (mcp/handle! sess {:id 2 :method "tools/list"})
                                [:result :tools]))]
        (is (contains? names "done"))
        (is (not (contains? names "edit_group"))
            "episodes are inferred — no agent-facing grouping")
        (is (not (contains? names "checkpoint"))))
      (finally (api/close! sess)))))
(deftest ^:external test-run-wire-guards-the-whole-suite
  (let [sess (api/open!)]
    (try
      (call! sess "ns_create" {:ns "tg.core"
                              :source (str "(ns tg.core (:require [clojure.test :refer [deftest is]]))\n"
                                           "(defn f [x] x)\n(deftest f-t (is (= 1 (f 1))))\n")})
      (testing "bare test_run gives GUIDANCE, does not silently run everything"
        (let [r (call! sess "test_run" {})]
          (is (re-find #":guidance" r) r)
          (is (re-find #"done runs the affected" r))
          (is (not (re-find #":pass" r)) "no suite actually ran")))
      (testing "a named spot-check runs"
        (is (re-find #":pass" (call! sess "test_run" {:ns "tg.core"}))))
      (testing "all:true runs the in-image suite AND warns done covers it"
        (let [r (call! sess "test_run" {:all true})]
          (is (re-find #":pass" r) r)
          (is (re-find #"rarely needed" r))))
      (finally (api/close! sess)))))
(deftest ^:external review-scan-is-on-the-wire-and-read-only
  (let [sess (api/open!)]
    (try
      (let [tools   (get-in (mcp/handle! sess {:id 2 :method "tools/list"})
                            [:result :tools])
            by-name (into {} (map (juxt :name identity)) tools)]
        (is (contains? by-name "review_scan"))
        (is (true? (get-in by-name ["review_scan" :annotations :readOnlyHint]))
            "a review tool must not prompt in plan mode"))
      (call! sess "ns_create" {:ns "rw.io" :source "(ns rw.io)\n(defn zap! [x] (spit \"/dev/null\" x))\n"})
      (let [r (call! sess "review_scan" {})]
        (is (re-find #":flagged" r) r)
        (is (re-find #"rw.io/zap!" r) "the effectful undocumented fn is flagged"))
      (finally (api/close! sess)))))
(deftest tool-registry-changes-notify-the-client
  ;; a live reload can rename/add tools (edit_move_forms replaced
  ;; edit_extract_ns mid-session and no client could see it) — the server
  ;; must declare tools.listChanged and emit the notification when the
  ;; registry drifts from what it last advertised.
  (let [sess (atom {})]
    (testing "the capability is declared"
      (is (true? (get-in (mcp/handle! sess {:id 1 :method "initialize"})
                         [:result :capabilities :tools :listChanged]))))
    (testing "no baseline advertised → nothing to invalidate"
      (is (nil? (#'mcp/tools-note! sess))))
    (testing "tools/list records the advertised baseline"
      (mcp/handle! sess {:id 2 :method "tools/list"})
      (is (some? (:slopp.mcp/tools-hash @sess)))
      (is (nil? (#'mcp/tools-note! sess)) "freshly advertised → current"))
    (testing "a drifted registry emits the notification, once"
      (swap! sess assoc :slopp.mcp/tools-hash -1)
      (let [n (#'mcp/tools-note! sess)]
        (is (= "notifications/tools/list_changed" (:method n)))
        (is (nil? (:id n)) "a notification carries no id"))
      (is (nil? (#'mcp/tools-note! sess)) "baseline updated after emitting"))))
(deftest query-store-rides-the-wire-read-only
  (is (some #(= "query_store" (:name %)) tools/tools)
      "the store oracle is a tool")
  (is (contains? @#'tools/read-only-tools "query_store")
      "plan mode may call it without prompts"))
(use-fixtures :once
  (fn [run]
    (reset! @#'mcp/strict-boundary? true)
    (try (run) (finally (reset! @#'mcp/strict-boundary? false)))))
(deftest the-boundary-refuses-file-line-coordinates
  ;; agents NEVER think in files: no agent-facing response may carry a
  ;; source file:line coordinate or a :row/:col key. The strict-boundary
  ;; audit (on across the wire test suite) turns that invariant into a
  ;; throw, so any tool — current or future — that leaks a coordinate
  ;; fails a test the moment it is exercised.
  (testing "the scanner catches coordinates and :row/:col keys, spares clean data"
    (is (#'mcp/boundary-leak {:error "boom at (foo/bar.clj:12:3)"}))
    (is (#'mcp/boundary-leak {:lint [{:type :redundant-do :row 5 :col 2}]}))
    (is (#'mcp/boundary-leak ["ok" {:at "(defn f [] (g))" :nested {:col 1}}]))
    (is (nil? (#'mcp/boundary-leak {:form 'a.b/c :at "(defn c [] (d))"
                                    :error "No matching method"})))
    (is (nil? (#'mcp/boundary-leak {:built "/tmp/build-xyz/src/a.clj"}))
        "a bare build path is not a coordinate"))
  (testing "text! throws under the audit when a response leaks"
    (reset! @#'mcp/strict-boundary? true)
    (try
      (is (thrown-with-msg? Exception #"boundary leak"
                            (#'mcp/text! {:error "at (x/y.clj:9)"})))
      (is (map? (#'mcp/text! {:form 'a.b/c :at "(defn c [])"})) "clean passes")
      (finally (reset! @#'mcp/strict-boundary? false)))))
(deftest ^:external a-compile-failure-crosses-the-wire-anchored
  ;; drives a real compile error THROUGH the wire under the boundary audit:
  ;; the response must anchor (form + snippet) and carry NO coordinate —
  ;; the audit would throw otherwise, so this pins the compile-error path.
  (let [sess (api/open!)]
    (try
      (call! sess "ns_create" {:ns "wce.core" :source "(ns wce.core)\n(defn f [x] x)\n"})
      (let [r (call! sess "edit_replace_form"
                    {:ns "wce.core" :name "f"
                     :source "(defn f [x] (String/noSuchStaticThing x))"})]
        (is (re-find #"wce\.core/f" r) "the owning form is named")
        (is (re-find #"noSuchStaticThing" r) "a match-ready snippet rides")
        (is (not (re-find #"\.clj:\d" r)) "no file:line in the wire text"))
      (finally (api/close! sess)))))

(deftest ^:external module-purity-rides-the-wire
  (let [sess (api/open!)]
    (try
      (call! sess "ns_create"
            {:ns "wcore" :source "(ns wcore)\n(defn add \"A.\" [x y] (+ x y))\n"})
      (testing "module_purity declares a tier on the wire"
        (let [r (call! sess "module_purity" {:module "wcore" :tier "pure"
                                            :prompt "core stays pure"})]
          (is (re-find #":pure" r) r)
          (is (not (re-find #":error" r)) r)))
      (testing "an effectful write into the pure module is refused on the wire"
        (let [r (call! sess "edit_add_form"
                      {:ns "wcore" :source "(defn tick! \"T.\" [a] (swap! a inc))"
                       :prompt "mutation"})]
          (is (re-find #"functional-core" r) r)))
      (finally (api/close! sess)))))

(deftest boundary-leak-tolerates-non-keyword-keyed-maps
  ;; a sorted-map with STRING keys: (contains? v :row)/(get v :row) compares the
  ;; probe keyword against the string keys via the tree comparator and throws
  ;; "String cannot be cast to Keyword" — boundary-leak must not (D9 found it in
  ;; module_purity's :tiers result).
  (is (nil? (#'mcp/boundary-leak {:tiers (into (sorted-map) {"a.b" :pure})})))
  ;; and it still catches a genuine coordinate leak
  (is (re-find #"row/col" (str (#'mcp/boundary-leak {:row 5 :col 2}))))
  (is (re-find #"\.clj" (str (#'mcp/boundary-leak {:at "foo.clj:42"})))))

(deftest ^:external source-arg-friction
  (let [sess (api/open!)]
    (try
      (call! sess "ns_create" {:ns "sa" :source "(ns sa)\n(defn f [x] x)\n"})
      (testing "a misnamed new_source names the real :source param, not a paren/parse error"
        (let [r (call! sess "edit_replace_form"
                      {:ns "sa" :name "f" :new_source "(defn f [x] (inc x))"})]
          (is (re-find #"missing required argument :source" r))
          (is (re-find #"new_source" r))
          (is (not (re-find #"got 0" r)))))
      (testing "a genuinely missing source is a clear message too"
        (let [r (call! sess "edit_replace_form" {:ns "sa" :name "f"})]
          (is (re-find #"missing required argument :source" r))))
      (testing "edit_add_form guards its source arg the same way"
        (let [r (call! sess "edit_add_form" {:ns "sa" :new_source "(defn g [x] x)"})]
          (is (re-find #"missing required argument :source" r))))
      (testing "a correctly-named source still lands"
        (let [r (edn/read-string
                 (call! sess "edit_replace_form"
                       {:ns "sa" :name "f" :source "(defn f \"D.\" [x] (inc x))"}))]
          (is (nil? (:error r)) (pr-str r))))
      (finally (api/close! sess)))))

(deftest ^:external query-vocabulary-rides-the-wire
  (let [sess (api/open!)]
    (try
      (call! sess "ns_create"
            {:ns "voc"
             :source (str "(ns voc)\n"
                          "(defn a [m] {:user/email (:x m)})\n"
                          "(defn b [m] {:user/email (:y m) :order/id 1})\n")})
      (let [r (edn/read-string (call! sess "query_vocabulary" {}))]
        (is (= 2 (:count r)) (pr-str r))
        (is (= {:kw :user/email :uses 2} (first (:attributes r))) (pr-str r)))
      (testing "ns narrows by keyword namespace"
        (let [r (edn/read-string (call! sess "query_vocabulary" {:ns "order"}))]
          (is (= [:order/id] (mapv :kw (:attributes r))) (pr-str r))))
      (finally (api/close! sess)))))

(deftest ^:external query-rules-rides-the-wire
  (let [sess (api/open!)]
    (try
      (let [rs (edn/read-string (call! sess "query_rules" {}))]
        (is (>= (count rs) 9) (pr-str rs))
        (is (contains? (set (map :rule rs)) :schema-drift) (pr-str rs))
        (is (= :refuse (:severity (first (filter #(= :schema-refusal (:rule %)) rs))))
            (pr-str rs)))
      (testing "a per-store severity override is reflected"
        (api/config-file! sess "rules" :key "schema-drift" :value "advisory"
                          :prompt "dial schema-drift down")
        (let [rs (edn/read-string (call! sess "query_rules" {}))
              drift (first (filter #(= :schema-drift (:rule %)) rs))]
          (is (= :advisory (:severity drift)) (pr-str drift))))
      (finally (api/close! sess)))))

(deftest ^:external query-rule-telemetry-rides-the-wire
  (let [sess (api/open!)]
    (try
      (call! sess "ns_create" {:ns "tl" :source "(ns tl)\n(defn seed \"S.\" [x] x)\n"})
      (call! sess "edit_add_form" {:ns "tl" :source "(defn bare [x] x)" :prompt "undocumented public"})
      (call! sess "done" {:label "d"})
      (let [t (edn/read-string (call! sess "query_rule_telemetry" {}))]
        (is (map? (:fire-rate t)) (pr-str t))
        (is (>= (get-in t [:window :dones]) 1) (pr-str t))
        (is (every? #(contains? (:escape-markers t) %) [:unsafe :reads :unused-ok]) (pr-str t))
        (is (contains? t :dials) (pr-str t)))
      (finally (api/close! sess)))))

(deftest ^:external cleanup-is-reachable-over-the-wire
  ;; The done-point tidy has to be callable for ONE namespace, because a legacy
  ;; declare is otherwise unaddressable: it and the defn share a name, so
  ;; edit_delete_form / edit_replace_form cannot resolve it. Exposed as a
  ;; general `cleanup` rather than a declare-specific tool on purpose —
  ;; declares are auto-managed, and the tool surface should not teach an agent
  ;; that it owns them.
  (let [sess (api/open!)]
    (try
      (let [by-name (into {} (map (juxt :name identity))
                          (get-in (mcp/handle! sess {:id 2 :method "tools/list"})
                                  [:result :tools]))]
        (is (contains? by-name "cleanup"))
        (is (not (contains? by-name "fix_declares"))
            "superseded — one general tidy, not a declare-specific tool")
        (is (nil? (get-in by-name ["cleanup" :annotations]))
            "it writes — no read-only claim"))
      (api/ingest! sess 'fd.wire
                   (str "(ns fd.wire)\n\n"
                        "(declare b)\n\n"
                        "(defn a [] (b))\n\n"
                        "(defn b [] 2)\n"))
      (testing "calling it retires a declare the pipeline can satisfy by ordering"
        (is (re-find #"1" (call! sess "cleanup" {:ns "fd.wire"})))
        (is (not (re-find #"declare"
                          (call! sess "query_source" {:ns "fd.wire" :full true})))))
      (finally (api/close! sess)))))

(deftest ^:external undo-is-reachable-over-the-wire
  ;; undo must be on the wire to do its job: it is only reached for reflexively
  ;; if it is one call away the moment a write turns out wrong.
  (let [sess (api/open!)]
    (try
      (let [by-name (into {} (map (juxt :name identity))
                          (get-in (mcp/handle! sess {:id 2 :method "tools/list"})
                                  [:result :tools]))]
        (is (contains? by-name "undo"))
        (is (nil? (get-in by-name ["undo" :annotations]))
            "it writes — no read-only claim"))
      (call! sess "ns_create" {:ns "un.wire"
                              :source "(ns un.wire)\n(defn keep-me [] 1)\n"})
      (call! sess "edit_add_form" {:ns "un.wire" :source "(defn oops [] 2)"
                                  :prompt "a write that turns out wrong"})
      (testing "one call takes the bad write back"
        (is (re-find #"1" (call! sess "undo" {:prompt "that was wrong"})))
        (let [src (call! sess "query_source" {:ns "un.wire" :full true})]
          (is (not (re-find #"oops" src)))
          (is (re-find #"keep-me" src) "unrelated work survives")))
      (finally (api/close! sess)))))

(deftest ^:external module-purity-accepts-the-spelling-the-docs-use
  ;; Every surface writes the tier WITH the colon — the tool description says
  ;; ":pure (may reach NO effect…)", the skill says `tier :pure`, and
  ;; query_depends reports :pure back. So an agent naturally sends ":pure",
  ;; which (keyword ":pure") turned into ::pure and got refused. Both
  ;; spellings must land on the same tier.
  (let [sess (api/open!)]
    (try
      (doseq [spelling ["pure" ":pure"]]
        (let [r (call! sess "module_purity" {:module "mp.core" :tier spelling
                                            :prompt "a pure core"})]
          (is (not (re-find #"tier must be" r)) (str spelling " → " r))
          (is (re-find #":pure" r) (str spelling " → " r))))
      (finally (api/close! sess)))))

(deftest ^:external review-scan-reports-a-size-distribution-not-just-a-count
  ;; ":large 3" is honest but misleading as a progress signal: DECOMPOSING a
  ;; god-form ADDS forms, so the count can rise while the codebase improves —
  ;; it did, 50 -> 54, during this sweep. Large forms are a distribution to
  ;; flatten, not a quantity to minimize, so report the shape.
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'rs.core
                   (str "(ns rs.core)\n\n"
                        "(defn small [x] (inc x))\n\n"
                        "(defn big [x]\n"
                        (apply str (repeat 60 "  (println x)\n"))
                        "  x)\n"))
      (let [r (review/review-scan sess)]
        (is (= 'rs.core/big (get-in r [:loc :largest])) (pr-str (:loc r)))
        (is (>= (get-in r [:loc :max]) 60) (pr-str (:loc r)))
        (testing "the median is the honest counterweight to the max"
          (is (< (get-in r [:loc :median]) (get-in r [:loc :max]))
              (pr-str (:loc r)))))
      (finally (api/close! sess)))))

(deftest ^:external untested-does-not-flag-plain-defs
  ;; :untested means "no runtime evidence reaches this form". A plain (def x
  ;; <data>) has no invocation to trace, so it can NEVER acquire evidence —
  ;; flagging it is a finding no one can ever discharge, which is worse than
  ;; not flagging it: it pads the number the sweep is trying to drive to zero.
  ;; defn/defmulti stay flaggable; they are callable.
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'ut.core
                   (str "(ns ut.core)\n\n"
                        "(def threshold 42)\n\n"
                        "(defn untouched [x] (+ x threshold))\n"))
      (let [flags (into {} (map (juxt :form :flags))
                        (:top (review/review-scan sess :ns 'ut.core :limit 50)))]
        (is (not (contains? (set (get flags 'ut.core/threshold)) :untested))
            (str "a plain def cannot be traced: " (pr-str flags)))
        (is (contains? (set (get flags 'ut.core/untouched)) :untested)
            (str "a callable fn with no evidence still flags: " (pr-str flags))))
      (finally (api/close! sess)))))

(deftest ^:external a-zero-test-verification-is-unverified-not-green
  ;; The single most expensive dishonesty in the response shape. A write whose
  ;; verification ran NOTHING reported :status :green with :coverage :none
  ;; beside it — two fields saying opposite things, and :green is the one an
  ;; agent acts on. A rename_sweep across 11 forms reported green having run
  ;; zero tests, which is how it shipped code that read nil at runtime.
  ;;
  ;; Green must mean "tests ran and passed". Nothing ran is UNVERIFIED — the
  ;; agent's cue to write a test or name one, rather than a habit of running
  ;; test_run manually after every write because the status cannot be trusted.
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'uv.core "(ns uv.core)\n")
      (let [r (call! sess "edit_add_form"
                     {:ns "uv.core" :source "(defn ^:unused-ok f [x] (inc x))"
                      :prompt "no covering test exists"})]
        (is (re-find #":status :unverified" r) r)
        (is (not (re-find #":status :green" r)) r)
        (is (re-find #":coverage :none" r) r))
      (testing "a run that DID execute tests still reports green"
        (api/ingest! sess 'uv.core-test
                     (str "(ns uv.core-test\n"
                          "  (:require [clojure.test :refer [deftest is]]\n"
                          "            [uv.core :as c]))\n\n"
                          "(deftest t (is (= 2 (c/f 1))))\n"))
        (let [r (call! sess "edit_replace_form"
                       {:ns "uv.core" :name "f"
                        :source "(defn ^:unused-ok f [x] (inc x))"
                        :prompt "touch it so its test runs"})]
          (is (not (re-find #":unverified" r)) r)))
      (finally (api/close! sess)))))

(deftest ^:external unverified-says-why-it-verified-nothing
  ;; :unverified alone repeats the original sin at one remove. "No test covers
  ;; this yet" and "the fallback looked in the wrong place" are different
  ;; facts: the first is the agent's to fix by writing a test, the second is a
  ;; slopp bug. They were indistinguishable, which is exactly how the empty
  ;; fallback hid — it looked like an ordinary untested form for months.
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'uw.core "(ns uw.core)\n")
      (let [r (call! sess "edit_add_form"
                     {:ns "uw.core" :source "(defn ^:unused-ok f [x] (inc x))"
                      :prompt "genuinely nothing covers this"})]
        (is (re-find #":status :unverified" r) r)
        (is (re-find #":reason :no-covering-tests" r)
            (str "an :unverified must name its cause: " r)))
      (finally (api/close! sess)))))

(deftest ^:external edit-group-stays-off-the-wire-on-purpose
  ;; Its absence is a MEASURED design decision, not an oversight, and it looks
  ;; exactly like an oversight from the outside — I argued for registering it
  ;; within one session of arriving at this codebase, on the grounds that the
  ;; API had a capability the wire did not.
  ;;
  ;; Exposed, agents batch a whole feature into one call instead of working
  ;; incrementally, which is too much to hold and skips the property the whole
  ;; system rests on: every step is a VALID PROGRAM, verified, with the
  ;; completeness judgement made at done.
  ;;
  ;; It stays as an internal primitive for transformations a TOOL derives from
  ;; ONE intent (change-signature!, rename-sweep!, revert-episode!, undo!,
  ;; sync/apply-ns!) — those intermediates are invalid by construction and
  ;; nobody was asked to reason about them.
  (let [sess (api/open!)]
    (try
      (let [by-name (into {} (map (juxt :name identity))
                          (get-in (mcp/handle! sess {:id 2 :method "tools/list"})
                                  [:result :tools]))]
        (is (not (contains? by-name "edit_group"))
            "off the wire on purpose — see slopp.api/edit-group!'s docstring")
        (testing "while the TOOL-derived multi-form ops that use it stay exposed"
          (is (contains? by-name "rename_sweep"))
          (is (contains? by-name "change_signature"))
          (is (contains? by-name "undo"))))
      (finally (api/close! sess)))))

(deftest ^:external dry-run-is-honored-over-the-wire
  ;; A preview that silently performs the operation is far worse than no
  ;; preview. api/rename-sweep! gained :dry-run, but the MCP tool schema and
  ;; dispatch did not — and the layer IGNORES unknown arguments, so asking for
  ;; a preview ran a real store-wide sweep. Caught only because I read the
  ;; result and saw :deltas 5 where :in-code should have been.
  ;;
  ;; Silently dropping an unrecognised SAFETY flag turns it into a no-op with
  ;; the opposite meaning of what was asked.
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'dw.core "(ns dw.core)\n(defn f [] {:dw/target 1})\n")
      (let [before (count (store/deltas (:store @sess)))
            r      (call! sess "rename_sweep" {:from ":dw/target"
                                               :to ":dw/renamed"
                                               :dry-run true})]
        (is (re-find #":dry-run true" r) r)
        (is (= before (count (store/deltas (:store @sess))))
            "a preview over the wire must append NO delta")
        (is (re-find #":dw/target" (query/query-source sess 'dw.core))
            "and must not rewrite anything"))
      (finally (api/close! sess)))))

(deftest ^:external a-write-cannot-green-what-the-tier-never-ran
  ;; Writing an ^:external deftest returned :status :green — a green earned by
  ;; OTHER tests in the namespace, for a form the in-image tier structurally
  ;; cannot run. traced-run! computes :external-pending correctly; summarize's
  ;; terse path rebuilds :test from a fixed key list and dropped it, the same
  ;; way :dry-run's payload and :drift were dropped before it.
  (let [dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-isogreen"
                   (make-array java.nio.file.attribute.FileAttribute 0)))
        sess (api/open! {:slopp.api/dir dir})]
    (try
      ;; MIXED tiers: a fast test runs, an external one defers
      (call! sess "ns_create" {:ns "iso.core" :source "(ns iso.core)\n(defn f [] 1)\n"})
      (call! sess "ns_create"
             {:ns "iso.core-test"
              :source (str "(ns iso.core-test\n"
                           "  (:require [clojure.test :refer [deftest is]]\n"
                           "            [iso.core :as c]))\n"
                           "(deftest quick-t (is (= 1 (c/f))))\n")})
      (let [r (edn/read-string
               (call! sess "edit_add_form"
                      {:ns "iso.core-test"
                       :prompt "an external spec"
                       :source "(deftest ^:external slow-t (is (= 1 (c/f))))"}))
            t (:test r)]
        (testing "the deferral survives to the wire"
          (is (some #{'slow-t} (:tests (:external-pending t))) (pr-str r)))
        (testing "and the write does not claim green for what that tier never ran"
          (is (= :partial (:status t)) (pr-str r))))
      ;; ISOLATED ONLY: nothing can run in-image, which is design, not a bug
      (call! sess "ns_create" {:ns "only.core" :source "(ns only.core)\n(defn g [] 1)\n"})
      (call! sess "ns_create"
             {:ns "only.core-test"
              :source (str "(ns only.core-test\n"
                           "  (:require [clojure.test :refer [deftest is]]\n"
                           "            [only.core :as c]))\n"
                           "(deftest ^:external slow-only-t (is (= 1 (c/g))))\n")})
      (testing "all-external scope is named as design, not blamed on a scope bug"
        (let [raw (call! sess "edit_replace_form"
                         {:ns "only.core"
                          :name "g"
                          :prompt "touch a form only external tests cover"
                          :source "(defn g [] (inc 0))"})
              t   (try (:test (edn/read-string raw)) (catch Exception _ nil))]
          (is (map? t) raw)
          (is (= :unverified (:status t)) raw)
          (is (= :all-impacted-external (:reason t)) raw)))
      (finally (api/close! sess)))))

(deftest ^:external a-previews-payload-survives-the-wire
  ;; The fourth silent loss on this path. :dry-run's payload, :drift and
  ;; :external-pending were each computed correctly and dropped by an
  ;; allow-list in the dispatch; the fourth was edit_requalify's :in-code,
  ;; dropped while building the tool meant to be careful about the third.
  ;;
  ;; An api-level test cannot catch this — the api was always right. The
  ;; invariant belongs where the agent reads it: over the wire.
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'wp.core
                   (str "(ns wp.core)\n"
                        "(defn opts \"O.\" [{:keys [dir]}] dir)\n"
                        "(defn ^:unused-ok a \"A.\" [] (opts {:dir \"x\"}))\n"
                        "(defn ^:unused-ok b \"B.\" [m] (opts m))\n"))
      (let [r (edn/read-string
               (call! sess "edit_requalify" {:ns "wp.core" :name "opts"
                                            :dry_run true :verbose true}))]
        (testing "the preview reports what it WOULD rewrite, by name"
          (is (some #{'wp.core/opts} (:in-code r)) (pr-str r))
          (is (some #{'wp.core/a} (:in-code r)) (pr-str r)))
        (testing "and what it could NOT reach"
          (is (= '[wp.core/b] (:unknown-shape r)) (pr-str r)))
        (testing "a preview writes nothing — the property, not the report"
          (is (re-find #"\{:keys \[dir\]\}"
                       (get-in (query/query-slice sess 'wp.core 'opts)
                               [:target :source]))
              "the arglist must be untouched after a dry run")))
      (finally (api/close! sess)))))
