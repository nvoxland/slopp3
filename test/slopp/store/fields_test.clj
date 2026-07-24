(ns slopp.store.fields-test
  "The registry's own shape-and-coherence guard — the tripwire that makes
  registering an op a CHECKED act. The merge round-trip harness generated
  from the same registry lives in slopp.merge-test."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.store.fields :as fields] [slopp.store.merge :as merge]))

(deftest the-registry-is-total
  (testing "every field-carrying op declares fold, merge strategy, sample, crossed"
    (doseq [[op e] fields/op-registry]
      (is (fn? (:fold e)) (str op " has no fold"))
      (is (#{:replay :bespoke} (:merge e)) (str op " has no merge strategy"))
      (is (map? (:sample e)) (str op " has no sample"))
      (is (= op (:op (:sample e))) (str op " sample op mismatch"))
      (is (fn? (:crossed e)) (str op " has no crossed assertion"))
      (is (contains? fields/field-registry (:field e))
          (str op " names an unregistered field"))))
  (testing "op classifications are disjoint"
    (is (empty? (set/intersection (set (keys fields/op-registry)) fields/markers)))
    (is (empty? (set/intersection (set (keys fields/op-registry)) fields/element-ops)))
    (is (empty? (set/intersection fields/markers fields/element-ops)))
    (is (set/subset? fields/silent-markers fields/markers)))
  (testing "every persisted field declares init + meta-key or table storage"
    (doseq [[field e] fields/field-registry]
      (is (contains? e :init) (str field))
      (is (or (:meta-key e) (= :table (:storage e))) (str field))))
  (testing "each fold satisfies its own sample — the harness inputs are real"
    (doseq [[op {:keys [sample sample-pre crossed]}] fields/op-registry]
      (let [st (reduce (fn [st p] (or (fields/fold st p) st))
                       (store/empty-store) sample-pre)]
        (is (crossed (fields/fold st sample)) (str op))))))

(deftest empty-store-seeds-every-registered-field
  ;; the nil-pun cleanup: :files/:config/:blobs used to be absent-until-first-
  ;; write, defaulted at each consumer — every field now starts at its :init
  (let [st (store/empty-store)]
    (doseq [[field {:keys [init]}] fields/field-registry]
      (is (contains? st field) (str field " missing from empty-store"))
      (is (= init (get st field)) (str field " seeded off-registry")))))

(defn- harness-base
  "A minimal shared-history base for the round-trip harness — one ingested
  namespace, so ours/theirs fork from real content."
  []
  (store/ingest (store/empty-store) 'h.core
                "(ns h.core)\n\n(defn ^:unused-ok a [x] x)\n"))

(defn- replay-payload
  "Append a raw delta PAYLOAD to `st` as a journal-true write: mint an id,
  replay through the one fold. An op replay-delta does not know (the
  refusal test's forged op) appends bare so the merge engine still sees it."
  [st payload]
  (let [[did st'] (store/gen-id st "d")
        d (merge {:id did :parent (:id (last (:deltas st'))) :at 0 :ns '*session*}
                 payload)]
    (or (store/replay-delta st' d)
        (update st' :deltas conj d))))

(deftest every-registered-op-crosses-a-merge
  ;; GENERATED from the registry: each field-carrying op's :sample lands on a
  ;; branch copy and :crossed must hold on the merged store. Registering an op
  ;; without merge semantics is therefore impossible — the class where merges
  ;; silently dropped config/file deltas for three waves cannot reopen.
  (doseq [[op {:keys [sample sample-pre crossed]}] fields/op-registry]
    (let [b      (reduce replay-payload (harness-base) sample-pre)
          theirs (replay-payload b sample)
          ours   (store/ingest b 'o.side "(ns o.side)\n(defn ^:unused-ok f [x] x)\n")
          r      (merge/merge-logs ours theirs)]
      (is (nil? (:error r)) (str op ": " (:error r)))
      (is (crossed (:store r)) (str op " did not cross the merge"))
      (is (not-any? #(= op (:skipped %)) (:notes r))
          (str op " was skipped: " (pr-str (:notes r)))))))

(deftest an-unregistered-op-refuses-the-merge
  ;; the silent-drop lesson made structural: an op the registry does not
  ;; know REFUSES the merge with teaching, never a quiet :skipped note
  (let [b      (harness-base)
        theirs (replay-payload b {:op :quux-op :payload "x"})
        ours   (store/ingest b 'o.side "(ns o.side)\n(defn ^:unused-ok f [x] x)\n")
        r      (merge/merge-logs ours theirs)]
    (is (some? (:error r)) (pr-str (dissoc r :store)))
    (is (re-find #"slopp\.store\.fields" (str (:error r))) (str (:error r)))))

(deftest canonical-coord-coerces-symbol-typed-fields
  (testing ":exclusions arrive as strings over JSON and must be stored as symbols"
    (is (= {:mvn/version "1.0" :exclusions ['com.yahoo.platform.yui/yuicompressor]}
           (fields/canonical-coord
            {:mvn/version "1.0" :exclusions ["com.yahoo.platform.yui/yuicompressor"]}))))
  (testing "already-symbol exclusions are left alone (idempotent)"
    (is (= {:exclusions ['a/b]} (fields/canonical-coord {:exclusions ['a/b]}))))
  (testing "a mixed vector coerces only what needs it"
    (is (= {:exclusions ['a/b 'c/d]} (fields/canonical-coord {:exclusions ["a/b" 'c/d]}))))
  (testing "a coord with no symbol-typed field is untouched"
    (is (= {:mvn/version "1.0"} (fields/canonical-coord {:mvn/version "1.0"}))))
  (testing "total on junk — the fold/replay path must never throw here"
    (is (= {:exclusions []} (fields/canonical-coord {:exclusions []})))
    (is (= {} (fields/canonical-coord {})))))
