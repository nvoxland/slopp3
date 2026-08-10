(ns slopp.lab.verdicts
  "Does the external tier repeat work a content-keyed cache could skip?

  An INSTRUMENT, run by hand, and it exists to answer one question that has
  been open since 2026-07-22: a verdict cache is designed, and the argument
  against building it was never that the design is wrong — it is that nobody
  could say how much waste there is to remove. A cache keyed by content is
  sound in principle and a HIT runs nothing, so a wrong one persists a false
  green until the content changes. That is a bad trade for an unmeasured win,
  and the cheap levers on this tier have a history of measuring zero: a warm
  image pool was built end to end and reverted at no gain, and raising the
  shard count shipped a 44% regression.

  What was missing was the record. A verdict could not be keyed to anything,
  so the waste could not be counted. The `:observe` delta now carries the
  content each run observed, which makes the question a replay over the
  journal rather than a study to be commissioned — and the number it produces
  is allowed to say no.

  Nothing here decides anything by itself. It reports; a human reads it
  against the threshold and chooses.")

(defn reuse-rate
  "Replay the journal's `:observe` deltas and report how much of the external
  tier's work re-verified content that was ALREADY green at exactly that
  content — the measurement the verdict cache is gated on.

  Returns `{:observations :without-closure :namespace-runs :first-sighting
  :already-green :fraction :first :last}`. `:namespace-runs` is the
  population: one per (observation, namespace-in-its-scope) pair that carries
  a closure hash, so a suite sweep over a hundred namespaces is a hundred
  chances to reuse rather than one. `:already-green` counts the pairs whose
  hash a PRIOR green observation had already covered — the runs a
  content-keyed cache would have skipped.

  Three rules keep the number from arguing for a cache that is not warranted,
  and all three matter because both ways this can lie argue FOR building:

  - Only a GREEN observation contributes content, so a red run followed by a
    re-run at the same hash is the retry that finds the fix, not reuse.
  - An observation with no closure key counts in `:without-closure` instead of
    vanishing. Every observation recorded before the key existed lacks one,
    and a fraction computed over the handful that carry it looks exactly like
    a fraction computed over all of them.
  - A pair whose namespace has never been observed green before is a FIRST
    SIGHTING and cannot possibly be a hit. A journal made only of those yields
    0.0 by construction — which reads exactly like *measured, and there is no
    waste to remove*. So `:fraction` is nil when nothing was comparable, the
    same way it is nil for an empty journal: there is no rate, rather than a
    rate of none. Observed for real the first time this ran on this store —
    108 namespace-runs, every one a first sighting.

  Read the result against the threshold in the idea file rather than as a
  target: the point of the number is that it can say NO, and the cheap levers
  on this tier have measured zero before — one of them shipped a 44%
  regression."
  [store]
  (let [obs (filter #(= :observe (:op %)) (:deltas store))]
    (loop [[o & more] obs, green {}, runs 0, hits 0, firsts 0, blind 0]
      (if-not o
        (let [comparable (- runs firsts)]
          {:observations    (count obs)
           :without-closure blind
           :namespace-runs  runs
           :first-sighting  firsts
           :already-green   hits
           :fraction        (when (pos? comparable) (double (/ hits runs)))
           :first           (:id (first obs))
           :last            (:id (last obs))})
        (let [cl    (:closure o)
              pairs (for [n (:scope o) :let [h (get cl n)] :when h] [n h])]
          (recur more
                 (if (= :green (get-in o [:result :status]))
                   (reduce (fn [m [n h]] (update m n (fnil conj #{}) h)) green pairs)
                   green)
                 (+ runs (count pairs))
                 (+ hits (count (filter (fn [[n h]] (contains? (get green n) h)) pairs)))
                 (+ firsts (count (remove (fn [[n _]] (contains? green n)) pairs)))
                 (cond-> blind (empty? cl) inc)))))))
