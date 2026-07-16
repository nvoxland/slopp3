(ns slopp.orientation-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.api :as api]))

(deftest ^:isolated outline-and-namespaces                        ; T2
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'o.core
                   (str "(ns o.core (:require [clojure.test :refer [deftest is]]))\n"
                        "(defn pure-f\n  \"Adds one.\n  Slowly.\"\n  [x] (inc x))\n"
                        "(defn mut! [a] (swap! a inc))\n"
                        "(deftest t (is (= 2 (pure-f 1))))\n"))
      (api/ingest! sess 'o.util "(ns o.util)\n(defn helper [x] x)\n")
      (testing "query-namespaces: what exists, without knowing names up front"
        (is (= [{:ns 'o.core :forms 4} {:ns 'o.util :forms 2}]
               (api/query-namespaces sess))))
      (testing "query-outline: names + arities + !-status + test-ness + doc line"
        (let [o       (api/query-outline sess 'o.core :detail true)
              by-name (into {} (map (juxt :name identity)) (:forms o))]
          (is (= [1] (:arities (by-name 'pure-f))))
          (is (= "Adds one." (:doc (by-name 'pure-f))))
          (is (nil? (:effectful? (by-name 'pure-f))))
          (is (true? (:effectful? (by-name 'mut!))))
          (is (true? (:test? (by-name 't))))
          (is (nil? (:effectful? (by-name 't))))))    ; T1: tests aren't flagged
      (testing "query-project: the whole store's shape in ONE call, with :head"
        (let [p (api/query-project sess)]
          (is (string? (:head p)))
          (is (= '[o.core o.util] (mapv :ns (:namespaces p))))
          (is (some #(= 'mut! (:name %))
                    (:forms (first (filter #(= 'o.core (:ns %))
                                           (:namespaces p))))))))
      (testing "query-search: the missing grep, form-addressed"
        (let [hits (api/query-search sess "swap!")]
          (is (= 1 (count hits)))
          (is (= 'o.core (:ns (first hits))))
          (is (= 'mut! (:form (first hits)))))
        (is (= 2 (count (api/query-search sess "defn \\w+-f|helper"))))
        (is (empty? (api/query-search sess "nonexistent-thing")))
        (testing "bad regex is a clean error"
          (is (:error (api/query-search sess "([")))))
      (finally (api/close! sess)))))
(deftest ^:isolated batched-source-reads
  (let [sess (api/open!)]
    (try
      (api/create-ns! sess 'bq.a :source "(ns bq.a)\n(defn f [] 1)\n(defn g [] 2)\n")
      (api/create-ns! sess 'bq.b :source "(ns bq.b)\n(defn h [] 3)\n")
      (let [r (api/query-sources sess [{:ns 'bq.a :name 'f}
                                       {:ns 'bq.b}
                                       {:ns 'bq.a :name 'nope}
                                       {:ns 'bq.zz}])]
        (is (= "(defn f [] 1)" (:source (nth r 0))))
        (is (re-find #"defn h" (:source (nth r 1))))
        (is (:error (nth r 2)))
        (is (:error (nth r 3))))
      (finally (api/close! sess)))))
(deftest ^:isolated query-project-since
  (let [sess (api/open!)]
    (try
      (api/create-ns! sess 'qs.core :source "(ns qs.core)\n(defn f [] 1)\n")
      (let [head (:head (api/query-project sess))]
        (testing "unchanged structure is a one-liner"
          (is (= {:unchanged-since head :head head}
                 (select-keys (api/query-project sess :since head)
                              [:unchanged-since :head]))))
        (testing "a write invalidates it"
          (api/add-form! sess 'qs.core "(defn g [] 2)" :agent "t")
          (let [r (api/query-project sess :since head)]
            (is (nil? (:unchanged-since r)))
            (is (seq (:namespaces r))))))
      (finally (api/close! sess)))))
(deftest ^:isolated outline-is-compact-by-default
  (let [sess (api/open!)]
    (try
      (api/create-ns! sess 'oc.core
                      :source "(ns oc.core)\n(defn f\n  \"Adds one.\"\n  [x] (inc x))\n")
      (testing "no doc lines unless asked"
        (is (nil? (-> (api/query-outline sess 'oc.core) :forms first :doc)))
        (is (= "Adds one."
               (-> (api/query-outline sess 'oc.core :detail true) :forms first :doc))))
      (testing "query-project passes detail through"
        (is (nil? (-> (api/query-project sess) :namespaces first :forms first :doc)))
        (is (some? (-> (api/query-project sess :detail true)
                       :namespaces first :forms first :doc))))
      (finally (api/close! sess)))))
(deftest ^:isolated query-brief-is-the-one-call-dossier
  (let [sess (api/open!)]
    (try
      (api/create-ns! sess 'qb.a :source "(ns qb.a (:require [clojure.test :refer [deftest is]]))\n(defn f\n  \"Core fn.\"\n  [x] (inc x))\n(deftest f-t (is (= 2 (f 1))))\n")
      (api/module-dep! sess "qb.b" "qb.a" :prompt "fixture edge")
      (api/create-ns! sess 'qb.b :source "(ns qb.b (:require [qb.a :as a]))\n(defn g [x] (a/f x))\n")
      (api/edit-replace! sess 'qb.a 'f "(defn f\n  \"Core fn.\"\n  [x] (+ x 1))" :prompt "prefer + for clarity" :agent "t")
      (api/test-run! sess nil)
      (let [b (api/query-brief sess 'qb.a 'f)]
        (is (re-find #"\+ x 1" (:source b)))
        (testing "cross-ns callers included"
          (is (some #(= 'qb.b (:from-ns %)) (:callers b))))
        (testing "covering tests from the trace map"
          (is (= ['qb.a/f-t] (:covered-by b))))
        (testing "the recorded why is in the dossier"
          (is (= "prefer + for clarity" (get-in b [:why :prompt])))))
      (testing "unknown form is an honest error"
        (is (:error (api/query-brief sess 'qb.a 'nope))))
      (finally (api/close! sess)))))
(deftest ^:isolated flow-and-impact-answer-the-thread
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'fl.a "(ns fl.a)\n(defn mk [r?] {:rush? r?})\n")
      (api/module-dep! sess "fl.b" "fl.a" :prompt "fixture edge")
      (api/ingest! sess 'fl.b
                   (str "(ns fl.b (:require [fl.a :as a] [clojure.test :refer [deftest is]]))\n"
                        "(defn ship [o] (if (:rush? o) 2 1))\n"
                        "(defn total [o] (* (ship o) 10))\n"
                        "(defn pick [f o] (f o))\n"
                        "(defn use-ho [o] (pick ship o))\n"
                        "(deftest ship-t (is (= 2 (ship (a/mk true)))))\n"))
      (api/test-run! sess 'fl.b)
      (testing "query-flow threads a keyword across namespaces"
        (let [r (api/query-flow sess ":rush?")]
          (is (= #{['fl.a 'mk] ['fl.b 'ship]}
                 (into #{} (map (juxt :ns :form)) r))
              (pr-str r))))
      (testing "query-impact reads the blast radius"
        (let [r (api/query-impact sess 'fl.b 'ship)]
          (is (= ['fl.b/ship-t] (:covered-by r)) (pr-str r))
          (is (some #(and (= 'total (:form %)) (= 1 (:calls %))) (:callers r)) (pr-str r))
          (is (some #(and (= 'use-ho (:form %)) (pos? (:value-refs %))) (:callers r)) (pr-str r))))
      (finally (api/close! sess)))))
(deftest ^:isolated draft-test-scaffolds-from-observation
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'dt.core
                   "(ns dt.core)\n(defn scale [x r] (long (Math/round (* x r))))\n")
      (testing "with a driver, observed calls become assertions (Rock 5)"
        (let [r (api/draft-test sess 'dt.core 'scale
                                :code "(do (dt.core/scale 100 0.5) (dt.core/scale 9 0.1))")]
          (is (re-find #"deftest scale-t" (str (:draft r))) (pr-str r))
          (is (re-find #"\(is \(= 50 \(dt.core/scale 100 0.5\)\)\)" (str (:draft r))) (pr-str r))
          (is (= 2 (:observed r)) (pr-str r))))
      (testing "without a driver, a signature skeleton with named holes"
        (let [r (api/draft-test sess 'dt.core 'scale)]
          (is (re-find #"deftest scale-t" (str (:draft r))) (pr-str r))
          (is (re-find #":TODO-x :TODO-r" (str (:draft r))) (pr-str r))))
      (finally (api/close! sess)))))
(deftest ^:isolated session-brief-is-the-one-call-orientation
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'br.a "(ns br.a)\n(defn f [x] x)\n(defn g [x] x)\n")
      (api/ingest! sess 'br.b "(ns br.b)\n(defn h [x] x)\n")
      (api/commit-point! sess
                         (str "seeded the br domain "
                              (apply str (repeat 40 "with a very long story ")))
                         :agent "t")
      (let [r (api/session-brief sess)]
        (testing "names-only project map"
          (is (= '[f g] (:forms (first (filter #(= 'br.a (:ns %)) (:project r)))))
              (pr-str r)))
        (testing "milestones ride along"
          (is (some #(re-find #"seeded the br domain" (str (:description %)))
                    (:milestones r))
              (pr-str r)))
        (testing "the loop is stated and the whole thing is SMALL"
          (is (re-find #"commit_point" (str (:loop r))) (pr-str r))
          (is (< (count (pr-str r)) 1500) (str (count (pr-str r))))))
      (finally (api/close! sess)))))
(deftest ^:isolated report-composes-the-handoff
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'rp.core "(ns rp.core)\n(defn f [x] x)\n")
      (api/commit-point! sess "seed rp" :agent "t")
      (api/edit-replace! sess 'rp.core 'f "(defn f [x] (inc x))"
                         :prompt (str "make f increment "
                                      (apply str (repeat 40 "because reasons ")))
                         :agent "t")
      (api/commit-point! sess "f increments now" :agent "t")
      (let [r (api/report sess)]
        (testing "milestones + changes with their recorded asks"
          (is (some #(re-find #"f increments" (str (:description %))) (:milestones r)) (pr-str r))
          (is (some #(and (= 'rp.core (:ns %)) (= 'f (:form %))
                          (some (fn [a] (re-find #"make f increment" (str a))) (:asks %)))
                    (:changes r))
              (pr-str r)))
        (testing "last verification state + the verify command ride along"
          (is (:suite r) (pr-str r))
          (is (re-find #"test_run" (str (:verify r))) (pr-str r)))
        (testing "asks are SNIPPED — composites must not give back their savings"
          (is (every? #(<= (count %) 150)
                      (mapcat :asks (:changes r)))
              (pr-str (mapv :asks (:changes r))))))
      (finally (api/close! sess)))))
(deftest ^:isolated slices-are-source-plus-cards
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'sl.util
                   "(ns sl.util)\n(defn pad \"Pads.\" [s] (str \" \" s))\n(defn fmt \"Formats cents.\" [c] (pad (str \"$\" c)))\n")
      (api/module-dep! sess "sl.core" "sl.util" :prompt "fixture edge")
      (api/ingest! sess 'sl.core
                   (str "(ns sl.core (:require [sl.util :as u]))\n"
                        "(defn- tax \"Ten percent.\" [c] (long (* c 0.1)))\n"
                        "(defn total \"Subtotal plus tax.\" [c] (u/fmt (+ c (tax c))))\n"))
      (testing "target rides as full source; the neighborhood rides as cards"
        (let [r (api/query-slice sess 'sl.core 'total)]
          (is (re-find #"u/fmt" (str (get-in r [:target :source]))) (pr-str r))
          (is (= #{'sl.core/tax 'sl.util/fmt 'sl.util/pad}
                 (set (map :form (:cards r)))) (pr-str r))
          (is (every? #(nil? (:source %)) (:cards r)))
          (is (< (count (pr-str r)) 1400) (str (count (pr-str r))))))
      (testing "match windows the target — giant forms read as neighborhoods"
        (let [r (api/query-slice sess 'sl.core 'total :match "u/fmt" :window 0)]
          (is (re-find #"u/fmt" (get-in r [:target :source])) (pr-str r))
          (is (zero? (count (re-seq #"\n" (get-in r [:target :source])))))
          (is (get-in r [:target :window]))))
      (testing "depth 1 = direct callees only"
        (let [r (api/query-slice sess 'sl.core 'total :depth 1)]
          (is (= #{'sl.core/tax 'sl.util/fmt}
                 (set (map :form (:cards r)))) (pr-str r))))
      (finally (api/close! sess)))))
(deftest ^:isolated briefs-arrive-task-shaped
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'bw.core
                   (str "(ns bw.core)\n"
                        "(defn billable-weight-g \"Greater of actual and volumetric.\" [p] (max (:w p) (:v p)))\n"
                        "(defn unrelated-thing [x] x)\n"
                        "(defn quote-breakdown \"Quote parts.\" [p] {:w (billable-weight-g p)})\n"))
      (api/turn-begin! sess :agent "t"
                       :intent "oversize parcels should use the billable weight in the quote breakdown")
      (testing "the brief mines the ask and arrives with the relevant cards (intent-scoped orientation)"
        (let [b (api/session-brief sess)]
          (is (some #(= 'bw.core/billable-weight-g (:form %)) (:relevant b))
              (pr-str (:relevant b)))
          (is (not-any? #(= 'bw.core/unrelated-thing (:form %)) (:relevant b)))))
      (finally (api/close! sess)))))
(deftest ^:isolated briefs-roll-up-namespace-families
  (let [sess (api/open!)]
    (try
      (doseq [i (range 1 7)]
        (api/ingest! sess (symbol (str "fam.r0" i))
                     (str "(ns fam.r0" i ")\n(defn probe [] " i ")\n")))
      (api/ingest! sess 'solo.core "(ns solo.core)\n(defn f [x] x)\n")
      (testing "sibling families collapse to one row; solos keep their names (breadth stays cheap)"
        (let [b (api/session-brief sess)]
          (is (some #(and (:family %) (= 6 (:nses %))) (:project b))
              (pr-str (:project b)))
          (is (some #(= 'solo.core (:ns %)) (:project b)))
          (is (not-any? #(= 'fam.r01 (:ns %)) (:project b)))))
      (finally (api/close! sess)))))
(deftest ^:isolated query-depends-is-the-generic-front-door
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'dp.base "(ns dp.base)\n(defn fee \"Fee.\" [z] (get {1 500} z 0))\n")
      (api/module-dep! sess "dp.app" "dp.base" :prompt "fixture edge")
      (api/ingest! sess 'dp.app
                   (str "(ns dp.app (:require [dp.base :as base]))\n"
                        "(defn total [o] (+ 100 (base/fee (:dest-zone o))))\n"))
      (testing "a NAMESPACE answer: who requires it + what it requires"
        (let [r (api/query-depends sess "dp.base")]
          (is (= :namespace (:kind r)) (pr-str r))
          (is (= '[dp.app] (:required-by r)) (pr-str r))))
      (testing "a VAR answer delegates to the blast radius"
        (let [r (api/query-depends sess "dp.base/fee")]
          (is (= :var (:kind r)) (pr-str r))
          (is (some #(= 'total (:form %)) (:callers r)) (pr-str r))))
      (testing "a KEYWORD answer delegates to the flow"
        (let [r (api/query-depends sess ":dest-zone")]
          (is (= :keyword (:kind r)) (pr-str r))
          (is (some #(= 'dp.app (:ns %)) (:rows r)) (pr-str r))))
      (testing "an unknown thing teaches the kinds"
        (is (re-find #"namespace, var" (str (:error (api/query-depends sess "nope.zip"))))))
      (testing "direction :dependencies gives the callee tree (absorbs query_deps)"
        (let [r (api/query-depends sess "dp.app/total" :direction :dependencies)]
          (is (= :var (:kind r)) (pr-str r))
          (is (contains? (:calls r) 'dp.base/fee) (pr-str r))))
      (testing "a namespace's :dependencies are its requires"
        (let [r (api/query-depends sess "dp.app" :direction :dependencies)]
          (is (some #{'dp.base} (:requires r)) (pr-str r))))
      (finally (api/close! sess)))))
(deftest ^:isolated review-scan-triages-by-risk
  ;; a whole-codebase review shouldn't read 800 forms blindly — the store
  ;; knows which are risky. :untested must be STATIC (reachable from any
  ;; test ns), not trace-only — else an isolated-test codebase looks 100%
  ;; untested (the flaw dogfooding caught).
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'rv.core
                   (str "(ns rv.core (:require [clojure.test :refer [deftest is]]))\n"
                        "(defn safe \"Doc.\" [x] (inc x))\n"
                        "(deftest safe-t (is (= 2 (safe 1))))\n"))
      (api/ingest! sess 'rv.io
                   (str "(ns rv.io)\n"
                        "(defn orphan! [x] (spit \"/dev/null\" x))\n"
                        "(defn probed! [x] (spit \"/dev/null\" x))\n"))
      (api/module-dep! sess "rv.app" "rv.io" :prompt "fixture edge")
      (api/ingest! sess 'rv.app
                   (str "(ns rv.app (:require [rv.io :as io]))\n"
                        "(defn a [x] (io/orphan! x))\n"
                        "(defn b [x] (io/orphan! x))\n"
                        "(defn c [x] (io/orphan! x))\n"))
      (api/module-dep! sess "rv.probe" "rv.io" :prompt "test edge")
      (api/ingest! sess 'rv.probe-test
                   (str "(ns rv.probe-test (:require [rv.io :as io]\n"
                        "                            [clojure.test :refer [deftest is]]))\n"
                        "(deftest probe-t (is (nil? (io/probed! 1))))\n"))
      (api/test-run! sess 'rv.core)   ; trace covers ONLY safe — not probed!
      (let [r   (api/review-scan sess)
            top (mapv :form (:top r))
            row (fn [q] (first (filter #(= q (:form %)) (:top r))))]
        (testing "the orphan fn (no test reaches it) ranks first, flagged untested"
          (is (= 'rv.io/orphan! (first top)) (pr-str (:top r)))
          (is (contains? (set (:flags (row 'rv.io/orphan!))) :untested))
          (is (contains? (set (:flags (row 'rv.io/orphan!))) :effectful))
          (is (= 3 (:callers (row 'rv.io/orphan!)))))
        (testing "a fn a TEST ns references is NOT untested (isolated-safe)"
          (let [pr (row 'rv.io/probed!)]
            (is (or (nil? pr)
                    (not (contains? (set (:flags pr)) :untested)))
                (pr-str pr))))
        (testing "the tested, documented, pure fn is not flagged"
          (is (not (some #{'rv.core/safe} top))))
        (testing "totals roll up the risks"
          (is (pos? (get-in r [:totals :untested] 0)))))
      (finally (api/close! sess)))))
(deftest ^:isolated query-store-is-the-data-oracle
  ;; the image answers questions OF the code; query_store answers questions
  ;; ABOUT it — read-only eval over the immutable store value, in the server
  ;; where that value lives. The sanctioned home for ad-hoc analysis that
  ;; otherwise becomes a canned tool or a raw db read.
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'qs.core
                   (str "(ns qs.core)\n\n"
                        "(defn f \"F.\" [x] x)\n\n"
                        "(defn- g \"G.\" [x] x)\n"))
      (testing "pure analysis over the live store value"
        (let [r (api/query-store sess
                                 "(fn [store] (count (slopp.store/forms store 'qs.core)))")]
          (is (= 3 (:result r)) (pr-str r)))
        (let [r (api/query-store sess
                                 "(fn [store] (sort (map :name (slopp.store/forms store 'qs.core))))")]
          (is (= '(f g qs.core) (:result r)) (pr-str r))))
      (testing "the code must be one fn of the store"
        (is (re-find #"\(fn \[store\]" (str (:error (api/query-store sess "(+ 1 2)"))))))
      (testing "effects refuse with teaching"
        (is (re-find #"(?i)read-only"
                     (str (:error (api/query-store
                                   sess "(fn [store] (spit \"/tmp/x\" store))")))))
        (is (:error (api/query-store
                     sess "(fn [store] (slopp.db/append! nil store nil))")))
        (is (:error (api/query-store sess "(fn [store] (eval '(+ 1 2)))"))))
      (testing "runaway code times out instead of wedging the server"
        (is (re-find #"timed out"
                     (str (:error (api/query-store
                                   sess "(fn [store] (loop [] (recur)))"
                                   :timeout-ms 300))))))
      (testing "exceptions surface as errors"
        (is (re-find #"boom"
                     (str (:error (api/query-store
                                   sess "(fn [store] (throw (ex-info \"boom\" {})))"))))))
      (finally (api/close! sess)))))
(deftest ^:isolated review-scan-flags-unused-publics
  ;; kondo sees unused PRIVATES per-namespace; unused PUBLICS need the
  ;; whole-store call graph review_scan already builds — zero in-store
  ;; callers on a public defn/def is dead code or unadvertised surface.
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'ru.core
                   (str "(ns ru.core)\n\n"
                        "(defn used \"U.\" [x] x)\n\n"
                        "(defn orphan \"O.\" [x] x)\n\n"
                        "(defn -main \"M.\" [x] (used x))\n"))
      (let [r    (api/review-scan sess)
            row  (fn [q] (first (filter #(= q (:form %)) (:top r))))]
        (is (some #{:unused} (:flags (row 'ru.core/orphan))) (pr-str (:top r)))
        (is (not (some #{:unused} (:flags (row 'ru.core/used))))
            "called forms aren't flagged")
        (is (not (some #{:unused} (:flags (row 'ru.core/-main))))
            "entry points are exempt from the unused flag"))
      (finally (api/close! sess)))))
