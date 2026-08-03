(ns slopp.api.reads-test
  "The store browser through the PORTLESS pipeline: route → policy →
  declared reads → handler, against an in-memory fixture store. The
  escaping assertion is a SECURITY test — the browser renders arbitrary
  store source."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.api.reads :as pages]))

(deftest a-defs-value-is-not-its-docstring
  ;; Pattern 1, alive in the shipped UI. `form-doc` read `(nth sx 2 nil)` and
  ;; accepted any string it found — but index 2 of a `def` is the VALUE when
  ;; there is no docstring, so `(def greeting "hello")` rendered "hello" as the
  ;; form's documentation on the reviewer page.
  ;;
  ;; The failure is silent by construction: a wrong index does not throw, it
  ;; yields something plausible. `store/form-docstring` exists to ask whether a
  ;; docstring can LEGALLY be there rather than whether index 2 happens to hold
  ;; a string, and this is the second site caught not using it.
  (let [st  (store/ingest (store/empty-store) 'fd.core
                          (str "(ns fd.core)\n"
                               "(def greeting \"hello\")\n"
                               "(def ^:ambient-ok counter \"How many.\" (atom 0))\n"
                               "(defn f \"F does a thing.\" [x] x)\n"
                               "(defn g [x] x)\n"))
        doc (fn [nm] (#'pages/form-doc (store/form-named st 'fd.core nm)))]
    (testing "a def with a string VALUE and no docstring has no docstring"
      (is (nil? (doc 'greeting))))
    (testing "a def that really is documented still reports it"
      (is (= "How many." (doc 'counter))))
    (testing "a documented defn reports its docstring"
      (is (= "F does a thing." (doc 'f))))
    (testing "an undocumented defn reports none"
      (is (nil? (doc 'g))))))
