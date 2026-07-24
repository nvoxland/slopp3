(ns slopp.api.done-test
  "The done-point require-prune candidate logic — pure over the store, so it
   runs in-image. The effectful try-remove-verify-restore loop is exercised
   through a real session in slopp.api-test."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.api.done :as done] [slopp.api.external :as external] [slopp.boot :as boot] [slopp.api :as api]))

(defn- store-with-requires []
  (store/ingest (store/empty-store) 'du.core
                (str "(ns du.core\n"
                     "  (:require [clojure.set :as s]\n"
                     "            ^:side-effect [du.eff :as e]\n"
                     "            [du.used :as u]))\n"
                     "(defn f [] (u/g))\n")))

(deftest unused-requires-finds-only-prunable-candidates
  (let [st (store-with-requires)
        cands (done/unused-requires st 'du.core)]
    (testing "an unused require is a candidate; a USED one is not"
      (is (= '[clojure.set] (mapv :lib cands)) (pr-str cands)))
    (testing "a candidate carries the ^:side-effect re-add form for the restore path"
      (is (= "^:side-effect [clojure.set :as s]" (:marked (first cands)))))
    (testing "a require already marked ^:side-effect is NOT re-offered (no churn)"
      (is (not (contains? (set (map :lib cands)) 'du.eff)) (pr-str cands)))))

(deftest side-effect-required-reads-the-marker
  (let [st (store-with-requires)]
    (is (true?  (done/side-effect-required? st 'du.core 'du.eff)))
    (is (false? (done/side-effect-required? st 'du.core 'clojure.set)))
    (is (false? (done/side-effect-required? st 'du.core 'du.used)))))

(deftest ^:external a-stale-host-rides-the-verdict-not-just-the-brief
  ;; The currency record has always existed; it only ever reached
  ;; session_brief. A host running superseded code produced verdicts that said
  ;; nothing about it, and the resulting investigation eliminated four correct
  ;; mechanisms in `rt` before finding the stale process.
  ;;
  ;; boot-info is nil in any process that did not boot from a store (every
  ;; test JVM), so the record is planted here — that IS the path under test:
  ;; done reaches the kernel carrier and puts what it finds on the verdict.
  (let [sess (external/open!)]
    (try
      (api/ingest! sess 'hs.core "(ns hs.core)\n(defn f \"F.\" [] 1)\n")
      (testing "no boot record — the process cannot be stale, and the verdict is quiet"
        (let [r (external/done! sess :label "clean")]
          (is (nil? (get-in r [:findings :host-stale])) (pr-str (:findings r)))))
      (testing "a snapshot host with code deltas since boot rides the verdict"
        (reset! boot/boot-info {:dir "." :mode :snapshot :booted-at 1})
        (api/edit-replace! sess 'hs.core 'f "(defn f \"F.\" [] 2)"
                           :prompt "a code delta the host cannot be running")
        (let [r  (external/done! sess :label "stale host")
              hs (get-in r [:findings :host-stale])]
          (is (some? hs) (pr-str (:findings r)))
          (is (= :snapshot (:mode hs)))
          (is (re-find #"(?i)suspect" (str (:verdict-note hs)))
              "it must say what staleness means for THIS result")))
      (finally
        (reset! boot/boot-info nil)
        (api/close! sess)))))
