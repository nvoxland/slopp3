(ns slopp.read.orient-test
  "Cover for `slopp.read.orient` — the first thing a session reads.

  Unusually for this codebase, the subject's output is largely PROSE, and the
  prose is the product: an agent acts on \"this verdict was produced by a host
  running code the store has moved past\" the way it acts on a return value. So
  the assertions here are about what the words CLAIM — that a failure names
  itself, that a note does not promise a retry it cannot deliver, that a quiet
  host says nothing rather than something reassuring.

  Mostly in-image, because the assembly is pure. The external ones are the
  cases that need a real session to have a real history to be oriented in."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.api :as api] [slopp.read.orient :as orient] [slopp.api.external :as external] [clojure.string :as str] [slopp.store :as store]))

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
    (is (nil? (orient/host-brief nil 0 false nil))))
  (testing "snapshot mode names the CODE deltas the host cannot be running"
    (let [h (orient/host-brief {:mode :snapshot :booted-at 100} 3 false nil)]
      (is (= :snapshot (:mode h)))
      (is (re-find #"3 code delta" (:note h)))
      (is (re-find #"restart" (:note h)))))
  (testing "snapshot mode with nothing since boot is quiet — :mode carries it"
    (let [h (orient/host-brief {:mode :snapshot :booted-at 100} 0 false nil)]
      (is (= :snapshot (:mode h)))
      (is (nil? (:note h)))))
  (testing "review V-F3: a snapshot host ON a branch gets BOTH stances"
    (let [h (orient/host-brief {:mode :snapshot :booted-at 100} 2 true nil)]
      (is (re-find #"2 code delta" (:note h)))
      (is (re-find #"branch" (:note h)))))
  (testing "live on main with clean reloads is QUIET — no note, no noise"
    (let [h (orient/host-brief {:mode :live :booted-at 100 :last-reload-at 200
                                :reloads 4 :failed []} 0 false nil)]
      (is (= :live (:mode h)))
      (is (nil? (:note h)))
      (is (nil? (:failed h)))))
  (testing "live on a BRANCH teaches the main-journal blindness"
    (let [h (orient/host-brief {:mode :live :booted-at 100} 0 true nil)]
      (is (re-find #"branch" (:note h)))
      (is (re-find #"image" (:note h)))))
  (testing "failed reloads are NAMED — a silent hold-back is the old bug"
    (let [h (orient/host-brief {:mode :live :booted-at 100 :failed '[a.core]} 0 false nil)]
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
    (is (nil? (orient/host-warning nil 0 nil))))
  (testing "live with clean reloads is SILENT — a poll-interval lag is not a finding"
    (is (nil? (orient/host-warning {:mode :live :booted-at 100 :last-reload-at 200
                                    :reloads 9 :failed []} 3 nil))))
  (testing "a FAILED reload rides the verdict, naming the namespace"
    (let [w (orient/host-warning {:mode :live :booted-at 100 :failed '[a.core]} 0 nil)]
      (is (= '[a.core] (:failed w)))
      (is (re-find #"(?i)failed" (:note w)))
      (is (re-find #"(?i)verdict" (:verdict-note w))
          "it must say what this means for the result it is attached to")))
  (testing "a snapshot host with post-boot code deltas is running old code, and says so"
    (let [w (orient/host-warning {:mode :snapshot :booted-at 100} 3 nil)]
      (is (= :snapshot (:mode w)))
      (is (re-find #"3 code delta" (:note w)))))
  (testing "a snapshot host with nothing since boot is current — silent"
    (is (nil? (orient/host-warning {:mode :snapshot :booted-at 100} 0 nil)))))

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

(deftest doc-summaries-end-at-a-sentence-not-mid-word
  ;; The card is the right vehicle at the right moment — every query_slice
  ;; returns one for each callee — and a 90-char cut destroyed what it was
  ;; carrying. The real example, in front of me while I wrote a broken
  ;; stylesheet: slopp.web.css/render's card read
  ;;
  ;;   "Garden rules → a minified CSS string. Every string in the rule data — a"
  ;;
  ;; A trailing fragment is worse than a clean stop: it looks like content.
  (let [doc (str "Garden rules → a minified CSS string. Every string in the"
                 " rule data — a selector or a value — is validated against"
                 " CSS block-breakout first.")]
    (testing "the first SENTENCE, whole"
      (is (= "Garden rules → a minified CSS string."
             (orient/doc-summary doc))))
    (testing "a one-sentence doc with no terminator is returned whole"
      (is (= "The form in `ns-sym` defining symbol `nm`, or nil"
             (orient/doc-summary "The form in `ns-sym` defining symbol `nm`, or nil"))))
    (testing "a decimal or an abbreviation is not a sentence end"
      ;; the naive split — on "." — cuts "1.5" in half and reads worse than
      ;; the character cut it replaces
      (is (= "Caps at 1.5 KB by default."
             (orient/doc-summary "Caps at 1.5 KB by default. More here.")))
      (is (= "Uses e.g. malli here."
             (orient/doc-summary "Uses e.g. malli here. Then more."))))
    (testing "a sentence longer than the cap still gets capped, with an ellipsis"
      ;; the budget is the point — an unbounded first sentence would let one
      ;; verbose docstring eat a whole result
      (let [long-doc (str (apply str (repeat 40 "verylongword ")) ". tail")
            out      (orient/doc-summary long-doc)]
        (is (<= (count out) 121) (str (count out) ": " out))
        (is (str/ends-with? out "…"))
        (testing "and the cap lands on a word boundary"
          (is (str/ends-with? out "verylongword…") out))))
    (testing "nil and blank are nil, not \"\" or \"…\""
      (is (nil? (orient/doc-summary nil)))
      (is (nil? (orient/doc-summary "   "))))))

(deftest a-card-carries-the-trap-not-just-the-purpose
  ;; The card is what a CALLER sees, and it already carries sig, doc, why and
  ;; warranty. What it could not carry is the one line that would have stopped
  ;; the caller making the mistake — because a docstring's first line says
  ;; what the function DOES and the trap is in paragraph three.
  ;;
  ;; The real case: web.css/render's card was on screen while a stylesheet was
  ;; written with `[:a :b]` meaning a descendant. It means a GROUP. The
  ;; docstring said so — four paragraphs down.
  ;;
  ;; ^{:teach "…"} is the same vocabulary every rule already uses for
  ;; explain-at-point-of-use, and it rides the card.
  (let [sess (atom {:store (store/ingest
                            (store/empty-store) 'app.css
                            (str "(ns app.css \"Styling.\")\n\n"
                                 "(defn ^{:teach \"[:a :b] is a GROUP, not a descendant\"}\n"
                                 "  render \"Rules to a CSS string.\" [rules] rules)\n\n"
                                 "(defn plain \"No trap here.\" [x] x)\n"))})]
    (testing "the caveat rides the card, beside the doc rather than inside it"
      (let [c (orient/form-card sess 'app.css 'render)]
        (is (= "[:a :b] is a GROUP, not a descendant" (:teach c)) (pr-str c))
        (is (= "Rules to a CSS string." (:doc c))
            "and the doc summary is untouched — they answer different questions")))
    (testing "a form with no trap carries no key at all"
      ;; not "" and not nil-valued: an empty :teach on every card would train
      ;; the reader to skip the field, which is how the one that matters gets
      ;; missed
      (is (not (contains? (orient/form-card sess 'app.css 'plain) :teach))))
    (testing "a non-STRING teach is dropped, not rendered as a literal list"
      ;; metadata is read as DATA and never evaluated, so ^{:teach (str "a" "b")}
      ;; is a LIST. Rendering it would put `(str "a" "b")` on the card — a
      ;; confident-looking value that teaches nothing, which is worse than the
      ;; absence it replaces. Caught while authoring the first real caveat.
      (let [s2 (atom {:store (store/ingest
                              (store/empty-store) 'app.bad
                              (str "(ns app.bad \"B.\")\n\n"
                                   "(defn ^{:teach (str \"a\" \"b\")} f \"D.\" [x] x)\n"))})]
        (is (not (contains? (orient/form-card s2 'app.bad 'f) :teach))
            "a teach that is not a string is not a teach")))))

(deftest a-stuck-reload-says-why-and-stops-promising-a-retry
  ;; Two complaints from the same incident, both about the same note.
  ;;
  ;; The REASON never reached the agent: "the failure is in the server log" —
  ;; a file in ~/Library/Logs that no slopp surface exposes, on a system whose
  ;; whole claim is that the store answers everything. Grepping it found
  ;; nothing usable.
  ;;
  ;; And the note promised "the next poll retries" for many minutes while the
  ;; reload had been failing identically every time. A reload is deterministic
  ;; over the same source: if it failed on this text it will fail on this text.
  ;; A retry loop that cannot converge should escalate, not reassure.
  (testing "the first failure names the reason, not the log file"
    (let [b (orient/host-brief {:mode :live :booted-at 1 :failed '[app.views]
                                :failed-why '{app.views {:why "Unable to resolve symbol: nsfilter"
                                                         :attempts 1}}} 0 false nil)]
      (is (= '[app.views] (:failed b)))
      (is (str/includes? (:note b) "Unable to resolve symbol: nsfilter"))
      (is (not (str/includes? (:note b) "server log"))
          "the reason is HERE now; pointing at a file nothing exposes was the bug")))
  (testing "a failure that keeps failing escalates instead of repeating itself"
    (let [b (orient/host-brief {:mode :live :booted-at 1 :failed '[app.views]
                                :failed-why '{app.views {:why "Unable to resolve symbol: nsfilter"
                                                         :attempts 12}}} 0 false nil)]
      (is (str/includes? (:note b) "12"))
      (is (str/includes? (:note b) "restart"))
      (is (not (str/includes? (:note b) "next poll retries"))
          "a loop that has failed twelve times identically is not about to succeed")))
  (testing "a host with nothing wrong still says nothing"
    (is (nil? (:note (orient/host-brief {:mode :live :booted-at 1} 0 false nil))))))

(deftest a-half-loaded-store-says-so-and-says-it-is-editable
  ;; frictions 3b/3f/19. boot used to rethrow on the first namespace that did
  ;; not compile, so one bad form took down every tool in every process — the
  ;; edit verbs that could have repaired it included. Loading best-effort is
  ;; only half the fix; the other half is that orientation NAMES what did not
  ;; load, because an agent who is not told discovers it as an unrelated
  ;; failure somewhere downstream.
  (let [info {:mode :live :booted-at 1
              :load-failures [{:ns 'app.views :why "Unable to resolve symbol: gone"}]}
        b    (orient/host-brief info 0 false [])]
    (is (re-find #"did NOT load at boot" (:note b)))
    (is (re-find #"app\.views" (:note b))
        "naming the namespace is the difference between a warning and a fix")
    (is (re-find #"FIX them" (:note b))
        "and it has to say the store is still editable, or the advice reads as 'reimport'"))

  (testing "a fully loaded store stays silent about it"
    (is (nil? (:note (orient/host-brief {:mode :live :booted-at 1} 0 false []))))))

(deftest a-failed-host-reload-is-not-exonerated-by-the-oracle
  ;; CORRECTION to what landed with the currency work. host-brief answered a
  ;; failed live-reload with "the image was COMPARED to the store and holds
  ;; every form at its current source, so this is the watcher stuck, not stale
  ;; code". Two different processes are being conflated there:
  ;;
  ;;   - the reload that FAILED is the HOST's — this server process, whose
  ;;     watcher lives in slopp.boot;
  ;;   - the image that was COMPARED is the child ORACLE, a separate JVM.
  ;;     `currency/stamp!` runs server-side, but it records what was pushed
  ;;     INTO the oracle, so `currency/drift` measures the oracle.
  ;;
  ;; A failed host reload is precisely the case where the two diverge, so the
  ;; oracle comparing clean is no evidence about the host at all. Friction
  ;; 20a's lesson still holds in the other direction — do not assert staleness
  ;; nobody measured either. What is left is the honest third answer: name the
  ;; watcher failure, and say the host's own currency is UNMEASURED.
  ;;
  ;; host-warning is a different reader and is correct as it stands: a VERDICT
  ;; is produced by the oracle, so oracle drift is exactly what should qualify
  ;; it. The bug is confined to the section that describes this process.
  (let [info {:mode :live :booted-at 1 :failed '[a.core]
              :failed-why '{a.core {:why "boom" :attempts 1}}}
        b    (orient/host-brief info 0 false [])
        note (str (:note b))]
    (is (nil? (:image-verified b))
        "an oracle comparison says nothing about whether THIS process is current")
    (is (:oracle-verified b)
        "the oracle's cleanliness is still worth reporting — under its own name")
    (is (not (re-find #"not stale code" note)) note)
    (is (re-find #"a\.core" note) "the failing namespace is still named")
    (is (re-find #"not been measured" note)
        "unmeasured is the honest answer, and it has to be said out loud"))
  (testing "the host section does not call the oracle's drift its own"
    (let [drift [{:ns 'a.core :form 'f :why :superseded}]
          b     (orient/host-brief {:mode :live :booted-at 1} 0 false drift)]
      (is (= 1 (:oracle-drift-count b)) (pr-str b))
      (is (nil? (:drift b)) (pr-str b))
      (is (re-find #"(?i)verification image" (str (:note b))) (pr-str (:note b))))))

(deftest a-failed-reload-is-a-stuck-watcher-when-THIS-process-compares-clean
  ;; The claim removed earlier today, now earned. It was wrong because the
  ;; comparison behind it was against the child ORACLE. Measured against this
  ;; process — `slopp.boot/host-drift`, which every door into this JVM writes
  ;; — "the watcher is stuck, not stale code" is exactly right, and it is what
  ;; friction 20a needed: a rename left the watcher retrying a namespace the
  ;; store no longer had, failing forever and reporting staleness that a
  ;; comparison would have cleared on the next poll.
  ;;
  ;; The two subjects now read side by side: :host-drift / :host-verified and
  ;; :oracle-drift / :oracle-verified. Same quantity, named for who it is about.
  (let [info {:mode :live :booted-at 1 :failed '[a.core]
              :failed-why '{a.core {:why "boom" :attempts 9}}
              :host-drift []}
        b    (orient/host-brief info 0 false nil)
        note (str (:note b))]
    (is (:host-verified b) "measured, and this process holds current source")
    (is (re-find #"watcher" note) note)
    (is (not (re-find #"not been measured" note)) note))
  (testing "measured behind names the namespaces rather than blaming the watcher"
    (let [b    (orient/host-brief {:mode :live :booted-at 1 :host-drift '[a.core b.core]}
                                  0 false nil)
          note (str (:note b))]
      (is (= '[a.core b.core] (:host-drift b)) (pr-str b))
      (is (nil? (:host-verified b)))
      (is (re-find #"a\.core" note) note)))
  (testing "absent still means nobody measured, and it still says so"
    (let [b (orient/host-brief {:mode :live :booted-at 1 :failed '[a.core]} 0 false nil)]
      (is (nil? (:host-verified b)))
      (is (re-find #"not been measured" (str (:note b))) (pr-str (:note b))))))

(deftest a-verdict-is-doubted-when-the-HOST-is-measured-behind
  ;; host-warning was right to use ORACLE drift — the tests run there. But the
  ;; host runs the code that ORCHESTRATES the run (slopp.api: what to verify,
  ;; what the trace says, how the result is judged), so a host measured behind
  ;; is also a reason to doubt a verdict, by a different route.
  ;;
  ;; And the failed-reload branch can stop guessing. It used to fire whenever a
  ;; reload had failed and the ORACLE had not been compared — the oracle
  ;; standing in for a question about the host. With the host measuring itself,
  ;; a failed reload on a host that compares clean is a watcher problem and
  ;; nothing more.
  (testing "measured behind qualifies the verdict and names the namespaces"
    (let [w (orient/host-warning {:mode :live :booted-at 1 :host-drift '[a.core]}
                                 0 nil)]
      (is (some? w))
      (is (re-find #"a\.core" (str (:verdict-note w) (:note w))) (pr-str w))))
  (testing "a failed reload whose HOST compares clean does not qualify anything"
    (is (nil? (orient/host-warning {:mode :live :booted-at 1 :failed '[a.core]
                                    :failed-why '{a.core {:why "boom" :attempts 2}}
                                    :host-drift []}
                                   0 nil))
        "the watcher is stuck; the code that produced this verdict is current"))
  (testing "a failed reload with NO host measurement still warns — cautious by default"
    (is (some? (orient/host-warning {:mode :live :booted-at 1 :failed '[a.core]}
                                    0 nil)))))
