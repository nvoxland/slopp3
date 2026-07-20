(ns slopp.multiproc-test
  "Phase 4 m5b: TWO servers, ONE store dir — the per-agent-server split.
  The journal (m5a) arbitrates commits; sync-with-journal! lets each server
  notice and absorb the other's work (cache refresh + image catch-up +
  trace invalidation)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.shell]
            [rewrite-clj.parser]
            [slopp.store :as store]
            [slopp.render]
            [slopp.api :as api] [slopp.api.branch :as branch] [slopp.api.query :as query] [slopp.api.external :as external]))

(deftest ^:external two-servers-one-store
  (let [dir (str (System/getProperty "java.io.tmpdir")
                 "/slopp-m5b-" (System/nanoTime))
        s1  (external/open! {:slopp.api/dir dir})]
    (try
      (api/ingest! s1 'tp.core
                   (str "(ns tp.core (:require [clojure.test :refer [deftest is]]))\n"
                        "(defn f [x] (inc x))\n"
                        "(defn h [x] (dec x))\n"
                        "(deftest f-t (is (= 2 (f 1))))\n"))
      (let [s2 (external/open! {:slopp.api/dir dir})]           ; second server, same dir
        (try
          (testing "server 2 opens onto server 1's work"
            (is (= [2] (api/query-eval s2 "(tp.core/f 1)"))))

          (testing "s1 commits; s2 absorbs it — cache AND image"
            (api/edit-replace! s1 'tp.core 'f "(defn f [x] (+ x 10))"
                               :prompt "s1's change" :agent "server-1")
            (api/edit-replace! s1 'tp.core 'f-t
                               "(deftest f-t (is (= 11 (f 1))))")
            (let [r (api/sync-with-journal! s2)]
              (is (pos? (:synced r 0))))
            (is (= [11] (api/query-eval s2 "(tp.core/f 1)")))
            (is (re-find #"\(\+ x 10\)" (query/query-source s2 'tp.core))))

          (testing "and the other direction"
            (api/add-form! s2 'tp.core "(defn g [x] (* 2 (f x)))"
                           :prompt "s2's addition" :agent "server-2")
            (api/sync-with-journal! s1)
            (is (= [22] (api/query-eval s1 "(tp.core/g 1)"))))

          (testing "a STALE different-form write rebases and lands (no sync needed)"
            (api/edit-replace! s1 'tp.core 'f "(defn f [x] (+ x 100))")
            (api/edit-replace! s1 'tp.core 'f-t
                               "(deftest f-t (is (= 101 (f 1))))")
            ;; s2 has NOT synced; its base is stale, but it touches h only
            (let [r (api/edit-replace! s2 'tp.core 'h "(defn h [x] (- x 5))"
                                       :prompt "stale but different form")]
              (is (nil? (:error r)) (pr-str r))
              (is (nil? (:conflict r))))
            (api/sync-with-journal! s1)
            (is (re-find #"\(- x 5\)" (query/query-source s1 'tp.core)))
            (is (re-find #"\(\+ x 100\)" (query/query-source s1 'tp.core))))

          (testing "a cross-server same-form race surfaces the conflict"
            (api/edit-replace! s1 'tp.core 'h "(defn h [x] :server-1)")
            ;; s2 unsynced: edits the SAME form from a stale base
            (let [r (api/edit-replace! s2 'tp.core 'h "(defn h [x] :server-2)")]
              (is (some? (:conflict r)) (pr-str r)))
            (is (re-find #":server-1" (query/query-source s1 'tp.core))))

          (testing "provenance shows which server did what"
            (api/sync-with-journal! s2)
            (let [hist (pr-str (query/query-history s2))]
              (is (re-find #"server-1" hist))
              (is (re-find #"server-2" hist))))
          (finally (api/close! s2))))
      (finally
        (api/close! s1)
        (clojure.java.shell/sh "rm" "-rf" dir)))))

(deftest ^:external private-checkouts-shared-branch-storage        ; m5c
  (let [dir (str (System/getProperty "java.io.tmpdir")
                 "/slopp-m5c-" (System/nanoTime))
        s1  (external/open! {:slopp.api/dir dir})]
    (try
      (api/ingest! s1 'pc.core
                   (str "(ns pc.core)\n(defn f [x] (inc x))\n"))
      ;; server 1 branches and works there; its checkout is ITS state
      (branch/branch! s1 "feature")
      (api/edit-replace! s1 'pc.core 'f "(defn f [x] (+ x 50))"
                         :prompt "feature work" :agent "server-1")
      (let [s2 (external/open! {:slopp.api/dir dir})]          ; server 2: own checkout (main)
        (try
          (testing "checkouts are per-server: s2 is on main, unaffected"
            (is (= "main" (:current (branch/query-branches s2))))
            (is (= [2] (api/query-eval s2 "(pc.core/f 1)"))))
          (testing "s2 can see and switch to s1's branch (shared storage)"
            (is (some #(= "feature" (:name %))
                      (:branches (branch/query-branches s2))))
            (branch/branch-switch! s2 "feature")
            (is (= [51] (api/query-eval s2 "(pc.core/f 1)"))))
          (testing "both on feature: commits flow across servers via the journal"
            (api/edit-replace! s1 'pc.core 'f "(defn f [x] (+ x 500))"
                               :prompt "more feature work")
            (api/sync-with-journal! s2)
            (is (= [501] (api/query-eval s2 "(pc.core/f 1)"))))
          (testing "meanwhile s1 can go back to main independently"
            (branch/branch-switch! s1 "main")
            (is (= [2] (api/query-eval s1 "(pc.core/f 1)")))
            (is (= "feature" (:current (branch/query-branches s2)))))
          (finally (api/close! s2))))
      (finally
        (api/close! s1)
        (clojure.java.shell/sh "rm" "-rf" dir)))))

(deftest ^:external incremental-sync-replays-the-suffix-exactly    ; backlog: no full reload
  (let [b  (store/ingest (store/empty-store) 'ir.core
                         "(ns ir.core)\n(defn f [x] x)\n(defn g [x] x)\n")
        ;; the writer's side: a realistic suffix of ops
        w  (-> b
               (store/replace-node 'ir.core 'f
                                   (rewrite-clj.parser/parse-string
                                    "(defn f [x] (+ x 1))")
                                   :prompt "edit" :agent "w")
               first
               (store/append-form 'ir.core
                                  (rewrite-clj.parser/parse-string
                                   "(defn h [x] (f x))")
                                  :prompt "add" :agent "w")
               first
               (store/remove-form 'ir.core 'g :prompt "drop" :agent "w")
               first)
        ;; the reader replays the suffix onto its trailing copy of b
        suffix (drop (count (store/deltas b)) (store/deltas w))
        r      (reduce store/replay-delta b suffix)]
    (testing "replay reproduces the writer's store exactly"
      (is (some? r))
      (is (= (slopp.render/render-ns w 'ir.core)
             (slopp.render/render-ns r 'ir.core)))
      (is (= (:next-id w) (:next-id r)))
      (is (= (store/deltas w) (store/deltas r))))
    (testing ":ingest in the suffix signals full-reload fallback"
      (let [w2 (store/ingest w 'ir.extra "(ns ir.extra)\n")]
        (is (nil? (store/replay-delta r (last (store/deltas w2)))))))))
