(ns slopp.api.history
  "Package-private history/status helpers for the slopp.api module: delta
  timeline readings (status-at/after, resolve-at, verify-at) and the human
  renderings (line diffs, change/form-history stories, timestamps). Deep ns
  — reachable only within slopp.api.* (and its specs); the public surface
  stays on slopp.api."
  (:require [clojure.string :as str]
            [slopp.store :as store]))

(defn verify-after
  "The `:verify` delta a write PRODUCED: the first one at or after `at-id`,
  since a write is immediately followed by its verification. Nil when none
  followed.

  Split out because `status-after` and the per-version COST both need it, and
  asking the same question at two call sites is this codebase's Pattern 2 —
  four instances, every one found only after two surfaces had already
  disagreed about the same fact."
  [store at-id]
  (->> (store/deltas store)
       (drop-while #(not= at-id (:id %)))
       (filter #(= :verify (:op %)))
       first))

(defn status-after
  "The verification outcome a delta PRODUCED: the first `:verify` at or after
  `at-id` (a write is immediately followed by its verify) — :green / :red /
  :unknown. This is 'did THIS version land green', vs `status-at`'s 'what
  was the state standing AT this point'."
  [store at-id]
  (if-let [r (:result (verify-after store at-id))]
    (if (zero? (+ (:fail r 0) (:error r 0))) :green :red)
    :unknown))

(defn human-time
  "Epoch ms → \"2026-07-04 09:15\" in the local zone (the human rendering of
  a delta's `:at`; agents keep the raw ms in the store)."
  [ms]
  (when ms
    (.format (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm")
             (java.time.LocalDateTime/ofInstant
              (java.time.Instant/ofEpochMilli ms)
              (java.time.ZoneId/systemDefault)))))

(defn ^:export diff-lines
  "Minimal LCS line diff turning `was` into `now` (either may be nil):
  [[:same|:del|:add line] ...]. Forms are small — clarity over speed.

  Exported: the reviewer UI renders this same diff, and a second LCS in the
  app layer would be a second answer to what changed in a form."
  [was now]
  (let [a   (if was (vec (str/split-lines was)) [])
        b   (if now (vec (str/split-lines now)) [])
        n   (count a)
        m   (count b)
        tbl (reduce (fn [tbl [i j]]
                      (assoc tbl [i j]
                             (if (= (a i) (b j))
                               (inc (get tbl [(inc i) (inc j)] 0))
                               (max (get tbl [(inc i) j] 0)
                                    (get tbl [i (inc j)] 0)))))
                    {}
                    (for [i (range (dec n) -1 -1)
                          j (range (dec m) -1 -1)]
                      [i j]))]
    (loop [i 0, j 0, out []]
      (cond
        (and (< i n) (< j m) (= (a i) (b j)))
        (recur (inc i) (inc j) (conj out [:same (a i)]))

        (and (< i n) (or (= j m) (>= (get tbl [(inc i) j] 0)
                                     (get tbl [i (inc j)] 0))))
        (recur (inc i) j (conj out [:del (a i)]))

        (< j m)
        (recur i (inc j) (conj out [:add (b j)]))

        :else out))))

(defn render-changes-text
  "query-changes as a human story: steps with prompts, per-form LINE diffs
  (context/-/+ — unchanged lines are never re-emitted as churn), and the
  red→green verification arc."
  [c]
  (str/join
   "\n"
   (concat
    [(str "changes" (when (:agent c) (str " [" (:agent c) "]"))
          " since " (:since c))]
    (when (seq (:steps c))
      (cons "steps:"
            (map #(str "  " (:id %) " " (name (:op %))
                       (when (:ns %) (str " " (:ns %)))
                       (when (:prompt %) (str " — " (:prompt %))))
                 (:steps c))))
    (when (seq (:forms c))
      (cons "forms:"
            (mapcat (fn [f]
                      (cons (str "  " (case (:status f)
                                        :added "+" :deleted "-" "~")
                                 " " (:form f))
                            (map (fn [[tag line]]
                                   (str "    " (case tag
                                                 :same "  "
                                                 :del  "- "
                                                 :add  "+ ")
                                        line))
                                 (diff-lines (:was f) (:now f)))))
                    (:forms c))))
    (when (seq (:verification-arc c))
      [(str "verification: "
            (str/join " → " (map #(if (zero? (:fail %))
                                    "green"
                                    (str "red(" (:fail %) ")"))
                                 (:verification-arc c))))]))))

(defn render-form-history-text
  "One form's LIFE as a story (HM4): each version's header (delta, op, the
  prompt/intent that produced it, its green/red, when) followed by the LINE
  diff FROM the previous version (the first version shows as all-added).
  Reuses `diff-lines` — unchanged lines are context, not churn."
  [qsym versions]
  (str/join
   "\n"
   (into [(str "form " qsym " — " (count versions) " version"
               (when (not= 1 (count versions)) "s"))]
         (mapcat
          (fn [prev v]
            (cons (str "  " (:delta v) " " (name (:op v))
                       (when-let [why (or (:prompt v) (:turn-intent v))]
                         (str " — " why))
                       "  [" (name (:status v)) "]"
                       (when (:at v) (str "  @ " (:at v))))
                  (map (fn [[tag line]]
                         (str "    " (case tag :same "  " :del "- " :add "+ ")
                              line))
                       (diff-lines (:source prev) (:source v)))))
          (cons nil versions)
          versions))))

