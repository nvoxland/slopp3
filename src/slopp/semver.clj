(ns slopp.semver
  "Tiny mvn-version utility: parse a version string into its numeric segments
  and compare them NUMERICALLY (so 1.10.0 > 1.2.0, unlike a lexical string
  compare). Used by the deps-manifest merge to auto-resolve version divergence
  to the newer coord. Built fresh, red-first, during self-host dogfooding.")

(defn parse
  "Parse an mvn version string into a vector of its numeric segments, ignoring
  qualifiers: \"1.10.0-SNAPSHOT\" => [1 10 0]."
  [s]
  (mapv parse-long (re-seq #"\d+" s)))

(defn newer?
  "True if version string `a` is strictly newer than `b`, comparing numeric
  segments (so 1.10.0 > 1.2.0, unlike a lexical compare). (Edited directly on GitHub.)"
  [a b]
  (pos? (compare (parse a) (parse b))))
(defn older?
  "True if version string `a` is strictly older than `b` — newer? flipped."
  [a b]
  (newer? b a))
