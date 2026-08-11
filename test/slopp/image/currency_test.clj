(ns slopp.image.currency-test
  "Does the registry keep an honest record of what the image was given?

  Small surface, and the whole comparison rests on it: if a stamp is missing
  the form reads as never-loaded forever, and if a stamp outlives its image it
  claims code is present that is not. So the tests care about exactly three
  things — that absence stays absent rather than defaulting to clean, that the
  order counter really is monotonic (it is the only thing that can see a value
  captured before its input moved), and that forgetting actually forgets.

  The analysis over these stamps lives in `slopp.rules.currency-test`."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.image.currency :as image.currency]))

(deftest stamping-records-what-the-image-loaded
  (let [img {:currency (image.currency/new-registry)}]
    (testing "an unstamped form is simply absent — never a false clean bill"
      (is (nil? (image.currency/stamped img "f1"))))

    (testing "a stamp carries the source hash and its evaluation order"
      (image.currency/stamp! img "f1" "(def a 1)")
      (image.currency/stamp! img "f2" "(def b a)")
      (let [a (image.currency/stamped img "f1")
            b (image.currency/stamped img "f2")]
        (is (= (image.currency/hash-of "(def a 1)") (:hash a)))
        (is (< (:seq a) (:seq b))
            "order is what catches a value captured from a form re-evaluated later")))

    (testing "re-stamping moves the form to the FRONT of the order"
      ;; this is the whole mechanism for the derived-stale class: `b` captured
      ;; `a`'s value at load, so re-evaluating `a` alone leaves `b` behind even
      ;; though b's own source never changed.
      (let [before (:seq (image.currency/stamped img "f2"))]
        (image.currency/stamp! img "f1" "(def a 2)")
        (is (> (:seq (image.currency/stamped img "f1")) before))
        (is (= (image.currency/hash-of "(def a 2)") (:hash (image.currency/stamped img "f1"))))))

    (testing "forgetting a namespace's forms drops exactly those"
      (image.currency/stamp! img "f3" "(def c 3)")
      (image.currency/forget! img ["f1" "f2"])
      (is (nil? (image.currency/stamped img "f1")))
      (is (nil? (image.currency/stamped img "f2")))
      (is (some? (image.currency/stamped img "f3"))))

    (testing "emptying is for a RECYCLED image — the one that keeps its handle"
      ;; `reset-to-baseline!` hands this same handle to the next tenant, so the
      ;; record must be emptied without the image dying. That is the only
      ;; caller; a genuinely new image is minted empty.
      (image.currency/forget-all! img)
      (is (nil? (image.currency/stamped img "f3")))
      (is (nil? (image.currency/snapshot img))
          "and unmeasured again, not measured-and-empty"))))

(deftest an-unarmed-registry-says-it-has-not-looked
  ;; the record's own instance of the rule it enforces: no record and a record
  ;; of nothing must not share a representation. A record born mid-session
  ;; holds a handful of forms out of thousands, and comparing THAT against the
  ;; store would report the whole codebase as never-loaded.
  (let [img {:currency (image.currency/new-registry)}]
    (image.currency/stamp! img "f1" "(def a 1)")
    (is (nil? (image.currency/snapshot img))
        "one hot-loaded form must not arm a record holding nothing else")

    (image.currency/arm! img)
    (is (= 1 (count (image.currency/snapshot img)))
        "armed, the same record is a positive claim about what is loaded")

    (testing "a fresh image is UNMEASURED again — not measured-and-empty"
      ;; and this needs no reset call: `repl/start!` mints the record, so a new
      ;; image cannot be carrying the previous one's answer
      (is (nil? (image.currency/snapshot {:currency (image.currency/new-registry)}))))

    (testing "and nil for no image at all, which means the same thing"
      (is (nil? (image.currency/snapshot nil))))))

(deftest a-record-belongs-to-the-image-it-describes
  ;; The registry answers "does THIS image hold this form's current source".
  ;; While it was one process-global atom that question had an implied subject
  ;; — the oracle — and slopp runs two images on purpose, so the subject had
  ;; to be enforced by every caller picking the right loader. Now the record
  ;; is the image's own and there is no shared place for two images to meet.
  (let [a {:currency (image.currency/new-registry)}
        b {:currency (image.currency/new-registry)}]
    (image.currency/stamp! a "f1" "(def x 1)")
    (image.currency/arm! a)
    (image.currency/arm! b)
    (is (some? (image.currency/stamped a "f1")))
    (is (nil? (image.currency/stamped b "f1"))
        "b never loaded it, and no door-picking is what keeps that true")
    (is (= {} (image.currency/snapshot b))
        "armed and empty is a POSITIVE claim: b looked and holds nothing")
    (testing "and the seq counters are independent, so neither can order the other"
      (image.currency/stamp! b "f9" "(def y 1)")
      (is (= 1 (:seq (image.currency/stamped b "f9")))
          "b's first stamp is its own first, not a continuation of a's")))
  (testing "a fresh registry has not looked, which is not the same as empty"
    (let [c {:currency (image.currency/new-registry)}]
      (is (nil? (image.currency/snapshot c)))
      (image.currency/arm! c)
      (is (= {} (image.currency/snapshot c))))))
