(ns slopp.mcp-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [cheshire.core :as json]
            [slopp.api :as api]
            [slopp.mcp :as mcp] [clojure.java.io :as io]))

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
        (is (re-find #"defn add" (call sess "query_source" {:ns "demo"}))))
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
                     (call sess "query_outline" {}))))
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
  ;; the plugin's UserPromptSubmit hook drops the user's verbatim prompt in
  ;; .slopp/pending-intent; the turn gate opens the turn from it instead of
  ;; refusing the first write
  (let [dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-turnhook"
                   (make-array java.nio.file.attribute.FileAttribute 0)))
        sess (api/open! {:dir dir})]
    (try
      (swap! sess assoc :require-turns? true)
      (spit (io/file dir ".slopp" "pending-intent") "add a widget feature")
      (let [r (edn/read-string
               (call sess "ns_create" {:ns "pi.core"
                                       :source "(ns pi.core)\n(defn f [] 1)\n"
                                       :agent "claude"}))]
        (is (nil? (:error r)) (pr-str r)))
      (is (false? (.exists (io/file dir ".slopp" "pending-intent"))))
      (testing "the turn carries the verbatim prompt"
        (is (seq (api/query-search-history sess "add a widget feature"))))
      (testing "with no pending intent and no turn, the gate still refuses"
        (call sess "turn_end" {:agent "claude"})
        (let [r (call sess "edit_add_form" {:ns "pi.core"
                                            :source "(defn g [] 2)"
                                            :agent "claude"})]
          (is (re-find #"no open turn" r))))
      (finally (api/close! sess)))))
