(ns slopp.concurrency-test
  "Item 4: CRDT-aligned concurrent commits — the granularity dodge in action.
  Different-form concurrent writes both land; same-form contention surfaces a
  conflict (the Phase-1 face of C5's MV-register)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.shell]
            [slopp.store :as store]
            [slopp.api :as api] [slopp.api.session :as session]))

(def seed
  (str "(ns cc.core)\n"
       "(defn a [x] x)\n(defn b [x] x)\n(defn c [x] x)\n(defn d [x] x)\n"))

(deftest ^:isolated parallel-different-form-edits-all-land
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'cc.core seed)
      (let [results (doall
                     (pmap (fn [nm]
                             (api/edit-replace! sess 'cc.core nm
                                                (format "(defn %s [x] (+ x %s))"
                                                        nm (int (first (str nm))))
                                                :prompt (str "bump " nm)))
                           '[a b c d]))]
        (testing "every concurrent different-form write succeeded"
          (is (every? #(nil? (:error %)) results))
          (is (every? #(nil? (:conflict %)) results)))
        (testing "no lost updates: all four changes present in the store"
          (let [src (api/query-source sess 'cc.core)]
            (doseq [nm '[a b c d]]
              (is (re-find (re-pattern (format "defn %s \\[x\\] \\(\\+ x" nm)) src)
                  (str nm " lost")))))
        (testing "all four :replace deltas recorded"
          (is (= 4 (count (filter #(= :replace (:op %))
                                  (store/deltas (:store @sess)))))))
        (testing "the image agrees with the store"
          (is (= [(+ 5 97)] (api/query-eval sess "(cc.core/a 5)")))))
      (finally (api/close! sess)))))

^:unsafe (deftest ^:isolated same-form-contention-surfaces-a-conflict
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'cc.core seed)
      ;; deterministic contention: between this write's hot-load and its
      ;; commit, a competing write to the SAME form lands (one-shot: the hook
      ;; must not re-fire on the rebase retry)
      (let [fired (atom false)
            r (binding [session/*pre-commit-hook*
                        (fn [] (when (compare-and-set! fired false true)
                                 (binding [session/*pre-commit-hook* nil]
                                   (api/edit-replace! sess 'cc.core 'a
                                                      "(defn a [x] :competitor)"
                                                      :prompt "raced in first"))))]
                (api/edit-replace! sess 'cc.core 'a "(defn a [x] :loser)"
                                   :prompt "should conflict"))]
        (is (some? (:conflict r)))
        (is (re-find #"changed concurrently" (str (:conflict r))))
        (testing "the competitor's write is what survived"
          (is (re-find #":competitor" (api/query-source sess 'cc.core)))
          (is (not (re-find #":loser" (api/query-source sess 'cc.core))))))
      (testing "but a DIFFERENT-form competitor rebases cleanly instead"
        (let [fired (atom false)
              r (binding [session/*pre-commit-hook*
                          (fn [] (when (compare-and-set! fired false true)
                                   (binding [session/*pre-commit-hook* nil]
                                     (api/edit-replace! sess 'cc.core 'b
                                                        "(defn b [x] :other)"
                                                        :prompt "raced, different form"))))]
                  (api/edit-replace! sess 'cc.core 'c "(defn c [x] :mine)"
                                     :prompt "should rebase and land"))]
          (is (nil? (:error r)))
          (is (nil? (:conflict r)))
          (let [src (api/query-source sess 'cc.core)]
            (is (re-find #":other" src))
            (is (re-find #":mine" src)))))
      (finally (api/close! sess)))))

(deftest ^:isolated revert-restores-a-prior-version
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'rv.core "(ns rv.core)\n(defn f [x] x)\n")
      (api/edit-replace! sess 'rv.core 'f "(defn f [x] (inc x))" :prompt "v2")
      (api/edit-replace! sess 'rv.core 'f "(defn f [x] (+ 2 x))" :prompt "v3")
      (let [r (api/revert-form! sess 'rv.core 'f)]     ; default: previous
        (is (nil? (:error r)))
        (is (re-find #"\(inc x\)" (api/query-source sess 'rv.core)))
        (is (= [6] (api/query-eval sess "(rv.core/f 5)")))
        (testing "the revert is itself provenance"
          (is (re-find #"revert to"
                       (str (:prompt (last (api/query-lineage sess 'rv.core 'f))))))))
      (testing "revert to a specific delta from form history"
        (let [v1 (first (api/query-form-history sess 'rv.core 'f))
              r  (api/revert-form! sess 'rv.core 'f :to (:delta v1))]
          (is (nil? (:error r)))
          (is (= [5] (api/query-eval sess "(rv.core/f 5)")))))
      (testing "errors"
        (is (:error (api/revert-form! sess 'rv.core 'nope)))
        (is (:error (api/revert-form! sess 'rv.core 'f :to "d99999"))))
      (finally (api/close! sess)))))

(deftest ^:isolated durable-concurrent-writers-share-the-journal   ; m5a storage inversion
  (let [dir (str (System/getProperty "java.io.tmpdir")
                 "/slopp-m5a-" (System/nanoTime))]
    (try
      (let [sess (api/open! {:slopp.api/dir dir})]
        (try
          (api/ingest! sess 'cc.core seed)
          (let [results (doall
                         (pmap (fn [nm]
                                 (api/edit-replace! sess 'cc.core nm
                                                    (format "(defn %s [x] (+ x %s))"
                                                            nm (int (first (str nm))))
                                                    :prompt (str "bump " nm)))
                               '[a b c d]))]
            (is (every? #(and (nil? (:error %)) (nil? (:conflict %))) results)
                (pr-str (mapv #(select-keys % [:error :conflict]) results))))
          (finally (api/close! sess))))
      ;; the journal is the record: a fresh session sees all four writes
      (let [sess (api/open! {:slopp.api/dir dir})]
        (try
          (let [src (api/query-source sess 'cc.core)]
            (doseq [nm '[a b c d]]
              (is (re-find (re-pattern (format "defn %s \\[x\\] \\(\\+ x" nm)) src)
                  (str nm " lost from the journal"))))
          (finally (api/close! sess))))
      (finally (clojure.java.shell/sh "rm" "-rf" dir)))))
