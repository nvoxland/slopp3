(ns slopp.mcp.smells
  (:require [clojure.edn :as edn]
            [slopp.db :as db]
            [slopp.mcp.tools :as tools]))

(defn bump-smell-counts
  "Fold one tool call into the smell counters (pure). :done-now is set
  only on the done/commit_point call itself (true when a test_run
  preceded it with no intervening write — the redundant pre-flight);
  every other call clears it."
  [c tool args]
  (let [c (-> (merge {:test-runs 0 :history 0 :dumps 0 :renames 0 :searches 0} c)
              (dissoc :done-now))]
    (cond
      (= tool "query_search")
      (update c :searches inc)

      (= tool "test_run")
      ;; the ISOLATED suite before a milestone is the documented gate —
      ;; only in-image runs count toward the redundant-pre-flight smell
      (-> c (update :test-runs (if (:external args) identity inc))
          (assoc :searches 0))

      ;; report is a history read too — it composes the same fan-out, so a
      ;; handoff that stitches report + query_history should still register
      (#{"query_history" "query_changes" "report"} tool)
      (update c :history inc)

      (and (= tool "query_source") (:full args))
      (update c :dumps inc)

      (= tool "edit_rename")
      (-> c (update :renames inc) (assoc :test-runs 0))

      (#{"done" "commit_point"} tool)
      (assoc c :done-now (pos? (:test-runs c 0)) :test-runs 0)

      (tools/write-tools tool)
      (assoc c :test-runs 0)

      :else (assoc c :searches 0))))

(def smell-registry
  "Deterministic bad-usage smells → one-line redirections naming the better
  tool. EXPAND HERE as new smells surface (dogfooding is the source): each
  entry is [key fires?-pred msg] over the bump-smell-counts map — one
  entry, no plumbing. Fire policy (once per session + 30-min per-store
  cooldown) lives in track-hint!; messages are suggestions, never refusals."
  [[:test-runs #(>= (:test-runs %) 3)
    "every write already verifies (its result includes :test) — test_run is rarely needed"]
   [:pre-done-test :done-now
    "done already runs the affected tests for everything you touched — a pre-flight test_run is redundant; mid-episode runs are for spot-checks"]
   [:history #(>= (:history %) 2)
    "stitching history calls — report {since/contains} composes milestones + changes + asks in ONE read"]
   [:dumps #(>= (:dumps %) 2)
    "repeated whole-namespace dumps — query_slice {ns name} gives one form's source + cards for what it reaches; targets [{ns name}] reads named forms"]
   [:renames #(>= (:renames %) 2)
    "several renames — if this is one CONCEPT changing name, rename_sweep {from to} does namespaces + vars + keys + prose in ONE call"]
   [:searches #(>= (:searches %) 3)
    "a search streak — asking a QUESTION instead may be one call: query_depends {on X} (who uses/what reaches), query_slice {ns name} (a neighborhood), report {contains} (history)"]])

(defn track-hint!
  "Run the smell registry over this call: bump counters, then let the FIRST
  fireable smell speak — `some` skips already-fired ones, so one smell never
  shadows another (the old cond did). Anti-spam by construction: each smell
  fires ONCE per session AND at most once per 30 minutes per STORE (db-meta
  cooldown survives sessions)."
  [session tool args]
  (let [s (::stats (swap! session update ::stats
                          #(bump-smell-counts % tool args)))
        fire! (fn [k msg]
                (when-not (contains? (:fired s) k)
                  (let [conn (:db @session)
                        now  (System/currentTimeMillis)
                        hist (or (when conn
                                   (try (some-> (db/get-meta conn "hint-cooldowns")
                                                edn/read-string)
                                        (catch Exception _ nil)))
                                 {})]
                    (when (or (nil? conn)
                              (< (* 30 60 1000) (- now (get hist k 0))))
                      (swap! session update-in [::stats :fired] (fnil conj #{}) k)
                      (when conn
                        (try (db/set-meta! conn "hint-cooldowns"
                                           (pr-str (assoc hist k now)))
                             (catch Exception _ nil)))
                      msg))))]
    (some (fn [[k pred msg]] (when (pred s) (fire! k msg)))
          smell-registry)))
