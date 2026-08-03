(ns slopp.phase4-test
  "Per-agent attribution on every write, and fork/edit/merge end to end.

  Named for Phase 4 m1, which was \"many agents, ONE store/image\" over an
  HTTP MCP transport. That transport is retired: MCP is stdio and one agent
  per server (user, 2026-08-01). What survived is what was never about the
  transport — attribution rides the DELTA, so it holds whoever wrote it and
  however they connected."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.shell]
            [slopp.ops :as api]
            [slopp.store :as store]
            [slopp.ops.branch :as branch] [slopp.ops.external :as external] [slopp.read.history :as history])
)

(deftest ^:external attribution-flows-through-every-write-kind
  (let [sess (external/open!)]
    (try
      (api/ingest! sess 'at.core "(ns at.core)\n(defn f [x] x)\n" :agent "ingester")
      (api/edit-replace! sess 'at.core 'f "(defn f [x] (inc x))"
                         :prompt "bump" :agent "replacer")
      (api/add-form! sess 'at.core "(defn g [x] (f x))" :agent "adder")
      (api/rename! sess 'at.core 'g 'h :agent "renamer")
      (let [by-op (into {} (map (juxt :op :agent)) (slopp.store/deltas (:store @sess)))]
        (is (= "ingester" (by-op :ingest)))
        (is (= "replacer" (by-op :replace)))
        (is (= "adder"    (by-op :add)))
        (is (= "renamer"  (by-op :rename))))
      (finally (api/close! sess)))))

(deftest ^:external fork-edit-merge-end-to-end                     ; m2, the whole story
  (let [root  (str (System/getProperty "java.io.tmpdir") "/slopp-m2-" (System/nanoTime))
        a-dir (str root "/main")
        b-dir (str root "/fork")]
    (try
      ;; 1. mainline project is born
      (let [sess (external/open! {:slopp.ops/dir a-dir})]
        (try
          (api/ingest! sess 'fm.core
                       (str "(ns fm.core (:require [clojure.test :refer [deftest is]]))\n"
                            "(defn f [x] (inc x))\n"
                            "(defn g [x] (f x))\n"
                            "(deftest f-t (is (= 2 (f 1))))\n")
                       :agent "founder")
          (finally (api/close! sess))))
      ;; 2. fork = copy the project dir
      (clojure.java.shell/sh "cp" "-r" a-dir b-dir)
      ;; 3. the fork diverges on its own server (edits g, adds h + a test)
      (let [sess (external/open! {:slopp.ops/dir b-dir})]
        (try
          (api/edit-replace! sess 'fm.core 'g "(defn g [x] (f (f x)))"
                             :prompt "double-apply" :agent "forker")
          (api/add-form! sess 'fm.core
                         "(defn h [x] (* 10 (g x)))" :agent "forker")
          (api/add-form! sess 'fm.core
                         "(deftest h-t (is (= 30 (h 1))))" :agent "forker")
          (finally (api/close! sess))))
      ;; 4. meanwhile mainline diverges on a DIFFERENT form
      (let [sess (external/open! {:slopp.ops/dir a-dir})]
        (try
          (api/edit-replace! sess 'fm.core 'f "(defn f [x] (+ 1 x))"
                             :prompt "same behavior, our style" :agent "mainliner")
          ;; 5. merge the fork back into the LIVE session
          (let [r (branch/merge! sess b-dir)]
            (is (nil? (:error r)) (pr-str r))
            (is (empty? (:conflicts r)))
            (is (= 3 (:merged r)))
            (testing "the live image runs the merged whole"
              (is (= [30] (api/query-eval sess "(fm.core/h 1)"))))
            (testing "merge verification ran BOTH sides' tests green"
              (is (zero? (+ (:fail (:test r)) (:error (:test r))))))
            (testing "provenance: the merge delta + their agent attribution"
              (is (some #(= :merge (:op %)) (store/deltas (:store @sess))))
              (is (re-find #"forker"
                           (pr-str (history/query-history sess :contains "double-apply"))))))
          ;; 6. merging again is a no-op (idempotent)
          (let [r2 (branch/merge! sess b-dir)]
            (is (zero? (:merged r2)))
            (is (empty? (:conflicts r2))))
          (finally (api/close! sess))))
      (finally
        (clojure.java.shell/sh "rm" "-rf" root)))))
