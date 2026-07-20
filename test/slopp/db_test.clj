(ns slopp.db-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.render :as render]
            [slopp.db :as db]
            [slopp.api :as api])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir []
  (str (Files/createTempDirectory "slopp-db-test" (make-array FileAttribute 0))))

(def corpus
  ["(ns foo)\n\n(defn add [x y]\n  (+ x y))\n\n;; a comment\n(def z 1)\n"
   "(ns bar\n  (:require [clojure.string :as str]))\n\n(def ^:private secret 42)\n"
   ";; leading comment\n(def a 1)(def b 2)\n\n\n"])

(deftest ^:external db-round-trip-is-exact
  (testing "persist -> load reconstructs the store exactly (render + deltas + ids)"
    (doseq [src corpus]
      (let [dir  (temp-dir)
            conn (db/open! dir)
            s    (store/ingest (store/empty-store) 'ns src)]
        (db/persist! conn s (last (store/deltas s)))
        (.close conn)
        (let [conn2  (db/open! dir)
              loaded (db/load-store conn2)]
          (is (= src (render/render-ns loaded 'ns))
              (str "render round-trip failed for: " (pr-str src)))
          (is (= (store/deltas s) (store/deltas loaded)))
          (is (= (map :id (store/forms s 'ns)) (map :id (store/forms loaded 'ns))))
          (is (= (:next-id s) (:next-id loaded)))
          (.close conn2))))))

(deftest ^:external session-survives-restart
  (let [dir (temp-dir)
        target (str "(ns demo\n  (:require [clojure.test :refer [deftest is]]))\n"
                    "(defn add [x y] (+ x y))\n"
                    "(deftest t (is (= 6 (add 2 3))))\n")
        sess (api/open! {:slopp.api/dir dir})]
    (try
      (api/ingest! sess 'demo target)
      (api/edit-replace! sess 'demo 'add "(defn add [x y] (+ x y 1))" :prompt "off-by-one")
      (api/test-run! sess 'demo)
      (finally (api/close! sess)))
    ;; process "restarts": a brand-new session over the same dir
    (let [sess2 (api/open! {:slopp.api/dir dir})]
      (try
        (testing "source is reconstructed from the db"
          (is (re-find #"\(\+ x y 1\)" (api/query-source sess2 'demo))))
        (testing "the image was reloaded from the store"
          (is (= [6] (api/query-eval sess2 "(demo/add 2 3)"))))
        (testing "lineage (incl. prompt and verification) survives"
          (let [lin (api/query-lineage sess2 'demo 'add)]
            (is (some #(= "off-by-one" (:prompt %)) lin))
            (is (contains? (set (map :op lin)) :ingest)))
          (is (= :verify (:op (last (store/deltas (:store @sess2)))))))
        (testing "new edits continue cleanly (no id collisions with history)"
          (let [r (api/edit-replace! sess2 'demo 'add "(defn add [x y] (* x y))"
                                     :prompt "mul")]
            (is (nil? (:error r)))
            (is (= [6] (api/query-eval sess2 "(demo/add 2 3)")))))
        (finally (api/close! sess2))))))

(deftest ^:external module-tiers-survive-persist-and-reload
  (testing "declared purity tiers reconstruct through persist! -> load-store"
    (let [dir     (temp-dir)
          conn    (db/open! dir)
          [s1 d1] (store/record-module-tier (store/empty-store) "app.core" :pure
                                             :prompt "core is pure")]
      (db/persist! conn s1 d1)
      (.close conn)
      (let [conn2  (db/open! dir)
            loaded (db/load-store conn2)]
        (is (= {"app.core" :pure} (:module-tiers loaded)))
        (.close conn2)))))
