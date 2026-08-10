(ns slopp.lab.verdicts-test
  "Cover for a number that authorizes a bypass.

  The reuse rate is read once, by a human, to decide whether to build a cache
  that makes some verifications not run. So the failure that matters is not
  \"the arithmetic is off\" — it is the number lying in the direction that
  argues FOR building, which is the direction nobody double-checks because it
  agrees with wanting the feature.

  Two shapes do that, and both are pinned here: crediting content that only a
  RED run has covered, and computing a fraction over the rows that happen to
  carry a closure key while every row without one quietly leaves the
  population. The second is the more dangerous, because today every recorded
  observation predates the key — so the honest answer is 'not measurable yet',
  and the shape that hides it looks like a confident small percentage."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.lab.verdicts :as verdicts]))

(deftest the-reuse-rate-counts-only-what-it-can-actually-see
  ;; `verdict-cache.md` has one gate: what fraction of scheduled external tests
  ;; had an UNCHANGED closure hash since their last green. Above ~40% at
  ;; done-grain, build the cache; below, do not. So this number decides whether
  ;; a load-bearing verification path gets a bypass, and the two ways it can
  ;; lie both argue for building.
  (let [obs (fn [store scope status closure]
              (store/record-observation store scope
                                        {:tier :external :status status
                                         :ran 1 :failures []}
                                        closure))
        rate verdicts/reuse-rate]
    (testing "an empty journal is not a zero rate — there is no fraction of
              nothing, and a 0.0 here reads as 'measured, and the answer is no'"
      (let [r (rate (store/empty-store))]
        (is (zero? (:namespace-runs r)) (pr-str r))
        (is (nil? (:fraction r)) (pr-str r))))
    (testing "the same content observed green twice: the second run is the
              waste a cache would have removed"
      (let [st (-> (store/empty-store)
                   (obs '[a-test] :green '{a-test "H1"})
                   (obs '[a-test] :green '{a-test "H1"}))
            r  (rate st)]
        (is (= 2 (:namespace-runs r)) (pr-str r))
        (is (= 1 (:already-green r)) (pr-str r))
        (is (= 0.5 (:fraction r)) (pr-str r))))
    (testing "content that CHANGED between the runs is not waste — this is the
              half that keeps the number from being a count of observations"
      (let [st (-> (store/empty-store)
                   (obs '[a-test] :green '{a-test "H1"})
                   (obs '[a-test] :green '{a-test "H2"}))]
        (is (= 0 (:already-green (rate st))) (pr-str (rate st)))))
    (testing "a RED run does not make its content green: a later run at the
              same hash is not reuse, it is the retry that finds the fix"
      (let [st (-> (store/empty-store)
                   (obs '[a-test] :red '{a-test "H1"})
                   (obs '[a-test] :green '{a-test "H1"}))]
        (is (= 0 (:already-green (rate st))) (pr-str (rate st)))))
    (testing "observations recorded before the closure key existed are COUNTED
              as unusable, never silently dropped — a fraction computed over
              the few rows that happen to carry the key reads exactly like a
              fraction computed over all of them"
      (let [st (-> (store/empty-store)
                   (store/record-observation '[a-test] {:tier :external :status :green})
                   (obs '[a-test] :green '{a-test "H1"})
                   (obs '[a-test] :green '{a-test "H1"}))
            r  (rate st)]
        (is (= 3 (:observations r)) (pr-str r))
        (is (= 1 (:without-closure r)) (pr-str r))
        (is (= 2 (:namespace-runs r)) (pr-str r))))
    (testing "a FIRST SIGHTING cannot be a hit, so a journal made only of them
              yields 0.0 by construction — which reads exactly like 'measured,
              and there is no waste to remove'. Observed for real the first time
              this ran on the store: 108 namespace-runs, all first sightings,
              :fraction 0.0."
      (let [st (obs (store/empty-store) '[a-test b-test] :green '{a-test "H1" b-test "H2"})
            r  (rate st)]
        (is (= 2 (:namespace-runs r)) (pr-str r))
        (is (= 2 (:first-sighting r)) (pr-str r))
        (is (nil? (:fraction r))
            (str "nothing was comparable, so there is no rate: " (pr-str r)))))
    (testing "once ONE pair is comparable the fraction is real again, and it is
              over every run rather than only the comparable ones — a test never
              seen before genuinely is not reusable"
      (let [st (-> (store/empty-store)
                   (obs '[a-test] :green '{a-test "H1"})
                   (obs '[a-test b-test] :green '{a-test "H1" b-test "H2"}))
            r  (rate st)]
        (is (= 3 (:namespace-runs r)) (pr-str r))
        (is (= 2 (:first-sighting r)) (pr-str r))
        (is (= 1 (:already-green r)) (pr-str r))
        (is (= (double (/ 1 3)) (:fraction r)) (pr-str r))))
    (testing "each namespace in a run's scope is its own question — a suite
              sweep is 100 chances to reuse, not one"
      (let [st (-> (store/empty-store)
                   (obs '[a-test b-test] :green '{a-test "H1" b-test "H2"})
                   (obs '[a-test b-test] :green '{a-test "H1" b-test "H9"}))
            r  (rate st)]
        (is (= 4 (:namespace-runs r)) (pr-str r))
        (is (= 1 (:already-green r)) (pr-str r))))))
