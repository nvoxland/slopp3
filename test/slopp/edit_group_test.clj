(ns slopp.edit-group-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.api :as api] [slopp.api.query :as query] [slopp.api.external :as external]))

(def buggy
  (str "(ns gdemo\n  (:require [clojure.test :refer [deftest is]]))\n"
       "(defn tier [ops tokens]\n"
       "  (reduce (fn [acc [op v]] ((case op :+ + :* *) acc v))\n"
       "          (first tokens)\n"
       "          (partition 2 (rest tokens))))\n"          ; collapses to scalar
       "(defn evaluate [ts] (->> ts (tier #{:* }) (tier #{:+})))\n"
       "(deftest eval-t (is (= 7 (evaluate [1 :+ 2 :* 3]))))\n"))

(deftest ^:external edit-group-fixes-multi-form-refactor-in-one-intent
  (let [sess (external/open!)]
    (try
      (api/ingest! sess 'gdemo buggy)
      (api/test-run! sess 'gdemo)                       ; red + builds trace map
      (let [verifies-before (count (filter #(= :verify (:op %))
                                           (store/deltas (:store @sess))))
            r (api/edit-group!
               sess
               [{:action :replace :ns 'gdemo :name 'tier
                 :source (str "(defn tier [ops tokens]\n"
                              "  (reduce (fn [acc [op v]]\n"
                              "            (if (ops op)\n"
                              "              (conj (pop acc) ((case op :+ + :* *) (peek acc) v))\n"
                              "              (conj acc op v)))\n"
                              "          [(first tokens)]\n"
                              "          (partition 2 (rest tokens))))")}
                {:action :replace :ns 'gdemo :name 'evaluate
                 :source "(defn evaluate [ts] (->> ts (tier #{:*}) (tier #{:+}) first))"}]
               :prompt "fix precedence (atomic)")]
        (testing "no error; both deltas share the group id"
          (is (nil? (:error r)))
          (is (some? (:group r)))
          (is (= 2 (count (:deltas r))))
          (is (apply = (map :group (:deltas r)))))
        (testing "verified ONCE, green, with NO mid-refactor red restart"
          (is (= (inc verifies-before)
                 (count (filter #(= :verify (:op %)) (store/deltas (:store @sess))))))
          (is (zero? (+ (:fail (:test r)) (:error (:test r)))))
          (is (nil? (:fresh-confirmed (:test r)))))
        (testing "both forms live in the image"
          (is (= [7] (api/query-eval sess "(gdemo/evaluate [1 :+ 2 :* 3])")))))
      (finally (api/close! sess)))))

(deftest ^:external edit-group-is-atomic-on-error
  (let [sess (external/open!)]
    (try
      (api/ingest! sess 'gdemo buggy)
      (let [deltas-before (count (store/deltas (:store @sess)))
            src-before    (query/query-source sess 'gdemo)
            r (api/edit-group!
               sess
               [{:action :replace :ns 'gdemo :name 'tier
                 :source "(defn tier [ops tokens] tokens)"}
                {:action :replace :ns 'gdemo :name 'nonexistent
                 :source "(defn nonexistent [] 1)"}]
               :prompt "should fail")]
        (testing "the whole group is rejected"
          (is (:error r))
          (is (= 1 (:step r))))
        (testing "NOTHING was committed — store, deltas, and image untouched"
          (is (= deltas-before (count (store/deltas (:store @sess)))))
          (is (= src-before (query/query-source sess 'gdemo)))
          ;; the first step's change never reached the image either
          (is (not= [[1 :+ 2]] (api/query-eval sess "(gdemo/tier #{:+} [1 :+ 2])")))))
      (finally (api/close! sess)))))

(deftest ^:external edit-group-supports-add-and-delete
  (let [sess (external/open!)]
    (try
      (api/ingest! sess 'gdemo "(ns gdemo)\n(defn old-helper [x] x)\n")
      (let [r (api/edit-group!
               sess
               [{:action :add :ns 'gdemo :source "(defn new-helper [x] (* 2 x))"}
                {:action :delete :ns 'gdemo :name 'old-helper}]
               :prompt "swap helpers")]
        (is (nil? (:error r)))
        (is (re-find #"new-helper" (query/query-source sess 'gdemo)))
        (is (not (re-find #"old-helper" (query/query-source sess 'gdemo))))
        (is (= [10] (api/query-eval sess "(gdemo/new-helper 5)")))
        (is (= [nil] (api/query-eval sess "(resolve 'gdemo/old-helper)"))))
      (finally (api/close! sess)))))

(deftest ^:external group-writes-report-contract-drift-too
  ;; contract-drift was wired into edit/replace-form only. Every GROUP write —
  ;; edit_group, rename_sweep, edit_move_forms, change_signature, edit_extract
  ;; — goes through apply-group-step, which calls store/replace-node directly
  ;; and bypassed it entirely.
  ;;
  ;; So the fix missed its own motivating case: the sweep that silently dropped
  ;; ^Repository and ^java.sql.Connection from slopp.git/close-ctx!, turning
  ;; direct interop into reflection, would STILL not report drift. A guard
  ;; wired into one of several paths is barely a guard — it is a guard on the
  ;; path that happened to be in front of me.
  (let [sess (external/open!)]
    (try
      (api/ingest! sess 'gd.core
                   (str "(ns gd.core)\n\n"
                        "(defn f\n  \"Doc.\"\n  [{:keys [^String a]} b] [a b])\n\n"
                        "(defn g [x] x)\n"))
      (testing "a group replace that drops a hint reports it"
        (let [r (api/edit-group! sess
                                 [{:action :replace :ns 'gd.core :name 'f
                                   :source "(defn f\n  \"Doc.\"\n  [{:keys [a]} b] [a b])"}]
                                 :prompt "drop the hint in a group")]
          (is (nil? (:error r)) (pr-str r))
          (is (some #(= :metadata-lost (:kind %)) (:drift r))
              (str "a group write must surface drift too: " (pr-str r)))))
      (testing "drift names the form, since a group touches many"
        (let [r (api/edit-group! sess
                                 [{:action :replace :ns 'gd.core :name 'f
                                   :source "(defn f [{:keys [a]} b] [a b])"}
                                  {:action :replace :ns 'gd.core :name 'g
                                   :source "(defn g [x] (identity x))"}]
                                 :prompt "one step drifts, one does not")]
          (is (= '[gd.core/f] (mapv :form (:drift r))) (pr-str r))))
      (testing "a clean group reports no drift"
        (let [r (api/edit-group! sess
                                 [{:action :replace :ns 'gd.core :name 'g
                                   :source "(defn g [x] (identity x))"}]
                                 :prompt "same contract")]
          (is (empty? (:drift r)) (pr-str r))))
      (finally (api/close! sess)))))
