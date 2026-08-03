(ns slopp.image-test
  "Tests for the store→image loading door.

  Everything here needs a REAL subprocess, so the tests are few and
  `^:external` by nature: what is worth checking is that a load actually
  reached a JVM, which no fake can tell you. They cover the two properties
  the rest of the system trusts blindly — that a store namespace becomes
  loadable despite having no classpath presence, and that only the ORACLE's
  door stamps the currency registry."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.image :as image]
            [slopp.image.currency :as currency]
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
  ;; `currency/stamps` is ONE global atom, and `load-ns!` stamps into it
  ;; unconditionally. `webdev.live` boots a SECOND image on purpose — the
  ;; oracle is cycled by ordinary editing (fresh-image! is on the path of
  ;; edit-replace!, rename!, move-forms!, deps-add!, merge-into-session!) and
  ;; so cannot host a running app. Loading that image through the stamping
  ;; door would file the APP image's loads as the ORACLE's, and every
  ;; currency surface would then report forms as current in a process that
  ;; never saw them — the false green the registry exists to prevent,
  ;; measured against the wrong image.
  (let [s   (-> (store/empty-store)
                (store/ingest 'zz.app "(ns zz.app)\n(defn hello \"H.\" [] :hi)\n")
                (store/ingest 'zz.user
                              (str "(ns zz.user (:require [zz.app :as a]))\n"
                                   "(defn go \"G.\" [] (a/hello))\n")))
        img (repl/start!)]
    (try
      (currency/forget-all!)
      (currency/arm!)
      (let [before (currency/snapshot)]
        (testing "it is a real load, not a no-op that happens not to stamp"
          (is (nil? (image/load-ns-into! img s 'zz.app)))
          ;; the dependent could not compile if zz.app were absent — this is
          ;; also what proves the *loaded-libs* marking happened, since a
          ;; store namespace has no classpath presence to fall back on
          (is (nil? (image/load-ns-into! img s 'zz.user))))
        (testing "and the oracle's record of ITSELF did not move"
          (is (= before (currency/snapshot)))))
      (finally (repl/stop! img)))))
