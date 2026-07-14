(ns slopp.mcp-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [cheshire.core :as json]
            [slopp.api :as api]
            [slopp.mcp :as mcp] [clojure.java.io :as io] [slopp.store :as store] [slopp.db :as db] [clojure.java.shell :as sh] [slopp.sync :as sync]))

(deftest ^:isolated protocol-handshake
  (let [sess (atom {})]
    (testing "initialize returns serverInfo named slopp"
      (let [r (mcp/handle sess {:jsonrpc "2.0" :id 1 :method "initialize" :params {}})]
        (is (= "slopp" (get-in r [:result :serverInfo :name])))
        (is (contains? (:result r) :protocolVersion))))
    (testing "tools/list returns schemas with MCP-legal names"
      (let [tools (get-in (mcp/handle sess {:id 2 :method "tools/list"}) [:result :tools])]
        (is (seq tools))
        (is (every? #(re-matches #"[a-zA-Z0-9_-]+" (:name %)) tools))
        (is (contains? (set (map :name tools)) "query_source"))
        (is (contains? (set (map :name tools)) "edit_replace_form"))))
    (testing "notifications (no id) produce no response"
      (is (nil? (mcp/handle sess {:method "notifications/initialized"}))))
    (testing "unknown method -> JSON-RPC error"
      (is (= -32601 (get-in (mcp/handle sess {:id 9 :method "bogus"}) [:error :code]))))))

(defn- call [sess tool args]
  (get-in (mcp/handle sess {:id 1 :method "tools/call"
                            :params {:name tool :arguments args}})
          [:result :content 0 :text]))

(deftest ^:isolated tools-call-end-to-end
  (let [sess (api/open!)]
    (try
      (call sess "ns_create" {:ns "demo" :source "(ns demo)\n(defn add [x y] (+ x y))\n"})
      (testing "query_source (VFS read)"
        (is (re-find #"defn add" (call sess "query_source" {:ns "demo" :full true}))))
      (testing "query_eval hits the oracle"
        (is (re-find #"\b5\b" (call sess "query_eval" {:code "(demo/add 2 3)"}))))
      (testing "edit_replace_form over the wire (JSON round-trip) hot-reloads"
        (let [wire-req (json/generate-string
                        {:jsonrpc "2.0" :id 4 :method "tools/call"
                         :params {:name "edit_replace_form"
                                  :arguments {:ns "demo" :name "add"
                                              :source "(defn add [x y] (* x y))"
                                              :prompt "mul"}}})
              resp (mcp/handle sess (json/parse-string wire-req true))
              wire-resp (json/parse-string (json/generate-string resp) true)]
          (is (nil? (:error wire-resp)))
          (is (re-find #"\b6\b" (call sess "query_eval" {:code "(demo/add 2 3)"})))))
      (finally (api/close! sess)))))

(deftest ^:isolated help-and-hints                                ; item 3: weak-model guidance
  (let [sess (api/open!)]
    (try
      (testing "the help tool exists (agents invented the name twice)"
        (let [h (call sess "help" {})]
          (is (re-find #"edit_group" h))
          (is (re-find #"query_project" h))))
      (call sess "ns_create" {:ns "hint" :source "(ns hint (:require [clojure.test :refer [deftest is]]))\n(defn f [x] x)\n(deftest f-t (is (= 1 (f 1))))\n"})
      (testing "redundant test_runs earn a hint; a write resets the counter"
        (call sess "test_run" {:ns "hint"})
        (call sess "test_run" {:ns "hint"})
        (let [r3 (call sess "test_run" {:ns "hint"})]
          (is (re-find #"rarely needed" r3)))
        (call sess "edit_replace_form" {:ns "hint" :name "f" :source "(defn f [x] (identity x))"})
        (is (not (re-find #"rarely needed" (call sess "test_run" {:ns "hint"})))))
      (testing "a hint fires ONCE per session — a fresh streak stays quiet"
        (call sess "test_run" {:ns "hint"})
        (call sess "test_run" {:ns "hint"})
        (let [r6 (call sess "test_run" {:ns "hint"})]
          (is (not (re-find #"rarely needed" r6)))))
      (testing "a streak of single-form writes suggests edit_group exactly ONCE"
        (let [rs (mapv (fn [i] (call sess "edit_add_form"
                                     {:ns "hint" :source (str "(defn g" i " [x] x)")}))
                       (range 4))]
          (is (= 1 (count (filter #(re-find #"edit_group" %) rs))))))
      (finally (api/close! sess)))))

(deftest ^:isolated rename-arg-forgiveness                        ; from the symmetric eval
  (let [sess (api/open!)]
    (try
      (call sess "ns_create" {:ns "ra" :source "(ns ra)\n(defn f [x] x)\n(defn g [x] (f x))\n"})
      (testing "the aliases every eval run guessed first now just work"
        (let [r (edn/read-string (call sess "edit_rename"
                                       {:ns "ra" :name "f" :to "h"}))]
          (is (nil? (:error r)))))
      (testing "missing args produce a clear message, not a raw conversion error"
        (is (re-find #"needs :old and :new"
                     (call sess "edit_rename" {:ns "ra"})))
        (is (re-find #"missing required argument :ns"
                     (call sess "query_source" {}))))
      (finally (api/close! sess)))))

(deftest ^:isolated write-op-arg-forgiveness                      ; eval round 2
  (let [sess (api/open!)]
    (try
      (call sess "ns_create" {:ns "wa" :source "(ns wa)\n(defn f [x] (+ x x 1))\n(defn g [x] (f x))\n"})
      (testing "edit_group accepts :op for :action; bad actions get a real message"
        (let [r (edn/read-string (call sess "edit_group"
                                       {:steps [{:op "replace" :ns "wa" :name "g"
                                                 :source "(defn g [x] (f (f x)))"}]}))]
          (is (nil? (:error r))))
        (is (re-find #"replace\|add\|delete"
                     (call sess "edit_group" {:steps [{:ns "wa" :name "g"}]}))))
      (testing "edit_extract accepts :source for :form; missing gets a real message"
        (let [r (edn/read-string (call sess "edit_extract"
                                       {:ns "wa" :from "f" :name "doubled"
                                        :source "(+ x x 1)"}))]
          (is (nil? (:error r))))
        (is (re-find #"needs :form" (call sess "edit_extract"
                                          {:ns "wa" :from "f" :name "z"}))))
      (finally (api/close! sess)))))

(deftest ^:isolated green-responses-are-terse                     ; B1
  (let [sess (api/open!)]
    (try
      (call sess "ns_create" {:ns "b1" :source "(ns b1 (:require [clojure.test :refer [deftest is]]))\n(defn f [x] x)\n(deftest f-t (is (= 1 (f 1))))\n"})
      (call sess "test_run" {:ns "b1"})
      (testing "a quiet green edit returns the terse shape"
        (let [r (edn/read-string (call sess "edit_replace_form"
                                       {:ns "b1" :name "f"
                                        :source "(defn f [x] (identity x))"}))]
          (is (true? (:ok r)))
          (is (nil? (:failures r)))
          (is (< (count (pr-str r)) 120) (pr-str r))))
      (testing ":verbose true forces the full shape"
        (let [r (edn/read-string (call sess "edit_replace_form"
                                       {:ns "b1" :name "f"
                                        :source "(defn f [x] x)" :verbose true}))]
          (is (map? (:delta r)))
          (is (map? (:test r)))))
      (testing "a red edit returns full detail incl. :failures"
        (let [r (edn/read-string (call sess "edit_replace_form"
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
(deftest ^:isolated one-shot-call
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
(deftest ^:isolated pending-intent-opens-the-turn
  ;; the plugin's UserPromptSubmit hook drops {session-id, prompt} JSON in
  ;; .slopp/pending-intent; the turn gate opens the turn from it and the
  ;; session ADOPTS the harness session id as its identity — no agent
  ;; field anywhere on the wire
  (let [dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-turnhook"
                   (make-array java.nio.file.attribute.FileAttribute 0)))
        sess (api/open! {:dir dir})]
    (try
      (swap! sess assoc :require-turns? true)
      (spit (io/file dir ".slopp" "pending-intent")
            "{\"session-id\":\"sess-abc123\",\"prompt\":\"add a widget feature\"}")
      (let [r (edn/read-string
               (call sess "ns_create" {:ns "pi.core"
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
        (is (seq (api/query-search-history sess "add a widget feature"))))
      (testing "with no pending intent and no turn, the gate still refuses"
        (call sess "turn_end" {})
        (let [r (call sess "edit_add_form" {:ns "pi.core"
                                            :source "(defn g [] 2)"})]
          (is (re-find #"no open turn" r))))
      (finally (api/close! sess)))))
(deftest ^:isolated two-sessions-never-merge-episodes
  ;; the P4 invariant, now free of wire labels: two sessions on ONE store
  ;; get DISTINCT generated identities, so episode_revert scopes to the
  ;; session that calls it (under a constant label these episodes MERGED)
  (let [dir (str (java.nio.file.Files/createTempDirectory
                  "slopp-iso"
                  (make-array java.nio.file.attribute.FileAttribute 0)))
        sa  (api/open! {:dir dir})
        sb  (api/open! {:dir dir})]
    (try
      (is (not= (:agent-id @sa) (:agent-id @sb)))
      (call sa "ns_create" {:ns "iso.a" :source "(ns iso.a)\n(defn fa [] 1)\n"})
      (call sb "ns_create" {:ns "iso.b" :source "(ns iso.b)\n(defn fb [] 2)\n"})
      (let [r (edn/read-string (call sb "episode_revert" {}))]
        (is (nil? (:error r)) (pr-str r)))
      (testing "B's work is gone, A's survives"
        (is (not (re-find #"defn fb" (call sb "query_source" {:targets [{:ns "iso.b" :name "fb"}]}))))
        (is (re-find #"defn fa" (call sa "query_source" {:targets [{:ns "iso.a" :name "fa"}]}))))
      (finally (api/close! sa) (api/close! sb)))))
(deftest ^:isolated terse-results-carry-forms
  (let [sess (api/open!)]
    (try
      (call sess "ns_create" {:ns "tf.core" :source "(ns tf.core)\n(defn f [x] x)\n"})
      (testing "replace names its form"
        (is (re-find #":forms \[\"tf.core/f\"\]"
                     (call sess "edit_replace_form" {:ns "tf.core" :name "f"
                                                     :source "(defn f [x] (identity x))"}))))
      (testing "groups list their named steps"
        (is (re-find #"tf.core/f"
                     (call sess "edit_group"
                           {:steps [{:action "replace" :ns "tf.core" :name "f"
                                     :source "(defn f [x] x)"}]}))))
      (finally (api/close! sess)))))
(deftest ^:isolated trimmed-responses-spool-the-full-version
  (let [sess (api/open!)]
    (try
      (call sess "ns_create" {:ns "sp.core" :source "(ns sp.core)\n(defn f [] 1)\n"})
      (testing "a response over the size gate is trimmed and retrievable"
        (let [r  (call sess "query_eval" {:code "(apply str (repeat 9000 \"x\"))"})
              id (second (re-find #"query_detail \{:id \"(r\d+)\"\}" r))]
          (is (some? id) r)
          (let [full (call sess "query_detail" {:id id})]
            (is (>= (count full) 8000))
            (is (not (re-find #"query_detail \{:id" full))))))
      (testing "giant failure strings never reach the agent whole
                (upstream capture truncates actuals; the text! heuristic
                covers the other fields)"
        (let [r (call sess "ns_create"
                      {:ns "sp.red"
                       :source "(ns sp.red (:require [clojure.test :refer [deftest is]]))\n(deftest big-t (is (= \"a\" (apply str (repeat 3000 \"z\")))))\n"})]
          (is (re-find #"fail 1" r))
          (is (not (re-find #"z{1000}" r)))))
      (testing "an unknown id is an honest error"
        (is (re-find #"no spooled response" (call sess "query_detail" {:id "r999"}))))
      (finally (api/close! sess)))))
(deftest ^:isolated untested-writes-stay-terse-and-honest
  (let [sess (api/open!)]
    (try
      (call sess "ns_create" {:ns "ut.core" :source "(ns ut.core)\n(defn f [x] x)\n(defn g [x] x)\n"})
      (call sess "ns_create" {:ns "ut.core-test" :source "(ns ut.core-test (:require [clojure.test :refer [deftest is]] [ut.core :as c]))\n(deftest f-t (is (= 1 (c/f 1))))\n"})
      (call sess "test_run" {:ns "ut.core-test"})
      (testing "an untested green write is terse — flagged, no source echo"
        (let [r (call sess "edit_replace_form" {:ns "ut.core" :name "g"
                                                :source "(defn g [x] (identity x))"})]
          (is (re-find #":ok true" r) r)
          (is (re-find #":untested true" r) r)
          (is (not (re-find #"identity" r)) r)
          (testing "…and a zero-test verification names its emptiness (Q8)"
            (is (re-find #":coverage :none" r) r))))
      (testing "a new deftest is not 'untested' — it IS a test"
        (call sess "edit_add_form" {:ns "ut.core-test"
                                    :source "(deftest g-t (is (= 2 (c/g 2))))"})
        (let [r (call sess "edit_replace_form" {:ns "ut.core-test" :name "g-t"
                                                :source "(deftest g-t (is (= 3 (c/g 3))))"})]
          (is (not (re-find #":untested" r)) r)))
      (finally (api/close! sess)))))
(deftest ^:isolated rename-names-leftover-prose-mentions
  (let [sess (api/open!)]
    (try
      (call sess "ns_create"
            {:ns "pm.core"
             :source (str "(ns pm.core)\n"
                          "(defn bulk-rate [n] (if (>= n 10) 0.1 0.0))\n"
                          "(defn describe\n  \"Applies the bulk-rate tier.\"\n  [n]\n"
                          "  (str \"bulk-rate applies: \" (bulk-rate n)))\n")})
      (testing "the rename result points at docstring/string mentions of the old name (Q11)"
        (let [r (call sess "edit_rename" {:ns "pm.core" :old "bulk-rate" :new "volume-rate"})]
          (is (re-find #":mentions" r) r)
          (is (re-find #":ns pm.core, :form describe" r) r)))
      (testing "a clean rename carries no :mentions"
        (call sess "edit_replace_form"
              {:ns "pm.core" :name "describe"
               :source "(defn describe [n] (str \"tier: \" (volume-rate n)))"})
        (let [r (call sess "edit_rename" {:ns "pm.core" :old "volume-rate" :new "tier-rate"})]
          (is (not (re-find #":mentions" r)) r)))
      (finally (api/close! sess)))))
(deftest ^:isolated milestones-publish-themselves
  (let [dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-pub" (make-array java.nio.file.attribute.FileAttribute 0)))
        bare (str (java.nio.file.Files/createTempDirectory
                   "slopp-pub-remote" (make-array java.nio.file.attribute.FileAttribute 0)))
        _    (sh/sh "git" "init" "--bare" bare)
        sess (api/open! {:dir dir})]
    (try
      (db/set-meta! (:db @sess) "git-remote" bare)
      (call sess "ns_create" {:ns "pub.core" :source "(ns pub.core)\n(defn f [x] x)\n"})
      (testing "commit_point pushes the projection to the configured remote (Q10)"
        (let [r (call sess "commit_point" {:description "first"})]
          (is (re-find #":published" r) r)
          (is (re-find #":pushed" r) r)))
      (finally (api/close! sess)))))
(deftest ^:isolated groups-take-subform-and-require-steps
  (let [sess (api/open!)]
    (try
      (call sess "ns_create"
            {:ns "gs.core"
             :source "(ns gs.core)\n(defn calc\n  \"Applies the bulk tier.\"\n  [n]\n  (if (>= n 10) (* n 2) n))\n"})
      (testing "one group mixes subform + text subform + require + add (Rock 3 core)"
        (let [r (call sess "edit_group"
                      {:steps [{:action "subform" :ns "gs.core" :name "calc"
                                :match "(>= n 10)" :source "(>= n 8)"}
                               {:action "subform" :ns "gs.core" :name "calc"
                                :match "Applies the bulk tier."
                                :source "Applies the volume tier." :text true}
                               {:action "require" :ns "gs.core"
                                :require "[clojure.string :as str]"}
                               {:action "add" :ns "gs.core"
                                :source "(defn label [n] (str/upper-case (str \"tier \" n)))"}]
                       :prompt "volume tier + labels"})]
          (is (re-find #":ok true" r) r)
          (is (re-find #":deltas 4" r) r)))
      (testing "the result verifies against the final state"
        (let [r (call sess "query_eval" {:code "[(gs.core/calc 8) (gs.core/label 1)]"})]
          (is (re-find #"\[16 \"TIER 1\"\]" r) r)))
      (finally (api/close! sess)))))
(deftest ^:isolated commits-prove-git-alignment
  (let [dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-align" (make-array java.nio.file.attribute.FileAttribute 0)))
        bare (str (java.nio.file.Files/createTempDirectory
                   "slopp-align-remote" (make-array java.nio.file.attribute.FileAttribute 0)))
        _    (sh/sh "git" "init" "--bare" bare)
        sess (api/open! {:dir dir})]
    (try
      (db/set-meta! (:db @sess) "git-remote" bare)
      (call sess "ns_create" {:ns "al.core" :source "(ns al.core)\n(defn f [x] x)\n"})
      (call sess "commit_point" {:description "first"})
      (testing "query_commits carries the alignment PROOF (Q12)"
        (let [r (call sess "query_commits" {})]
          (is (re-find #":aligned true" r) r)
          (is (re-find #":branch-head" r) r)
          (is (re-find #"no worktree" r) r)))
      (finally (api/close! sess)))))
(deftest ^:isolated whole-ns-source-is-outline-by-default
  (let [sess (api/open!)]
    (try
      (call sess "ns_create" {:ns "gt.core" :source "(ns gt.core)\n(defn f [x] (* x 2))\n(defn g [x] (+ x 1))\n"})
      (testing "a bare {ns} read returns the outline + the way in, NOT the dump"
        (let [r (call sess "query_source" {:ns "gt.core"})]
          (is (not (re-find #"\(\* x 2\)" r)) r)
          (is (re-find #"f" r) r)
          (is (re-find #"full" r) r)))
      (testing "named targets stay a cheap direct read"
        (is (re-find #"\(\* x 2\)"
                     (call sess "query_source" {:targets [{:ns "gt.core" :name "f"}]}))))
      (testing "full: true is the explicit whole-namespace dump"
        (is (re-find #"\(\* x 2\)" (call sess "query_source" {:ns "gt.core" :full true}))))
      (finally (api/close! sess)))))
(deftest ^:isolated rename-sweep-is-one-intent
  (let [sess (api/open!)]
    (try
      (call sess "ns_create"
            {:ns "sw.zone"
             :source (str "(ns sw.zone (:require [clojure.test :refer [deftest is]]))\n"
                          "(def zone-fees {1 500, 2 900})\n"
                          "(defn zone-fee \"The zone fee table lookup.\" [z] (get zone-fees z 0))\n"
                          "(deftest zone-t (is (= 500 (zone-fee 1))))\n")})
      (call sess "ns_create"
            {:ns "sw.core"
             :source (str "(ns sw.core (:require [clojure.test :refer [deftest is]] [sw.zone :as zone]))\n"
                          "(defn total \"Base plus the zone fee.\" [z] (+ 100 (zone/zone-fee z)))\n"
                          "(deftest total-t (is (= 600 (total 1))))\n")})
      (testing "one call sweeps namespaces, vars, keys, and prose (Q14)"
        (let [r (call sess "rename_sweep" {:from "zone" :to "region"})]
          (is (re-find #":renamed-namespaces" r) r)
          (is (not (re-find #":error" r)) r)))
      (testing "the sweep is total"
        (is (= "[]" (call sess "query_search" {:pattern "zone"}))))
      (testing "behavior survives under the new names"
        (is (re-find #"600" (call sess "query_eval" {:code "(sw.core/total 1)"})))
        (is (re-find #"500" (call sess "query_eval" {:code "(sw.region/region-fee 1)"}))))
      (finally (api/close! sess)))))
(deftest ^:isolated repeated-reads-are-free
  (let [sess (api/open!)]
    (try
      (call sess "ns_create"
            {:ns "tk.core"
             :source (apply str "(ns tk.core)\n"
                            (for [i (range 1 6)]
                              (str "(defn f" i " [x] (+ x " i "))\n")))})
      (testing "an identical re-read returns an :unchanged stub, not the payload (told-tracking)"
        (let [a (call sess "query_source" {:ns "tk.core"})
              b (call sess "query_source" {:ns "tk.core"})]
          (is (re-find #":outline" a) a)
          (is (re-find #":unchanged true" b) b)
          (is (< (count b) (count a)))))
      (testing "a body edit leaves the OUTLINE honestly unchanged"
        (call sess "edit_replace_form" {:ns "tk.core" :name "f1"
                                        :source "(defn f1 [x] (* x 9))"})
        (is (re-find #":unchanged true" (call sess "query_source" {:ns "tk.core"}))))
      (testing "a change the view can SEE invalidates it"
        (call sess "edit_add_form" {:ns "tk.core" :source "(defn g [x] x)"})
        (is (re-find #":outline" (call sess "query_source" {:ns "tk.core"}))))
      (finally (api/close! sess)))))
(deftest ^:isolated usage-smells-hint-once
  (let [sess (api/open!)]
    (try
      (call sess "ns_create" {:ns "sm.a" :source "(ns sm.a)\n(defn f [x] x)\n(defn g [x] x)\n"})
      (call sess "ns_create" {:ns "sm.b" :source "(ns sm.b)\n(defn h [x] x)\n"})
      (testing "a second whole-namespace dump earns the slice hint, ONCE"
        (call sess "query_source" {:ns "sm.a" :full true})
        (let [r2 (call sess "query_source" {:ns "sm.b" :full true})]
          (is (re-find #"query_slice" r2) r2))
        (call sess "ns_create" {:ns "sm.c" :source "(ns sm.c)\n(defn i [x] x)\n"})
        (let [r3 (call sess "query_source" {:ns "sm.c" :full true})]
          (is (not (re-find #"query_slice" r3)) r3)))
      (testing "a rename streak earns the sweep hint"
        (call sess "edit_rename" {:ns "sm.a" :old "f" :new "f2"})
        (let [r (call sess "edit_rename" {:ns "sm.a" :old "g" :new "g2"})]
          (is (re-find #"rename_sweep" r) r)))
      (finally (api/close! sess)))))
(deftest ^:isolated one-off-pushes-keep-the-default-remote
  (let [dir   (str (java.nio.file.Files/createTempDirectory
                    "slopp-oop" (make-array java.nio.file.attribute.FileAttribute 0)))
        barea (str (java.nio.file.Files/createTempDirectory
                    "slopp-oop-a" (make-array java.nio.file.attribute.FileAttribute 0)))
        bareb (str (java.nio.file.Files/createTempDirectory
                    "slopp-oop-b" (make-array java.nio.file.attribute.FileAttribute 0)))
        _     (sh/sh "git" "init" "--bare" barea)
        _     (sh/sh "git" "init" "--bare" bareb)
        sess  (api/open! {:dir dir})]
    (try
      (api/ingest! sess 'pr.core "(ns pr.core)\n(defn f [x] x)\n")
      (api/commit-point! sess "seed" :agent "t")
      (testing "the FIRST url is saved as the default"
        (is (nil? (:error (sync/push! dir :url barea))))
        (is (= barea (db/get-meta (:db @sess) "git-remote"))))
      (testing "a one-off push elsewhere succeeds but the default STAYS (user regression)"
        (let [r (sync/push! dir :url bareb)]
          (is (nil? (:error r)) (pr-str r))
          (is (= barea (db/get-meta (:db @sess) "git-remote")))
          (is (= barea (str (:default-remote r))) (pr-str r))))
      (finally (api/close! sess)))))
