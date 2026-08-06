(ns slopp.edit.refactor
  "Coordinated structural rewrites (Phase-3 ops; rename first).

  Rename is POSITION-BASED: clj-kondo resolves every reference (the def's name
  token + each var usage, including alias-qualified cross-ns uses), and only the
  symbol tokens at those exact positions are rewritten — so a local that shadows
  the var is never touched, the failure mode of string-replace renames.

  Known limitation (documented, Phase-1): symbols inside `:refer` vectors are
  not usage sites in clj-kondo's var-usages and are not rewritten."
  (:require [rewrite-clj.parser :as p]
            [rewrite-clj.node :as n]
            [rewrite-clj.zip :as z]
            [slopp.store :as store]
            [slopp.store.render :as render]
            [clojure.string :as str] [clojure.set :as set] [slopp.index.refs :as refs] [slopp.index.analyze :as analyze] [slopp.edit.modules :as edit.modules] [slopp.edit :as edit]))

(defn- sites-in-analysis
  "[row col] positions (in the analyzed source) where `def-ns/def-name` is
  written: its definition's name token (when this IS the defining ns) plus every
  resolved usage."
  [analysis def-ns def-name defining-ns?]
  (concat
   (when defining-ns?
     (for [d (:var-definitions analysis)
           :when (and (= def-ns (:ns d)) (= def-name (:name d)))]
       [(:name-row d) (:name-col d)]))
   ;; for a call `(helper x)` kondo's :row/:col point at the call's paren; the
   ;; symbol token itself is at :name-row/:name-col
   (for [u (:var-usages analysis)
         :when (and (= def-ns (:to u)) (= def-name (:name u)))]
     [(or (:name-row u) (:row u)) (or (:name-col u) (:col u))])))

(defn- owner-idx
  "Index of the element (by its start offset) containing position [r c]."
  [offsets [r c]]
  (dec (count (take-while (fn [[er ec]]
                            (or (< er r) (and (= er r) (<= ec c))))
                          offsets))))

(defn- relative
  "Element-local position of absolute [r c] given the element's start [er ec]."
  [[er ec] [r c]]
  (if (= er r) [1 (inc (- c ec))] [(inc (- r er)) c]))

(defn- renamed-symbol [written new-name]
  (if (namespace written)
    (symbol (namespace written) (str new-name))
    (symbol (str new-name))))

(defn- replace-at
  "Replace the symbol token starting at [row col] in `src` (one form's source)
  with its renamed spelling; returns the new source."
  [src [row col] old-name new-name]
  (let [zloc (->> (z/of-string src {:track-position? true})
                  (iterate z/next)
                  (take-while (complement z/end?))
                  (filter #(and (= [row col] (z/position %))
                                (= :token (z/tag %))
                                (symbol? (z/sexpr %))
                                (= (str old-name) (name (z/sexpr %)))))
                  first)]
    (assert zloc (str "rename: no `" old-name "` token at " row ":" col))
    (z/root-string
     (z/replace zloc (n/token-node (renamed-symbol (z/sexpr zloc) new-name))))))

