(ns slopp.read.history
  "The store over TIME — when, by whom, and in what state. One namespace,
  because a timeline reading and the query that reports it are one subject.

  Two halves that lived apart until they did not: the pure FOLDS over the
  delta log (status at and after a delta, milestone rows, line diffs,
  timestamps, what one form cost to get green) and the READS built on them
  (lineage, a form's every version, delta-log search, the log as a story, an
  episode's net per-form diffs, and time travel to a form as its source
  stood). The reads arrived from `slopp.read.query`, which held them beside
  two unrelated subjects.

  Spans take NAMED anchors (`:start`, `:last-commit`, `:last-done`) rather
  than raw delta ids. Demanding an id sent agents hunting through
  `query_history` for one, and when the hunt did not pay off they left for
  `git diff` instead — eval9 measured ~20k chars of it in a single handoff
  step.

  The anchor everything here is relative to is an EPISODE boundary, and it is
  per-agent: `episode-boundary` resolves to that agent's own last `:done`, so
  two agents working one store read different spans out of the same log."
  (:require [clojure.string :as str]
            [slopp.store :as store] [rewrite-clj.node :as n]))

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

(defn ^:export human-time
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

(defn ^:export status-at
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

(defn ^:export revert-steps
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

(defn ^:export label-ancestors
  "The ancestor prefixes of a `/`-delimited agent label, root-first:
  \"a/b/c\" → (\"a\" \"a/b\" \"a/b/c\"). A sub-agent labels itself by appending to
  its parent's path, so `turn-intents` walks these prefixes to resolve a
  delta's enclosing turn through its root agent's `turn-begin`."
  [agent-label]
  (when agent-label
    (let [parts (clojure.string/split agent-label #"/")]
      (map #(clojure.string/join "/" (take (inc %) parts))
           (range (count parts))))))

(defn ^:export turn-intents
  "delta-id → the enclosing turn's verbatim :intent (resolved through the
  delta's agent, sub-agent path labels riding their root's turn). Derived
  at query time; truncated for display."
  [ds]
  (loop [ds ds, open {}, out {}]
    (if-let [d (first ds)]
      (let [open (case (:op d)
                   :turn-begin (assoc open (:agent d) (:intent d))
                   :turn-end   (dissoc open (:agent d))
                   open)
            in   (some open (or (label-ancestors (:agent d)) []))
            out  (if in
                   (assoc out (:id d)
                          (if (> (count in) 160)
                            (str (subs in 0 157) "...")
                            in))
                   out)]
        (recur (rest ds) open out))
      out)))

(defn ^:export query-lineage
  "Provenance chain for `nm`: the deltas that created or changed its form (who
  touched it, via which op, driven by which prompt)."
  [session ns-sym nm]
  (let [st (:store @session)
        id (:id (store/form-named st ns-sym nm))]
    (when id
      (let [ti (turn-intents (store/deltas st))]
        (->> (store/deltas st)
             (filter (fn [d]
                       (or (= id (:form-id d))
                           (some #{id} (:form-ids d)))))
             ;; lean: bulk content lives in query-form-history, not here
             (mapv #(cond-> (dissoc % :sources :changeset :result)
                      (ti (:id %)) (assoc :turn-intent (ti (:id %))))))))))

(defn ^:export query-form-history
  "Every content version of `nm`'s form, oldest first, with the intent that
  produced it, when, the verification state it landed in, and what that
  verification COST:
  [{:delta :op :prompt :source :status :at :turn-intent :ms}]. `:status`
  (was-green-at, HM2) is the project's verification state governing each
  version — semantic × history, per form.

  Three views over ONE derivation of the form's life, because a second walk is
  how two surfaces come to disagree about the same version:
  - default — the versions as data;
  - `:format \"text\"` (HM4) — the form's LIFE as a per-version LINE-diff story;
  - `:effort true` — what the form COST to get green (`history/form-effort`):
    red→green cycles, distinct asks, recorded time, and how much of the life
    that time actually covers."
  [session ns-sym nm & {:keys [format effort]}]
  (let [st (:store @session)
        id (:id (store/form-named st ns-sym nm))]
    (when id
      (let [ti       (turn-intents (store/deltas st))
            versions (vec (for [d     (store/deltas st)
                                :let  [src (get-in d [:sources id])]
                                :when src]
                            (cond-> {:delta (:id d) :op (:op d)
                                     :prompt (:prompt d) :source src
                                     :status (status-after st (:id d))
                                     :at (human-time (:at d))}
                              (ti (:id d)) (assoc :turn-intent (ti (:id d)))
                              ;; the cost the verification recorded, present
                              ;; only for versions written after verification
                              ;; started timing itself — form-effort reports
                              ;; that coverage rather than summing past it
                              (get-in (verify-after st (:id d)) [:result :ms])
                              (assoc :ms (get-in (verify-after st (:id d))
                                                 [:result :ms])))))]
        (cond
          effort (form-effort (symbol (str ns-sym) (str nm)) versions)
          (= "text" (some-> format name))
          (render-form-history-text (symbol (str ns-sym) (str nm)) versions)
          :else versions)))))

(defn ^:export query-search-history
  "Delta-log search — the 'which prompts touched X' query. Case-insensitive
  substring match of `pattern` against each delta's prompt, done label,
  commit/turn description, turn-end note, AND its enclosing turn intent;
  returns the matching deltas NEWEST-first with the forms they touched (as
  ns/name qsyms, resolved as of that delta) and the human time. `:limit`
  (default 25). Pairs with `query-form-at`/`query-lineage` to drill in."
  [session pattern & {:keys [limit] :or {limit 25}}]
  (if (str/blank? (str pattern))
    {:error "query-search-history needs a non-blank pattern"}
    (let [st  (:store @session)
          ds  (store/deltas st)
          ti  (turn-intents ds)
          pat (str/lower-case (str pattern))
          hit? (fn [d]
                 (some #(and % (str/includes? (str/lower-case (str %)) pat))
                       [(:prompt d) (:label d) (:description d) (:note d)
                        (ti (:id d))]))
          form-name (fn [d fid]
                      (or (some-> (get-in d [:sources fid]) store/name-of-source str)
                          (some-> (store/form-by-id st fid) :name str)
                          (when (= fid (:form-id d)) (some-> (:name d) str))
                          (str fid)))
          touched (fn [d]
                    (vec (for [fid (delta-fids d)]
                           (symbol (str (or (store/ns-of-form-id st fid) (:ns d)))
                                   (form-name d fid)))))]
      (->> ds
           reverse
           (filter hit?)
           (take (or limit 25))
           (mapv (fn [d]
                   (cond-> {:delta (:id d) :op (:op d) :at (human-time (:at d))}
                     (:prompt d)      (assoc :prompt (:prompt d))
                     (:label d)       (assoc :label (:label d))
                     (:description d) (assoc :description (:description d))
                     (:note d)        (assoc :note (:note d))
                     (ti (:id d))     (assoc :turn-intent (ti (:id d)))
                     (seq (delta-fids d)) (assoc :forms (touched d)))))))))

(defn ^:export query-history
  "The delta log as a story, newest first. Filters: `:ns`, `:contains`
  (substring of prompt/label — and, collapsed, of turn intents). `:limit`
  (default 20). `:collapse true` returns EPISODE rows instead of raw deltas —
  one row per agent-work-unit between done-points, the readable long-term
  view. `:dead-ends true` lists SCRAPPED explorations (the reverts) with their
  why and the forms they dropped; a namespace/form string narrows to those
  that touched it. All rows carry `:at` (local date-time)."
  [session & {:keys [ns contains limit collapse format]
              dead-ends? :dead-ends
              :or {limit 20}}]
  (let [
        rows
        (cond
          dead-ends?
          (dead-ends (:store @session)
                     (when (string? dead-ends?) dead-ends?))

          collapse
          (let [ds       (store/deltas (:store @session))
                relevant (filter #(or (contains? #{:ingest :add :replace :delete
                                                   :rename :normalize :move :merge}
                                                 (:op %))
                                      (= :done (:op %)))
                                 ds)
                pos      (into {} (map-indexed (fn [i d] [(:id d) i])) ds)
                rows     (episode-rows relevant)]
            (collapse-rows  ds pos rows contains limit))

          :else
          (->> (store/deltas (:store @session))
               reverse
               (filter #(or (nil? ns) (= ns (:ns %))))
               (filter #(or (nil? contains)
                            (some (fn [s] (and s (clojure.string/includes? (str s) contains)))
                                  [(:prompt %) (:label %)])))
               (take limit)
               (mapv (fn [d]
                       (cond-> (select-keys d [:id :op :ns :prompt :label :group
                                               :agent :form-id :form-ids :old
                                               :new :before])
                         (:at d) (assoc :at (human-time (:at d))))))))]
    (cond-> rows
      (= "text" (some-> format name)) render-history-text)))

(def ^:export content-ops
  #{:ingest :add :replace :delete :rename :normalize :move :merge})

(defn ^:export episode-boundary
  "Where `agent-label`'s episode begins: its own last :done — or, for
  an agent that has never marked done, the last stable spot (ANY agent's
  done) before its first activity, so pre-existing history is never
  mistaken for contested work. nil = log start."
  [store agent-label]
  (let [ds  (store/deltas store)
        own (last (filter #(and (= :done (:op %))
                                (= agent-label (:agent %)))
                          ds))]
    (:id (or own
             (let [ckpts     (filter #(= :done (:op %)) ds)
                   first-own (first (filter #(and (contains? content-ops (:op %))
                                                  (= agent-label (:agent %)))
                                            ds))]
               (if first-own
                 (let [pos  (into {} (map-indexed (fn [i d] [(:id d) i])) ds)
                       fpos (get pos (:id first-own))]
                   (last (filter #(< (get pos (:id %)) fpos) ckpts)))
                 (last ckpts)))))))

(defn ^:export episode-span
  "Deltas after `agent`'s episode boundary (all agents' — callers filter)."
  [store agent]
  (let [ds (store/deltas store)]
    (if-let [b (episode-boundary store agent)]
      (rest (drop-while #(not= b (:id %)) ds))
      ds)))

(defn span-anchor
  "Resolve a NAMED span anchor to the delta id a span should START at:
  `:start` (the whole log), `:last-commit` (work since the last milestone),
  `:last-done` (work since the last done) — the same vocabulary `undo!`
  accepts, as keyword or wire string. Anything else passes through as a
  literal delta id.

  Why: `from` used to demand a raw delta id, so \"what changed across this
  lifetime, with code\" began with a hunt through `query_history` for the
  right id — and when the hunt didn't pay off, agents left for `git diff`
  (eval9 measured ~20k chars of it in one handoff step). An anchor that
  points at nothing resolves to nil, which falls back to the episode view
  rather than throwing."
  [st from]
  (let [ds     (store/deltas st)
        named? (fn [ks] (contains? ks from))
        ;; the delta AFTER the last marker — the span starts with the work
        ;; that FOLLOWS the milestone/done, not the marker itself
        after  (fn [pred]
                 (when-let [m (last (filter pred ds))]
                   (:id (second (drop-while #(not= (:id m) (:id %)) ds)))))]
    (cond
      (named? #{:start "start" ":start"})
      (:id (first ds))

      (named? #{:last-commit "last-commit" ":last-commit"})
      (after #(= :commit (:op %)))

      (named? #{:last-done "last-done" ":last-done"})
      (after #(= :done (:op %)))

      :else from)))

(defn ^:export query-changes
  "The agent's EPISODE — everything since `:agent`'s last done: net
  per-form diffs (:was/:now), the step list, and the verification arc. The
  'what have I done since my last stable spot' view. Parallel agents with
  distinct :agent labels each see only their own work. `:format \"text\"`
  renders it as a human story with LINE diffs instead of full sources."
  [session & {:keys [agent from to format]}]
  (let [st       (:store @session)
        from     (span-anchor st from)
        boundary (if from
                   ;; historical span: `from`/`to` are delta ids (e.g. from a
                   ;; collapsed history row); boundary = just BEFORE `from`
                   (:id (last (take-while #(not= from (:id %))
                                          (store/deltas st))))
                   (episode-boundary st agent))
        span     (if from
                   (let [ds (drop-while #(not= from (:id %))
                                        (store/deltas st))]
                     (if to
                       (let [[pre [t & _]] (split-with #(not= to (:id %)) ds)]
                         (concat pre (when t [t])))
                       ds))
                   (episode-span st agent))
        mine     (filter #(and (contains? content-ops (:op %))
                               (or (nil? agent) (= agent (:agent %))))
                         span)
        fids     (distinct (mapcat delta-fids mine))
        was      (store/sources-at st boundary)
        del-info (into {}
                       (keep (fn [d]
                               (when (= :delete (:op d))
                                 [(:form-id d) [(:ns d) (:name d)]])))
                       mine)
        at-end   (when to (store/sources-at st to))
        forms    (vec (keep (fn [fid]
                              (let [e   (store/form-by-id st fid)
                                    now (if to
                                          (get at-end fid)
                                          (some-> e :node n/string))
                                    old (get was fid)]
                                (when (not= old now)
                                  (let [[dns dnm] (get del-info fid)
                                        qform (if e
                                                (symbol (str (store/ns-of-form-id st fid))
                                                        (str (or (:name e) fid)))
                                                (symbol (str dns) (str (or dnm fid))))]
                                    (cond-> {:form    qform
                                             :form-id fid
                                             :status  (cond (nil? old) :added
                                                            (nil? now) :deleted
                                                            :else      :modified)}
                                      old (assoc :was old)
                                      now (assoc :now now))))))
                            fids))
        arc      (vec (for [d span
                            :when (= :verify (:op d))
                            :let [r (:result d)]]
                        {:delta (:id d)
                         :fail  (+ (:fail r 0) (:error r 0))}))]
    (cond-> {:agent agent
             :since (or boundary :log-start)
             :steps (mapv #(select-keys % [:id :op :ns :prompt]) mine)
             :forms forms
             :verification-arc arc}
      (= "text" (some-> format name)) render-changes-text)))

(defn ^:export query-status-at
  "was-green-at: the project's verification state that GOVERNED delta `at`
  (a delta id, or a commit-point id → its target) — the last `:verify` at or
  before it. Returns {:at :status (:green|:red|:unknown) :verify <delta-id>}
  or {:error} for an unknown delta."
  [session & {:keys [at]}]
  (let [st (:store @session)]
    (cond
      (nil? at)              {:error "query-status-at needs :at"}
      (nil? (resolve-at st at)) {:error (str "no delta " at
                                             " in this branch's history")}
      :else (let [rid (resolve-at st at)]
              (cond-> {:at rid :status (status-at st rid)}
                (verify-at st rid) (assoc :verify (:id (verify-at st rid))))))))

(defn ^:export fid-ns-at
  "form-id → owning namespace as of delta `at-id`, folded from the log (each
  content delta carries its `:ns` and the form-ids it touched). Lets
  time-travel disambiguate same-named forms in different namespaces at a
  PAST point, without depending on the current store's membership."
  [store at-id]
  (reduce (fn [m d]
            (let [m (reduce #(assoc %1 %2 (:ns d)) m (delta-fids d))]
              (if (= at-id (:id d)) (reduced m) m)))
          {} (store/deltas store)))

(defn ^:export query-form-at
  "Time-travel: form `nm` in `ns-sym` as its SOURCE stood at delta `at` (a
  delta id, or a commit-point id → its target). Returns
  {:ns :name :at :source :status} — `:status` is the project's verification
  state that governed that point (was-green-at) — or {:error}. Names are
  resolved AT that delta (so a form that was later renamed still answers to
  the name it had then). The form's source is stored verbatim per version,
  so this is exact, not reconstructed."
  [session ns-sym nm & {:keys [at]}]
  (let [st (:store @session)]
    (cond
      (nil? at)
      {:error "query-form-at needs :at (a delta id or a commit-point id)"}

      (nil? (resolve-at st at))
      {:error (str "no delta " at " in this branch's history")}

      :else
      (let [rid   (resolve-at st at)
            srcs  (store/sources-at st rid)
            ns-of (fid-ns-at st rid)
            fid   (some (fn [[fid src]]
                          (when (and (= ns-sym (get ns-of fid))
                                     (= (str nm) (str (store/name-of-source src))))
                            fid))
                        srcs)]
        (if fid
          {:ns ns-sym :name nm :at rid :source (get srcs fid)
           :status (status-at st rid)}
          {:error (str nm " was not present in " ns-sym " at " rid)})))))
