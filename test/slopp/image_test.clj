(ns slopp.image-test
  "Tests for the store→image loading door.

  Everything here needs a REAL subprocess, so the tests are few and
  `^:external` by nature: what is worth checking is that a load actually
  reached a JVM, which no fake can tell you. They cover the two properties
  the rest of the system trusts blindly — that a store namespace becomes
  loadable despite having no classpath presence, and that an image's currency
  record describes THAT image and no other."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.image :as image]
            [slopp.image.currency :as image.currency]
            [slopp.image.repl :as repl]))

(def target
  (str "(ns demo\n"
       "  (:require [clojure.test :refer [deftest is]]))\n\n"
       "(defn add [x y] (+ x y))\n\n"
       "(deftest add-works (is (= 5 (add 2 3))))\n"))

(deftest ^:external load-and-test-run
  (let [s (store/ingest (store/empty-store) 'demo target)
        h (repl/start!)]
    (try
      (image/load-ns! h s 'demo)
      (testing "the namespace lives in the image (loaded from the store, no disk)"
        (is (= [5] (repl/eval! h "(demo/add 2 3)"))))
      (testing "test.run reports green"
        (let [r (image/test-run h 'demo)]
          (is (= 1 (:pass r)))
          (is (= 0 (:fail r)))
          (is (= 0 (:error r)))
          (testing "and the result is recorded on a delta (provenance, C4)"
            (let [s2 (store/record-verification s 'demo r)
                  d  (last (store/deltas s2))]
              (is (= :verify (:op d)))
              (is (= 'demo (:ns d)))
              (is (= r (:result d)))))))
      (finally (repl/stop! h)))))

(deftest ^:external a-second-image-loads-without-claiming-the-oracle-holds-it
  ;; This used to pin a WORKAROUND. `currency/stamps` was one global atom, so
  ;; `webdev.live`'s second image had to be loaded through `load-ns-into!` —
  ;; a door that deliberately did not stamp — or its loads would be filed as
  ;; the oracle's and every currency surface would report forms as current in
  ;; a process that never saw them.
  ;;
  ;; The record is now the image's own, so there is one loader and the
  ;; property holds by construction rather than by choosing a door. Same
  ;; guarantee, asserted where it can no longer be got wrong: TWO real images,
  ;; both stamped through the same function, neither able to see the other.
  (let [s   (-> (store/empty-store)
                (store/ingest 'zz.app "(ns zz.app)\n(defn hello \"H.\" [] :hi)\n")
                (store/ingest 'zz.user
                              (str "(ns zz.user (:require [zz.app :as a]))\n"
                                   "(defn go \"G.\" [] (a/hello))\n")))
        a   (repl/start!)
        b   (repl/start!)]
    (try
      (testing "it is a real load, not a no-op that happens not to stamp"
        (is (nil? (image/load-ns! a s 'zz.app)))
        ;; the dependent could not compile if zz.app were absent — this is
        ;; also what proves the *loaded-libs* marking happened, since a store
        ;; namespace has no classpath presence to fall back on
        (is (nil? (image/load-ns! a s 'zz.user))))
      (image.currency/arm! a)
      (image.currency/arm! b)
      (testing "a holds what it loaded"
        (is (= 4 (count (image.currency/snapshot a)))
            "two namespaces, two forms each — ns form and defn"))
      (testing "and b, which loaded nothing, says so rather than inheriting it"
        ;; armed and empty is the POSITIVE claim, and it is the one the global
        ;; could never make about a second image
        (is (= {} (image.currency/snapshot b)))
        (doseq [e (concat (store/elements s 'zz.app) (store/elements s 'zz.user))]
          (is (nil? (image.currency/stamped b (:id e))) (str (:id e)))))
      (finally (repl/stop! a) (repl/stop! b)))))
