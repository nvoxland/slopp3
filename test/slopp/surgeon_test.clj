(ns slopp.surgeon-test
  "clj-surgeon-inspired structural ops, slopp-grade: gated, verified,
  recorded. query_deps / fix_declares / ns_rename / edit_move_forms."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.api :as api]))

(def core-src
  (str "(ns sg.core (:require [clojure.test :refer [deftest is]]))\n"
       "(defn leaf [x] (* 2 x))\n"
       "(defn mid [x] (leaf x))\n"
       "(defn top [x] (mid (mid x)))\n"
       "(deftest top-t (is (= 8 (top 2))))\n"))

(def util-src
  (str "(ns sg.util (:require [sg.core :as c]\n"
       "                      [clojure.test :refer [deftest is]]))\n"
       "(defn wrap [x] (c/top x))\n"
       "(deftest wrap-t (is (= 8 (wrap 2))))\n"))

(deftest ^:isolated query-deps-transitive-callee-tree
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'sg.core core-src)
      (api/module-dep! sess "sg.util" "sg.core" :prompt "fixture edge")
      (api/ingest! sess 'sg.util util-src)
      (let [d (api/query-deps sess 'sg.util 'wrap)]
        (is (= 'sg.util/wrap (:root d)))
        (is (= ['sg.core/top] (get (:calls d) 'sg.util/wrap)))
        (is (= ['sg.core/mid] (get (:calls d) 'sg.core/top)))
        (is (= ['sg.core/leaf] (get (:calls d) 'sg.core/mid)))
        (is (= [] (get (:calls d) 'sg.core/leaf))))
      (finally (api/close! sess)))))

(deftest ^:isolated fix-declares-moves-and-deletes
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'fd.core
                   (str "(ns fd.core (:require [clojure.test :refer [deftest is]]))\n"
                        "(declare helper)\n"
                        "(defn caller [x] (helper x))\n"
                        "(defn helper [x] (+ x 1))\n"
                        "(deftest caller-t (is (= 3 (caller 2))))\n"))
      (let [r (api/fix-declares! sess 'fd.core :agent "tidier")]
        (is (= 1 (:removed r)) (pr-str r))
        (is (zero? (+ (:fail (:test r)) (:error (:test r)))))
        (let [src (api/query-source sess 'fd.core)]
          (is (not (re-find #"declare" src)))
          (is (< (.indexOf src "defn helper") (.indexOf src "defn caller")))))
      (testing "mutual recursion: the hand-written declare MIGRATES to a pipeline-owned marked one"
        (api/ingest! sess 'fd.rec
                     (str "(ns fd.rec)\n"
                          "(declare odd-x)\n"
                          "(defn even-x [n] (if (zero? n) true (odd-x (dec n))))\n"
                          "(defn odd-x [n] (if (zero? n) false (even-x (dec n))))\n"))
        (let [r (api/fix-declares! sess 'fd.rec)]
          (is (nil? (:error r)) (pr-str r))
          (let [src (api/query-source sess 'fd.rec)]
            (is (re-find #"\(declare" src) "a real cycle still needs a declare")
            (is (re-find #":auto-declare" src)
                "but it is the PIPELINE's now, and it says why"))))
      (finally (api/close! sess)))))

(deftest ^:isolated ns-rename-rewrites-the-world
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'sg.core core-src)
      (api/module-dep! sess "sg.util" "sg.core" :prompt "fixture edge")
      (api/ingest! sess 'sg.util util-src)
      ;; a fully-qualified reference too
      (api/add-form! sess 'sg.util "(defn fq [x] (sg.core/leaf x))")
      (let [r (api/ns-rename! sess 'sg.core 'sg.central :agent "renamer")]
        (is (nil? (:error r)) (pr-str r))
        (is (zero? (+ (:fail (:test r)) (:error (:test r)))))
        (testing "old namespace is GONE, new one answers"
          (is (nil? (get-in @sess [:store :namespaces 'sg.core])))
          (is (= [8] (api/query-eval sess "(sg.central/top 2)"))))
        (testing "requires and FQ refs across the store were rewritten"
          (let [u (api/query-source sess 'sg.util)]
            (is (re-find #"\[sg\.central :as c\]" u))
            (is (re-find #"sg\.central/leaf" u))
            (is (not (re-find #"sg\.core" u)))))
        (testing "still verified end-to-end in the image"
          (is (= [8] (api/query-eval sess "(sg.util/wrap 2)")))))
      (finally (api/close! sess)))))

(deftest ^:isolated extract-forms-to-a-new-namespace
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'sg.core core-src)
      (testing "guard: moved forms may not call what stays behind"
        (let [r (api/move-forms! sess 'sg.core '[top] 'sg.top)]
          (is (re-find #"mid" (:error r)))))
      (let [r (api/move-forms! sess 'sg.core '[leaf mid] 'sg.calc
                               :prompt "split the pure core" :agent "alice")]
        (is (nil? (:error r)) (pr-str r))
        (is (zero? (+ (:fail (:test r)) (:error (:test r)))))
        (testing "the new namespace holds the moved forms"
          (let [src (api/query-source sess 'sg.calc)]
            (is (re-find #"defn leaf" src))
            (is (re-find #"defn mid" src))))
        (testing "the source ns requires the new one; callers are REWRITTEN"
          (let [src (api/query-source sess 'sg.core)]
            (is (re-find #"\[sg\.calc :as calc\]" src))
            (is (re-find #"calc/mid" src))
            (is (not (re-find #"defn leaf" src)))))
        (testing "behavior intact in the live image"
          (is (= [8] (api/query-eval sess "(sg.core/top 2)")))))
      (finally (api/close! sess)))))
(deftest ^:isolated extract-into-a-deep-child-namespace
  ;; the slopp.api split shape: internal helpers move into a PACKAGE-PRIVATE
  ;; deep child ns, and the parent requires its own child back. Regression
  ;; for the live FileNotFound (the new store-only ns must be loadable when
  ;; the parent's ns form re-evaluates).
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'xr.core
                   (str "(ns xr.core)\n\n"
                        "(def ^:private factor \"F.\" 2)\n\n"
                        "(defn- helper-a \"A.\" [x] (inc x))\n\n"
                        "(defn- helper-b \"B.\" [x] (* factor (helper-a x)))\n\n"
                        "(defn entry \"E.\" [x] (+ factor (helper-b x)))\n"))
      (let [r (api/move-forms! sess 'xr.core '[factor helper-a helper-b] 'xr.core.impl
                               :prompt "package-private helpers" :agent "alice")]
        (is (nil? (:error r)) (pr-str r))
        (testing "the parent requires the deep child; callers rewritten"
          (let [src (api/query-source sess 'xr.core)]
            (is (re-find #"\[xr\.core\.impl :as impl\]" src))
            (is (re-find #"impl/helper-b" src))))
        (testing "behavior intact in the live image"
          (is (= [8] (api/query-eval sess "(xr.core/entry 2)"))))
        (testing "the deep boundary holds: a foreign module can't reach impl"
          (let [w (api/ingest! sess 'zz.probe
                               (str "(ns zz.probe (:require [xr.core.impl :as i]))\n\n"
                                    "(defn steal \"S.\" [x] (i/helper-a x))\n"))]
            (is (:error w) (pr-str w))
            (is (re-find #"package-private" (str (:error w)))))))
      (finally (api/close! sess)))))
(deftest ^:isolated move-rewrites-callers-everywhere
  ;; THE v1 gap: in a tested codebase everything has external references, so
  ;; no real cluster was movable. v2 rewrites every caller — production AND
  ;; tests — injects requires, and the export dial covers deep targets.
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'mvx.core
                   (str "(ns mvx.core)\n\n"
                        "(defn util \"U.\" [x] (inc x))\n\n"
                        "(defn entry \"E.\" [x] (util x))\n"))
      (api/module-dep! sess "mvx.app" "mvx.core" :prompt "consumer")
      (api/ingest! sess 'mvx.app
                   (str "(ns mvx.app (:require [mvx.core :as core]))\n\n"
                        "(defn go \"G.\" [x] (core/util x))\n"))
      (api/ingest! sess 'mvx.core-test
                   (str "(ns mvx.core-test (:require [mvx.core :as core]\n"
                        "                            [clojure.test :refer [deftest is]]))\n\n"
                        "(deftest util-t (is (= 3 (core/util 2))))\n"))
      (testing "a deep target with foreign callers refuses, teaching the dial"
        (let [r (api/move-forms! sess 'mvx.core '[util] 'mvx.core.util)]
          (is (re-find #"export" (str (:error r))) (pr-str r))))
      (let [r (api/move-forms! sess 'mvx.core '[util] 'mvx.core.util
                               :export true :prompt "deep home")]
        (is (nil? (:error r)) (pr-str r))
        (is (= '[mvx.app mvx.core mvx.core-test] (:callers r)) (pr-str r))
        (testing "every caller rewritten, requires injected"
          (is (re-find #"util/util" (api/query-source sess 'mvx.app)))
          (is (re-find #"\[mvx\.core\.util :as util\]"
                       (api/query-source sess 'mvx.core-test))))
        (testing "behavior lives at the new address"
          (is (= [4] (api/query-eval sess "(mvx.app/go 3)")))
          (is (= [3] (api/query-eval sess "(mvx.core/entry 2)")))))
      (finally (api/close! sess)))))
(deftest ^:isolated move-into-an-existing-namespace
  ;; consolidation: the target already exists — moved forms append, the
  ;; stay-behind caller is rewritten, only missing requires are added.
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'mve.a
                   (str "(ns mve.a)\n\n"
                        "(defn f \"F.\" [x] (* 2 x))\n\n"
                        "(defn g \"G.\" [x] (f x))\n"))
      (api/ingest! sess 'mve.b "(ns mve.b)\n\n(defn spare \"S.\" [x] x)\n")
      (api/module-dep! sess "mve.a" "mve.b" :prompt "f is moving to b")
      (let [r (api/move-forms! sess 'mve.a '[f] 'mve.b :prompt "consolidate")]
        (is (nil? (:error r)) (pr-str r))
        (testing "appended to the existing target, caller rewritten"
          (is (re-find #"defn f" (api/query-source sess 'mve.b)))
          (is (re-find #"defn spare" (api/query-source sess 'mve.b))
              "existing content untouched")
          (is (re-find #"b/f" (api/query-source sess 'mve.a))))
        (testing "behavior intact"
          (is (= [6] (api/query-eval sess "(mve.a/g 3)")))))
      (finally (api/close! sess)))))
(deftest ^:isolated fix-declares-prunes-phantom-names
  ;; a declare naming a var NOT defined in this ns (moved away by an earlier
  ;; move-forms) is a PHANTOM: it mints an unbound var, so a typo'd unqualified
  ;; call resolves silently instead of failing loudly. It must never BLOCK
  ;; cleanup of the declare around it.
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'ph.core
                   (str "(ns ph.core)\n"
                        "(declare helper gone-away)\n"
                        "(defn caller [x] (helper x))\n"
                        "(defn helper [x] (inc x))\n"))
      (let [r (api/fix-declares! sess 'ph.core :agent "t")]
        (is (nil? (:error r)) (pr-str r))
        (let [src (api/query-source sess 'ph.core)]
          (is (not (re-find #"gone-away" src)) "the phantom name is gone")
          (is (not (re-find #"\(declare" src))
              "helper was reorderable, so the whole declare goes")))
      (finally (api/close! sess)))))
