(ns slopp.lang-test
  "`slopp.lang` — the portable standard library the single-dialect rule
  requires."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.lang :as lang]))

(deftest percent-decoding-is-portable-and-does-not-throw-on-garbage
  ;; D3.1: slopp denies reader conditionals, so a platform call an author
  ;; would have branched on lives here instead — written once, in code that
  ;; touches no platform API at all. slopp-ui paid ~35 lines of this bit math
  ;; in an application, verified on one platform of the two it shipped to.
  ;;
  ;; Portable means something specific and narrow here: `count`, `nth`, `=`,
  ;; map lookup keyed by whatever `nth` yields, `char`, `str`, and integer
  ;; arithmetic. Nothing that asks what a character IS — that is the exact
  ;; question whose answer differs, and the reason the hand-rolled version
  ;; could only be reasoned about rather than measured.
  (testing "the token shapes slopp names are actually made of"
    (is (= "!" (lang/decode-component "%21")))
    (is (= "->>" (lang/decode-component "-%3E%3E")))
    (is (= "swap!" (lang/decode-component "swap%21")))
    (is (= "a/b" (lang/decode-component "a%2Fb"))))

  (testing "a multi-byte character — the half a byte-at-a-time decoder gets wrong"
    (is (= "café" (lang/decode-component "caf%C3%A9")))
    (is (= "→" (lang/decode-component "%E2%86%92")))
    (is (= "日本" (lang/decode-component "%E6%97%A5%E6%9C%AC"))))

  (testing "a four-byte character needs a SURROGATE PAIR, not one char"
    ;; the case a decoder passes by accident until it meets an emoji
    (is (= "🙂" (lang/decode-component "%F0%9F%99%82"))))

  (testing "form encoding: + is a space, %2B is a plus"
    (is (= "kg zone" (lang/decode-component "kg+zone")))
    (is (= "a+b" (lang/decode-component "a%2Bb"))))

  (testing "garbage is returned, never thrown — this is text off a network"
    ;; a query string is arbitrary bytes from outside; the answer to malformed
    ;; input is a page, not a 500
    (is (= "100%" (lang/decode-component "100%")))
    (is (= "%zz" (lang/decode-component "%zz")))
    (is (= "%2" (lang/decode-component "%2")))
    (is (= "" (lang/decode-component "")))
    (is (= "" (lang/decode-component nil))))

  (testing "text with nothing to decode comes back identical"
    (is (= "slopp.index.refs/occurrences-of"
           (lang/decode-component "slopp.index.refs/occurrences-of")))))
