(ns slopp.edit-group-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.api :as api]))

(def buggy
  (str "(ns gdemo\n  (:require [clojure.test :refer [deftest is]]))\n"
       "(defn tier [ops tokens]\n"
       "  (reduce (fn [acc [op v]] ((case op :+ + :* *) acc v))\n"
       "          (first tokens)\n"
       "          (partition 2 (rest tokens))))\n"          ; collapses to scalar
       "(defn evaluate [ts] (->> ts (tier #{:* }) (tier #{:+})))\n"
       "(deftest eval-t (is (= 7 (evaluate [1 :+ 2 :* 3]))))\n"))

(deftest ^:isolated edit-group-fixes-multi-form-refactor-in-one-intent
  (let [sess (api/open!)]
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

(deftest ^:isolated edit-group-is-atomic-on-error
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'gdemo buggy)
      (let [deltas-before (count (store/deltas (:store @sess)))
            src-before    (api/query-source sess 'gdemo)
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
          (is (= src-before (api/query-source sess 'gdemo)))
          ;; the first step's change never reached the image either
          (is (not= [[1 :+ 2]] (api/query-eval sess "(gdemo/tier #{:+} [1 :+ 2])")))))
      (finally (api/close! sess)))))

(deftest ^:isolated edit-group-supports-add-and-delete
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'gdemo "(ns gdemo)\n(defn old-helper [x] x)\n")
      (let [r (api/edit-group!
               sess
               [{:action :add :ns 'gdemo :source "(defn new-helper [x] (* 2 x))"}
                {:action :delete :ns 'gdemo :name 'old-helper}]
               :prompt "swap helpers")]
        (is (nil? (:error r)))
        (is (re-find #"new-helper" (api/query-source sess 'gdemo)))
        (is (not (re-find #"old-helper" (api/query-source sess 'gdemo))))
        (is (= [10] (api/query-eval sess "(gdemo/new-helper 5)")))
        (is (= [nil] (api/query-eval sess "(resolve 'gdemo/old-helper)"))))
      (finally (api/close! sess)))))
