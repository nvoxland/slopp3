(ns slopp.bench-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.bench :as bench]))

(def sample
  (str "(ns sample)\n\n"
       "(defn helper [x] (* x 2))\n\n"
       "(defn area [w h] (* w h))\n"
       "(defn perimeter [w h] (* 2 (+ w h)))\n"
       "(defn scale [x] (+ 1 (helper x)))\n"          ; ref 1
       "(defn shrink [x] (- (helper x) 3))\n"         ; ref 2
       "(defn clamp [x lo hi] (max lo (min hi x)))\n"
       "(defn lerp [a b t] (+ a (* t (- b a))))\n"
       "(defn sq [x] (* x x))\n"
       "(defn cube [x] (* x x x))\n"
       "(defn avg [xs] (/ (reduce + xs) (count xs)))\n"
       "(defn normalize [x] (helper (sq x)))\n"       ; ref 3
       "(defn describe [x] (str \"value=\" x))\n"
       "(defn positive? [x] (pos? x))\n"
       "(defn negative? [x] (neg? x))\n"
       "(defn between? [x lo hi] (and (<= lo x) (<= x hi)))\n"
       "(defn midpoint [a b] (/ (+ a b) 2))\n"
       "(defn distance [a b] (abs (- a b)))\n"
       "(defn safe-div [a b] (if (zero? b) 0 (/ a b)))\n"))

(deftest reference-query-is-cheaper-than-grep-read
  (let [sess (atom {:store (store/ingest (store/empty-store) 'sample sample)})
        c    (bench/reference-query-cost sess 'sample 'helper)]
    (testing "slopp's targeted answer is a fraction of reading the whole file"
      (is (< (:slopp-chars c) (:read-file-chars c)))
      (is (< (:ratio-vs-read c) 0.5)))
    (println "\n[bench] query 'where is helper referenced + its def' in ns sample:")
    (println (format "  slopp query.* payload : %4d chars (~%d tokens)"
                     (:slopp-chars c) (quot (:slopp-chars c) 4)))
    (println (format "  grep matching lines   : %4d chars" (:grep-lines-chars c)))
    (println (format "  read whole file       : %4d chars (~%d tokens)"
                     (:read-file-chars c) (quot (:read-file-chars c) 4)))
    (println (format "  slopp / read-file     : %.2f" (:ratio-vs-read c)))))
