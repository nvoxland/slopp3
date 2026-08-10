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

(deftest percent-encoding-round-trips-through-the-decoder-beside-it
  ;; The other half of D3.1's bargain. `decode-component` has existed since
  ;; 2026-08-06 and there was no encoder, so slopp-ui built the same missing
  ;; thing TWICE in one week — a deliberately partial `query-escape`, and a
  ;; `url-parts` split that hands the real encoding to the browser. Two
  ;; workarounds for one gap is the count that decides these.
  ;;
  ;; What makes this better than either is not that it escapes more: it is
  ;; that it is proven against the decoder that will read it back. A pair that
  ;; round-trips is a pair; two functions written apart are two guesses.
  (let [round (fn [s] (lang/decode-component (lang/encode-component s)))]

    (testing "the bug that prompted it: a literal + in a name must not come
              back as a space"
      ;; `merge+` is an ordinary Clojure name, and + is how a form encoder
      ;; spells a space — so an unescaped one silently renames the thing
      (is (= "merge%2B" (lang/encode-component "merge+")))
      (is (= "merge+" (round "merge+"))))

    (testing "unreserved characters are left alone — an encoder that escapes
              what it need not makes every url unreadable"
      (is (= "slopp.index.refs-a_b~c"
             (lang/encode-component "slopp.index.refs-a_b~c"))))

    (testing "the token shapes slopp names are actually made of"
      (is (= "swap%21" (lang/encode-component "swap!")))
      (is (= "a%2Fb" (lang/encode-component "a/b")))
      (is (= "-%3E%3E" (lang/encode-component "->>"))))

    (testing "a space is %20, not +: %20 is right in a PATH as well as a query,
              and + in a path is a literal plus"
      (is (= "kg%20zone" (lang/encode-component "kg zone")))
      (is (= "kg zone" (round "kg zone"))))

    (testing "every character that ends a component or changes its meaning"
      (is (= "%3F%23%26%3D%25" (lang/encode-component "?#&=%"))))

    (testing "ROUND TRIP over the shapes the decoder makes a point of handling"
      (doseq [s ["" "plain" "a/b" "100%" "swap!" "->>" "kg zone" "merge+"
                 "café" "→" "日本" "🙂" "slopp.index.refs/occurrences-of"]]
        (is (= s (round s)) (str "round trip: " (pr-str s)))))

    (testing "nil is the empty string, matching the decoder"
      (is (= "" (lang/encode-component nil))))

    (testing "NON-ASCII PASSES THROUGH VERBATIM, and that is a stated limit
              rather than an oversight. Percent-encoding a character requires
              its CODE POINT, and asking what a character IS is the one
              question this namespace exists to avoid — D3 denies the reader
              conditional that would answer it per platform. Round trip still
              holds, because the decoder passes non-% text through; what a
              caller gives up is a wire form that is strictly RFC 3986."
      (is (= "café" (lang/encode-component "café")))
      (is (= "🙂" (lang/encode-component "🙂"))))))
