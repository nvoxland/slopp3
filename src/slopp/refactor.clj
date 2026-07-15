(ns slopp.refactor
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
            [slopp.render :as render]
            [slopp.index :as index] [clojure.string :as str]))

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

(defn ns-rename-changeset
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
                     (= 'case head) (and (<= 2 idx) (even? idx))
                     (= 'cond head) (odd? idx)
                     :else false))
         false)))))
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
      {:error (str "the match isn't well-formed Clojure on its own (" pe
                   ") — match COMPLETE forms: a whole expression, clause, or "
                   "binding pair, never a fragment that opens a delimiter it "
                   "doesn't close. Often the fix is matching the ENCLOSING "
                   "form and restating it in the replacement")}
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
                           "map, binding vector, or case/cond clause — this span "
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

(defn keyed-replace-plan
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
(defn subform-replace-plan
  "Plan replacing the unique occurrence of `match-src` inside `form-name` with
  `new-src` (item 5 — paredit's valid-tree→valid-tree invariant, content-
  addressed: siblings are never re-transcribed). A pair match (P1) replaces
  the WHOLE pair span. Returns {:new-form-src s} or {:error msg}."
  [store ns-sym form-name match-src new-src]
  (try
    (if-let [e (store/form-named store ns-sym form-name)]
      (let [form-src (n/string (:node e))
            found    (find-unique-subform form-src match-src form-name)]
        (if (:error found)
          found
          (let [m       (:zloc found)
                e2      (or (:end-zloc found) m)
                [r c]   (z/position m)
                [er ec] (node-span (z/position e2) (n/string (z/node e2)))]
            {:new-form-src (replace-span form-src [r c] [er ec] new-src)})))
      {:error (str "no form named " form-name " in " ns-sym)})
    (catch Exception ex
      {:error (str "subform edit failed: " (ex-message ex))})))

(defn extract-plan
  "Plan extracting the unique occurrence of `subform-src` inside `from-name`
  into a new fn `new-name`: params = the free locals (bound outside the
  subform, used inside), in first-use order. Pair matches (P1) are refused —
  a pair is not an expression. Returns
  {:new-defn-src :new-from-src :params} or {:error msg}."
  [store ns-sym from-name subform-src new-name]
  (try
    (if-let [e (store/form-named store ns-sym from-name)]
      (let [form-src (n/string (:node e))
            found    (find-unique-subform form-src subform-src from-name)]
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
                an       (index/analyze-with-locals (render/render-ns store ns-sym))
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
                                    (str/replace args-template #"\$(\d)"
                                                 (fn [[_ d]]
                                                   (nth args (dec (parse-long d))))))
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
(defn change-signature-plan
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
             (let [an      (index/analyze (render/render-ns store ns-sym))
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
(defn rename-changeset
  "Compute {form-id new-node} renaming `def-ns/old-name` to `new-name` across
  every store namespace."
  [store def-ns old-name new-name]
  (into {}
        (mapcat (fn [ns-sym]
                  (let [an      (index/analyze (render/render-ns store ns-sym))
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
(defn text-replace-plan
  "Plan a RAW-TEXT replace inside form `form-name`: `match-text` must occur
  exactly ONCE in the form's source, and the spliced result must still parse
  to ONE form. The escape hatch for content no structural match can address
  — string literals, docstrings. Rides the same gated replace pipeline.
  Returns {:new-form-src s} or {:error msg}."
  [store ns-sym form-name match-text new-text]
  (try
    (if-let [e (store/form-named store ns-sym form-name)]
      (let [src   ^String (n/string (:node e))
            m     ^String (str match-text)
            i     (.indexOf src m)]
        (cond
          (str/blank? m)
          {:error "text mode needs a non-empty match"}

          (neg? i)
          {:error (str "text not found in " form-name)}

          (>= (.indexOf src m (inc i)) 0)
          {:error (str "text occurs more than once in " form-name
                       " — give a longer unique snippet")}

          :else
          (let [out   (str (subs src 0 i) new-text (subs src (+ i (count m))))
                nodes (filter n/sexpr-able?
                              (n/children (p/parse-string-all out)))]
            (if (= 1 (count nodes))
              {:new-form-src out}
              {:error (str "the replacement does not leave ONE valid form ("
                           (count nodes) " forms parsed)")}))))
      {:error (str "no form named " form-name " in " ns-sym)})
    (catch Exception ex
      {:error (str "text replace failed: " (ex-message ex))})))
