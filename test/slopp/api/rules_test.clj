(ns slopp.api.rules-test
  (:require [clojure.test :refer [deftest testing is]]
            [slopp.api.rules :as rules] [slopp.store :as store] [slopp.api :as api] [slopp.edit.modules :as edit.modules] [clojure.set :as set]))

(deftest done-advisory-registry-and-severity
  (testing "the registry carries every done-time advisory with a key, severity, and check"
    (is (set/subset? #{:schema-drift :key-typos :breaking-changes}
                     (set (map :key rules/done-advisories))))
    (is (every? (fn [r] (and (:severity r) (:check r))) rules/done-advisories))
    (testing "schema-drift is status-affecting (a lying schema is a real failure); the rest advise"
      (is (= :error (:severity (first (filter #(= :schema-drift (:key %)) rules/done-advisories)))))
      (is (= :advisory (:severity (first (filter #(= :key-typos (:key %)) rules/done-advisories)))))))
  (testing "status-affecting-fired? — only an :error-severity advisory with results flips it"
    (let [s0 (store/empty-store)]
      (is (true?  (rules/status-affecting-fired? s0 {:schema-drift [{:form 'a/b}]})))
      (is (false? (rules/status-affecting-fired? s0 {:key-typos [{:used :a/b}]})))
      (is (false? (rules/status-affecting-fired? s0 {:breaking-changes [{:form 'a/b}]})))
      (is (false? (rules/status-affecting-fired? s0 {})))
      (testing "a per-store severity override retunes it (dial up key-typos, down schema-drift)"
        (let [drift-off (first (store/record-config-put s0 "rules" :manifest "schema-drift" "advisory"))
              typos-err (first (store/record-config-put s0 "rules" :manifest "key-typos" "error"))]
          (is (false? (rules/status-affecting-fired? drift-off {:schema-drift [{:form 'a/b}]})))
          (is (true?  (rules/status-affecting-fired? typos-err {:key-typos [{:used :a/b}]}))))))))

(deftest ^:isolated per-store-severity-config-retunes-done
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'sv.core
                   (str "(ns sv.core)\n"
                        "(defn a [m] {:user/email (:x m)})\n"
                        "(defn b [m] {:user/email (:y m)})\n"
                        "(defn handle \"H.\" ([x] x) ([x y] (+ x y)))\n"))
      (api/done! sess :label "baseline")
      (api/config-file! sess "rules" :key "key-typos" :value "off"
                        :prompt "silence typos for this project")
      (api/config-file! sess "rules" :key "breaking-changes" :value "error"
                        :prompt "make a boundary break BLOCK here")
      (testing ":off silences an advisory end-to-end"
        (api/add-form! sess 'sv.core "(defn c [m] {:user/emial (:z m)})"
                       :prompt "typo — but the advisory is dialed off")
        (let [r (api/done! sess :label "typo-off")]
          (is (nil? (get-in r [:findings :key-typos])) (pr-str (:findings r)))))
      (testing ":error escalates an advisory to flip test-status red"
        (api/edit-replace! sess 'sv.core 'handle "(defn handle \"H.\" [x] x)"
                           :prompt "narrow away the 2-arity")
        (let [r (api/done! sess :label "narrow")]
          (is (seq (get-in r [:findings :breaking-changes])) (pr-str (:findings r)))
          (is (= :red (get-in r [:findings :test-status])) (pr-str (:findings r)))))
      (finally (api/close! sess)))))

(deftest catalog-covers-every-registered-rule
  (let [cataloged   (set (map :rule rules/rule-catalog))
        write-gates (set (edit.modules/write-gate-names))
        done-keys   (set (map :key rules/done-advisories))]
    (testing "every entry carries the declarative shape"
      (is (every? (fn [r] (and (:rule r) (:grain r) (:severity r) (:escape r) (:teach r)))
                  rules/rule-catalog)))
    (testing "every registered write gate is cataloged (drift guard)"
      (is (empty? (set/difference write-gates cataloged))
          (str "uncataloged write gates: " (set/difference write-gates cataloged))))
    (testing "every registered done advisory is cataloged (drift guard)"
      (is (empty? (set/difference done-keys cataloged))
          (str "uncataloged done advisories: " (set/difference done-keys cataloged))))))

(deftest ^:isolated query-rules-reports-write-gate-severity-honestly
  (let [sess (api/open!)]
    (try
      (testing "a write gate dialed :advisory now reports :advisory (warn-but-proceed)"
        (api/config-file! sess "rules" :key "schema-refusal" :value "advisory"
                          :prompt "soften a write gate to advisory")
        (let [sr (first (filter #(= :schema-refusal (:rule %)) (api/query-rules sess)))]
          (is (= :form (:grain sr)) (pr-str sr))
          (is (= :advisory (:severity sr)) (pr-str sr))))
      (testing ":off on a write gate is honored and reported"
        (api/config-file! sess "rules" :key "schema-refusal" :value "off"
                          :prompt "turn it off")
        (let [sr (first (filter #(= :schema-refusal (:rule %)) (api/query-rules sess)))]
          (is (= :off (:severity sr)) (pr-str sr))))
      (testing "a done advisory keeps its full severity range"
        (api/config-file! sess "rules" :key "key-typos" :value "error"
                          :prompt "escalate an advisory")
        (let [kt (first (filter #(= :key-typos (:rule %)) (api/query-rules sess)))]
          (is (= :error (:severity kt)) (pr-str kt))))
      (finally (api/close! sess)))))

(deftest ambient-state-and-bare-throw-checks
  (let [src (str "(ns app.core)\n"
                 "(def cache (atom {}))\n"
                 "(def limit 5)\n"
                 "(defn handle [x] (throw (IllegalArgumentException. \"no\")))\n"
                 "(defn ok [x] (throw (ex-info \"no\" {})))\n")
        s   (store/ingest (store/empty-store) 'app.core src)
        all (mapv #(:id (store/form-named s 'app.core %)) '[cache limit handle ok])]
    (testing "ambient-state flags a global (def _ (atom …)), not a plain def"
      (is (= '[app.core/cache] (mapv :form (rules/ambient-state-check nil s all)))))
    (testing "bare-throw flags a boundary fn throwing a constructed non-ex-info exception"
      (is (= '[app.core/handle] (mapv :form (rules/bare-throw-check nil s all)))))))

(deftest ^:isolated done-surfaces-ambient-state-and-bare-throw
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'as.core "(ns as.core)\n\n(defn seed \"S.\" [x] x)\n")
      (api/done! sess :label "baseline")
      (api/add-form! sess 'as.core "(def cache (atom {}))" :prompt "a global atom")
      (api/add-form! sess 'as.core "(defn boom \"B.\" [x] (throw (IllegalArgumentException. \"e\")))"
                     :prompt "a bare throw at a boundary")
      (let [r (api/done! sess :label "check")]
        (testing "the ambient global atom is flagged"
          (is (= '[as.core/cache] (mapv :form (get-in r [:findings :ambient-state])))
              (pr-str (:findings r))))
        (testing "the boundary bare throw is flagged"
          (is (= '[as.core/boom] (mapv :form (get-in r [:findings :bare-throw])))
              (pr-str (:findings r)))))
      (finally (api/close! sess)))))
