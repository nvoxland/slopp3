(ns slopp.api.orient-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.api :as api] [slopp.api.orient :as orient] [slopp.api.external :as external]))

(deftest fit-report-keeps-reports-under-the-gate
  (let [fat {:milestones [{:commit "d9" :description "m"}]
             :changes (vec (for [i (range 80)]
                             {:ns (symbol (str "big.ns" i)) :form (symbol (str "fn" i))
                              :ops [:replace]
                              :asks [(apply str (repeat 130 "x")) (apply str (repeat 130 "y"))]}))
             :suite {:status :green} :verify "test_run"}
        r  (#'orient/fit-report fat)]
    (is (<= (count (pr-str r)) 6500) (str (count (pr-str r))))
    (is (seq (:milestones r)))
    (is (re-find #"narrows" (str (:note r))) (pr-str (keys r)))))

(deftest fit-report-aggregates-instead-of-amputating
  (let [fat {:milestones [{:commit "d9" :description "m"}]
             :changes (vec (for [i (range 80)]
                             {:ns (symbol (str "big.ns" (mod i 8))) :form (symbol (str "fn" i))
                              :ops [:replace]
                              :asks [(apply str (repeat 130 "x")) (apply str (repeat 130 "y"))]}))
             :suite {:status :green} :verify "test_run"}
        r  (#'orient/fit-report fat)]
    (is (<= (count (pr-str r)) 6500) (str (count (pr-str r))))
    (testing "over-budget changes ROLL UP by namespace — information aggregates, never amputates"
      (is (some #(and (:ns %) (number? (:forms %))) (:changes r)) (pr-str (take 3 (:changes r))))
      (is (re-find #"rolled up" (str (:note r))) (pr-str (:note r))))))

(deftest ^:external form-cards-are-the-interface-view
  (let [sess (external/open!)]
    (try
      (api/ingest! sess 'cd.core
                   (str "(ns cd.core (:require [clojure.test :refer [deftest is]]))\n"
                        "(defn scale\n  \"Rounds to the nearest cent.\"\n  [cents rate]\n"
                        "  (long (Math/round (double (* cents rate)))))\n"
                        "(deftest scale-t (is (= 50 (scale 100 0.5))))\n"))
      (api/test-run! sess 'cd.core)
      (api/edit-replace! sess 'cd.core 'scale
                         "(defn scale\n  \"Rounds to the nearest cent.\"\n  [cents rate]\n  (long (Math/round (* (double cents) rate))))"
                         :prompt "avoid double-coercion of the product" :agent "t")
      (let [c (orient/form-card sess 'cd.core 'scale)]
        (is (= 'cd.core/scale (:form c)) (pr-str c))
        (is (= '[cents rate] (:sig c)) (pr-str c))
        (is (re-find #"nearest" (str (:doc c))) (pr-str c))
        (is (re-find #"double-coercion" (str (:why c))) (pr-str c))
        (is (= 1 (get-in c [:warranty :covered])) (pr-str c))
        (is (nil? (:source c)) (pr-str c))
        (is (< (count (pr-str c)) 400) (str (count (pr-str c)))))
      (finally (api/close! sess)))))

(deftest ^:external cards-carry-observed-examples
  (let [dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-obs" (make-array java.nio.file.attribute.FileAttribute 0)))
        sess (external/open! {:slopp.api/dir dir})]
    (try
      (api/ingest! sess 'ob.core
                   "(ns ob.core)\n(defn scale \"Half it.\" [c r] (long (* c r)))\n")
      (api/remember-observation! sess 'ob.core 'scale
                                 (api/query-observe sess 'ob.core 'scale
                                                    "(ob.core/scale 100 0.5)"))
      (testing "the card carries observed input→output pairs (Q: examples don't lie)"
        (let [c (orient/form-card sess 'ob.core 'scale)]
          (is (vector? (:examples c)) (pr-str c))
          (is (some #(re-find #"100" %) (:examples c)) (pr-str c))
          (is (some #(re-find #"50" %) (:examples c)) (pr-str c))))
      (finally (api/close! sess)))))

(deftest ^:external observed-examples-survive-a-reopen
  ;; The half cards-carry-observed-examples does NOT cover: examples written
  ;; in one session must still show in the next. This pins the durable path
  ;; before form-card stops reading the db directly — without it, moving
  ;; observations into session state could silently reduce them to
  ;; this-session-only and every existing test would still pass.
  (let [dir (str (java.nio.file.Files/createTempDirectory
                  "slopp-obs-reopen" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (let [sess (external/open! {:slopp.api/dir dir})]
      (try
        (api/ingest! sess 'ob2.core
                     "(ns ob2.core)\n(defn scale \"Half it.\" [c r] (long (* c r)))\n")
        (api/remember-observation! sess 'ob2.core 'scale
                                   (api/query-observe sess 'ob2.core 'scale
                                                      "(ob2.core/scale 100 0.5)"))
        (finally (api/close! sess))))
    (let [sess2 (external/open! {:slopp.api/dir dir})]
      (try
        (let [c (orient/form-card sess2 'ob2.core 'scale)]
          (is (vector? (:examples c))
              (str "a reopened session must still carry observed examples: " (pr-str c)))
          (is (some #(re-find #"100" %) (:examples c)) (pr-str c)))
        (finally (api/close! sess2))))))

(deftest host-brief-reads-the-currency-record
  ;; frictions #8 (three sightings): which code the serving host actually
  ;; runs was invisible and line-entangled — every incident began with not
  ;; knowing. The brief now says it: snapshot hosts name the deltas they
  ;; cannot be running, live hosts stay quiet unless a reload failed, and a
  ;; branch line teaches that host code tracks the MAIN journal only.
  (testing "no record (a non-boot process) → nil, section absent"
    (is (nil? (orient/host-brief nil 0 false))))
  (testing "snapshot mode names the CODE deltas the host cannot be running"
    (let [h (orient/host-brief {:mode :snapshot :booted-at 100} 3 false)]
      (is (= :snapshot (:mode h)))
      (is (re-find #"3 code delta" (:note h)))
      (is (re-find #"restart" (:note h)))))
  (testing "snapshot mode with nothing since boot is quiet — :mode carries it"
    (let [h (orient/host-brief {:mode :snapshot :booted-at 100} 0 false)]
      (is (= :snapshot (:mode h)))
      (is (nil? (:note h)))))
  (testing "review V-F3: a snapshot host ON a branch gets BOTH stances"
    (let [h (orient/host-brief {:mode :snapshot :booted-at 100} 2 true)]
      (is (re-find #"2 code delta" (:note h)))
      (is (re-find #"branch" (:note h)))))
  (testing "live on main with clean reloads is QUIET — no note, no noise"
    (let [h (orient/host-brief {:mode :live :booted-at 100 :last-reload-at 200
                                :reloads 4 :failed []}
                               0 false)]
      (is (= :live (:mode h)))
      (is (nil? (:note h)))
      (is (nil? (:failed h)))))
  (testing "live on a BRANCH teaches the main-journal blindness"
    (let [h (orient/host-brief {:mode :live :booted-at 100} 0 true)]
      (is (re-find #"branch" (:note h)))
      (is (re-find #"image" (:note h)))))
  (testing "failed reloads are NAMED — a silent hold-back is the old bug"
    (let [h (orient/host-brief {:mode :live :booted-at 100 :failed '[a.core]}
                               0 false)]
      (is (= '[a.core] (:failed h)))
      (is (re-find #"(?i)failed" (:note h))))))

(deftest host-warning-fires-only-when-a-verdict-should-be-doubted
  ;; The currency record was already right (host-brief); it was in the wrong
  ;; PLACE. session_brief is read once, at the start; `done` and `full_check`
  ;; are read after every unit of work, and they are the surfaces whose whole
  ;; output is a claim about the code. This is the same producer, aimed there.
  ;;
  ;; Quiet is load-bearing: a live host lags the store by up to one poll by
  ;; design, so a warning on every done would train the reader to ignore it.
  (testing "no record — the process did not boot from a store, so it cannot be stale"
    (is (nil? (orient/host-warning nil 0))))
  (testing "live with clean reloads is SILENT — a poll-interval lag is not a finding"
    (is (nil? (orient/host-warning {:mode :live :booted-at 100 :last-reload-at 200
                                    :reloads 9 :failed []}
                                   3))))
  (testing "a FAILED reload rides the verdict, naming the namespace"
    (let [w (orient/host-warning {:mode :live :booted-at 100 :failed '[a.core]} 0)]
      (is (= '[a.core] (:failed w)))
      (is (re-find #"(?i)failed" (:note w)))
      (is (re-find #"(?i)verdict" (:verdict-note w))
          "it must say what this means for the result it is attached to")))
  (testing "a snapshot host with post-boot code deltas is running old code, and says so"
    (let [w (orient/host-warning {:mode :snapshot :booted-at 100} 3)]
      (is (= :snapshot (:mode w)))
      (is (re-find #"3 code delta" (:note w)))))
  (testing "a snapshot host with nothing since boot is current — silent"
    (is (nil? (orient/host-warning {:mode :snapshot :booted-at 100} 0)))))

(deftest code-deltas-since-is-the-one-counter-for-host-currency
  ;; Markers (:verify :done :commit :turn-begin …) are bookkeeping — a host
  ;; that has not "loaded" a :done delta is not stale. The set lives in
  ;; store.fields/markers and this is the only place that reads it for this
  ;; question, so the count cannot drift between session_brief and a verdict.
  (let [st {:deltas [{:id "d1" :op :add     :at 50}
                     {:id "d2" :op :replace :at 150}
                     {:id "d3" :op :verify  :at 160}
                     {:id "d4" :op :done    :at 170}
                     {:id "d5" :op :commit  :at 180}
                     {:id "d6" :op :replace :at 190}]}]
    (testing "counts only CODE deltas after the mark"
      (is (= 2 (orient/code-deltas-since st 100))))
    (testing "everything after 0 is counted except the markers"
      (is (= 3 (orient/code-deltas-since st 0))))
    (testing "nothing after the newest"
      (is (zero? (orient/code-deltas-since st 999))))
    (testing "a nil mark reads as 0, never as 'skip the check'"
      (is (= 3 (orient/code-deltas-since st nil))))))
