(ns slopp.api.history
  "Package-private history/status helpers for the slopp.api module: delta
  timeline readings (status-at/after, resolve-at, verify-at) and the human
  renderings (line diffs, change/form-history stories, timestamps). Deep ns
  — reachable only within slopp.api.* (and its specs); the public surface
  stays on slopp.api."
  (:require [clojure.string :as str]
            [slopp.store :as store]))

(defn status-after
  "The verification outcome a delta PRODUCED: the first `:verify` at or after
  `at-id` (a write is immediately followed by its verify) — :green / :red /
  :unknown. This is 'did THIS version land green', vs `status-at`'s 'what
  was the state standing AT this point'."
  [store at-id]
  (let [ds (drop-while #(not= at-id (:id %)) (store/deltas store))
        v  (first (filter #(= :verify (:op %)) ds))]
    (if-let [r (:result v)]
      (if (zero? (+ (:fail r 0) (:error r 0))) :green :red)
      :unknown)))

(defn human-time
  "Epoch ms → \"2026-07-04 09:15\" in the local zone (the human rendering of
  a delta's `:at`; agents keep the raw ms in the store)."
  [ms]
  (when ms
    (.format (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm")
             (java.time.LocalDateTime/ofInstant
              (java.time.Instant/ofEpochMilli ms)
              (java.time.ZoneId/systemDefault)))))

(defn diff-lines
  "Minimal LCS line diff turning `was` into `now` (either may be nil):
  [[:same|:del|:add line] ...]. Forms are small — clarity over speed."
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
  and name-addressed revert cannot."
  [changes others]
  (let [{shared true mine false} (group-by #(contains? others (:form-id %))
                                           (:forms changes))]
    {:shared (mapv :form shared)
     :steps  (vec (keep (fn [{:keys [form status was]}]
                          (when (namespace form)   ; anonymous forms: skip
                            (let [ns-sym (symbol (namespace form))
                                  nm     (symbol (name form))]
                              (case status
                                :modified {:action :replace :ns ns-sym
                                           :name nm :source was}
                                :added    {:action :delete :ns ns-sym
                                           :name nm}
                                :deleted  {:action :add :ns ns-sym
                                           :source was}))))
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
        :else
        [(str (:id row) " " (:op row)
              (when (:agent row) (str " [" (:agent row) "]"))
              (when (:prompt row) (str " — " (:prompt row)))
              (when (:at row) (str "  @ " (:at row))))]))
    rows)))