(defn status-at
  "Verification status as of delta `at-id`: the last `:verify` delta at or
  before it — :green / :red / :unknown (no verification on record)."
  [store at-id]
  (let [upto (reduce (fn [acc d]
                       (let [acc (conj acc d)]
                         (if (= at-id (:id d)) (reduced acc) acc)))
                     [] (store/deltas store))
        v    (last (filter #(= :verify (:op %)) upto))]
    (if-let [r (:result v)]
      (if (zero? (+ (:fail r 0) (:error r 0))) :green :red)
      :unknown)))

(defn resolve-at
  "Normalize an `at` argument to a plain delta id: a `:commit` marker id
  becomes its `:target` (time-travel to a milestone points at the
  milestone's state); any other existing delta id passes through; an unknown
  id → nil (the caller reports it)."
  [store at]
  (when at
    (let [d (first (filter #(= at (:id %)) (store/deltas store)))]
      (cond
        (nil? d)            nil
        (= :commit (:op d)) (:target d)
        :else               at))))

(defn verify-at
  "The last `:verify` delta at or before `at-id` (the one governing that
  point), or nil."
  [store at-id]
  (let [upto (reduce (fn [acc d]
                       (let [acc (conj acc d)]
                         (if (= at-id (:id d)) (reduced acc) acc)))
                     [] (store/deltas store))]
    (last (filter #(= :verify (:op %)) upto))))

(defn revert-steps
  "Pure: turn a `query-changes` result into the edit-group steps that put every
  form back the way it was, holding back any form another agent also touched.
  `others` is the set of form-ids those agents wrote in the span — those are
  never stomped. Returns `{:steps [...] :shared [qualified-form ...]}`.

  The inverse of an `:added` form is a delete, of a `:modified` form a replace
  with its prior source, and of a `:deleted` form an ADD of the source the log
  still holds — which is why delta-addressed undo reaches a deleted form at all
  and name-addressed revert cannot.

  Namespace creation has no inverse: the (ns …) form itself is never deleted
  (every delete path refuses that as a destructive-write guard), so reverting
  an :ingest empties the namespace but leaves its shell in place."
  [changes others]
  (let [{shared true mine false} (group-by #(contains? others (:form-id %))
                                           (:forms changes))]
    {:shared (mapv :form shared)
     :steps  (vec (keep (fn [{:keys [form status was]}]
                          (let [ns-sym (symbol (namespace form))
                                nm     (symbol (name form))]
                            (case status
                              :modified {:action :replace :ns ns-sym
                                         :name nm :source was}
                              :added    (when-not (= (str ns-sym) (str nm))
                                          {:action :delete :ns ns-sym
                                           :name nm})
                              :deleted  {:action :add :ns ns-sym
                                         :source was})))
                        mine))}))

(defn render-history-text
  "query-history rows as a human story, one line per row — TURN headers with
  their nested episodes, episode summaries, COMMIT lines, and raw delta rows.
  The text sibling of `render-changes-text`: pure rows-in, string-out, so the
  rendering is testable by value and `query-history` keeps only its query."
  [rows]
  (clojure.string/join
   "\n"
   (mapcat
    (fn [row]
      (cond
        (:turn row)
        (let [t (:turn row)]
          (cons (str "TURN [" (:agent t) (when (:user t)
                                           (str " for " (:user t)))
                     "] " (or (some-> (:intent t)
                                      (clojure.string/split-lines)
                                      first)
                              "(no intent)")
                     (when (:open? t) "  (open)")
                     (when (:at t) (str "  @ " (:at t))))
                (map (fn [e]
                       (str "  episode " (:agent e)
                            (when (:label e) (str " \"" (:label e) "\""))
                            ": " (:ops e) " ops, " (:forms e) " forms"
                            (when (:open? e) " (open)")
                            (when (:at e) (str "  @ " (:at e)))))
                     (:episodes t))))
        (:episode row)
        (let [e (:episode row)]
          [(str "episode " (or (:agent e) "-")
                (when (:label e) (str " \"" (:label e) "\""))
                ": " (:ops e) " ops, " (:forms e) " forms"
                (when (:open? e) " (open)")
                (when (:at e) (str "  @ " (:at e))))])
        (:commit row)
        (let [c (:commit row)]
          [(str "COMMIT \"" (:description c) "\""
                (when (:agent c) (str " [" (:agent c) "]"))
                (when (= :red (:status c)) "  (RED)")
                (when (:at c) (str "  @ " (:at c))))])
        (contains? row :why)   ; a dead-end row: {:at :why :forms :namespaces :undid}
        [(str "DEAD-END"
              (when (:why row) (str " \"" (:why row) "\""))
              (when (seq (:forms row)) (str "  dropped: " (str/join " " (:forms row))))
              (when (seq (:undid row)) (str "  (undid " (str/join " " (:undid row)) ")"))
              (when (:at row) (str "  @ " (:at row))))]

        :else
        [(str (:id row) " " (:op row)
              (when (:agent row) (str " [" (:agent row) "]"))
              (when (:prompt row) (str " — " (:prompt row)))
              (when (:at row) (str "  @ " (:at row))))]))
    rows)))

(defn ^:export delta-fids
  "The form ids a delta touched — THE accessor for that question, so the
  two shapes (`:form-id` for a single write, `:form-ids` for a group) are
  read in one place and cannot drift apart in a second reader."
  [d]
  (concat (when (:form-id d) [(:form-id d)]) (:form-ids d)))

(defn collapse-rows [ ds pos rows contains limit]
  (let [turn-brackets
                  (vec (mapcat (fn [[agent ms]]
                                 (loop [ms ms, open nil, out []]
                                   (if-let [m (first ms)]
                                     (cond
                                       (= :turn-begin (:op m))
                                       (recur (rest ms) m out)
                                       (and open (= :turn-end (:op m)))
                                       (recur (rest ms) nil
                                              (conj out {:agent agent
                                                         :intent (:intent open)
                                                         :user (:user open)
                                                         :at (human-time (:at open))
                                                         :from (:id open)
                                                         :to (:id m)}))
                                       :else (recur (rest ms) open out))
                                     (if open
                                       (conj out {:agent agent :open? true
                                                  :intent (:intent open)
                                                  :user (:user open)
                                                  :at (human-time (:at open))
                                                  :from (:id open)})
                                       out))))
                               (group-by :agent
                                         (filter #(contains? #{:turn-begin :turn-end}
                                                             (:op %))
                                                 ds))))
                  parent-of (fn [agent]
                              (when-let [i (and agent
                                                (clojure.string/last-index-of agent "/"))]
                                (subs agent 0 i)))
                  contains?* (fn [p c]        ; child's span inside parent's span
                               (let [pf (get pos (get-in p [:episode :from]) 0)
                                     pt (get pos (get-in p [:episode :to])
                                             Long/MAX_VALUE)
                                     cf (get pos (get-in c [:episode :from]) 0)]
                                 (and (<= pf cf) (<= cf pt))))
                  kids   (filter #(parent-of (get-in % [:episode :agent])) rows)
                  tops   (remove #(parent-of (get-in % [:episode :agent])) rows)
                  nested (mapv (fn [p]
                                 (let [cs (filterv #(and (= (parent-of
                                                             (get-in % [:episode :agent]))
                                                            (get-in p [:episode :agent]))
                                                         (contains?* p %))
                                                   kids)]
                                   (if (seq cs)
                                     (update p :episode assoc :children
                                             (mapv :episode cs))
                                     p)))
                               tops)
            ;; orphans: children whose parent episode isn't in view
                  claimed (into #{} (mapcat #(get-in % [:episode :children])) nested)
                  orphans (remove #(claimed (:episode %)) kids)
                  eps     (concat nested orphans)
                  in-turn? (fn [t e]
                             (let [ta (:agent t)
                                   ea (get-in e [:episode :agent])]
                               (and ea ta
                                    (or (= ea ta)
                                        (clojure.string/starts-with? ea (str ta "/")))
                                    (<= (get pos (:from t) 0)
                                        (get pos (get-in e [:episode :from]) 0))
                                    (<= (get pos (get-in e [:episode :from]) 0)
                                        (get pos (:to t) Long/MAX_VALUE)))))
                  turns   (mapv (fn [t]
                                  {:turn (assoc t :episodes
                                                (mapv :episode
                                                      (filter #(in-turn? t %) eps)))})
                                turn-brackets)
                  claimed-eps (into #{}
                                    (mapcat #(get-in % [:turn :episodes]))
                                    turns)
                  eps     (remove #(claimed-eps (:episode %)) eps)
                  ;; commit points: the MILESTONE grain above turns
                  commits (vec (for [d ds :when (= :commit (:op d))]
                                 {:commit
                                  (cond-> {:id          (:id d)
                                           :description (:description d)
                                           :target      (:target d)
                                           :at          (human-time (:at d))}
                                    (:agent d)  (assoc :agent (:agent d))
                                    (:status d) (assoc :status (:status d)))}))]
              (->> (concat turns eps commits)
                   (sort-by #(- (get pos (or (get-in % [:episode :to])
                                             (get-in % [:episode :from])
                                             (get-in % [:turn :to])
                                             (get-in % [:turn :from])
                                             (get-in % [:commit :id]))
                                     0)))
                   (filter #(or (nil? contains)
                                ;; commits match their description; turns
                                ;; match what the USER said (intent, user,
                                ;; agent, contained episode labels);
                                ;; episodes on label/agent
                                (clojure.string/includes?
                                 (cond
                                   (:commit %)
                                   (str (get-in % [:commit :description]) " "
                                        (get-in % [:commit :agent]))
                                   (:turn %)
                                   (let [t (:turn %)]
                                     (clojure.string/join
                                      " " (concat [(:intent t) (:user t) (:agent t)]
                                                  (map :label (:episodes t)))))
                                   :else
                                   (str (get-in % [:episode :label]) " "
                                        (get-in % [:episode :agent])))
                                 contains)))
                   (take limit)
                   vec)))

(defn episode-rows [relevant]
  (mapcat
                          (fn [[agent ads]]
                            (loop [ads ads, cur [], out []]
                              (if-let [d (first ads)]
                                (if (= :done (:op d))
                                  (recur (rest ads) []
                                         (if (seq cur)
                                           (conj out {:episode
                                                      (cond-> {:agent agent
                                                               :label (:label d)
                                                               :at    (human-time (:at d))
                                                               :from  (:id (first cur))
                                                               :to    (:id d)
                                                               :ops   (count cur)
                                                               :forms (count (distinct (mapcat delta-fids cur)))}
                                                        (nil? agent) (dissoc :agent))})
                                           out))
                                  (recur (rest ads) (conj cur d) out))
                                (if (seq cur)
                                  (conj out {:episode
                                             (cond-> {:agent agent
                                                      :open? true
                                                      :at    (human-time (:at (last cur)))
                                                      :from  (:id (first cur))
                                                      :ops   (count cur)
                                                      :forms (count (distinct (mapcat delta-fids cur)))}
                                               (nil? agent) (dissoc :agent))})
                                  out))))
                          (group-by :agent relevant)))

(defn dead-ends
  "The scrapped lines of work in the log — the `:revert` markers — newest
  first, each `{:at :why :forms :namespaces :undid}`. `match` (a namespace or
  qualified form, symbol or string) narrows to dead-ends that touched it.

  A dead end that vanished teaches nothing; recorded, it answers 'did someone
  already try this here, and why did they drop it?' before the work is
  re-walked."
  ([store] (dead-ends store nil))
  ([store match]
   (let [m     (some-> match str)
         ns-of (fn [f] (namespace (symbol (str f))))
         hit?  (fn [d]
                 (or (nil? m)
                     (some (fn [f] (or (= m (str f)) (= m (ns-of f))))
                           (:forms d))))]
     (->> (:deltas store)
          (filter #(= :revert (:op %)))
          (filter hit?)
          reverse
          (mapv (fn [d]
                  (cond-> {:at         (human-time (:at d))
                           :why        (:why d)
                           :forms      (vec (:forms d))
                           :namespaces (vec (distinct (keep #(some-> (ns-of %) symbol)
                                                            (:forms d))))}
                    (:undid d) (assoc :undid (:undid d)))))))))

(defn ^:export milestone-rows
  "Milestones newest first, as a PURE fold over the delta log:
  `[{:commit :description :target :status :at :agent :sha}]`. `:sha` is
  present only when the DELTA carries one (imported markers do from birth);
  the projection's pinning table is a db read and lives one tier up, in
  `slopp.api/query-commits`, which is this fold plus that join.

  `:titles-only true` is the LIST rung: each description trimmed to its
  first line, with the remaining non-blank lines counted into
  `:more-lines`. Needing one sha used to fetch five whole milestone essays.

  Exported: the reviewer UI's timeline is a pure reader and would otherwise
  have to fold the same log a second time."
  [store & {:keys [titles-only]}]
  (let [rows (->> (store/deltas store)
                  (filter #(= :commit (:op %)))
                  reverse
                  (mapv (fn [d]
                          (cond-> {:commit      (:id d)
                                   :description (:description d)
                                   :target      (:target d)
                                   :status      (:status d)
                                   :at          (human-time (:at d))}
                            (:agent d)   (assoc :agent (:agent d))
                            (:git-sha d) (assoc :sha (:git-sha d))))))]
    (if-not titles-only
      rows
      (mapv (fn [row]
              (let [lines (str/split-lines (str (:description row)))
                    body  (count (remove str/blank? (rest lines)))]
                (cond-> (assoc row :description (first lines))
                  (pos? body) (assoc :more-lines body))))
            rows))))

(defn ^:export form-effort
  "What one form COST to get green — the semantic × history join, over the
  `versions` the caller already derived.

  A git log can tell you a file changed twelve times. This says how many of
  those landed RED, how many red→green recoveries it took, how many distinct
  ASKS shaped it, and how much verification time is recorded against it —
  provenance × verification × cost, all out of one journal.

  `:cycles` is the number the others cannot supply and the one worth reading:
  a red→green transition is a thing that had to be FIXED, so a form with two
  versions and two cycles was harder than one with twenty and none.

  **`:measured` is not decoration.** Verification only started recording `:ms`
  recently, so most of a long-lived form's history carries no cost at all — a
  bare sum reads as \"this form cost 42ms\" when it means \"the four versions we
  measured cost 42ms\". `:verification-ms` is nil rather than 0 when nothing was
  measured, because zero would read as \"measured, and it was free\".

  Takes versions rather than the store deliberately: `query-form-history`
  already derives them with their statuses and intents, and a second walk here
  is the shape that lets two surfaces disagree about one form's life."
  [qsym versions]
  (let [vs      (vec versions)
        costs   (keep :ms vs)
        cycles  (count (filter (fn [[a b]] (and (= :red (:status a))
                                                (= :green (:status b))))
                               (map vector vs (rest vs))))]
    {:form            qsym
     :versions        (count vs)
     :reds            (count (filter #(= :red (:status %)) vs))
     :cycles          cycles
     :asks            (count (distinct (keep #(or (:prompt %) (:turn-intent %)) vs)))
     :verification-ms (when (seq costs) (reduce + costs))
     :measured        {:with-cost (count costs) :of (count vs)}}))
