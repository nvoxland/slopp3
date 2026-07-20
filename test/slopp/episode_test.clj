(ns slopp.episode-test
  "Episodes: the automatic work-unit between an agent's done-points — derived
  from the journal (no tagging), PER-AGENT so parallel sub-agents don't
  collapse into one braid, with a shared-form guard on revert."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.shell]
            [slopp.store :as store]
            [slopp.turn]
            [slopp.mcp]
            [slopp.api :as api]))

(def seed
  (str "(ns ep.core (:require [clojure.test :refer [deftest is]]))\n"
       "(defn f [x] (inc x))\n"
       "(defn ^:unused-ok g [x] (dec x))\n"
       "(defn ^:unused-ok h [x] x)\n"
       "(deftest f-t (is (= 2 (f 1))))\n"))

(deftest ^:isolated solo-episode-lifecycle
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'ep.core seed)
      (api/done! sess :label "baseline")
      ;; a classic TDD arc: red test change, then the fix
      (api/edit-replace! sess 'ep.core 'f-t "(deftest f-t (is (= 11 (f 1))))"
                         :prompt "want +10 behavior")
      (api/edit-replace! sess 'ep.core 'f "(defn f [x] (+ x 10))"
                         :prompt "implement +10")
      (testing "query-changes = my work since my last stable spot"
        (let [c (api/query-changes sess)]
          (is (= 2 (count (:steps c))))
          (is (= #{'ep.core/f 'ep.core/f-t}
                 (set (map :form (:forms c)))))
          (let [f-chg (first (filter #(= 'ep.core/f (:form %)) (:forms c)))]
            (is (= :modified (:status f-chg)))
            (is (re-find #"inc x" (:was f-chg)))
            (is (re-find #"\+ x 10" (:now f-chg))))
          (testing "the red→green arc is visible"
            (is (= [1 0] (mapv :fail (:verification-arc c)))))))
      (testing "done closes the episode"
        (api/done! sess :label "plus-ten")
        (is (empty? (:forms (api/query-changes sess)))))
      (testing "collapsed history reads at episode grain"
        (let [rows (api/query-history sess :collapse true)]
          (is (some #(= "plus-ten" (get-in % [:episode :label])) rows))))
      (finally (api/close! sess)))))

(deftest ^:isolated parallel-agents-have-independent-episodes
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'ep.core seed)
      (api/done! sess :label "baseline")
      ;; two "sub-agents" interleave on one session
      (api/edit-replace! sess 'ep.core 'f "(defn f [x] (+ x 1 1))"
                         :prompt "alice's work" :agent "alice")
      (api/edit-replace! sess 'ep.core 'g "(defn g [x] (- x 2))"
                         :prompt "bob's work" :agent "bob")
      (testing "each agent sees only ITS episode"
        (is (= #{'ep.core/f}
               (set (map :form (:forms (api/query-changes sess :agent "alice"))))))
        (is (= #{'ep.core/g}
               (set (map :form (:forms (api/query-changes sess :agent "bob")))))))
      (testing "alice marking done does NOT close bob's episode"
        (api/done! sess :label "alice done" :agent "alice")
        (is (empty? (:forms (api/query-changes sess :agent "alice"))))
        (is (= #{'ep.core/g}
               (set (map :form (:forms (api/query-changes sess :agent "bob")))))))
      (finally (api/close! sess)))))

(deftest ^:isolated episode-revert-scraps-only-my-unshared-work
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'ep.core seed)
      (api/done! sess :label "baseline")
      ;; alice: modifies f, adds a helper — and touches the SHARED form h
      (api/edit-replace! sess 'ep.core 'f "(defn f [x] (* x 9))"
                         :prompt "alice attempt" :agent "alice")
      (api/edit-replace! sess 'ep.core 'f-t "(deftest f-t (is (= 9 (f 1))))"
                         :agent "alice")
      (api/add-form! sess 'ep.core "(defn alice-helper [x] x)" :agent "alice")
      (api/edit-replace! sess 'ep.core 'h "(defn h [x] :alice-touched)"
                         :agent "alice")
      ;; bob also touches h (the shared-form hazard)
      (api/edit-replace! sess 'ep.core 'h "(defn h [x] :bob-touched)"
                         :agent "bob")
      (let [r (api/revert-episode! sess :agent "alice")]
        (testing "alice's exclusive work is rolled back to the boundary"
          (is (nil? (:error r)) (pr-str r))
          (is (= [2] (api/query-eval sess "(ep.core/f 1)")))
          (is (not (re-find #"alice-helper" (api/query-source sess 'ep.core)))))
        (testing "the SHARED form is skipped and reported, not stomped"
          (is (= ['ep.core/h] (:skipped-shared r)))
          (is (re-find #":bob-touched" (api/query-source sess 'ep.core))))
        (testing "the revert is itself provenance, and tests are green again"
          (is (zero? (+ (:fail (:test r)) (:error (:test r)))))))
      (finally (api/close! sess)))))

(deftest ^:isolated turn-trees-from-label-paths                    ; P4-m6.1
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'ep.core seed)
      (api/done! sess :label "baseline" :agent "alice")
      ;; alice's turn: her own edit + two sub-agents she spawned
      (api/edit-replace! sess 'ep.core 'f "(defn f [x] (+ x 1))"
                         :prompt "alice's own step" :agent "alice")
      (api/edit-replace! sess 'ep.core 'g "(defn g [x] (- x 9))"
                         :prompt "sub tests work" :agent "alice/tests")
      (api/edit-replace! sess 'ep.core 'h "(defn h [x] :sub-impl)"
                         :prompt "sub impl work" :agent "alice/impl")
      (api/done! sess :label "alice turn done" :agent "alice")
      (testing "the collapsed history nests sub-agent episodes under the turn"
        (let [rows   (api/query-history sess :collapse true)
              alice  (first (filter #(= "alice turn done"
                                        (get-in % [:episode :label]))
                                    rows))
              kids   (set (map :agent (get-in alice [:episode :children])))]
          (is (some? alice))
          (is (= #{"alice/tests" "alice/impl"} kids))
          (testing "children don't ALSO appear as top-level rows"
            (is (not-any? #(= "alice/tests" (get-in % [:episode :agent]))
                          rows)))))
      (testing "deltas carry wall-clock provenance"
        (is (number? (:at (last (store/deltas (:store @sess))))))
        (is (every? #(number? (:at %)) (store/deltas (:store @sess)))))
      (finally (api/close! sess)))))

(deftest ^:isolated turn-markers-bracket-the-history               ; P4-m6.2
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'ep.core seed)
      (api/turn-begin! sess :agent "alice"
                       :intent "add rush-order support to checkout"
                       :user "nathan")
      (api/edit-replace! sess 'ep.core 'f "(defn f [x] (+ x 7))"
                         :prompt "step 1" :agent "alice")
      (api/edit-replace! sess 'ep.core 'g "(defn g [x] :sub-work)"
                         :prompt "sub step" :agent "alice/impl")
      (api/done! sess :label "rush support" :agent "alice")
      (let [r (api/turn-end! sess :agent "alice")]
        (is (nil? (:error r))))
      (testing "lineage + form-history resolve the enclosing turn's ask"
        (let [lin (api/query-lineage sess 'ep.core 'f)
              fh  (api/query-form-history sess 'ep.core 'f)]
          (is (= "add rush-order support to checkout"
                 (:turn-intent (last lin))))
          (is (= "add rush-order support to checkout"
                 (:turn-intent (last fh))))))
      (testing "the collapsed history has a TURN bracket with the verbatim ask"
        (let [rows (api/query-history sess :collapse true)
              turn (first (keep :turn rows))]
          (is (some? turn))
          (is (= "add rush-order support to checkout" (:intent turn)))
          (is (= "nathan" (:user turn)))
          (testing "the turn contains its episode tree (sub-agents nested)"
            (let [agents (set (concat (map :agent (:episodes turn))
                                      (mapcat #(map :agent (:children %))
                                              (:episodes turn))))]
              (is (contains? agents "alice"))
              (is (contains? agents "alice/impl"))))
          (testing "turn contents don't ALSO appear as top-level rows"
            (is (not-any? #(= "alice" (get-in % [:episode :agent])) rows)))))
      (finally (api/close! sess)))))

(deftest ^:isolated hook-driven-turn-markers-flow-through-the-journal
  ;; the Claude Code hooks path: a one-shot CLI appends the turn delta
  ;; OUT-OF-BAND; the agent's server absorbs it via journal sync (m5b)
  (let [dir (str (System/getProperty "java.io.tmpdir")
                 "/slopp-turn-" (System/nanoTime))
        sess (api/open! {:slopp.api/dir dir})]
    (try
      (api/ingest! sess 'ep.core seed)
      ;; simulate the UserPromptSubmit hook (separate process in production)
      (slopp.turn/-main dir "begin" "alice" "fix" "the" "flaky" "test")
      (api/sync-with-journal! sess)
      (api/edit-replace! sess 'ep.core 'f "(defn f [x] (* x 2))"
                         :prompt "the fix" :agent "alice")
      (slopp.turn/-main dir "end" "alice")
      (api/sync-with-journal! sess)
      (let [turn (first (keep :turn (api/query-history sess :collapse true)))]
        (is (= "fix the flaky test" (:intent turn)))
        (is (= 1 (count (:episodes turn)))))
      (finally
        (api/close! sess)
        (clojure.java.shell/sh "rm" "-rf" dir)))))

(deftest ^:isolated turn-gate-blocks-unrooted-writes               ; P4-m6.2 enforcement
  (let [sess (api/open!)]
    (try
      (swap! sess assoc :require-turns? true)   ; transport policy (real servers set this)
      (api/ingest! sess 'ep.core seed)          ; api-level stays ungated
      (let [call (fn [tool args]
                   (get-in (slopp.mcp/handle! sess
                                             {:id 1 :method "tools/call"
                                              :params {:name tool :arguments args}})
                           [:result :content 0 :text]))]
        (testing "a write with no open turn is refused, with teaching"
          (let [r (call "edit_replace_form"
                        {:ns "ep.core" :name "f" :agent "alice"
                         :source "(defn f [x] (* x 3))"})]
            (is (re-find #"turn_begin" r))))
        (testing "a write with no agent label still gates on the TURN (identity
                  is the session's now — labels are never demanded)"
          (is (re-find #"no open turn" (call "edit_add_form"
                                             {:ns "ep.core"
                                              :source "(defn zz [x] x)"}))))
        (testing "after turn_begin the same write lands"
          (call "turn_begin" {:agent "alice" :intent "triple f"})
          (let [r (call "edit_replace_form"
                        {:ns "ep.core" :name "f" :agent "alice"
                         :source "(defn f [x] (* x 3))"})]
            (is (not (re-find #"turn_begin" r)))))
        (testing "a sub-agent path rides the ROOT agent's open turn"
          (let [r (call "edit_add_form"
                        {:ns "ep.core" :agent "alice/impl"
                         :source "(defn sub-added [x] x)"})]
            (is (not (re-find #"turn_begin" r)))))
        (testing "after turn_end the gate closes again"
          (call "turn_end" {:agent "alice"})
          (is (re-find #"turn_begin"
                       (call "edit_delete_form"
                             {:ns "ep.core" :name "sub-added"
                              :agent "alice"})))))
      (finally (api/close! sess)))))

(deftest ^:isolated hook-json-mode-records-the-exact-user-words    ; the real hook shape
  (let [dir (str (System/getProperty "java.io.tmpdir")
                 "/slopp-hook-" (System/nanoTime))
        sess (api/open! {:slopp.api/dir dir})]
    (try
      (api/ingest! sess 'ep.core seed)
      ;; UserPromptSubmit pipes {"prompt": "..."} on stdin
      (with-in-str "{\"prompt\":\"please add rush orders — exactly these words\",\"session_id\":\"x\"}"
        (slopp.turn/-main dir "hook-begin" "alice"))
      (api/sync-with-journal! sess)
      (is (api/turn-open? sess "alice"))
      (api/edit-replace! sess 'ep.core 'f "(defn f [x] (* x 4))"
                         :prompt "work" :agent "alice")
      (with-in-str "{}"
        (slopp.turn/-main dir "hook-end" "alice"))
      (api/sync-with-journal! sess)
      (is (not (api/turn-open? sess "alice")))
      (let [turn (first (keep :turn (api/query-history sess :collapse true)))]
        (is (= "please add rush orders — exactly these words" (:intent turn))))
      (finally
        (api/close! sess)
        (clojure.java.shell/sh "rm" "-rf" dir)))))

(deftest ^:isolated history-drill-down-and-text-rendering          ; granularity gaps
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'ep.core seed)
      (api/turn-begin! sess :agent "alice" :intent "make f add ten")
      (api/edit-replace! sess 'ep.core 'f-t "(deftest f-t (is (= 11 (f 1))))"
                         :prompt "red first" :agent "alice")
      (api/edit-replace! sess 'ep.core 'f "(defn f [x] (+ x 10))"
                         :prompt "green" :agent "alice")
      (api/done! sess :label "plus-ten" :agent "alice")
      (api/turn-end! sess :agent "alice")
      ;; more work after, so the span is genuinely historical
      (api/edit-replace! sess 'ep.core 'g "(defn g [x] :later)"
                         :prompt "later work" :agent "bob")
      (testing "a PAST episode inspects like the current one: plug the
                from/to ids from its collapsed row into query_changes"
        (let [row  (first (keep :turn (api/query-history sess :collapse true)))
              ep   (first (:episodes row))
              c    (api/query-changes sess :agent "alice"
                                      :from (:from ep) :to (:to ep))]
          (is (= #{'ep.core/f 'ep.core/f-t}
                 (set (map :form (:forms c)))))
          (is (re-find #"inc x" (:was (first (filter #(= 'ep.core/f (:form %))
                                                     (:forms c))))))
          (is (= [1 0 0] (mapv :fail (:verification-arc c)))
            "red write, green fix, then the done-point's own verification")
          (testing "bob's later work is NOT in the span"
            (is (not-any? #(= 'ep.core/g (:form %)) (:forms c))))))
      (testing "format text renders a human story"
        (let [txt (api/query-history sess :collapse true :format "text")]
          (is (string? txt))
          (is (re-find #"make f add ten" txt))
          (is (re-find #"plus-ten" txt))))
      (finally (api/close! sess)))))

(deftest ^:isolated human-history-timestamps-diffs-and-intent-search
  ;; the human-side gaps: WHEN did it happen, WHAT changed (as a diff, not
  ;; two full sources), and finding a turn by what the user actually asked
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'ep.core seed)
      (api/turn-begin! sess :agent "alice" :intent "teach h to double, loudly")
      (api/edit-replace! sess 'ep.core 'h
                         "(defn h [x]\n  ;; loud on purpose\n  (* x 2))"
                         :prompt "make h double" :agent "alice")
      (api/done! sess :label "h doubles" :agent "alice")
      (api/turn-end! sess :agent "alice")
      ;; a later, still-open episode so was/now spans a real line-level change
      (api/edit-replace! sess 'ep.core 'h
                         "(defn h [x]\n  ;; loud on purpose\n  (* x 3))"
                         :prompt "actually triple" :agent "alice")
      (testing "collapsed rows carry human-readable timestamps"
        (let [rows (api/query-history sess :collapse true)
              turn (first (keep :turn rows))
              ep   (first (:episodes turn))]
          (is (re-matches #"\d{4}-\d{2}-\d{2} \d{2}:\d{2}" (str (:at turn))))
          (is (re-matches #"\d{4}-\d{2}-\d{2} \d{2}:\d{2}" (str (:at ep))))))
      (testing "raw rows and the text story show when, too"
        (is (re-matches #"\d{4}-\d{2}-\d{2} \d{2}:\d{2}"
                        (str (:at (first (api/query-history sess))))))
        (is (re-find #"@ \d{4}-\d{2}-\d{2} \d{2}:\d{2}"
                     (api/query-history sess :collapse true :format "text"))))
      (testing "contains searches TURN INTENTS in collapsed mode"
        (let [rows (api/query-history sess :collapse true :contains "loudly")]
          (is (= "teach h to double, loudly"
                 (:intent (:turn (first rows))))))
        (is (empty? (filter :turn (api/query-history sess :collapse true
                                                     :contains "zz-no-match")))))
      (testing "query-changes format=text renders line diffs with context"
        (let [txt (api/query-changes sess :agent "alice" :format "text")]
          (is (string? txt))
          (is (re-find #"actually triple" txt))            ; the step's prompt
          (is (re-find #"(?m)^\s+- .*\* x 2" txt))         ; removed line only
          (is (re-find #"(?m)^\s+\+ .*\* x 3" txt))        ; added line only
          ;; the unchanged line is CONTEXT, not re-emitted churn
          (is (re-find #"(?m)^\s+;; loud on purpose" txt))
          (is (not (re-find #"(?m)^\s*[-+] .*loud on purpose" txt)))))
      (testing "the EDN shape is unchanged when no format is asked for"
        (let [c (api/query-changes sess :agent "alice")]
          (is (map? c))
          (is (re-find #"\* x 2" (:was (first (:forms c)))))))
      (finally (api/close! sess)))))
(deftest ^:isolated done-always-verifies-the-episode
  ;; the done-point IS the test run — it must verify the episode's changes
  ;; even when normalization has nothing to rewrite
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'dv.core
                   (str "(ns dv.core (:require [clojure.test :refer [deftest is]]))\n"
                        "(defn f \"F.\" [x] (inc x))\n"
                        "(deftest f-t (is (= 2 (f 1))))\n"))
      (api/test-run! sess 'dv.core)
      (api/done! sess :label "baseline")
      (api/edit-replace! sess 'dv.core 'f "(defn f \"F2.\" [x] (inc x))"
                         :prompt "docstring only — normalize will not rewrite")
      (let [r (api/done! sess :label "tweaked")]
        (is (zero? (:normalized r)) (pr-str r))
        (is (pos? (:test (:test r) 0))
            (str "tests must run at the done-point even with zero rewrites: "
                 (pr-str r))))
      (finally (api/close! sess)))))
(deftest ^:isolated done-findings-resurface-in-the-next-session
  ;; a turn-end auto-done that leaves reds must greet the next session —
  ;; findings ride the boundary delta and surface in session-brief
  (let [dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-findings"
                   (make-array java.nio.file.attribute.FileAttribute 0)))
        sess (api/open! {:slopp.api/dir dir})]
    (try
      (api/ingest! sess 'fr.core
                   (str "(ns fr.core (:require [clojure.test :refer [deftest is]]))\n"
                        "(defn f \"F.\" [x] x)\n"
                        "(deftest f-t (is (= 1 (f 1))))\n"))
      (api/test-run! sess 'fr.core)
      (api/edit-replace! sess 'fr.core 'f "(defn f \"F.\" [x] (inc x))"
                         :prompt "breaks f-t")
      (let [r (api/done! sess :label "left red")]
        (is (pos? (+ (:fail (:test r) 0) (:error (:test r) 0))) (pr-str r)))
      (finally (api/close! sess)))
    (let [sess2 (api/open! {:slopp.api/dir dir})]
      (try
        (let [b (api/session-brief sess2)]
          (is (= "left red" (get-in b [:last-done :label])) (pr-str (:last-done b)))
          (is (pos? (get-in b [:last-done :findings :failures] 0))
              (pr-str (:last-done b))))
        (finally (api/close! sess2))))))
(deftest ^:isolated done-reaches-tests-in-other-namespaces
  ;; dogfooding catch: with no trace coverage, done's fallback ran "all
  ;; tests in the CHANGED nses" — which contain none when tests live in a
  ;; separate -test ns. The require-closure bounds the honest fallback.
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'dr.core "(ns dr.core)\n(defn f \"F.\" [x] (inc x))\n")
      (api/ingest! sess 'dr.core-test
                   (str "(ns dr.core-test (:require [dr.core :as c]\n"
                        "                           [clojure.test :refer [deftest is]]))\n"
                        "(deftest f-t (is (= 2 (c/f 1))))\n"))
      (api/done! sess :label "baseline")
      ;; break f WITHOUT ever running tests — the trace map knows nothing
      (api/edit-replace! sess 'dr.core 'f "(defn f \"F.\" [x] (+ x 2))"
                         :prompt "breaks f-t; only dr.core-test can prove it")
      (let [r (api/done! sess :label "must catch the red")]
        (is (pos? (:test (:test r) 0))
            (str "the done-point must reach dr.core-test: " (pr-str r)))
        (is (= :red (get-in r [:findings :test-status])) (pr-str (:findings r))))
      (finally (api/close! sess)))))
(deftest ^:isolated episode-reds-compress-to-direction
  ;; response diet for the REPL flow: full failure detail rides ONCE (when a
  ;; test newly goes red); re-running the same red mid-episode compresses to
  ;; :still-red names; recovery reports :went-green. Direction, not repetition.
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'er.core
                   (str "(ns er.core (:require [clojure.test :refer [deftest is]]))\n"
                        "(defn f \"F.\" [x] (inc x))\n"
                        "(deftest f-t (is (= 2 (f 1))))\n"))
      (api/test-run! sess 'er.core)
      (testing "the FIRST red carries full failure detail"
        (let [r (api/edit-replace! sess 'er.core 'f "(defn f \"F.\" [x] (+ x 9))"
                                   :prompt "break f-t")]
          (is (seq (get-in r [:test :failures])) (pr-str (:test r)))))
      (testing "the SAME red on the next write compresses to :still-red"
        (let [r (api/edit-replace! sess 'er.core 'f "(defn f \"F.\" [x] (+ x 8))"
                                   :prompt "still broken, differently")]
          (is (= '[er.core/f-t] (get-in r [:test :still-red])) (pr-str (:test r)))
          (is (empty? (get-in r [:test :failures]))
              "no re-printed expected/actual blocks")))
      (testing "recovery reports :went-green"
        (let [r (api/edit-replace! sess 'er.core 'f "(defn f \"F.\" [x] (inc x))"
                                   :prompt "fixed")]
          (is (= '[er.core/f-t] (get-in r [:test :went-green])) (pr-str (:test r)))))
      (finally (api/close! sess)))))
(deftest ^:isolated missing-doc-waits-for-the-done-point
  ;; advisories are episode-level concerns: writes stay quiet, the boundary
  ;; names the undocumented public surface once
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'md.core "(ns md.core)\n(defn seeded \"S.\" [x] x)\n")
      (api/done! sess :label "baseline")
      (testing "the write itself stays quiet"
        (let [r (api/add-form! sess 'md.core "(defn bare [x] x)"
                               :prompt "no docstring yet")]
          (is (nil? (:error r)) (pr-str r))
          (is (not-any? :missing-doc (:warnings r)) (pr-str (:warnings r)))))
      (testing "the done-point names the undocumented surface"
        (let [r (api/done! sess :label "review")]
          (is (= '[md.core/bare] (get-in r [:findings :missing-doc]))
              (pr-str (:findings r)))))
      (finally (api/close! sess)))))
(deftest ^:isolated done-separates-new-lint-from-carried
  ;; every done re-listed the same stale warnings from untouched forms —
  ;; alarm fatigue that buries real findings. NEW warnings (on forms this
  ;; episode touched) ride :lint in full; carried ones compress to a count
  ;; + form names. Errors are never demoted.
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'lc.core
                   (str "(ns lc.core)\n\n"
                        "(defn ^:unused-ok stale \"S.\" [x] (let [a x] (let [b a] b)))\n\n"
                        "(defn ^:unused-ok fresh \"F.\" [x] x)\n"))
      (api/done! sess :label "baseline" :agent "t")
      (api/edit-replace! sess 'lc.core 'fresh
                         "(defn ^:unused-ok fresh \"F.\" [x] (let [c x] (let [d c] d)))"
                         :prompt "introduce a new warning" :agent "t")
      (let [r (api/done! sess :label "the split" :agent "t")]
        (testing "the new warning rides in full"
          (is (some #(= 'lc.core/fresh (:form %)) (:lint r)) (pr-str (:lint r))))
        (testing "the carried one compresses to a count + names"
          (is (not-any? #(= 'lc.core/stale (:form %)) (:lint r)))
          (is (= 1 (:count (:lint-carried r))) (pr-str (:lint-carried r)))
          (is (some #{'lc.core/stale} (:forms (:lint-carried r))))))
      (finally (api/close! sess)))))
(deftest ^:isolated done-runs-impacted-isolated-tests
  ;; the tier is an implementation detail: done's contract is "everything
  ;; impacted, whatever tier" — ^:isolated tests reached by the episode's
  ;; changes run in the external JVM without the agent asking.
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'ti.core "(ns ti.core)\n\n(defn f \"F.\" [x] (* 2 x))\n"
                   :agent "t")
      (api/ingest! sess 'ti.core-test
                   (str "(ns ti.core-test (:require [ti.core :as core]\n"
                        "                           [clojure.test :refer [deftest is]]))\n\n"
                        "(deftest ^:isolated f-t (is (= 6 (core/f 3))))\n")
                   :agent "t")
      (let [r (api/done! sess :label "isolated impact" :agent "t")]
        (is (= 1 (:ran (:isolated r))) (pr-str (:isolated r)))
        (is (= :green (:status (:isolated r))))
        (is (= :green (get-in r [:findings :test-status]))
            (pr-str (:findings r))))
      (finally (api/close! sess)))))
(deftest ^:isolated done-caps-the-isolated-slice-and-reports
  ;; The cap is on TESTS (40) and it still exists for the honest reason: a
  ;; :only run is one serial JVM, and a change whose reach is most of the
  ;; suite belongs to the milestone gate. What CHANGED (#132): a fresh, small
  ;; slice used to defer too — the trace was silent about brand-new forms, one
  ;; silent form collapsed all narrowing, and the ns-grain closure blew a cap
  ;; of 4 on 84.6% of source namespaces. Per-form expansion means the small
  ;; case now RUNS and only a genuinely-wide reach defers.
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'hub.core "(ns hub.core)\n\n(defn f \"F.\" [x] x)\n"
                   :agent "t")
      ;; deep test nses: hub.core.uN-test folds into module hub.core, so the
      ;; fixture needs no edge ceremony
      (doseq [i (range 2)]
        (api/ingest! sess (symbol (str "hub.core.u" i "-test"))
                     (str "(ns hub.core.u" i "-test (:require [hub.core :as core]\n"
                          "                            [clojure.test :refer [deftest is]]))\n\n"
                          "(deftest ^:isolated t" i " (is (= 1 (core/f 1))))\n")
                     :agent "t"))
      (testing "a small fresh slice RUNS — brand-new tests are their own reach"
        (let [r (api/done! sess :label "small hub" :agent "t")]
          (is (nil? (get-in r [:findings :isolated-pending])) (pr-str (:findings r)))
          (is (= 2 (:ran (:isolated r))) (pr-str (:isolated r)))))
      ;; now push the reach over the cap: one ns, 41 isolated tests
      (api/ingest! sess 'hub.core.wide-test
                   (apply str
                          "(ns hub.core.wide-test (:require [hub.core :as core]\n"
                          "                          [clojure.test :refer [deftest is]]))\n\n"
                          (for [i (range 41)]
                            (str "(deftest ^:isolated w" i " (is (= 1 (core/f 1))))\n\n")))
                   :agent "t")
      (testing "over the cap: defers to the milestone gate, REPORTED as tests"
        (let [r (api/done! sess :label "wide hub" :agent "t")]
          (is (nil? (:isolated r)) "above the cap, nothing runs")
          (is (<= 41 (get-in r [:findings :isolated-pending :count]))
              (pr-str (:findings r)))
          (is (seq (get-in r [:findings :isolated-pending :tests])))))
      (finally (api/close! sess)))))
(deftest ^:isolated unused-publics-gate-the-done
  ;; unused public surface FAILS the done gate (error-grade lint + findings)
  ;; and refuses the milestone. The deliberate escape is ^:unused-ok on the
  ;; name — and a STALE marker (the var IS called now) fails symmetrically,
  ;; so the dial can never rot.
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'up.core
                   (str "(ns up.core)\n\n"
                        "(defn keeper \"K.\" [x] x)\n\n"
                        "(defn orphan \"O.\" [x] (keeper x))\n")
                   :agent "t")
      (testing "an unmarked unused public is an ERROR-grade finding"
        (let [r (api/done! sess :label "check" :agent "t")]
          (is (= '[up.core/orphan] (get-in r [:findings :unused-public]))
              (pr-str (:findings r)))
          (is (some #(and (= :unused-public (:type %)) (= :error (:level %)))
                    (:lint r))
              (pr-str (:lint r)))
          (is (pos? (get-in r [:findings :lint-errors])))))
      (testing "...and it refuses the milestone"
        (let [r (api/commit-point! sess "should refuse")]
          (is (re-find #"unused" (str (:error r))) (pr-str (dissoc r :test)))))
      (testing "the ^:unused-ok marker is the deliberate escape"
        (api/edit-replace! sess 'up.core 'orphan
                           "(defn ^:unused-ok orphan \"O.\" [x] (keeper x))"
                           :prompt "external surface" :agent "t")
        (let [r (api/done! sess :label "marked" :agent "t")]
          (is (nil? (get-in r [:findings :unused-public]))
              (pr-str (:findings r)))))
      (testing "a STALE marker fails too — remove the flag when it's called"
        (api/edit-replace! sess 'up.core 'keeper
                           "(defn ^:unused-ok keeper \"K.\" [x] x)"
                           :prompt "wrongly marked — orphan calls it" :agent "t")
        (let [r (api/done! sess :label "stale" :agent "t")]
          (is (= '[up.core/keeper] (get-in r [:findings :stale-unused-ok]))
              (pr-str (:findings r)))
          (is (some #(and (= :stale-unused-ok (:type %))
                          (re-find #"remove" (str (:message %))))
                    (:lint r)))))
      (finally (api/close! sess)))))
(deftest ^:isolated done-runs-the-traced-isolated-slice
  ;; The complement of done-caps-the-isolated-slice-and-reports. THAT one's tests
  ;; have never run, so the trace is silent and it proves the closure fallback
  ;; still defers honestly. This one HAS evidence, and proves done routes it —
  ;; rather than re-deriving the require-closure, which selects a median 43 of 46
  ;; isolated test namespaces (measured 2026-07-17), blows the cap, and gives up.
  ;;
  ;; Five test nses reach hub.core/f by require-closure. Exactly one has ever
  ;; touched it. Before #127 done deferred all five and ran nothing.
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'hub.core "(ns hub.core)\n\n(defn f \"F.\" [x] x)\n" :agent "t")
      (doseq [i (range 5)]
        (api/ingest! sess (symbol (str "hub.core.u" i "-test"))
                     (str "(ns hub.core.u" i "-test (:require [hub.core :as core]\n"
                          "                            [clojure.test :refer [deftest is]]))\n\n"
                          "(deftest ^:isolated t" i " (is (= 1 (core/f 1))))\n")
                     :agent "t"))
      (api/done! sess :label "setup" :agent "t")
      (swap! sess assoc :test-map {'hub.core.u0-test/t0 #{'hub.core/f}})
      (api/edit-replace! sess 'hub.core 'f "(defn f \"F.\" [x] (identity x))"
                         :prompt "traced edit" :agent "t")
      (let [r (api/done! sess :label "traced edit" :agent "t")]
        (is (nil? (get-in r [:findings :isolated-pending]))
            (str "the traced slice fits the cap — nothing should defer: "
                 (pr-str (:findings r))))
        (is (= 1 (:ran (:isolated r)))
            (str "exactly the one test the evidence names, not all five: "
                 (pr-str (:isolated r)))))
      (finally (api/close! sess)))))
(deftest ^:isolated undo-walks-back-by-delta-not-by-name
  ;; The hole undo! fills. edit_revert is NAME-addressed, so it cannot undo a
  ;; DELETE — the name is gone, so query-form-history returns nil and you get
  ;; "no form named". episode_revert reaches it but is all-or-nothing: it costs
  ;; you every unrelated good change in the episode. undo is DELTA-addressed,
  ;; which is the coordinate system in which a deleted form still exists.
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'un.core
                   (str "(ns un.core)\n\n"
                        "(defn a [] 1)\n\n"
                        "(defn b [] 2)\n"))
      (api/edit-replace! sess 'un.core 'a "(defn a [] 99)"
                         :prompt "bad edit" :agent "u")
      (testing "the default undoes the single last write"
        (let [r (api/undo! sess :agent "u" :prompt "undo the bad edit")]
          (is (nil? (:error r)) (pr-str r))
          (is (= 1 (:reverted r))))
        (is (re-find #"\(defn a \[\] 1\)" (api/query-source sess 'un.core))))
      (testing "undo restores a DELETED form — the case edit_revert cannot reach"
        (api/delete-form! sess 'un.core 'b :prompt "bad delete" :agent "u")
        (is (:error (api/revert-form! sess 'un.core 'b))
            "name-addressed revert has no name left to look up")
        (let [r (api/undo! sess :agent "u" :prompt "undo the bad delete")]
          (is (nil? (:error r)) (pr-str r))
          (is (= 1 (:reverted r))))
        (is (re-find #"\(defn b \[\] 2\)" (api/query-source sess 'un.core))))
      (testing "a chain that went off the rails comes back in one call"
        (api/edit-replace! sess 'un.core 'a "(defn a [] 7)" :prompt "w1" :agent "u")
        (api/edit-replace! sess 'un.core 'b "(defn b [] 8)" :prompt "w2" :agent "u")
        (api/add-form! sess 'un.core "(defn c [] 9)" :prompt "w3" :agent "u")
        (let [r (api/undo! sess :deltas 3 :agent "u"
                           :prompt "that whole line of work was wrong")]
          (is (nil? (:error r)) (pr-str r))
          (is (= 3 (:reverted r))))
        (let [src (api/query-source sess 'un.core)]
          (is (re-find #"\(defn a \[\] 1\)" src))
          (is (re-find #"\(defn b \[\] 2\)" src))
          (is (not (re-find #"defn c" src)))))
      (finally (api/close! sess)))))

(deftest ^:isolated lint-warnings-are-listed-never-blocking
  ;; D-rule-grain, expressed through the CONFIG rather than a second set:
  ;; slopp's kondo config tiers every linter by one question — could a form
  ;; legitimately look like this MID-EDIT? `unused-binding` routinely can, so
  ;; it is :warning: reported for the agent to judge, blocking nothing.
  ;; Measured: gating writes on warning-grade findings killed red-first TDD,
  ;; the module lifecycle and carried-lint compression (13 assertions, 7 tests).
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'lg2.core "(ns lg2.core)\n(defn ^:unused-ok ok \"D.\" [] 1)\n")
      (testing "a warning-grade finding does NOT refuse the write"
        (let [r (api/add-form! sess 'lg2.core
                               "(defn ^:unused-ok w \"D.\" [] (let [x 1] 2))"
                               :prompt "an unused binding")]
          (is (nil? (:error r)) (pr-str r))))
      (let [r (api/done! sess :label "with a warning")]
        (testing "done LISTS it, so it is visible without being enforced"
          (is (some #(= :unused-binding (:type %))
                    (get-in r [:findings :lint-warnings]))
              (pr-str (:findings r))))
        (testing "and it does not count — the done is still green"
          (is (zero? (get-in r [:findings :lint-errors])) (pr-str (:findings r)))
          (is (= :green (get-in r [:findings :test-status])) (pr-str (:findings r)))))
      (testing "an ERROR-grade finding still refuses the write"
        (let [r (api/add-form! sess 'lg2.core
                               "(defn ^:unused-ok bad \"D.\" [] (if (nil? 1) 2))"
                               :prompt "missing else branch is error-grade")]
          (is (:error r) (pr-str r))
          (is (re-find #"missing-else-branch" (str (:error r))) (str (:error r)))))
      (finally (api/close! sess)))))

(deftest ^:isolated done-runs-the-whole-suite-regardless-of-what-was-touched
  ;; Replaces one-untraced-form-no-longer-collapses-narrowing, which pinned the
  ;; #132 fix to impacted-test NARROWING. That machinery is gone: "done means
  ;; done" is a claim about the codebase, not about what the episode touched,
  ;; so done runs everything. Narrowing was ALSO a source of misses — one
  ;; untraced form (an ns form, which ns_add_require edits) used to discard the
  ;; evidence for every other form, on 54.4% of real episodes. This pins the
  ;; inverse, so nobody reintroduces selection for speed without deciding to.
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'wsa.core "(ns wsa.core)\n\n(defn f \"F.\" [x] x)\n\n(defn g \"G.\" [x] x)\n"
                   :agent "t")
      (api/ingest! sess 'wsb.core "(ns wsb.core)\n\n(defn h \"H.\" [x] x)\n" :agent "t")
      (api/ingest! sess 'wsa.core-test
                   (str "(ns wsa.core-test (:require [wsa.core :as a]\n"
                        "                            [clojure.test :refer [deftest is]]))\n\n"
                        "(deftest f-t (is (= 1 (a/f 1))))\n\n"
                        "(deftest g-t (is (= 1 (a/g 1))))\n")
                   :agent "t")
      (api/ingest! sess 'wsb.core-test
                   (str "(ns wsb.core-test (:require [wsb.core :as b]\n"
                        "                            [clojure.test :refer [deftest is]]))\n\n"
                        "(deftest h-t (is (= 1 (b/h 1))))\n")
                   :agent "t")
      (api/done! sess :label "setup" :agent "t")
      ;; touch ONE form in ONE namespace
      (api/edit-replace! sess 'wsa.core 'f "(defn f \"F.\" [x] (identity x))"
                         :prompt "one small edit" :agent "t")
      (let [r (api/done! sess :label "one edit" :agent "t")]
        (testing "every test in the store ran, not just the ones f reaches"
          (is (= 3 (:test (:test r))) (pr-str (:test r))))
        (testing "green, with no isolated-pending noise"
          (is (= :green (get-in r [:findings :test-status])) (pr-str (:findings r)))
          (is (nil? (get-in r [:findings :isolated-pending])))))
      (finally (api/close! sess)))))
