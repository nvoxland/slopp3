(ns slopp.api.schema-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [slopp.api.schema :as schema]
            [slopp.store :as store]
            [slopp.repl :as repl] [slopp.api :as api]))

(deftest schema-of-reads-name-metadata
  (let [src (str "(ns app.bnd)\n\n"
                 "(defn ^{:malli/schema [:=> [:cat :int] :int]} inc1 \"D.\" [x] (inc x))\n\n"
                 "(defn plain \"D.\" [x] x)\n\n"
                 "(defn ^{:malli/schema [:map [:x :int]]} shaped \"D.\" [x] x)\n")
        s   (store/ingest (store/empty-store) 'app.bnd src)]
    (testing "the :=> schema on the name is read straight off the stored node"
      (is (= [:=> [:cat :int] :int] (schema/schema-of s 'app.bnd/inc1))))
    (testing "no schema → nil"
      (is (nil? (schema/schema-of s 'app.bnd/plain))))
    (testing "a non-:=> schema is not a candidate (mg/check needs to generate args)"
      (is (nil? (schema/schema-of s 'app.bnd/shaped))))))

(deftest schema-candidates-keeps-pure-schemad-only
  (let [src (str "(ns app.cand)\n\n"
                 "(defn ^{:malli/schema [:=> [:cat :int] :int]} pure1 \"D.\" [x] (inc x))\n\n"
                 "(defn ^{:malli/schema [:=> [:cat :any] :any]} bang! \"D.\" [a] (swap! a inc))\n\n"
                 "(defn plainpure \"D.\" [x] (dec x))\n")
        s (store/ingest (store/empty-store) 'app.cand src)
        cands (schema/schema-candidates s '#{app.cand/pure1 app.cand/bang! app.cand/plainpure})]
    (testing "exactly the pure, :=>-schemad form survives"
      (is (= [{:form 'app.cand/pure1 :schema [:=> [:cat :int] :int]}]
             (vec cands))))
    (testing "an effectful (mutation-reaching) form is excluded even with a schema"
      (is (not-any? #(= 'app.cand/bang! (:form %)) cands)))
    (testing "analyzer-pure? agrees on the mutation-reaching form"
      (is (false? (schema/analyzer-pure? s 'app.cand/bang!)))
      (is (true? (schema/analyzer-pure? s 'app.cand/pure1))))))

(deftest check-string-embeds-schema-var-and-check
  (let [s (schema/check-string [{:form 'app.b/f :schema [:=> [:cat :int] :int]}])]
    (testing "self-contained: resolves malli in the image, no server-side require"
      (is (str/includes? s "malli.generator/check")))
    (testing "embeds the var qsym and its schema EDN verbatim"
      (is (str/includes? s "app.b/f"))
      (is (str/includes? s "[:=> [:cat :int] :int]")))
    (testing "no candidates → a well-formed empty-vector expression"
      (let [empty-s (schema/check-string [])]
        (is (str/includes? empty-s "["))
        (is (not (str/includes? empty-s "check ")))))))

(deftest ^:external drift-flags-a-lying-schema
  (let [src (str "(ns demo.sch)\n\n"
                 "(defn ^{:malli/schema [:=> [:cat :int] :int]} honest \"D.\" [x] (inc x))\n\n"
                 "(defn ^{:malli/schema [:=> [:cat :int] :string]} liar \"D.\" [x] (inc x))\n")
        s (store/ingest (store/empty-store) 'demo.sch src)
        h (repl/start!)]
    (try
      (repl/eval! h "(ns demo.sch) (defn honest [x] (inc x)) (defn liar [x] (inc x)) (in-ns 'user)")
      (let [drift (schema/drift! h s '#{demo.sch/honest demo.sch/liar})]
        (testing "the lying schema (returns int, declares :string) is caught; honest is silent"
          (is (= '[demo.sch/liar] (mapv :form drift))))
        (testing "a shrunk counterexample rides the finding"
          (is (contains? (first drift) :counterexample))))
      (finally (repl/stop! h)))))

(deftest ^:external done-surfaces-schema-drift
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'sd.core "(ns sd.core)\n(defn seeded \"S.\" [x] x)\n")
      (api/done! sess :label "baseline")
      (testing "an honest :=> schema draws no drift finding"
        (api/add-form! sess 'sd.core
                       "(defn ^{:malli/schema [:=> [:cat :int] :int]} honest \"H.\" [x] (inc x))"
                       :prompt "honest schema")
        (let [r (api/done! sess :label "honest")]
          (is (nil? (get-in r [:findings :schema-drift])) (pr-str (:findings r)))))
      (testing "a lying :=> schema surfaces as :schema-drift and flips status red"
        (api/add-form! sess 'sd.core
                       "(defn ^{:malli/schema [:=> [:cat :int] :string]} liar \"L.\" [x] (inc x))"
                       :prompt "lying schema")
        (let [r (api/done! sess :label "lying")]
          (is (= '[sd.core/liar] (mapv :form (get-in r [:findings :schema-drift])))
              (pr-str (:findings r)))
          (is (= :red (get-in r [:findings :test-status])) (pr-str (:findings r)))))
      (finally (api/close! sess)))))

(deftest analyzer-pure-excludes-nondeterminism
  (let [src (str "(ns app.nd)\n\n"
                 "(defn ^{:malli/schema [:=> [:cat :int] :int]} roll \"D.\" [x] (+ x (rand-int 6)))\n\n"
                 "(defn ^{:malli/schema [:=> [:cat :int] :int]} pure1 \"D.\" [x] (inc x))\n")
        s (store/ingest (store/empty-store) 'app.nd src)]
    (testing "a non-deterministic fn is NOT analyzer-pure (unsafe to generatively call)"
      (is (false? (schema/analyzer-pure? s 'app.nd/roll)))
      (is (true?  (schema/analyzer-pure? s 'app.nd/pure1))))
    (testing "so it is excluded from schema-candidates — the oracle-check can't flake on it"
      (is (= [{:form 'app.nd/pure1 :schema [:=> [:cat :int] :int]}]
             (schema/schema-candidates s '#{app.nd/roll app.nd/pure1}))))))
