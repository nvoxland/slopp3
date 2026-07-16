(ns slopp.branch-test
  "Phase 4 m3: branches WITHIN one repo/session. A branch is an O(1) snapshot
  of the store (values are cheap); the single live image gets checkout
  semantics; merging down to main rides the m2 causal-delivery engine."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.shell]
            [slopp.api :as api] [slopp.api.branch :as branch]))

(def seed
  (str "(ns br.core (:require [clojure.test :refer [deftest is]]))\n"
       "(defn f [x] (inc x))\n"
       "(deftest f-t (is (= 2 (f 1))))\n"))

(deftest ^:isolated branch-edit-switch-isolation
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'br.core seed)
      (testing "branching is instant and starts identical (no image work)"
        (let [r (branch/branch! sess "feature")]
          (is (= "feature" (:branch r)))
          (is (= "main" (:from r))))
        (is (= [2] (api/query-eval sess "(br.core/f 1)"))))
      (testing "branch edits are verified writes like any other"
        (let [r (api/edit-replace! sess 'br.core 'f "(defn f [x] (+ x 10))"
                                   :prompt "feature behavior")]
          (is (= 1 (:fail (:test r))))          ; honest red: f-t expects inc
          (is (= :genuine (:diagnosis (:test r)))))
        (api/edit-replace! sess 'br.core 'f-t
                           "(deftest f-t (is (= 11 (f 1))))"
                           :prompt "test the feature behavior")
        (is (= [11] (api/query-eval sess "(br.core/f 1)"))))
      (testing "switching back to main restores main's code AND image"
        (let [r (branch/branch-switch! sess "main")]
          (is (= "main" (:switched r))))
        (is (= [2] (api/query-eval sess "(br.core/f 1)")))
        (is (re-find #"\(inc x\)" (api/query-source sess 'br.core))))
      (testing "and forward again"
        (branch/branch-switch! sess "feature")
        (is (= [11] (api/query-eval sess "(br.core/f 1)"))))
      (finally (api/close! sess)))))

(deftest ^:isolated merge-branch-down-to-main
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'br.core seed)
      (branch/branch! sess "feature")
      (api/add-form! sess 'br.core "(defn g [x] (* 2 (f x)))"
                     :prompt "feature work" :agent "brancher")
      (api/add-form! sess 'br.core "(deftest g-t (is (= 4 (g 1))))"
                     :agent "brancher")
      (branch/branch-switch! sess "main")
      ;; main did its own (different-form) work meanwhile
      (api/add-form! sess 'br.core "(defn h [x] (- x 1))" :prompt "main work")
      (testing "merge lands the branch's work on main, verified"
        (let [r (branch/branch-merge! sess "feature")]
          (is (nil? (:error r)) (pr-str r))
          (is (empty? (:conflicts r)))
          (is (= 2 (:merged r)))
          (is (zero? (+ (:fail (:test r)) (:error (:test r)))))
          (is (= [4] (api/query-eval sess "(br.core/g 1)")))
          (is (re-find #"defn h" (api/query-source sess 'br.core)))))
      (testing "the branch can keep going and merge again exactly"
        (branch/branch-switch! sess "feature")
        (api/edit-replace! sess 'br.core 'g "(defn g [x] (* 3 (f x)))"
                           :prompt "round 2")
        (api/edit-replace! sess 'br.core 'g-t "(deftest g-t (is (= 6 (g 1))))")
        (branch/branch-switch! sess "main")
        (let [r (branch/branch-merge! sess "feature")]
          (is (empty? (:conflicts r)))
          (is (= 2 (:merged r)))
          (is (= [6] (api/query-eval sess "(br.core/g 1)")))))
      (testing "same-form divergence surfaces the MV conflict; main stays live"
        (branch/branch-switch! sess "feature")
        (api/edit-replace! sess 'br.core 'g "(defn g [x] :branch-version)")
        (branch/branch-switch! sess "main")
        (api/edit-replace! sess 'br.core 'g "(defn g [x] :main-version)")
        (let [r (branch/branch-merge! sess "feature")]
          (is (= 1 (count (:conflicts r))))
          (is (re-find #":main-version" (api/query-source sess 'br.core)))))
      (finally (api/close! sess)))))

(deftest ^:isolated branch-guards-and-listing
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'br.core seed)
      (branch/branch! sess "feature")
      (testing "listing shows every line and where we are"
        (let [b (branch/query-branches sess)]
          (is (= "feature" (:current b)))
          (is (= #{"main" "feature"} (set (map :name (:branches b)))))))
      (testing "guards"
        (is (:error (branch/branch! sess "feature")))          ; taken
        (is (:error (branch/branch! sess "main")))             ; reserved
        (is (:error (branch/branch-switch! sess "nope")))      ; unknown
        (is (:error (branch/branch-merge! sess "feature")))    ; into itself
        (is (:error (branch/branch-delete! sess "feature"))))  ; current
      (testing "delete after switching away"
        (branch/branch-switch! sess "main")
        (is (nil? (:error (branch/branch-delete! sess "feature"))))
        (is (= ["main"] (mapv :name (:branches (branch/query-branches sess))))))
      (finally (api/close! sess)))))

(deftest ^:isolated branches-survive-restart-of-a-durable-session
  (let [dir (str (System/getProperty "java.io.tmpdir")
                 "/slopp-br-" (System/nanoTime))]
    (try
      (let [sess (api/open! {:dir dir})]
        (try
          (api/ingest! sess 'br.core seed)
          (branch/branch! sess "feature")
          (api/edit-replace! sess 'br.core 'f "(defn f [x] (+ x 10))"
                             :prompt "feature work")
          (api/edit-replace! sess 'br.core 'f-t
                             "(deftest f-t (is (= 11 (f 1))))")
          (branch/branch-switch! sess "main")
          (finally (api/close! sess))))
      (let [sess (api/open! {:dir dir})]
        (try
          (testing "the branch is still there after reopen"
            (is (some #(= "feature" (:name %))
                      (:branches (branch/query-branches sess)))))
          (testing "switching to it restores its content and image"
            (branch/branch-switch! sess "feature")
            (is (= [11] (api/query-eval sess "(br.core/f 1)"))))
          (finally (api/close! sess))))
      (finally
        (clojure.java.shell/sh "rm" "-rf" dir)))))

(deftest ^:isolated per-branch-images-park-adopt-and-reap          ; m4
  (let [sess (api/open! {:branch-image-ttl-ms 200})]
    (try
      (api/ingest! sess 'br.core seed)
      (branch/branch! sess "feature")
      (api/edit-replace! sess 'br.core 'f "(defn f [x] (+ x 10))"
                         :prompt "feature behavior")
      (api/edit-replace! sess 'br.core 'f-t "(deftest f-t (is (= 11 (f 1))))")
      (let [feature-port (:port (:image @sess))]
        (testing "switching away PARKS the branch image; main boots its own"
          (branch/branch-switch! sess "main")
          (is (not= feature-port (:port (:image @sess))))
          (is (= [2] (api/query-eval sess "(br.core/f 1)")))
          (is (some? (get-in @sess [:lines "feature" :image]))))
        (testing "switching back ADOPTS the parked image — same process"
          (let [r (branch/branch-switch! sess "feature")]
            (is (:adopted r)))
          (is (= feature-port (:port (:image @sess))))
          (is (= [11] (api/query-eval sess "(br.core/f 1)"))))
        (testing "idle parked images get reaped after the TTL"
          (branch/branch-switch! sess "main")
          (Thread/sleep 400)                       ; > ttl
          (api/reap-idle-images! sess)
          (is (nil? (get-in @sess [:lines "feature" :image])))
          (testing "...and switching back just boots a fresh one, correct code"
            (let [r (branch/branch-switch! sess "feature")]
              (is (:booted r))
              (is (not= feature-port (:port (:image @sess))))
              (is (= [11] (api/query-eval sess "(br.core/f 1)")))))))
      (finally (api/close! sess)))))

(deftest ^:isolated branches-have-identity-beyond-their-name
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'br.core seed)
      (branch/branch! sess "feature")
      (let [id1 (:id (first (filter #(= "feature" (:name %))
                                    (:branches (branch/query-branches sess)))))]
        (is (string? id1))
        (api/edit-replace! sess 'br.core 'f "(defn f [x] (+ x 10))")
        (api/edit-replace! sess 'br.core 'f-t "(deftest f-t (is (= 11 (f 1))))")
        (branch/branch-switch! sess "main")
        (branch/branch-merge! sess "feature")
        (branch/branch-delete! sess "feature")
        (testing "a RECREATED branch with the same name is a fresh identity"
          (branch/branch! sess "feature")
          (let [id2 (:id (first (filter #(= "feature" (:name %))
                                        (:branches (branch/query-branches sess)))))]
            (is (not= id1 id2)))
          ;; and it merges cleanly as its own line of work
          (api/edit-replace! sess 'br.core 'f "(defn f [x] (+ x 20))")
          (api/edit-replace! sess 'br.core 'f-t "(deftest f-t (is (= 21 (f 1))))")
          (branch/branch-switch! sess "main")
          (let [r (branch/branch-merge! sess "feature")]
            (is (nil? (:error r)) (pr-str r))
            (is (empty? (:conflicts r)))
            (is (= [21] (api/query-eval sess "(br.core/f 1)"))))))
      (finally (api/close! sess)))))

(deftest ^:isolated concurrent-branch-creation-races-yield-one-winner
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'br.core seed)
      (let [results (doall (pmap (fn [_] (branch/branch! sess "contested"))
                                 (range 2)))
            wins    (filter #(= "contested" (:branch %)) results)
            errs    (filter :error results)]
        (is (= 1 (count wins)))
        (is (= 1 (count errs)))
        (is (re-find #"already exists" (:error (first errs)))))
      (finally (api/close! sess)))))
