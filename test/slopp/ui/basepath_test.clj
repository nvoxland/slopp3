(ns slopp.ui.basepath-test
  "Two properties, both of which show up as a blank screen when they break:
  no base is the identity, and prefix-then-strip round-trips. Plus the
  segment-boundary case, where a bare `starts-with?` would hand one project's
  url to another project's router."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.ui.basepath :as bp]))

(deftest no-base-changes-nothing-which-is-the-case-that-must-never-regress
  ;; Every slopp app served directly runs with no prefix, so this is not an
  ;; edge case — it is the default, and a base of "" has to be the identity
  ;; through both directions or part 2 breaks part 1's own UI.
  (doseq [base [nil "" "/"]]
    (testing (str "base " (pr-str base))
      (is (= "/store" (bp/prefixed base "/store")))
      (is (= "/" (bp/prefixed base "/")))
      (is (= "/store" (bp/strip base "/store")))
      (is (= "/" (bp/strip base "/"))))))

(deftest a-prefix-round-trips-and-an-unknown-path-is-left-alone
  (testing "adding then removing is the identity — the server builds urls with
            prefixed and the browser takes them apart with strip, so a
            disagreement between the two is a blank screen"
    (doseq [p ["/" "/store" "/store/ns/slopp.ui.hub" "/change/d1..d2"]]
      (is (= p (bp/strip "/p/slopp2" (bp/prefixed "/p/slopp2" p))) p)))
  (testing "the project root keeps its trailing slash, because /p/slopp2/ is
            the href the picker emits"
    (is (= "/p/slopp2/" (bp/prefixed "/p/slopp2" "/")))
    (is (= "/" (bp/strip "/p/slopp2" "/p/slopp2/")))
    (is (= "/" (bp/strip "/p/slopp2" "/p/slopp2"))
        "the router must reach the timeline whether or not the slash survived"))
  (testing "a base written with a trailing slash means the same thing —
            it arrives over a header and nobody should have to normalise it"
    (is (= "/p/slopp2/store" (bp/prefixed "/p/slopp2/" "/store"))))
  (testing "a path OUTSIDE the base is returned untouched rather than mangled:
            it is not ours to rewrite, and a silent mangle is the failure that
            looks like a routing bug three layers away"
    (is (= "/other/thing" (bp/strip "/p/slopp2" "/other/thing")))
    (is (= "/p/slopp22/x" (bp/strip "/p/slopp2" "/p/slopp22/x"))
        "a prefix match must be on SEGMENTS — slopp22 is not slopp2")))