(defn- rewrite-form
  "Rewrite one form's node at the given element-local positions (applied in
  descending position order so earlier positions stay valid)."
  [node positions old-name new-name]
  (let [src' (reduce (fn [src pos] (replace-at src pos old-name new-name))
                     (n/string node)
                     (sort-by (fn [[r c]] [(- r) (- c)]) (distinct positions)))
        nodes (n/children (p/parse-string-all src'))]
    (assert (= 1 (count nodes)) "rename: form no longer parses to one node")
    (first nodes)))

;; --- extract (Phase-3 op #2) ---
(defn- node-span
  "Exclusive end position of a node printed as `s` starting at [row col]."
  [[row col] s]
  (let [ls (clojure.string/split s #"\n" -1)
        n  (count ls)]
    (if (= 1 n)
      [row (+ col (count s))]
      [(+ row (dec n)) (inc (count (peek ls)))])))

(defn- inside?
  "Is [r c] within the half-open span [start end)?"
  [[sr sc] [er ec] [r c]]
  (and (or (> r sr) (and (= r sr) (>= c sc)))
       (or (< r er) (and (= r er) (< c ec)))))

(defn- replace-span
  "Replace the text between 1-based [start end) positions in `s` with `repl`."
  [s [sr sc] [er ec] repl]
  (let [ls (vec (clojure.string/split s #"\n" -1))]
    (clojure.string/join
     "\n"
     (concat (subvec ls 0 (dec sr))
             [(str (subs (ls (dec sr)) 0 (dec sc)) repl (subs (ls (dec er)) (dec ec)))]
             (subvec ls er)))))

(defn- under-meta
  "Apply `f` to the form node beneath any form-level meta wrappers
  (`^:reads (defn- ...)` parses as a :meta node around the list) —
  the wrapper survives, the transform sees the defn."
  [node f]
  (if (= :meta (n/tag node))
    (let [kids (vec (n/children node))
          i    (last (keep-indexed
                      (fn [i k] (when-not (#{:whitespace :comment} (n/tag k)) i))
                      kids))]
      (n/replace-children node (assoc kids i (under-meta (kids i) f))))
    (f node)))

(defn publicize
  "The form node with its top-level privacy stripped: the `defn-` operator
  becomes `defn`, and a `^:private` marker on the def symbol is removed.
  A form moved to another namespace must be PUBLIC for its old neighbors'
  rewritten calls to compile — when the new home is a deep ns, the module
  system still keeps it package-private at the module grain (the right
  boundary). Only the form's immediate children are touched; a map-meta
  `^{:private true}` is left alone (rare; the load fails honestly)."
  [node]
  (n/replace-children
   node
   (map (fn [k]
          (case (n/tag k)
            :token (if (= 'defn- (n/sexpr k)) (n/token-node 'defn) k)
            :meta  (let [kids (remove #(#{:whitespace :comment} (n/tag %))
                                      (n/children k))]
                     (if (and (= 2 (count kids))
                              (= :private (n/sexpr (first kids))))
                       (second kids)
                       k))
            k))
        (n/children node))))

(defn rewrite-symbols
  "Zipper-walk `node`, replacing symbol tokens via `f` (sym → sym|nil).
  Returns the (possibly identical) node. z/root wraps its result in a
  :forms node — unwrapped here, or every changeset-rewritten form would
  lose its :name downstream (apply-changeset recomputes names via
  form-symbol, which rightly refuses :forms wrappers; found via Q14's
  sweep when a consumer's rewritten ns decl broke image load order)."
  [node f]
  (loop [zl (z/of-node node)]
    (let [zl (if (and (= :token (z/tag zl))
                      (symbol? (z/sexpr zl)))
               (if-let [s' (f (z/sexpr zl))]
                 (z/replace zl s')
                 zl)
               zl)
          nxt (z/next zl)]
      (if (z/end? nxt)
        (let [root (z/root zl)]
          (if (= :forms (n/tag root))
            (or (first (filter n/sexpr-able? (n/children root))) root)
            root))
        (recur nxt)))))

(defn ns-sym-mapper
  "Symbol rewriter old-ns → new-ns: the ns name itself, and any
  old-ns/qualified symbol."
  [old new]
  (let [o (str old) n (str new)]
    (fn [sym]
      (cond
        (= sym old) new
        (and (namespace sym) (= o (namespace sym)))
        (symbol n (name sym))
        (clojure.string/starts-with? (str sym) (str o "/"))
        (symbol (str n (subs (str sym) (count o))))
        :else nil))))

(defn ^:export ns-rename-changeset
  "Every form in the STORE mentioning `old` as a namespace — its own ns decl,
  require clauses, fully-qualified refs — rewritten to `new`. {form-id node}."
  [store old new]
  (let [mapper (ns-sym-mapper old new)]
    (into {}
          (for [ns-sym (keys (:namespaces store))
                e (store/forms store ns-sym)
                :let [node' (rewrite-symbols (:node e) mapper)]
                :when (not= (n/string node') (n/string (:node e)))]
            [(:id e) node']))))

(defn- norm-src
  "Whitespace-insensitive comparison key for source text: outside string
  literals, runs of whitespace/commas collapse to one space; string bytes
  and char literals stay verbatim. The match fallback for nodes whose sexpr
  can never compare equal (fn literals gensym their args; regex Patterns
  don't =)."
  [^String s]
  (let [n  (.length s)
        sb (StringBuilder.)]
    (loop [i 0, in-str? false, esc? false, ws? false]
      (if (>= i n)
        (str sb)
        (let [c (.charAt s i)]
          (cond
            in-str?
            (do (.append sb c)
                (cond
                  esc?     (recur (inc i) true false false)
                  (= c \\) (recur (inc i) true true false)
                  (= c \") (recur (inc i) false false false)
                  :else    (recur (inc i) true false false)))

            ;; char literal in code: backslash + next char verbatim
            (= c \\)
            (do (when ws? (when (pos? (.length sb)) (.append sb \space)))
                (.append sb c)
                (when (< (inc i) n) (.append sb (.charAt s (inc i))))
                (recur (+ i 2) false false false))

            (or (Character/isWhitespace c) (= c \,))
            (recur (inc i) false false true)

            :else
            (do (when (and ws? (pos? (.length sb))) (.append sb \space))
                (.append sb c)
                (recur (inc i) (= c \") false false))))))))

(def ^:private pair-binding-heads
  "List heads whose FIRST vector argument pairs [name init ...] — a 2-form
  match starting on an even slot of that vector is a well-formed pair.
  Partly built from strings: naming binding/with-redefs as symbols would
  trip the D3 denylist, and here they're lookup keys, not uses."
  (into '#{let let* loop loop* doseq for if-let when-let if-some when-some
           with-open}
        (map symbol)
        ["binding" "with-redefs" "with-local-vars"]))

(defn- sexpr-index
  "How many sexpr-able siblings precede `zl` in its container (the z API
  skips whitespace, so plain z/left counts real forms)."
  [zl]
  (count (take-while some? (rest (iterate z/left zl)))))

(defn- safe-sexpr [zl]
  (try (z/sexpr zl) (catch Exception _ nil)))

(defn- pair-slot?
  "Does a two-form span starting at `zl` sit ON a pair boundary of a paired
  container? Map literals and binding vectors pair from slot 0, case
  clauses from 2, cond clauses from 1."
  [zl]
  (let [idx    (sexpr-index zl)
        parent (z/up zl)]
    (boolean
     (when parent
       (case (z/tag parent)
         :map    (even? idx)
         :vector (when-let [gp (z/up parent)]
                   (and (= :list (z/tag gp))
                        (contains? pair-binding-heads (some-> gp z/down safe-sexpr))
                        (= 1 (sexpr-index parent))
                        (even? idx)))
         :list   (let [head (some-> parent z/down safe-sexpr)]
                   (cond
                     ;; (case e k1 v1 …) and (cond-> x t1 e1 …) both pair
                     ;; from slot 2 — head, then the subject, then pairs
                     (contains? '#{case cond-> cond->>} head)
                     (and (<= 2 idx) (even? idx))
                     (= 'cond head) (odd? idx)
                     :else false))
         false)))))

(defn- fuzzy-spans
  "[[start end] ...] where `text` matches `src` with whitespace runs
  treated as equivalent (any run matches any run) — the tolerance text
  matches need across docstring reflows and re-indentation."
  [^String src ^String text]
  (let [words (str/split (str/trim text) #"\s+")]
    (when (seq words)
      (let [pat (re-pattern (str/join "\\s+" (map #(java.util.regex.Pattern/quote %)
                                                  words)))
            mt  (re-matcher pat src)]
        (loop [acc []]
          (if (.find mt)
            (recur (conj acc [(.start mt) (.end mt)]))
            acc))))))

(defn- find-unique-subform
  "The unique position-tracked zloc in `form-src` matching `match-src`
  (shared by extract and subform edits). A node matches when its sexpr
  structurally equals the match's OR its whitespace-normalized text does —
  the text fallback covers fn literals (gensym'd args never sexpr-compare
  equal) and regexes (Patterns don't =). `match-src` is ONE form — except
  that a TWO-form match landing on a pair boundary of a paired container
  (map literal, binding vector, case/cond clauses) addresses the pair as a
  unit (P1); any other multi-form match is refused (silently matching a
  multi-form string's first form misaligns paired structures like case).
  A match that doesn't parse on its own (mid-expression fragment) is refused
  with the rule named — the error is the only teaching that arrives at the
  moment it's needed (Q5).
  Returns {:zloc l} — plus :end-zloc (the pair's second node) for pair
  matches — or {:error msg}."
  [form-src match-src what]
  (let [parsed (try {:nodes (filter n/sexpr-able?
                                    (n/children (p/parse-string-all match-src)))}
                    (catch Exception e {:parse-error (ex-message e)}))]
    (if-let [pe (:parse-error parsed)]
      ;; when the fragment APPEARS once in the form, hand back the smallest
      ;; complete form containing it — the retry needs no re-read
      (let [cand (when (= 1 (count (fuzzy-spans form-src match-src)))
                   (->> (iterate z/next (z/of-string form-src))
                        (take-while (complement z/end?))
                        (map z/node)
                        (filter n/sexpr-able?)
                        (map n/string)
                        (filter #(seq (fuzzy-spans % match-src)))
                        (sort-by count)
                        first))]
        (cond-> {:error (str "the match isn't well-formed Clojure on its own ("
                             pe ") — match COMPLETE forms: a whole expression,"
                             " clause, or binding pair, never a fragment that"
                             " opens a delimiter it doesn't close."
                             (if cand
                               (str " :suggestion is the smallest complete form"
                                    " containing your fragment — match THAT and"
                                    " restate it in the replacement")
                               (str " Often the fix is matching the ENCLOSING"
                                    " form and restating it in the replacement")))}
          cand (assoc :suggestion cand)))
      (let [mnodes  (:nodes parsed)
            pair?   (= 2 (count mnodes))
            matcher (fn [mnode]
                      (let [msexpr (try (n/sexpr mnode) (catch Exception _ ::none))
                            mnorm  (norm-src (n/string mnode))]
                        (fn [zl]
                          (or (and (not= ::none msexpr)
                                   (try (= msexpr (z/sexpr zl))
                                        (catch Exception _ false)))
                              (= mnorm (norm-src (n/string (z/node zl))))))))]
        (if-not (or (= 1 (count mnodes)) pair?)
          {:error (str "match parses to " (count mnodes) " forms — give exactly "
                       "ONE subform as the match, or ONE key/value-style PAIR "
                       "inside a map, binding vector, or case/cond (the "
                       "REPLACEMENT may be several forms)")}
          (let [match1? (matcher (first mnodes))
                match2? (when pair? (matcher (second mnodes)))
                hit?    (fn [zl]
                          (and (match1? zl)
                               (or (not pair?)
                                   (boolean (some-> (z/right zl) match2?)))))
                matches (->> (iterate z/next (z/of-string form-src {:track-position? true}))
                             (take-while (complement z/end?))
                             (filter hit?)
                             vec)
                usable  (if pair? (filterv pair-slot? matches) matches)]
            (cond
              (and pair? (empty? usable) (seq matches))
              {:error (str "a two-form match must land on a pair boundary of a "
                           "map, binding vector, or case/cond/cond-> clause — this span "
                           "crosses one in " what "; match the single value form "
                           "instead")}

              (empty? usable)
              {:error (str "subform not found in " what
                           " — :source-now is its CURRENT text; correct the"
                           " match against it and resend, no read needed")
               :source-now form-src}

              (< 1 (count usable))
              {:error (str "subform occurs " (count usable) " times in " what
                           " — ambiguous; give a larger enclosing subform"
                           " (its current text is in :source-now)")
               :source-now form-src}

              pair?
              {:zloc (first usable) :end-zloc (z/right (first usable))}

              :else {:zloc (first usable)})))))))

(defn ^:export keyed-replace-plan
  "Plan replacing the UNIQUE map inside `form-name` that contains every
  entry of `where` (e.g. {:name \"query_history\"} addresses one tool
  descriptor in a registry vector) with `new-src` — first-person friction:
  registry-style edits shouldn't need the exact current text, just a key.
  Returns {:new-form-src s} or {:error msg}."
  [store ns-sym form-name where new-src]
  (try
    (if-let [e (store/form-named store ns-sym form-name)]
      (let [form-src (n/string (:node e))
            matches  (->> (iterate z/next (z/of-string form-src
                                                       {:track-position? true}))
                          (take-while (complement z/end?))
                          (filter #(= :map (z/tag %)))
                          (filter #(let [s (try (z/sexpr %) (catch Exception _ nil))]
                                     (and (map? s)
                                          (every? (fn [[k v]] (= v (get s k)))
                                                  where))))
                          vec)]
        (cond
          (empty? matches)
          {:error (str "no map containing " (pr-str where) " in " form-name)}

          (< 1 (count matches))
          {:error (str (count matches) " maps contain " (pr-str where) " in "
                       form-name " — add entries to `where` until unique")}

          :else
          (let [m       (first matches)
                [r c]   (z/position m)
                [er ec] (node-span (z/position m) (n/string (z/node m)))]
            {:new-form-src (replace-span form-src [r c] [er ec] new-src)})))
      {:error (str "no form named " form-name " in " ns-sym)})
    (catch Exception ex
      {:error (str "keyed edit failed: " (ex-message ex))})))

(defn anchor-subform-src
  "The source of the unique subform of `form-src` that the ANCHOR heads — the
  smallest complete form containing the anchor's single occurrence.

  Lets a caller point at a large subform by its first line instead of quoting
  its whole body, which is the difference between a mechanical extraction and
  a hand transcription of the exact code you were trying not to touch. The
  anchor need not parse on its own (`\"(let [turn-brackets\"` is the intended
  shape); whitespace runs are equivalent, so re-indentation does not break it.

  Returns {:src s}, or {:error msg :source-now form-src}."
  [form-src anchor what]
  (let [hits (fuzzy-spans form-src anchor)]
    (cond
      (empty? hits)
      {:error (str "anchor not found in " what
                   " — :source-now is its CURRENT text; correct the anchor"
                   " against it and resend, no read needed")
       :source-now form-src}

      (< 1 (count hits))
      {:error (str "anchor occurs " (count hits) " times in " what
                   " — extend it (usually one more line) until it is unique")
       :source-now form-src}

      :else
      (if-let [cand (->> (iterate z/next (z/of-string form-src))
                         (take-while (complement z/end?))
                         (map z/node)
                         (filter n/sexpr-able?)
                         (map n/string)
                         (filter #(seq (fuzzy-spans % anchor)))
                         (sort-by count)
                         first)]
        {:src cand}
        {:error (str "no complete form in " what " contains that anchor")
         :source-now form-src}))))

(defn ^:export extract-plan
  "Plan extracting the unique occurrence of `subform-src` inside `from-name`
  into a new fn `new-name`: params = the free locals (bound outside the
  subform, used inside), in first-use order. Pair matches (P1) are refused —
  a pair is not an expression. Returns
  {:new-defn-src :new-from-src :params} or {:error msg}."
  [store ns-sym from-name subform-src new-name & {:keys [at]}]
  (try
    (if-let [e (store/form-named store ns-sym from-name)]
      (let [form-src (n/string (:node e))
            resolved (when at (anchor-subform-src form-src at from-name))
            target   (if at (:src resolved) subform-src)
            found    (if (and at (:error resolved))
                       resolved
                       (find-unique-subform form-src target from-name))]
        (cond
          (:error found)    found
          (:end-zloc found) {:error (str "cannot extract a pair — extract "
                                         "needs ONE expression (usually the "
                                         "pair's value form)")}
          :else
          (let [m        (:zloc found)
                [r c]    (z/position m)
                sub-str  (n/string (z/node m))
                [er ec]  (node-span [r c] sub-str)
                elems    (store/elements store ns-sym)
                idx      (first (keep-indexed
                                 (fn [i el] (when (= (:id e) (:id el)) i)) elems))
                [fr fc]  (nth (render/element-offsets store ns-sym) idx)
                abs      (fn [[rr cc]] [(+ fr rr -1) (if (= rr 1) (+ fc cc -1) cc)])
                a-start  (abs [r c])
                a-end    (abs [er ec])
                an       (analyze/analyze-with-locals (render/render-ns store ns-sym))
                defs     (into {} (map (juxt :id identity)) (:locals an))
                params   (->> (:local-usages an)
                              (filter #(inside? a-start a-end [(:row %) (:col %)]))
                              (remove #(when-let [d (defs (:id %))]
                                         (inside? a-start a-end [(:row d) (:col d)])))
                              (sort-by (juxt :row :col))
                              (map :name)
                              distinct
                              vec)
                call-src (str "(" new-name
                              (apply str (map #(str " " %) params)) ")")]
            {:new-defn-src (str "(defn " new-name " ["
                                (clojure.string/join " " params) "]\n  " sub-str ")")
             :new-from-src (replace-span form-src [r c] [er ec] call-src)
             :params       params})))
      {:error (str "no form named " from-name " in " ns-sym)})
    (catch Exception ex
      {:error (str "extract failed: " (ex-message ex))})))

(defn ^:export fill-template
  "`template` with each `$n` replaced by the nth of `args` (1-based).

  The house template mechanism, in one place. `change_signature` rewrites call
  sites with `$1..$9` and `edit_subform {wrap}` fills a single `$1` with the
  form it matched — the same rule, and a second copy of it is how two surfaces
  come to disagree about what `$1` means (Pattern 2, four recorded instances).

  A `$n` with no corresponding argument is LEFT AS WRITTEN rather than dropped
  or thrown on: the callers check arity themselves and report it as a finding,
  and silently deleting the text would hide the mistake inside a plausible
  result."
  [template args]
  (str/replace template #"\$(\d)"
               (fn [[whole d]]
                 (let [i (dec (parse-long d))]
                   (if (< -1 i (count args)) (nth args i) whole)))))

(defn ^:export subform-replace-plan
  "Plan replacing the unique occurrence of `match-src` inside `form-name` with
  `new-src` (item 5 — paredit's valid-tree→valid-tree invariant, content-
  addressed: siblings are never re-transcribed). A pair match (P1) replaces
  the WHOLE pair span. Returns {:new-form-src s} or {:error msg}.

  With `wrap?`, `new-src` is a TEMPLATE and `$1` is filled with the source the
  match actually found — so the matched form ends up NESTED inside it. That is
  the third transformation shape: the verbs expressed replace-in-place and
  insert-beside, and introducing `(let [x …] <the thing that was there>)`
  around existing code was neither. Matching a fragment that opens a delimiter
  it does not close is correctly refused, so the only way to express it was to
  restate the whole enclosing form — measured at ~40 lines a time.

  `$1` is filled from the FOUND text, not from `match-src`: matching is
  whitespace-insensitive, so the two can differ and the source is what should
  survive. A template with no `$1` is refused rather than treated as a plain
  replace, because that would DELETE the matched form — a different operation
  than the one asked for."
  ([store ns-sym form-name match-src new-src]
   (subform-replace-plan store ns-sym form-name match-src new-src false))
  ([store ns-sym form-name match-src new-src wrap?]
   (try
     (if (and wrap? (not (re-find #"\$1" (str new-src))))
       {:error (str "a wrap template must contain $1 — the place the matched form"
                    " goes. Without it the match would be DELETED, which is"
                    " edit_subform without wrap")}
       (if-let [e (store/form-named store ns-sym form-name)]
         (let [form-src (n/string (:node e))
               found    (find-unique-subform form-src match-src form-name)]
           (if (:error found)
             found
             (let [m       (:zloc found)
                   e2      (or (:end-zloc found) m)
                   [r c]   (z/position m)
                   [er ec] (node-span (z/position e2) (n/string (z/node e2)))
                   ;; the source the match actually FOUND, not what the caller
                   ;; typed: matching is whitespace-insensitive, so the two can
                   ;; differ and it is the source that should survive the wrap
                   src     (if wrap?
                             (fill-template new-src [(n/string (z/node m))])
                             new-src)]
               {:new-form-src (replace-span form-src [r c] [er ec] src)})))
         {:error (str "no form named " form-name " in " ns-sym)}))
     (catch Exception ex
       {:error (str "subform edit failed: " (ex-message ex))}))))

(defn- rewrite-call-sites
  "Fold one element's usage sites (element-local [r c]) into the plan `acc`:
  head-position calls get their arg list rebuilt from `args-template`
  (spans applied last-first over the form source); anything else routes to
  :manual. `max-n` = the highest $n the template references."
  [acc ns-sym e locs args-template max-n]
  (let [form-src (n/string (:node e))
        zlocs    (->> (iterate z/next (z/of-string form-src {:track-position? true}))
                      (take-while (complement z/end?))
                      vec)
        at       (fn [rc] (first (filter #(= rc (z/position %)) zlocs)))
        info
        (reduce
         (fn [m rc]
           (if (:error m)
             m
             (let [zl (at rc)]
               (cond
                 (nil? zl)
                 (update m :manual conj {:ns ns-sym :form (:name e)
                                         :row (first rc) :col (second rc)
                                         :reason "site not found in form"})

                 (or (some? (z/left zl))
                     (not= :list (some-> zl z/up z/tag)))
                 (update m :manual conj {:ns ns-sym :form (:name e)
                                         :row (first rc) :col (second rc)
                                         :reason "not a call (higher-order reference)"})

                 :else
                 (let [args (->> (iterate z/right zl) (drop 1) (take-while some?)
                                 (mapv #(n/string (z/node %))))]
                   (if (< (count args) max-n)
                     (assoc m :error
                            (str "call site in " ns-sym "/" (:name e) " has "
                                 (count args) " args but the template needs $"
                                 max-n " — rewrite that call site yourself (edit_subform)"))
                     (let [head    (n/string (z/node zl))
                           subst   (str/trim
                                    (fill-template args-template args))
                           parent  (z/up zl)
                           [pr pc] (z/position parent)]
                       (update m :spans conj
                               {:start [pr pc]
                                :end   (node-span [pr pc] (n/string (z/node parent)))
                                :src   (if (str/blank? subst)
                                         (str "(" head ")")
                                         (str "(" head " " subst ")"))}))))))))
         {:spans [] :manual (:manual acc) :error nil}
         locs)]
    (cond
      (:error info)          (assoc acc :error (:error info))
      (empty? (:spans info)) (assoc acc :manual (:manual info))

      :else
      (let [spans   (sort-by :start (:spans info))
            nested? (some (fn [[a b]] (neg? (compare (:start b) (:end a))))
                          (partition 2 1 spans))]
        (if nested?
          (assoc acc :error (str "nested call sites of the fn in " ns-sym "/"
                                 (:name e) " — rewrite that form yourself (edit_subform)"))
          (-> acc
              (assoc :manual (:manual info))
              (update :caller-steps conj
                      {:action :replace :ns ns-sym :name (:name e)
                       :source (reduce (fn [s {:keys [start end src]}]
                                         (replace-span s start end src))
                                       form-src
                                       (reverse spans))})))))))

(defn ^:export change-signature-plan
  "Plan a signature change for `def-ns/fn-name` (P2): every CALL site (the
  fn in head position) gets its argument list rebuilt from `args-template`,
  a source string where $1..$9 are the site's existing arg sources — the
  callee stays exactly as written, so require aliases survive. The def form
  itself is NOT planned here (the caller supplies its replacement source;
  self-calls inside it are its business). Returns
  {:caller-steps [{:action :replace :ns n :name f :source s}] :manual [...]}
  or {:error msg}; :manual lists references that can't be template-rewritten
  (higher-order uses, nameless forms)."
  [store def-ns fn-name args-template]
  (try
    (if-let [def-e (store/form-named store def-ns fn-name)]
      (let [max-n (reduce max 0 (map (comp parse-long second)
                                     (re-seq #"\$(\d)" args-template)))]
        (reduce
         (fn [acc ns-sym]
           (if (:error acc)
             acc
             (let [an      (analyze/analyze (render/render-ns store ns-sym))
                   sites   (distinct
                            (for [u (:var-usages an)
                                  :when (and (= def-ns (:to u))
                                             (= fn-name (:name u)))]
                              [(or (:name-row u) (:row u))
                               (or (:name-col u) (:col u))]))
                   offsets (render/element-offsets store ns-sym)
                   elems   (store/elements store ns-sym)]
               (reduce
                (fn [acc [idx ss]]
                  (let [e (nth elems idx)]
                    (cond
                      (:error acc) acc

                      (= (:id e) (:id def-e)) acc

                      (nil? (:name e))
                      (update acc :manual into
                              (map (fn [[r c]] {:ns ns-sym :form nil
                                                :row r :col c
                                                :reason "nameless form"})
                                   ss))

                      :else
                      (rewrite-call-sites acc ns-sym e
                                          (map #(relative (nth offsets idx) %) ss)
                                          args-template max-n))))
                acc
                (group-by #(owner-idx offsets %) sites)))))
         {:caller-steps [] :manual []}
         (keys (:namespaces store))))
      {:error (str "no form named " fn-name " in " def-ns)})
    (catch Exception ex
      {:error (str "change-signature plan failed: " (ex-message ex))})))

(defn ^:export rename-changeset
  "Compute {form-id new-node} renaming `def-ns/old-name` to `new-name` across
  every store namespace."
  [store def-ns old-name new-name]
  (into {}
        (mapcat (fn [ns-sym]
                  (let [an      (analyze/analyze (render/render-ns store ns-sym))
                        sites   (sites-in-analysis an def-ns old-name (= ns-sym def-ns))
                        offsets (render/element-offsets store ns-sym)
                        elems   (store/elements store ns-sym)]
                    (for [[idx ss] (group-by #(owner-idx offsets %) sites)
                          :let [e (nth elems idx)]]
                      [(:id e)
                       (rewrite-form (:node e)
                                     (map #(relative (nth offsets idx) %) ss)
                                     old-name new-name)]))))
        (keys (:namespaces store))))

(defn ^:export text-replace-plan
  "Plan a RAW-TEXT replace inside form `form-name`: `match-text` must occur
  exactly ONCE in the form's source — exactly, or failing that under
  whitespace-fuzzy matching (runs of whitespace are equivalent, so a
  reflowed docstring still matches) — and the spliced result must still
  parse to ONE form. The escape hatch for content no structural match can
  address — string literals, docstrings. Misses carry :source-now (the
  form's current text) so the retry needs no read.
  Returns {:new-form-src s} or {:error msg [:source-now s]}."
  [store ns-sym form-name match-text new-text]
  (try
    (if-let [e (store/form-named store ns-sym form-name)]
      (let [src ^String (n/string (:node e))
            m   ^String (str match-text)]
        (if (str/blank? m)
          {:error "text mode needs a non-empty match"}
          (let [i     (.indexOf src m)
                dup?  (and (>= i 0) (>= (.indexOf src m (inc i)) 0))
                spans (when (neg? i) (fuzzy-spans src m))
                [start len] (cond
                              (and (>= i 0) (not dup?)) [i (count m)]
                              (= 1 (count spans)) (let [[s e] (first spans)]
                                                    [s (- e s)])
                              :else nil)]
            (cond
              dup?
              {:error (str "text occurs more than once in " form-name
                           " — give a longer unique snippet (:source-now is"
                           " the form's current text)")
               :source-now src}

              (nil? start)
              {:error (str (if (seq spans)
                             (str "text matches " (count spans) " places in "
                                  form-name)
                             (str "text not found in " form-name))
                           " — :source-now is the form's CURRENT text; correct"
                           " the match against it and resend, no read needed")
               :source-now src}

              :else
              (let [out   (str (subs src 0 start) new-text
                               (subs src (+ start len)))
                    nodes (filter n/sexpr-able?
                                  (n/children (p/parse-string-all out)))]
                (if (= 1 (count nodes))
                  {:new-form-src out}
                  {:error (str "the replacement does not leave ONE valid form ("
                               (count nodes) " forms parsed)")}))))))
      {:error (str "no form named " form-name " in " ns-sym)})
    (catch Exception ex
      {:error (str "text replace failed: " (ex-message ex))})))

(defn- require-specs
  "`ns-sym`'s :require clauses as [{:lib sym :alias sym|nil :refers #{sym}
  :spec str}] — the planner's resolution context (aliases to rewrite through,
  specs to copy into a move target verbatim)."
  [store ns-sym]
  (let [decl (some #(let [s (try (n/sexpr (:node %)) (catch Exception _ nil))]
                      (when (and (seq? s) (= 'ns (first s))) s))
                   (store/elements store ns-sym))]
    (vec (for [clause (drop 2 (or decl ()))
               :when  (and (seq? clause) (= :require (first clause)))
               spec   (rest clause)
               :let   [[lib alias refers]
                       (cond
                         (vector? spec) [(first spec)
                                         (second (drop-while #(not= :as %) spec))
                                         (set (second (drop-while #(not= :refer %) spec)))]
                         (symbol? spec) [spec nil #{}])]
               :when  lib]
           {:lib lib :alias alias :refers (or refers #{})
            :spec (pr-str spec)}))))

(defn- alias-for
  "The alias a namespace should call `to-ns` by: its existing alias when
  already required, else the last segment, else the last two joined —
  nil when both collide with other libs (the caller refuses)."
  [specs to-ns]
  (or (some #(when (= (:lib %) to-ns) (or (:alias %) to-ns)) specs)
      (let [taken (set (keep :alias specs))
            segs  (clojure.string/split (str to-ns) #"\.")
            c1    (symbol (last segs))
            c2    (symbol (clojure.string/join "." (take-last 2 segs)))]
        (cond (not (taken c1)) c1
              (not (taken c2)) c2
              :else nil))))

(defn- private-form?
  "Is this def form private (defn- operator or ^:private on the name)?"
  [node]
  (let [s (try (n/sexpr node) (catch Exception _ nil))]
    (and (seq? s)
         (or (= 'defn- (first s))
             (boolean (:private (meta (second s))))))))

(defn- name-export-level
  "The `:export` level a def's NAME node ALREADY declares — `true`, a prefix
   string, or nil for none.

   Walks every meta layer rather than checking the outermost, because a name
   can be wrapped more than once (`^:export ^:dynamic *hook*`) and the marker
   we are looking for may sit under one that is nothing to do with exports.

   [[export-mark]] asks this before wrapping. Without it a move stacked a
   second marker onto a var that was already exported, and `defn ^:export
   ^:export f` is what landed in the store — valid Clojure, since reader
   metadata merges, which is exactly why nothing failed and it took a
   regex-shaped test assertion to notice."
  [k]
  (when (= :meta (n/tag k))
    (let [kids (remove #(#{:whitespace :comment} (n/tag %)) (n/children k))
          sx   (try (n/sexpr (first kids)) (catch Exception _ nil))]
      (cond
        (= sx :export)                        true
        (and (map? sx) (contains? sx :export)) (:export sx)
        :else (some-> (second kids) name-export-level)))))

(defn- export-mark
  "The def form with an export marker on its name symbol — `level` true
  gives `^:export` (world surface); a prefix string gives
  `^{:export \"prefix\"}` (that subtree only) — the deliberate widening a
  deep-ns move needs when callers live outside the subtree. The name may
  itself be meta-wrapped (`^:dynamic *hook*`) — the marker stacks on top.

  A form written `^:reads (defn f ...)` is a top-level :meta node whose
  children hold no defn symbol AT ALL — the def is its VALUE. Mark inside
  and rewrap, so the outer marker survives and the head scan never runs on
  a node that has no head. An unrecognised shape returns unchanged rather
  than throwing: this is a widening, and refusing to widen is recoverable
  where an NPE mid-changeset is not."
  [node level]
  (if (= :meta (n/tag node))
    (let [kids (vec (n/children node))
          vi   (last (keep-indexed
                      (fn [i k] (when-not (n/whitespace-or-comment? k) i))
                      kids))]
      (if vi
        (n/replace-children
         node (map-indexed (fn [i k] (if (= i vi) (export-mark k level) k)) kids))
        node))
    (let [kids (n/children node)
          op?  (fn [k] (and (= :token (n/tag k)) (symbol? (n/sexpr k))))
          nameish? (fn [k] (or (op? k) (= :meta (n/tag k))))
          opi  (first (keep-indexed #(when (op? %2) %1) kids))
          nami (when opi
                 (first (keep-indexed
                         (fn [i k] (when (and (> i opi) (nameish? k)) i))
                         kids)))
          mark (if (true? level)
                 (n/keyword-node :export)
                 (p/parse-string (pr-str {:export (str level)})))]
      (if (and nami (not= level (name-export-level (nth (vec kids) nami))))
        (n/replace-children
         node
         (map-indexed (fn [i k] (if (= i nami) (n/meta-node mark k) k)) kids))
        node))))

(defn- imports-for
  "The (:import ...) clause text the moved `nodes` need from `ns-sym`'s
  declaration — entries filtered to the SIMPLE class names the moved code
  references (static calls `C/member`, ctors `C.`, bare `C`, `^C` type
  hints, via refs/walk-pruned), grouped and sorted; nil when nothing
  matches."
  [store ns-sym nodes]
  (let [decl    (some #(let [s (try (n/sexpr (:node %)) (catch Exception _ nil))]
                         (when (and (seq? s) (= 'ns (first s))) s))
                      (store/elements store ns-sym))
        entries (for [clause (drop 2 (or decl ()))
                      :when (and (seq? clause) (= :import (first clause)))
                      spec  (rest clause)]
                  (cond
                    (vector? spec) {:pkg (first spec) :classes (set (map str (rest spec)))}
                    (symbol? spec) (let [parts (str/split (str spec) #"\.")]
                                     {:pkg (symbol (str/join "." (butlast parts)))
                                      :classes #{(last parts)}})))
        used    (set
                 (for [node nodes
                       s (refs/walk-pruned
                          (fn [f]
                            (concat (when (symbol? f) [f])
                                    (when-let [t (:tag (meta f))]
                                      (when (symbol? t) [t]))))
                          (try (n/sexpr node) (catch Exception _ nil)))
                       :let [nm (name s) nsp (namespace s)]
                       c [(when (and nsp (Character/isUpperCase (char (first nsp))))
                            nsp)
                          (when (and (nil? nsp) (str/ends-with? nm ".")
                                     (Character/isUpperCase (char (first nm))))
                            (subs nm 0 (dec (count nm))))
                          (when (and (nil? nsp)
                                     (Character/isUpperCase (char (first nm))))
                            nm)]
                       :when c]
                   c))
        kept    (sort (keep (fn [{:keys [pkg classes]}]
                              (let [hit (sort (filter used classes))]
                                (when (seq hit)
                                  (str "[" pkg " " (str/join " " hit) "]"))))
                            entries))]
    (when (seq kept)
      (str "(:import " (str/join " " kept) ")"))))

(defn ^:export move-plan
  "PLAN moving `moved-names` from `from-ns` into `to-ns` (new or existing) —
  pure analysis over a store value; the executor applies it atomically.
  Returns {:error msg} with teaching for the impossible cases, else
  {:new-ns? :new-src|:append :to-require-adds :rewrites {fid {:ns :name :node
  :src}} :require-adds {ns spec-str} :module-rows :removals :moved :shadowed}.
  `:shadowed` is ALWAYS present, empty or not — an empty vector says the
  dequalification was checked against the moved forms' locals and cleared,
  where an absent key would only say this version does not look.
  Every `:module-rows` entry is `{:from-ns :from-var :to :to-name}` — the
  CALL, in both directions, so a consumer can act per moved var rather than
  per caller.
  Direction rules: stay→moved gives from-ns a require on to-ns; moved→stay
  gives to-ns a require back and QUALIFIES bare refs to PUBLIC stay-behinds
  (private callees refuse — move them too or make them public); both at once
  is a real cycle and refuses. Moved defs are publicized (module-grain
  visibility replaces var privacy); opts {:export true} marks them ^:export
  for a deep target with outside callers. Known limits (compile-gated, they
  fail honestly): :refer'd moved names refuse up front; java :import
  clauses aren't copied; a local shadowing a bare stay-callee inside a moved
  form mis-qualifies, and fails at COMPILE because the same rewrite reaches
  the binding vector.
  The OPPOSITE direction does not fail at all — a dequalified call landing on
  a local of its own name is valid Clojure that calls the local — so it is
  REPORTED as :shadowed rather than refused: the detector
  (slopp.edit/local-name?) has no scope tracking, and refusing on an
  over-match would block a legitimate move with no way through."
  [store from-ns moved-names to-ns opts]
  (let [moved    (set (map symbol moved-names))
        missing  (remove #(store/form-named store from-ns %) moved)
        to-ns    (symbol (str to-ns))
        from-ns  (symbol (str from-ns))
        new-ns?  (not (contains? (:namespaces store) to-ns))
        analyze* (fn [nsx] (:var-usages (analyze/analyze (render/render-ns store nsx))))
        rows     (analyze* from-ns)
        moved-rows (filter #(moved (:from-var %)) rows)
        
        ;; direction analysis reads THE graph (store-internal questions);
        ;; kondo rows remain only for EXTERNAL-lib require selection
        srefs (refs/ns-refs store from-ns)
        ;; the CALL, not just the caller. A module row that records only "this
        ;; var reaches to-ns" cannot say which moved var it reached, and every
        ;; consumer downstream is then forced to guess or go all-or-nothing.
        ;; The callee is right here in the reference row.
        stay->moved-calls (set (keep #(when (and (= from-ns (:to-ns %))
                                                 (moved (:to-name %))
                                                 (:from-var %)
                                                 (not (moved (:from-var %)))
                                                 (not= :declared (:via %)))
                                        [(:from-var %) (:to-name %)])
                                     srefs))
        stay->moved (set (map first stay->moved-calls))
        moved->stay (set (keep #(when (and (= from-ns (:to-ns %))
                                           (not (moved (:to-name %)))
                                           (moved (:from-var %))
                                           (not= :declared (:via %)))
                                  (:to-name %))
                               srefs))
        private-callees (filter #(private-form?
                                  (:node (store/form-named store from-ns %)))
                                (sort moved->stay))
        other-nses (remove #{from-ns} (sort (keys (:namespaces store))))
        refer-hits (for [nsx other-nses
                         spec (require-specs store nsx)
                         :when (and (= (:lib spec) from-ns)
                                    (seq (clojure.set/intersection (:refers spec) moved)))]
                     [nsx (sort (clojure.set/intersection (:refers spec) moved))])
        collisions (when-not new-ns?
                     (filter #(store/form-named store to-ns %) (sort moved)))]
    (cond
      (empty? moved)
      {:error (str "nothing to move — form-names is empty; name the forms to"
                   " move out of " from-ns)}

      (seq missing)
      {:error (str "no such forms in " from-ns ": " (vec missing))}

      (= from-ns to-ns)
      {:error "from and to are the same namespace"}

      (seq collisions)
      {:error (str to-ns " already defines " (vec collisions)
                   " — rename first or pick another home")}

      (and (seq stay->moved) (seq moved->stay))
      {:error (str "two-way split: " (vec (sort stay->moved))
                   " (staying) call the moved set, while the moved set calls "
                   (vec (sort moved->stay)) " (staying) — a real cycle; move"
                   " one of those groups too, or split differently")}

      (seq private-callees)
      {:error (str "the moved set calls PRIVATE stay-behinds "
                   (vec private-callees)
                   " — move these too or make them public first")}

      (seq refer-hits)
      {:error (str "moved names are :refer'd — rewrite those requires to"
                   " alias-qualified first: "
                   (clojure.string/join "; "
                                        (map (fn [[nsx names]]
                                               (str nsx " refers " (vec names)))
                                             refer-hits)))}

      :else
      (let [;; external callers from THE graph — one assembly, not a per-ns sweep
            ext-usages  (reduce (fn [m r]
                                  (if (and (= from-ns (:to-ns r))
                                           (moved (:to-name r))
                                           (:from-var r)
                                           (not= from-ns (:from-ns r))
                                           (not= :declared (:via r)))
                                    (update m (:from-ns r)
                                            (fnil conj #{})
                                            [(:from-var r) (:to-name r)])
                                    m))
                                {} (refs/refs store))
            need-alias  (cond-> (set (keys ext-usages))
                          (seq stay->moved) (conj from-ns))
            ;; the mirror of from-require-drops, on the CALLER side: a caller
            ;; rewritten to the new home may be left using NOTHING from
            ;; from-ns, and the stale require is worse than untidy — a :pure
            ;; caller keeps inheriting from-ns's TIER for a dependency it no
            ;; longer has, so the layering check reads a violation that the
            ;; code no longer commits.
            caller-require-drops
            (let [still (reduce (fn [s r]
                                  (if (and (= from-ns (:to-ns r))
                                           (not (moved (:to-name r)))
                                           (not= from-ns (:from-ns r))
                                           (not= :declared (:via r)))
                                    (conj s (:from-ns r))
                                    s))
                                #{} (refs/refs store))]
              (vec (sort (remove still (keys ext-usages)))))
            alias-of    (into {}
                              (map (fn [nsx] [nsx (alias-for (require-specs store nsx) to-ns)]))
                              need-alias)
            no-alias    (sort (keep (fn [[nsx a]] (when (nil? a) nsx)) alias-of))
            ;; requires the MOVED code needs, copied verbatim from from-ns
            from-specs  (require-specs store from-ns)
             ;; the requires THIS move orphans: libs the moved forms used
             ;; that no stay-behind still references. Scoped to the move's
             ;; own damage on purpose — pruning every unused require would
             ;; drop one kept for side effects (defmethod registration),
             ;; which kondo cannot tell from a dead one. A row with no
             ;; :from-var is ns-level and counts as a stay-behind user.
             stay-libs   (->> rows (remove #(moved (:from-var %)))
                              (map :to) (filter symbol?) set)
             from-require-drops
             (->> from-specs (map :lib)
                  (filter (->> moved-rows (map :to) (filter symbol?) set))
                  (remove stay-libs)
                  (remove #{from-ns to-ns 'clojure.core})
                  sort vec)
            needed-libs (->> moved-rows (map :to) distinct
                              ;; kondo marks a callee it cannot resolve with
                              ;; the KEYWORD :clj-kondo/unknown-namespace — a
                              ;; proxy method body is the usual source. It is
                              ;; not a library, and it made this sort compare
                              ;; a keyword against a symbol, which is why
                              ;; slopp.api/open! (the store's only proxy)
                              ;; could not be moved at all.
                              (filter symbol?)
                              (remove #{from-ns to-ns 'clojure.core nil}) sort)
            need-specs  (mapv (fn [lib]
                                {:lib lib
                                 :spec (or (some #(when (= (:lib %) lib) (:spec %))
                                                 from-specs)
                                           (pr-str lib))})
                              needed-libs)
            from-alias  (when (seq moved->stay)
                          (alias-for (if new-ns? [] (require-specs store to-ns)) from-ns))
            ;; refs INTO the target go bare — its ns gets no self-alias
            to-prefixes (into #{to-ns}
                              (keep #(when (= (:lib %) to-ns) (:alias %)))
                              from-specs)
            dequalify   (fn [node]
                          (rewrite-symbols node
                                           (fn [s] (when (and (some? (namespace s))
                                                              (to-prefixes (symbol (namespace s))))
                                                     (symbol (name s))))))
            ;; dequalify's blind spot, REPORTED rather than refused. `base/x`
            ;; goes bare, and if the moved form binds a LOCAL named `x` the
            ;; result is valid Clojure that calls the local — different
            ;; behaviour, nothing red until the runtime. The mirror direction
            ;; (qualify turning a bare local into `from/x`) rewrites the
            ;; binding vector too, so it fails at compile and stays a
            ;; documented limit. local-name? has no scope tracking, which is
            ;; exactly why this reports: refusing on an over-match would block
            ;; a legitimate move with no way through.
            shadowed    (vec (for [e (store/forms store from-ns)
                                   :when (moved (:name e))
                                   :let [sx (store/form-sexpr (:node e))]
                                   :when sx
                                   s (distinct (filter symbol? (tree-seq coll? seq sx)))
                                   :when (and (some? (namespace s))
                                              (to-prefixes (symbol (namespace s)))
                                              (edit/local-name? sx (symbol (name s))))]
                               {:form (:name e) :was s :now (symbol (name s))}))
            to-specs    (cond-> need-specs
                          from-alias (conj {:lib from-ns
                                            :spec (str "[" from-ns " :as " from-alias "]")}))
            ;; transform the moved nodes: public, maybe exported, stay refs qualified
            qualify     (fn [node]
                          (if (seq moved->stay)
                            (rewrite-symbols node
                                             (fn [s] (when (and (nil? (namespace s))
                                                                (moved->stay s))
                                                       (symbol (str from-alias) (str s)))))
                            node))
            moved-nodes (vec (for [e (store/forms store from-ns)
                                   :when (moved (:name e))]
                               (-> (:node e)
                                   (under-meta publicize)
                                   dequalify qualify
                                   (cond-> (:export opts)
                                     (under-meta #(export-mark % (:export opts)))))))
            ;; rewrites: from-ns stay forms (bare→alias) + external (alias→alias)
            from-alias* (get alias-of from-ns)
            from-rw     (when (seq stay->moved)
                          (for [e (store/forms store from-ns)
                                :when (and (:name e) (stay->moved (:name e)))
                                :let [node' (rewrite-symbols
                                             (:node e)
                                             (fn [s] (when (and (nil? (namespace s)) (moved s))
                                                       (symbol (str from-alias*) (str s)))))]
                                :when (not= (n/string node') (n/string (:node e)))]
                            [(:id e) {:ns from-ns :name (:name e) :node node'
                                      :src (n/string node')}]))
            ext-rw      (for [[nsx _] ext-usages
                              :let [prefixes (into #{from-ns}
                                                   (keep #(when (= (:lib %) from-ns) (:alias %)))
                                                   (require-specs store nsx))
                                    a (get alias-of nsx)]
                              e (store/forms store nsx)
                              :when (:name e)
                              :let [node' (rewrite-symbols
                                           (:node e)
                                           (fn [s]
                                             (when (and (some? (namespace s))
                                                        (prefixes (symbol (namespace s)))
                                                        (moved (symbol (name s))))
                                               (if (= nsx to-ns)
                                                 (symbol (name s))
                                                 (symbol (str a) (name s))))))]
                              :when (not= (n/string node') (n/string (:node e)))]
                          [(:id e) {:ns nsx :name (:name e) :node node'
                                    :src (n/string node')}])
            require-adds (into {}
                               (for [nsx (sort need-alias)
                                     :when (not= nsx to-ns)
                                     :let [specs (require-specs store nsx)]
                                     :when (not-any? #(= (:lib %) to-ns) specs)]
                                 [nsx (str "[" to-ns " :as " (get alias-of nsx) "]")]))
            ;; every row is {:from-ns :from-var :to :to-name} — ONE shape, in both
            ;; directions. The destination rows used to omit :to-name while the
            ;; moved→stay rows spelled the same fact `:name`, so a consumer
            ;; reading rows generically got nil on the majority of them.
            module-rows (vec (concat
                              (for [[nsx calls] ext-usages, [f nm] calls]
                                {:from-ns nsx :from-var f :to to-ns :to-name nm})
                              (when (seq stay->moved-calls)
                                (for [[f nm] (sort stay->moved-calls)]
                                  {:from-ns from-ns :from-var f
                                   :to to-ns :to-name nm}))
                              (when (seq moved->stay)
                                (for [nm (sort moved->stay)]
                                  {:from-ns to-ns :from-var (first (sort moved))
                                   :to from-ns :to-name nm}))))]
        (if (seq no-alias)
          {:error (str "no usable alias for " to-ns " in " (vec no-alias)
                       " — their existing aliases collide; rename those first")}
          (cond-> {:new-ns? new-ns?
                   :moved (vec (sort moved))
                   :rewrites (into {} (concat from-rw ext-rw))
                   :require-adds require-adds
                   :module-rows module-rows
                   :removals (vec (sort moved))
                   :from-require-drops from-require-drops
                   :caller-require-drops caller-require-drops
                   :shadowed shadowed}
            new-ns?
            (assoc :new-src
                   (str "(ns " to-ns
                        (when (seq to-specs)
                          (str "\n  (:require "
                               (clojure.string/join "\n            "
                                                    (sort (map :spec to-specs)))
                               ")"))
                        ;; interop moves carry the classes they use
                        (when-let [imp (imports-for store from-ns moved-nodes)]
                          (str "\n  " imp))
                        ")\n\n"
                        ;; the source ns may order callers before definitions
                        ;; (declare-then-use) — the moved set carries its own
                        
                        (clojure.string/join "\n\n" (map n/string moved-nodes))
                        "\n"))

            (not new-ns?)
            (assoc :append moved-nodes
                   :to-require-adds
                   (let [have (set (map :lib (require-specs store to-ns)))]
                     (vec (sort (map :spec (remove #(have (:lib %)) to-specs))))))))))))

(defn ^:export match-in-strings?
  "True when `pat` matches inside a STRING LITERAL of `src` — as opposed to
  matching code.

  A sweep rewrites prose and string contents deliberately (a docs-team rename
  means everything named that). But a string literal is not always prose: a
  test FIXTURE is data, and rewriting a keyword inside one while leaving the
  `{:keys [...]}` in that same string alone makes the fixture silently
  self-inconsistent. Separating the two is what lets a preview say which hits
  need a human eye."
  [src pat]
  (boolean
   (some (fn [zl]
           (let [nd (z/node zl)]
             (and (= :token (n/tag nd))
                  (string? (try (n/sexpr nd) (catch Exception _ nil)))
                  (re-find pat (str (try (n/sexpr nd) (catch Exception _ "")))))))
         (->> (iterate z/next (z/of-string src))
              (take-while (complement z/end?))))))

(defn ^:export requalify-call-args
  "Qualify key `key-name` with `to-ns` in the map LITERAL passed as argument 1
  to calls of the target fn in `src`. `heads` is the SET of head spellings that
  resolve to that fn in this source's namespace — `#{\"slopp.ops.external/open!\"
  \"external/open!\"}`, plus the bare name only inside the defining ns.

  Matching on the bare NAME instead was the bug this signature exists to
  prevent: `slopp.db/open!` and `slopp.ops.external/open!` share a name, and a name-only
  match rewrote calls to both. It showed up only because a dry-run reported 62
  forms where the caller graph said 60.

  The scope is otherwise the point. A store-wide keyword sweep cannot do this
  safely whenever the key means more than one thing: `:dir` names a session
  directory, a git context's directory and a repl cwd. Inside a call to ONE fn
  it unambiguously means that fn's option, and nothing else is touched — not
  another fn's identically-spelled key, not a bare map that is nobody's
  argument.

  Only KEY positions change, so `{:a :dir}` keeps its value. A call passing a
  non-literal (`(open! opts)`) or nothing is left exactly as it is: this reader
  cannot see through a binding and must not pretend to.

  Pure: source string in, source string out; untouched when nothing matches."
  [src heads key-name to-ns]
  (if (or (str/blank? (str to-ns)) (empty? heads))
    src
    (let [kw    (keyword (str key-name))
          qkw   (keyword (str to-ns) (str key-name))
          sx    (fn [nd] (try (n/sexpr nd) (catch Exception _ ::none)))
          kids  (fn [nd] (vec (filter n/sexpr-able? (n/children nd))))
          call? (fn [nd]
                  (and (= :list (n/tag nd))
                       (let [c (kids nd)
                             h (sx (first c))]
                         (and (symbol? h)
                              (contains? (set heads) (str h))
                              (= :map (some-> (second c) n/tag))))))
          requal (fn [m]
                   (n/map-node
                    (interpose (n/spaces 1)
                               (mapcat (fn [[k v]]
                                         [(if (= kw (sx k)) (n/keyword-node qkw) k) v])
                                       (partition 2 (kids m))))))]
      (loop [z (z/of-string src)]
        (cond
          (z/end? z) (z/root-string z)

          (call? (z/node z))
          (let [nd  (z/node z)
                tgt (second (kids nd))
                ch  (mapv #(if (identical? % tgt) (requal %) %) (n/children nd))]
            (recur (z/next (z/replace z (n/list-node ch)))))

          :else (recur (z/next z)))))))

(defn ^:export keys-entry
  "The destructuring entry a key qualified by `ns-part` is bound through:
  `:keys` when `ns-part` is blank, `:<ns-part>/keys` otherwise.

  Small, and named because it is the join between a KEYWORD (`:a/x`, what a
  rename is given) and a DESTRUCTURING (`{:a/keys [x]}`, where the same key is
  spelled with its qualifier one position to the left)."
  [ns-part]
  (if (str/blank? (str ns-part))
    :keys
    (keyword (str ns-part) "keys")))

(defn- keys-binding
  "Map node `mnode`'s `{k [… sym …]}` destructuring entry as
  `{:pairs :entry :vector :sym}`, or nil when this map does not bind `sym`
  through `k`.

  THE definition of \"this destructuring names that key\", in one place
  because its absence broke a keyword sweep in both directions at once. A
  `:keys` vector names its key as a SYMBOL, with the qualifier written only in
  the entry beside it — so a pass that matches the symbol alone both skips
  `{:a/keys [x]}` while renaming `:a/x` and rewrites `{:keys [x]}`, which
  names `:x` and has nothing to do with the rename. The entry keyword is the
  whole of the missing check.

  `k` is `:keys` for an unqualified key and `:<ns>/keys` for a qualified one —
  see `keys-entry`."
  [mnode k sym]
  (let [sx    (fn [nd] (try (n/sexpr nd) (catch Exception _ ::none)))
        kids  (fn [nd] (vec (filter n/sexpr-able? (n/children nd))))
        pairs (vec (partition 2 (kids mnode)))
        entry (first (filter #(= k (sx (first %))) pairs))
        vec-n (second entry)]
    (when (and entry (= :vector (n/tag vec-n)))
      (when-let [tgt (first (filter #(= sym (sx %)) (kids vec-n)))]
        {:pairs pairs :entry entry :vector vec-n :sym tgt}))))

(defn ^:export destructures-key?
  "True when `src` binds `key-name` through a `{:from-ns/keys [key-name]}`
  destructuring (`{:keys [key-name]}` when `from-ns` is blank).

  The question `requalify-keys` answers by rewriting, asked without
  rewriting — for the case it must DECLINE. A sweep that changes a key's
  NAME rather than its qualifier cannot move the symbol, because the symbol
  is a LOCAL BINDING the body still reads; renaming it here would rename a
  binding on the strength of a keyword. So the sweep leaves those alone and
  reports them, which is only possible if it can see them."
  [src key-name from-ns]
  (let [k      (keys-entry from-ns)
        wanted (symbol (str key-name))]
    (loop [z (z/of-string src)]
      (cond
        (z/end? z) false

        (and (= :map (n/tag (z/node z)))
             (keys-binding (z/node z) k wanted))
        true

        :else (recur (z/next z))))))

(defn ^{:export true
        :breaking-ok "from-ns is REQUIRED, not optional: which entry the rewrite matches is the whole correctness question, and a defaulted from would let a caller re-create the bug by omission. Both callers moved with it, and nothing outside this store calls it."}
  requalify-keys
  "Move the single key named `key-name` from the `from-ns`-qualified
  destructuring entry to the `to-ns`-qualified one, leaving every other key in
  the vector where it is. Either side may be blank, which is the unqualified
  `{:keys [x]}` entry.

  The half a textual keyword sweep cannot do. A map destructuring names its
  keys as SYMBOLS inside a `:keys` vector, so renaming the keyword LITERAL
  `:a/x` to `:b/x` everywhere leaves `{:a/keys [x]}` still asking for `:a/x` —
  code that compiles, passes every gate, and reads nil at runtime.

  **Which entry is matched is the entire correctness question**, and it is
  `from-ns` that answers it, never the symbol. `{:keys [x]}` names `:x`; a
  rename of `:a/x` must not touch it. That check's absence broke both
  directions of one sweep at once — the qualified destructurings were skipped
  and the unqualified ones were rewritten to read a key they had never named.
  See `keys-binding`.

  Rebuilds the destructuring map by MOVING the symbol's node, never by
  round-tripping through `sexpr`: a rebuild from sexpr silently drops type
  hints (`^Repository repo`), turning direct interop into reflection. Any
  other entry — `:as`, `:or`, another `:ns/keys` — is carried through
  untouched; the source entry disappears when its last member leaves, and an
  existing destination entry absorbs the symbol, so sweeping several keys of
  one handle converges on a single entry.

  Pure: source string in, source string out; untouched when nothing matches,
  and a no-op when the two qualifications are the same."
  [src key-name from-ns to-ns]
  (let [from-k (keys-entry from-ns)
        to-k   (keys-entry to-ns)]
    (if (= from-k to-k)
      src
      (let [wanted (symbol (str key-name))
            kids   (fn [nd] (vec (filter n/sexpr-able? (n/children nd))))
            vnode  (fn [ns] (n/vector-node (interpose (n/spaces 1) ns)))
            sx     (fn [nd] (try (n/sexpr nd) (catch Exception _ ::none)))
            rebuild
            (fn [mnode]
              (when-let [b (keys-binding mnode from-k wanted)]
                (let [tgt    (:sym b)
                      pairs  (:pairs b)
                      kept   (vec (remove #(= % tgt) (kids (:vector b))))
                      dest   (first (filter #(= to-k (sx (first %))) pairs))
                      others (remove #(or (= % (:entry b)) (= % dest)) pairs)
                      moved  (if dest (conj (kids (second dest)) tgt) [tgt])
                      new    (concat
                              (when (seq kept)
                                [[(n/keyword-node from-k) (vnode kept)]])
                              [[(n/keyword-node to-k) (vnode moved)]]
                              others)]
                  (n/map-node
                   (interpose (n/spaces 1) (apply concat new))))))]
        (loop [z (z/of-string src)]
          (cond
            (z/end? z) (z/root-string z)

            (= :map (n/tag (z/node z)))
            (if-let [m' (rebuild (z/node z))]
              (recur (z/next (z/replace z m')))
              (recur (z/next z)))

            :else (recur (z/next z))))))))

(def ^:private symbol-constituents
  "The characters that can sit INSIDE a Clojure symbol token, as a regex
  character-class body. Shared by the mention regexes below, which differ only
  in which side they bound — a second copy would drift the moment one of them
  learned about a character the other did not.

  `-` is last on purpose: anywhere else in a class it reads as a range."
  "A-Za-z0-9*+!_'?<>=/.&%$:#-")

(defn- qualifier-mention-re
  "A regex matching `alias` used as a QUALIFIER (`alias/…`) inside prose or a
  string. Bounded on the left only: the right side is the qualified name, which
  is symbol-constituent by definition, so [[symbol-mention-re]]'s trailing
  guard would refuse every real hit."
  [alias]
  (re-pattern (str "(?<![" symbol-constituents "])"
                   (java.util.regex.Pattern/quote (str alias)) "/")))

(defn ^:export symbol-mention-re
  "A regex matching `nm` as a whole SYMBOL token in prose or a string — bounded
  by symbol-constituent characters rather than `\\b`, which is a word boundary
  and so never fires at a name's punctuation edge (`valid?`, `->row`). Used to
  surface leftover prose/string mentions after a rename."
  [nm]
  (let [q (java.util.regex.Pattern/quote (str nm))]
    (re-pattern (str "(?<![" symbol-constituents "])" q
                     "(?![" symbol-constituents "])"))))

(defn ^:export qualified-mention-changeset
  "{form-id new-node} rewriting QUALIFIED references inside STRING LITERALS
  across the store, given `renames` as `{old-qsym new-qsym …}` — docstrings,
  teach strings, tool descriptions. `base` is an in-flight changeset
  ({form-id node}) whose nodes take precedence over the store's, so this
  composes with a positional rewrite instead of fighting it.

  A MAP, not a pair, because the operations that strand prose most often move
  many names at once: `edit_move_forms` and `ns_rename` re-address every form
  they touch, and the d9077 case — prose left naming a form's pre-move
  namespace — was a MOVE.

  Only QUALIFIED references are rewritten, and that is the whole safety
  argument: `a.b/c` in prose can only mean that var, while a bare `c` is
  usually a domain word (`zone`, `fee`, `open`) that must not be touched —
  those stay a reported `:mentions` hint for a human to judge. Code positions
  are handled positionally by the caller's own changeset; this pass edits
  string literals ONLY.

  Without it, a rename or move leaves its own documentation pointing at an
  address that no longer resolves — teaching that lies, which ships silently
  because no gate can see a var inside a string."
  [store renames base]
  (let [pairs (vec (for [[o n] renames] [(symbol-mention-re o) (str n)]))
        fix   (fn [s] (reduce (fn [acc [pat rep]] (str/replace acc pat rep)) s pairs))]
    (into {}
          (for [ns-sym (keys (:namespaces store))
                e      (store/elements store ns-sym)
                :when  (:id e)
                :let   [node (get base (:id e) (:node e))]
                :when  (and node
                            (let [txt (n/string node)]
                              (some #(str/includes? txt (str %)) (keys renames))))
                :let   [out (-> (z/of-node node)
                                (z/prewalk
                                 (fn [zl] (and (= :token (z/tag zl))
                                               (string? (z/sexpr zl))
                                               (not= (z/sexpr zl) (fix (z/sexpr zl)))))
                                 (fn [zl] (z/replace zl (n/string-node (fix (z/sexpr zl))))))
                                z/root)]
                :when  (not= (n/string out) (n/string node))]
            [(:id e) out]))))

(defn ^:export module-extract-plan
  "PLAN pulling `ns-syms` (each with its subtree and `-test` siblings) under
  `to-prefix` — the module-grain regroup, analysed before anything is
  written. A namespace that moves from two segments to three becomes
  PACKAGE-PRIVATE, so every caller outside the new parent silently becomes a
  module violation; `ns-rename-changeset` rewrites references faithfully and
  leaves exactly those behind. This names them first.

  Returns {:renames {old new} :exports [{:ns :name :forced-by [qsym…]}…]
  :edges-add [[from-mod to-mod]…] :edges-retire [[from-mod to-mod]…]}, or
  {:error msg} when the regroup would leave a module dependency CYCLE.

  Two rules are borrowed rather than restated: visibility is decided by
  `edit.modules/module-violations` (the same predicate the write gate
  enforces), and cycles are computed over PRODUCTION edges only, the way
  `store/module-layers` does — a `-test` namespace folds into its subject's
  module, so its fixture deps would manufacture cycles that do not exist in
  production (slopp.api ↔ slopp.db is exactly such a pair today)."
  [store ns-syms to-prefix]
  (let [all      (keys (:namespaces store))
        test-ns? (fn [n] (str/ends-with? (str n) "-test"))
        moving   (fn [n] (some (fn [s] (or (= (str n) (str s))
                                           (= (str n) (str s "-test"))
                                           (str/starts-with? (str n) (str s "."))))
                               ns-syms))
        renames  (into {} (for [n all :when (moving n)]
                            [n (symbol (str to-prefix "."
                                            (str/join "." (rest (str/split (str n) #"\.")))))]))
        rn       (fn [n] (get renames n n))
        manifest (or (edit.modules/modules-manifest store) {})
        internal (set (map str all))
        rs       (for [r (refs/refs store)
                       :let [from (:from-ns r) to (:to-ns r)]
                       :when (and (symbol? from) (symbol? to)
                                  (internal (str to))
                                  (not= (str from) (str to)))]
                   (assoc r :from-ns' (rn from) :to-ns' (rn to)))
        edge-of  (fn [k1 k2] (fn [r] [(edit.modules/module-of (k1 r))
                                      (edit.modules/module-of (k2 r))]))
        edges-of (fn [xs f] (into #{} (comp (map f) (remove (fn [[a b]] (= a b)))) xs))
        after    (edges-of rs (edge-of :from-ns' :to-ns'))
        before   (edges-of rs (edge-of :from-ns :to-ns))
        prod     (edges-of (remove #(test-ns? (:from-ns %)) rs)
                           (edge-of :from-ns' :to-ns'))
        g        (reduce (fn [m [a b]] (update m a (fnil conj #{}) b)) {} prod)
        cyclic   (vec (for [[a b] (sort prod)
                            :when (store/module-path (update g a disj b) b a)]
                        [a b]))
        touched  (into #{} (map edit.modules/module-of)
                       (concat (keys renames) (vals renames)))
        cands    (filter #(or (renames (:from-ns %)) (renames (:to-ns %))) rs)
        viol     (fn [r] (first (edit.modules/module-violations
                                 manifest
                                 [{:from-ns (:from-ns' r) :from-var (:from-var r)
                                   :to (:to-ns' r)
                                   :to-name (:to-name r)
                                   :to-export (edit.modules/export-level
                                               store (:to-ns r) (:to-name r))}])))
        exports  (->> cands
                      (keep (fn [r] (when (= :visibility (:rule (viol r))) r)))
                      (group-by (juxt :to-ns' :to-name))
                      (sort-by first)
                      (mapv (fn [[[nsx nm] rs*]]
                              {:ns nsx :name nm
                               :forced-by (->> rs*
                                               (keep (fn [r]
                                                       (when (:from-var r)
                                                         (symbol (str (:from-ns' r))
                                                                 (str (:from-var r))))))
                                               distinct sort vec)})))]
    (if (seq cyclic)
      {:error (str "the regroup would leave a module dependency cycle ("
                   (str/join ", " (map (fn [[a b]] (str a " → " b)) cyclic))
                   ") — extract the shared piece the other way, or restructure"
                   " the callers first")}
      {:renames renames
       :exports exports
       :edges-add (vec (sort (remove (fn [[a b]] (contains? (get manifest a #{}) b))
                                     after)))
       :edges-retire (vec (sort (for [[a bs] manifest b bs
                                      :when (and (or (touched a) (touched b))
                                                 (before [a b])
                                                 (not (after [a b])))]
                                  [a b])))})))

(defn ^:export export-changeset
  "Changeset hoisting each `{:ns :name}` in `targets` onto its module's world
  surface — `^:export` on the defn name, via the same `export-mark` a
  deep-target move uses. `level` (default true) may be a namespace-prefix
  string for subtree-only widening.

  Addressed at the store AS IT IS, so a regroup exports BEFORE it renames:
  the marker has to be in place by the time the namespace goes deep, or the
  intermediate store is one the module gate refuses. A var that already
  carries the marker contributes nothing — a re-run is a no-op, not churn."
  ([store targets] (export-changeset store targets true))
  ([store targets level]
   (into {}
         (keep (fn [{:keys [ns name]}]
                 (let [nsx (symbol (str ns)) nm (symbol (str name))]
                   (when-let [e (store/form-named store nsx nm)]
                     (when-not (edit.modules/export-level store nsx nm)
                       [(:id e) (export-mark (:node e) level)])))))
         targets)))

(defn- zlocs
  "Every zipper location of `node`, in walk order."
  [node]
  (->> (iterate z/next (z/of-node node))
       (take-while (complement z/end?))))

(defn- alias-declaration
  "`node` (an `ns` form) with the ONE alias token in `:as` position rewritten
  `old` → `new`, or nil when there is none.

  Found STRUCTURALLY — by the `:as` immediately to its left — never by symbol
  identity. The same spelling elsewhere in an `ns` form means something else
  entirely: a `:refer`red var, a lib whose last segment happens to match, a
  word in the docstring."
  [node old new]
  (loop [zl (z/of-node node) hit? false]
    (let [as? (boolean (and (= :token (z/tag zl))
                            (= old (safe-sexpr zl))
                            (when-let [l (z/left zl)]
                              (= :as (safe-sexpr l)))))
          zl  (if as? (z/replace zl new) zl)
          nxt (z/next zl)]
      (if (z/end? nxt)
        (when (or hit? as?)
          (let [root (z/root zl)]
            (if (= :forms (n/tag root))
              (or (first (filter n/sexpr-able? (n/children root))) root)
              root)))
        (recur nxt (or hit? as?))))))

(defn- qualified-site-count
  "How many symbol tokens in `node` are qualified by `alias` — counted with the
  same predicate the rewrite uses, so the number reported cannot drift from the
  number changed."
  [node alias]
  (let [a (str alias)]
    (count (for [zl    (zlocs node)
                 :when (= :token (z/tag zl))
                 :let  [s (safe-sexpr zl)]
                 :when (and (symbol? s) (= a (namespace s)))]
             s))))

(defn- strings-mentioning
  "The distinct STRING LITERALS of `src` that `pat` matches — the text a symbol
  rewriter cannot reach, so the caller can be shown it instead."
  [src pat]
  (->> (iterate z/next (z/of-string src))
       (take-while (complement z/end?))
       (keep (fn [zl]
               (let [nd (z/node zl)]
                 (when (= :token (n/tag nd))
                   (let [s (try (n/sexpr nd) (catch Exception _ nil))]
                     (when (and (string? s) (re-find pat s)) s))))))
       distinct
       vec))

(defn ^:export stranded-aliases
  "The callers whose require alias for `new` is still spelled from `old` — the
  residue an ns rename leaves, and the one relationship none of its rewrites
  can reach.

  A rename rewrites the LIB in every require clause and walks straight past the
  `:as` beside it, so `[old.thing :as thing]` becomes `[new.other :as thing]`:
  syntactically perfect, and every call site in that namespace goes on reading
  `thing/f` for a namespace called `other`. Harmless while the old name means
  nothing — and when it is REUSED, as `slopp.api` was, the alias starts naming a
  real and different module, which is both the worse failure and the quiet one.

  An alias is DERIVED from a name when its dot-separated segments are a
  contiguous run of that name's. A row is reported when the alias is derived
  from `old` and not from `new`, and that second half is what keeps an ordinary
  rename quiet: a namespace moving between modules under the same last segment
  (`x.api.query` → `x.read.query`, aliased `query`) is derived from both and
  says nothing false. Measured over slopp's own store — 52 rows for
  `slopp.api` → `slopp.ops`, and zero across four real renames of that shape,
  each of which has callers that do alias it.

  `:suggest` is the same-length SUFFIX of the new name, the alias the convention
  would have produced, and is OMITTED when that spelling is already taken in
  that caller: [[realias-plan]] refuses a taken alias, and a remedy the reader
  cannot run costs exactly what no remedy costs.

  An abbreviation (`caps` for `…capabilities`) is derived from nothing readable
  and is invisible here. Stated rather than papered over — this reports the
  aliases it can prove stale, not every alias a rename made questionable.

  Not folded into [[slopp.index.refs/occurrences-of]] with the other
  unrewritable residue, because it is the one member of that set needing BOTH
  names: an alias is stale relative to what replaced it, and the one-sided
  version — any alias spelled from `old` pointing elsewhere — fires on every
  unrelated lib that happens to share a segment."
  [store old new]
  (let [segs (fn [s] (vec (str/split (str s) #"\.")))
        o    (segs old)
        w    (segs new)
        run? (fn [a b] (boolean (some #(= a (subvec b % (+ % (count a))))
                                      (range 0 (inc (- (count b) (count a)))))))
        drv? (fn [alias name-segs]
               (let [a (segs alias)]
                 (and (<= (count a) (count name-segs)) (run? a name-segs))))]
    (vec
     (for [ns-sym (sort (keys (:namespaces store)))
           :let   [specs (require-specs store ns-sym)]
           s      specs
           :when  (and (:alias s)
                       (= (str new) (str (:lib s)))
                       (drv? (:alias s) o)
                       (not (drv? (:alias s) w)))
           :let   [n     (count (segs (:alias s)))
                   sugg  (when (<= n (count w))
                           (symbol (str/join "." (subvec w (- (count w) n)))))
                   taken (some #(= sugg (:alias %)) specs)]]
       (cond-> {:ns ns-sym :form ns-sym :via :alias :rewritable false
                :alias (:alias s)}
         (and sugg (not taken)) (assoc :suggest sugg))))))

(defn ^:export realias-plan
  "Plan renaming ONE namespace's require alias `old` → `new`: the `:as` in its
  `ns` form and every `old/…` in its bodies, as one step list for `edit-group!`.

  Scoped to `ns-sym` and nothing else, because that is what an alias IS — a
  name a single namespace chose for a lib. Two namespaces calling the same lib
  by different aliases is not drift, so a store-wide alias sweep would be a
  different and far more dangerous verb.

  The two halves are found by different means on purpose. In a BODY only
  `old/x` means the alias; a bare `old` is an ordinary symbol and is routinely
  a local (`(defn two [dep] … (dep/g dep) …)` — one spelling, two meanings,
  three tokens apart). In the `ns` FORM it is the reverse: the alias appears
  bare, so it is located by the `:as` beside it rather than by its spelling,
  which also occurs there as `:refer`red names and lib segments.

  Returns {:steps [...] :sites n :left-behind [...]} or {:error msg}.
  `:left-behind` is the alias mentioned inside STRING literals — a fixture that
  ingests source, a docstring naming `old/f`. Reported, never rewritten: those
  strings are as often data as prose, and rewriting one half of a fixture is
  how a half-renamed `ns` form shipped green in phase 2."
  [store ns-sym old new]
  (try
    (let [old (symbol (str old))
          new (symbol (str new))
          ns-decl? (fn [e] (let [s (try (n/sexpr (:node e)) (catch Exception _ nil))]
                             (boolean (and (seq? s) (= 'ns (first s))))))]
      (cond
        (nil? (get-in store [:namespaces ns-sym]))
        {:error (str "no namespace " ns-sym)}

        (= old new)
        {:error (str ns-sym " already calls it " old)}

        :else
        (let [specs (require-specs store ns-sym)
              held  (some #(when (= old (:alias %)) %) specs)
              taken (some #(when (= new (:alias %)) %) specs)]
          (cond
            (nil? held)
            {:error (str ns-sym " has no alias " old " — it aliases "
                         (clojure.string/join ", " (sort (map str (keep :alias specs)))))}

            taken
            {:error (str ns-sym " already calls " (:lib taken) " by " new
                         " — one qualifier cannot mean two libs")}

            :else
            (let [pat  (qualifier-mention-re old)
                  mapr (fn [sym] (when (= (str old) (namespace sym))
                                   (symbol (str new) (name sym))))
                  rows (for [e (store/forms store ns-sym)
                             :let [src   (n/string (:node e))
                                   node' (if (ns-decl? e)
                                           (alias-declaration (:node e) old new)
                                           (let [r (rewrite-symbols (:node e) mapr)]
                                             (when (not= (n/string r) src) r)))]]
                         {:e e :node' node'
                          :sites (qualified-site-count (:node e) old)
                          :strings (strings-mentioning src pat)})]
              {:steps (vec (for [{:keys [e node']} rows :when node']
                             {:action :replace :ns ns-sym :name (:name e)
                              :source (n/string node')}))
               :sites (reduce + 0 (map :sites rows))
               :lib (:lib held)
               :left-behind (vec (for [{:keys [e strings]} rows, s strings]
                                   {:ns ns-sym :name (:name e) :text s}))})))))
    (catch Exception ex
      {:error (str "realias plan failed: " (ex-message ex))})))
