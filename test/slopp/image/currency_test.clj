(ns slopp.image.currency-test
  "Does the registry keep an honest record of what the image was given?

  Small surface, and the whole comparison rests on it: if a stamp is missing
  the form reads as never-loaded forever, and if a stamp outlives its image it
  claims code is present that is not. So the tests care about exactly three
  things — that absence stays absent rather than defaulting to clean, that the
  order counter really is monotonic (it is the only thing that can see a value
  captured before its input moved), and that forgetting actually forgets.

  The analysis over these stamps lives in `slopp.api.currency-test`."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.image.currency :as currency]))

(deftest stamping-records-what-the-image-loaded
  (currency/forget-all!)
  (testing "an unstamped form is simply absent — never a false clean bill"
    (is (nil? (currency/stamped "f1"))))

  (testing "a stamp carries the source hash and its evaluation order"
    (currency/stamp! "f1" "(def a 1)")
    (currency/stamp! "f2" "(def b a)")
    (let [a (currency/stamped "f1")
          b (currency/stamped "f2")]
      (is (= (currency/hash-of "(def a 1)") (:hash a)))
      (is (< (:seq a) (:seq b))
          "order is what catches a value captured from a form re-evaluated later")))

  (testing "re-stamping moves the form to the FRONT of the order"
    ;; this is the whole mechanism for the derived-stale class: `b` captured
    ;; `a`'s value at load, so re-evaluating `a` alone leaves `b` behind even
    ;; though b's own source never changed.
    (let [before (:seq (currency/stamped "f2"))]
      (currency/stamp! "f1" "(def a 2)")
      (is (> (:seq (currency/stamped "f1")) before))
      (is (= (currency/hash-of "(def a 2)") (:hash (currency/stamped "f1"))))))

  (testing "forgetting a namespace's forms drops exactly those"
    (currency/stamp! "f3" "(def c 3)")
    (currency/forget! ["f1" "f2"])
    (is (nil? (currency/stamped "f1")))
    (is (nil? (currency/stamped "f2")))
    (is (some? (currency/stamped "f3"))))

  (testing "a fresh image forgets everything — a stale stamp would be worse than none"
    (currency/forget-all!)
    (is (nil? (currency/stamped "f3")))))

(deftest an-unarmed-registry-says-it-has-not-looked
  ;; the registry's own instance of the rule it enforces: no record and a
  ;; record of nothing must not share a representation. A registry born
  ;; mid-session holds a handful of forms out of thousands, and comparing THAT
  ;; against the store would report the whole codebase as never-loaded.
  (currency/forget-all!)
  (currency/stamp! "f1" "(def a 1)")
  (is (nil? (currency/snapshot))
      "one hot-loaded form must not arm a registry holding nothing else")

  (currency/arm!)
  (is (= 1 (count (currency/snapshot)))
      "armed, the same registry is a positive claim about what is loaded")

  (currency/forget-all!)
  (is (nil? (currency/snapshot))
      "a fresh image is UNMEASURED again — not measured-and-empty"))
