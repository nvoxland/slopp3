(ns slopp.web.screen.render
  "Turning a hiccup tree into lines — the machinery behind
  [[slopp.web.screen/of]], and none of it API.

  THE INVARIANT, stated once so it outlives the current marker set: **every
  marker structured mode emits lives inside `<…>`, and `<` `>` `&` are escaped
  in page text — a marker outside that escape is the v1 flaw returning.** The
  v1 defect was never `#` or `[click]` specifically; it was a marker sharing
  an alphabet with content. The day someone adds a marker outside the tag
  channel, the format will still LOOK like it has the guarantee, and will not.
  (Prose deliberately has no markers and no escaping — it makes no structural
  claims; its one place-holder, the svg, is named in words for the same
  reason.)

  Every decision here came from a real view bug that a careful reviewer's own
  assertions missed, which is why each carries its reason in place rather than
  reading as taste: what joins a line and what starts one, which tags survive
  and which dissolve into text, why a list is counted before it is capped, why
  an `<svg>` is censused by CLASS and never descended.

  Separate from the face so `slopp.web.screen` stays a surface a reader can
  take in — this is the largest thing in the feature and the least often looked
  at."
  (:require [clojure.string :as str]
            [slopp.web.screen.hiccup :as hiccup]))

(defn escape
  "Page text made inert: `&` `<` `>` escaped, HTML-style.

  This is the load-bearing half of the v2 format. The old markers shared an
  alphabet with page text, so a page containing `# xyz` or `[click]` was
  unfalsifiable — and the first real consumer renders CLOJURE SOURCE, a domain
  made of `#`, `[…]` and angle brackets. With every text node escaped, a raw
  `<` in the output can only be the reader speaking, and the provenance rule
  holds: an unprefixed tag was on the page, `slopp:*` was derived, everything
  else is words."
  [s]
  (-> s
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- open-tag
  "One tag with its kept attributes: `(open-tag \"input\" [[:name \"q\"] [:checked true]] true)`
  → `<input name=\"q\" checked/>`.

  `pairs` is ORDERED — attr order is part of the format, so assertions can
  match a whole tag by string. A `true` value renders HTML-boolean style
  (`checked`, `disabled`, `selected`); nil/false render nothing; every value
  is escaped with `\"` included, because attr values live inside quotes."
  [tag-name pairs self-close?]
  (let [esc #(-> (str %)
                 (str/replace "&" "&amp;")
                 (str/replace "<" "&lt;")
                 (str/replace ">" "&gt;")
                 (str/replace "\"" "&quot;"))]
    (str "<" tag-name
         (apply str (for [[k v] pairs
                          :when (and (some? v) (not (false? v)))]
                      (if (true? v)
                        (str " " (name k))
                        (str " " (name k) "=\"" (esc v) "\""))))
         (if self-close? "/>" ">"))))

(def kept-attrs
  "Which PAGE-FACT attributes each kept tag shows, IN ORDER — attr order is
  part of the format so a whole tag can be asserted as one string.

  The test for membership mirrors the tag whitelist's: an attribute survives
  when it carries something an agent acts on or asserts — an address
  (`name`/`id`/`placeholder`), a state a browser shows
  (`value`/`checked`/`selected`/`disabled`), a destination (`href`/`action`),
  the census vocabulary (`class`, on svg alone). The CROSS-CUTTING statements
  — `aria-label`, `aria-hidden`, `inert` — live in [[capability-attrs]] and
  render on every page tag after its row here; keyed per-tag they went
  missing one tag at a time, which is how a consumer twice found a control
  whose state the readout dropped.

  `style` and `class` (off svg) say how things LOOK, which a text readout
  cannot honour and must not pretend to; dropping class everywhere else is
  also what makes sugar verifiable — `:h1.big` and `[:h1 {:class \"big\"}]`
  must render identically, and they can only be seen to when class reaches no
  output.

  Fields keep every attr [[slopp.web.screen/fill!]] addresses by — what you
  see is always something you can drive. Consumed ONLY by [[page-tag]]; a
  branch building pairs by hand is the defect this layout exists to end."
  {:a        [:href]
   :input    [:type :name :id :placeholder :value :checked :disabled]
   :textarea [:name :id :placeholder :disabled]
   :select   [:name :id :disabled]
   :option   [:value :selected :disabled]
   :button   [:type :disabled]
   :form     [:action :method]
   :img      [:alt]
   :label    [:for]
   :svg      [:class]})

(defn handler-note
  "The `slopp:on` value for `node`, or nil — the one fact HTML has no attr
  for, which is why it carries the derived prefix: what handles an event here.

  `click :orders/expand true` — the event, the action, and its SCALAR
  arguments. Scalars travel because they are what tell two neighbouring
  controls apart (`true`/`false` twins); an entity passed whole is elided as
  `…`, the noise that motivated showing the kind alone. A function handler is
  `click (fn)` — honestly opaque, and the standing argument for the data
  idiom: a serializable action is the one shape a readout can REPORT.

  Input handlers report under the event name the app wrote (`change` for
  Reagent's `:on-change`, `input` for a Replicant `:on` map), so the
  annotation round-trips to the code you would grep for."
  [node]
  (let [fmt (fn [ev [how v]]
              (case how
                :fn   (str ev " (fn)")
                :data (if (and (coll? v) (keyword? (first v)))
                        (str ev " "
                             (str/join " " (cons (first v)
                                                 (map #(if (or (coll? %) (fn? %)) "…" (pr-str %))
                                                      (rest v)))))
                        (str ev " " (pr-str v)))
                nil))]
    (or (some->> (hiccup/handler node :click) (fmt "click"))
        (some->> (hiccup/handler node :change) (fmt "change"))
        (some->> (hiccup/handler node :input) (fmt "input")))))

(def capability-attrs
  "The page's cross-cutting statements about controlhood, rendered on EVERY
  page tag after its own [[kept-attrs]] row: `:aria-label` (an address for a
  control with no text), `:aria-hidden` and `:inert` (the page saying what is
  NOT a control).

  ONE list because it existed as two — the block and inline paths each held a
  private copy, and when slopp-ui asked whether a third private attr site
  existed, the copies had ALREADY drifted: the block path had lost `:inert`
  while the inline path kept it. Cross-cutting facts keyed per-tag is how a
  tag misses one; a list every tag appends cannot be missed per-tag."
  [:aria-label :aria-hidden :inert])

(defn- page-tag
  "THE tag builder for an element that was ON THE PAGE — deliberately the only
  route, so a branch cannot hand-build page attrs and drift from the
  whitelist. Three private attr sites accumulated in one day (the inline
  path's, the select branch's, and the block/inline capability-trio copies —
  the last pair already drifted apart when a consumer asked whether a third
  site existed); a sweep fixes instances, a single route fixes the shape.

  Renders, in order: `tag`'s [[kept-attrs]] row (empty for an unlisted tag —
  a `div` carries no page facts of its own), then [[capability-attrs]] (every
  page tag), then derived `extra` pairs (`slopp:count`), then the `slopp:on`
  `note`. Raw [[open-tag]] remains for `slopp:*` derived tags only —
  `slopp:region`, `slopp:elided` — which have no page attrs to be wrong
  about."
  [tag a note extra close?]
  (open-tag (name tag)
            (concat (for [k (kept-attrs tag)] [k (get a k)])
                    (for [k capability-attrs] [k (get a k)])
                    extra
                    [[:slopp:on note]])
            close?))

(defn- inline-str
  "An inline node as its piece of the line — escaped text for the text-only
  tags, a real `<a>` for links, a real tag wherever a handler needs its
  `slopp:on` to ride. Pieces CONCATENATE (a browser inserts nothing between
  adjacent nodes), so spacing comes from the markup — the CSS-margin lesson,
  kept on purpose. Whitespace squeezes but edges survive: a trailing space in
  `\"docs: \"` is the markup's own gap.

  Tags render through [[page-tag]] — the one route. This path has been both
  instances of the private-attr-list class: it hardcoded `href` (dropping
  `aria-hidden` exactly where the deliberately hidden duplicate anchor
  renders) and it held one of the two capability-trio copies that drifted.

  At `:prose` every tag drops away and only the words remain, unescaped —
  prose makes no structural claims, so it has nothing to be confused with."
  [x opts]
  (let [prose? (= :prose (:detail opts))
        squeeze #(str/replace % #"\s+" " ")]
    (cond
      (string? x) (if prose? (squeeze x) (squeeze (escape x)))
      (number? x) (str x)
      (vector? x)
      (let [tag   (hiccup/tag x)
            a     (hiccup/attrs x)
            inner (str/join "" (map #(inline-str % opts) (hiccup/kids x)))]
        (cond
          prose?      inner
          (= :a tag)  (str (page-tag :a a (handler-note x) nil false)
                           inner "</a>")
          (handler-note x)
          (str (page-tag tag a (handler-note x) nil false)
               inner "</" (name tag) ">")
          :else inner))
      :else "")))

(defn- svg-line
  "An `<svg>` as ONE line: its own class as a real attr, a census of what is
  inside it BY CLASS as its content.

  A diagram is coordinates. Dumping it costs a screen of context and answers
  nothing — nobody reads `M 0 0 C 12 40, 88 60, 88 100` and learns where the
  arrow went. What a reader wants from a picture they cannot see is how many of
  each KIND of thing is in it, which is a census.

  **By class, not by tag**, and this is the part worth keeping if nothing else
  survives: a `path` is an edge in one place and a sketched box in another, so
  the tag says nothing and the class says everything. It also makes an overlay
  legible for free — `18 module-node` becoming `1 gap-w2, 17 gap-w0` IS the
  tint check, with no pixels and no browser. `class` survives on svg alone for
  exactly this reason: here it is capability vocabulary, not styling — which
  is why `:svg [:class]` is a [[kept-attrs]] row and this renders through
  [[page-tag]] like every page tag."
  [node prose?]
  (let [a      (hiccup/attrs node)
        own    (:class a)
        census (->> (hiccup/nodes node)
                    (keep #(:class (hiccup/attrs %)))
                    (remove #{own})
                    frequencies
                    (sort-by (juxt (comp - val) key)))]
    (if prose?
      ;; WORDS, not brackets: prose is unescaped by design, so `<svg …>` here
      ;; was the v1 flaw surviving in the one mode where nothing could
      ;; distinguish it from content (slopp-ui, on a real module page)
      (str "svg " (or own "—"))
      (if (seq census)
        (str (page-tag :svg a (handler-note node) nil false)
             (escape (str/join ", " (for [[c n] census] (str n " " c))))
             "</svg>")
        (page-tag :svg a (handler-note node) nil true)))))

(defn emit
  "Hiccup → lines, in the v2 format: plain escaped text by default, a tag kept
  only where it carries a fact an agent acts on or asserts — interactive
  controls, enumeration (`slopp:count`/`slopp:elided`), structure (headings,
  tables, `pre`, `img`, the svg census) — and the provenance rule over all of
  it: an UNPREFIXED tag or attr was really on the page, `slopp:*` is derived
  by this reader, everything else is words.

  Every page tag renders through [[page-tag]] — the one attr route; a branch
  cannot hand-build page attrs, which is how two private lists and a drifted
  pair accumulated in one day. Raw [[open-tag]] appears below only for the
  `slopp:*` derived tags.

  Containers (`div`/`p`/`section`/anything unlisted) are transparent: their
  children render at the same depth, inline runs joining one line, unless the
  container carries a HANDLER — capability keeps a tag whatever its name, so
  a clickable `div` shows as one.

  `:prose` drops every tag and keeps the words and line breaks, unescaped —
  it makes no structural claims, so it has nothing to be confused with.

  `:list-head` caps list rows (nil = everything). The TEST path defaults to
  nil, because a test's tokens are cheap and its false failure is not; the
  `screen` tool passes 3 and the elision is a machine-visible tag, so an
  assertion can never be eaten silently."
  [node depth opts]
  (let [prose?   (= :prose (:detail opts))
        pad      (fn [d] (if prose? "" (apply str (repeat (* 2 d) \space))))
        t        (hiccup/tag node)
        a        (hiccup/attrs node)
        note     (handler-note node)
        ;; RECURSIVE on purpose. An inline TAG carrying a block child is not
        ;; inline — `[:span "a" [:p "b"]]` is a span and is two lines, because
        ;; a browser breaks there and a readout that does not disagrees with
        ;; the page it is describing. Asking the tag alone was one root cause
        ;; under three symptoms: a label glued its block child on, a span at
        ;; block position did the same, and a heading's own (every? inline?)
        ;; guard was answering the wrong question about its children.
        inline?  (fn inline? [x]
                   (or (string? x) (number? x)
                       (and (vector? x)
                            (contains? hiccup/inline-tags (hiccup/tag x))
                            (every? inline? (hiccup/kids x)))))
        run      (fn [ks] (-> (str/join "" (map #(inline-str % opts) ks))
                              (str/replace #" {2,}" " ")
                              str/trim))
        child-lines
        (fn [ks d]
          (let [flush (fn [buf] (when (seq buf)
                                  (let [s (-> (str/join "" buf)
                                              (str/replace #" {2,}" " ")
                                              str/trim)]
                                    (when (seq s) [(str (pad d) s)]))))]
            (loop [ks (seq ks), buf [], out []]
              (cond
                (nil? ks) (into out (flush buf))
                (inline? (first ks))
                (recur (next ks) (conj buf (inline-str (first ks) opts)) out)
                :else
                (recur (next ks) []
                       (-> out
                           (into (flush buf))
                           (into (emit (first ks) d opts))))))))]
    (cond
      (string? node) [(str (pad depth) (if prose? node (escape node)))]
      (number? node) [(str (pad depth) node)]
      (not (vector? node)) nil

      ;; a region wraps its element's own rendering, one level in
      (:data-region a)
      (let [bare  (if (map? (second node))
                    (assoc node 1 (dissoc (second node) :data-region))
                    node)
            inner (emit bare (inc depth) opts)]
        (if prose?
          inner
          (concat [(str (pad depth) "<slopp:region name=\"" (:data-region a) "\">")]
                  inner
                  [(str (pad depth) "</slopp:region>")])))

      (#{:h1 :h2 :h3 :h4 :h5 :h6} t)
      (let [ks (hiccup/kids node)]
        (cond
          prose?              [(str (pad depth) (hiccup/text node))]
          ;; the common heading: words, and inline children that carry
          ;; something — one line, joined the way a browser reads it
          (every? inline? ks) [(str (pad depth) (page-tag t a note nil false)
                                    (run ks) "</" (name t) ">")]
          ;; a heading wrapping a CONTROL opens out rather than swallowing it
          :else               (concat [(str (pad depth) (page-tag t a note nil false))]
                                      (child-lines ks (inc depth))
                                      [(str (pad depth) "</" (name t) ">")])))

      (= :svg t)
      [(str (pad depth) (svg-line node prose?))]

      (#{:ul :ol} t)
      (let [rows  (filterv vector? (hiccup/kids node))
            n     (count rows)
            cap   (:list-head opts)
            shown (if cap (take cap rows) rows)
            over  (when (and cap (> n cap)) (- n cap))]
        (if prose?
          (concat (mapcat #(emit % depth opts) shown)
                  (when over [(str "+" over " more")]))
          (if (zero? n)
            [(str (pad depth) (page-tag t a note [[:slopp:count 0]] true))]
            (concat [(str (pad depth) (page-tag t a note [[:slopp:count n]] false))]
                    (mapcat #(emit % (inc depth) opts) shown)
                    (when over [(str (pad (inc depth)) "<slopp:elided count=\"" over "\"/>")])
                    [(str (pad depth) "</" (name t) ">")]))))

      (= :li t)
      (let [ks (hiccup/kids node)]
        (cond
          prose?              (child-lines ks depth)
          (every? inline? ks) [(str (pad depth) (page-tag :li a note nil false)
                                    (run ks) "</li>")]
          :else               (concat [(str (pad depth) (page-tag :li a note nil false))]
                                      (child-lines ks (inc depth))
                                      [(str (pad depth) "</li>")])))

      (= :input t)
      (if prose?
        [(str (pad depth) (or (:placeholder a) (:name a) (:id a) (:aria-label a) "input"))]
        (let [a* (-> a
                     (update :value #(or % (:default-value a)))
                     (update :type #(some-> % name)))]
          [(str (pad depth) (page-tag :input a* note nil true))]))

      (= :textarea t)
      (let [content (str (or (:value a) (:default-value a)
                             (apply str (filter string? (hiccup/kids node)))))
            ls      (when (seq content) (str/split-lines content))]
        (if prose?
          [(str (pad depth) (or (:name a) (:id a) (:placeholder a) (:aria-label a) "textarea"))]
          (cond
            (empty? content) [(str (pad depth) (page-tag :textarea a note nil true))]
            (= 1 (count ls)) [(str (pad depth) (page-tag :textarea a note nil false)
                                   (escape content) "</textarea>")]
            :else            (concat [(str (pad depth) (page-tag :textarea a note nil false))]
                                     (map escape ls)
                                     [(str (pad depth) "</textarea>")]))))

      (= :select t)
      (let [options (filterv #(= :option (hiccup/tag %)) (hiccup/kids node))
            sel?    (fn [o] (let [oa (hiccup/attrs o)]
                              (boolean (or (:selected oa)
                                           (and (some? (:value a))
                                                (= (:value oa) (:value a)))))))]
        (if prose?
          ;; the SELECTED option's label — what a browser shows closed and a
          ;; screen reader announces — never the tag name as page text: prose
          ;; is unescaped, so "select" there was the v1 flaw class surviving
          ;; exactly where <svg …> did (slopp-ui's project switcher)
          (when-let [chosen (or (first (filter sel? options)) (first options))]
            [(str (pad depth) (hiccup/text chosen))])
          (concat [(str (pad depth) (page-tag :select a note nil false))]
                  (for [o options]
                    (let [oa (assoc (hiccup/attrs o) :selected (sel? o))]
                      (str (pad (inc depth))
                           (page-tag :option oa nil nil false)
                           (escape (hiccup/text o)) "</option>")))
                  [(str (pad depth) "</select>")])))

      (= :button t)
      (let [label (run (hiccup/kids node))]
        (if prose?
          (when (seq label) [(str (pad depth) label)])
          (let [a* (update a :type #(when (= "submit" (some-> % name)) (some-> % name)))]
            [(str (pad depth) (page-tag :button a* note nil false)
                  label "</button>")])))

      (= :form t)
      (let [ks (hiccup/kids node)]
        (if prose?
          (child-lines ks depth)
          (let [a* (update a :method #(some-> % name))]
            (concat [(str (pad depth) (page-tag :form a* note nil false))]
                    (child-lines ks (inc depth))
                    [(str (pad depth) "</form>")]))))

      (= :table t)
      (let [trs   (filter #(= :tr (hiccup/tag %)) (hiccup/nodes node))
            open  (fn [n] (page-tag (hiccup/tag n) (hiccup/attrs n) (handler-note n) nil false))
            close (fn [n] (str "</" (name (hiccup/tag n)) ">"))
            row   (fn [tr d]
                    (let [cells (filter #(#{:th :td} (hiccup/tag %)) (hiccup/kids tr))
                          flat? (every? #(every? inline? (hiccup/kids %)) cells)]
                      (cond
                        prose?
                        [(str (pad d) (str/join " " (map hiccup/text cells)))]

                        ;; the common row: every cell inline, so the whole row
                        ;; rides one line — a wide table stays readable
                        flat?
                        [(str (pad d) (open tr)
                              (apply str (for [c cells]
                                           (str (open c) (run (hiccup/kids c)) (close c))))
                              (close tr))]

                        ;; a cell carrying block content opens out, the way an
                        ;; <li> does — never flattened to its text, because a
                        ;; silently dropped actionable tag is the one thing the
                        ;; whitelist exists to prevent
                        :else
                        (concat [(str (pad d) (open tr))]
                                (mapcat (fn [c]
                                          (concat [(str (pad (inc d)) (open c))]
                                                  (child-lines (hiccup/kids c) (+ d 2))
                                                  [(str (pad (inc d)) (close c))]))
                                        cells)
                                [(str (pad d) (close tr))]))))]
        (if prose?
          (mapcat #(row % depth) trs)
          (concat [(str (pad depth) (page-tag :table a note nil false))]
                  (mapcat #(row % (inc depth)) trs)
                  [(str (pad depth) "</table>")])))

      (= :pre t)
      (let [raw (fn raw [n] (cond (string? n) n
                                  (number? n) (str n)
                                  (vector? n) (apply str (map raw (hiccup/kids n)))
                                  :else ""))
            ls  (str/split-lines (raw node))]
        (if prose?
          ls
          (concat [(str (pad depth) (page-tag :pre a note nil false))]
                  (map escape ls)
                  [(str (pad depth) "</pre>")])))

      (= :img t)
      (if prose?
        (when-let [alt (:alt a)] [(str (pad depth) alt)])
        [(str (pad depth) (page-tag :img a note nil true))])

      (= :label t)
      (let [ks (hiccup/kids node)]
        (cond
          prose?              (child-lines ks depth)
          ;; the ordinary label: words and controls on one row
          (every? inline? ks) [(str (pad depth) (page-tag :label a note nil false)
                                    (run ks) "</label>")]
          ;; a label carrying BLOCK content opens out, exactly as an li does —
          ;; a browser breaks there, so a one-line readout would be describing
          ;; a page that does not exist
          :else               (concat [(str (pad depth) (page-tag :label a note nil false))]
                                      (child-lines ks (inc depth))
                                      [(str (pad depth) "</label>")])))

      ;; an inline element standing at block position is its own one-line run —
      ;; but only if it is inline ALL THE WAY DOWN
      (contains? hiccup/inline-tags t)
      (cond
        (inline? node)
        (let [s (str/trim (inline-str node opts))]
          (when (seq s) [(str (pad depth) s)]))

        ;; opening out is a STRUCTURED-mode decision. Prose makes no structural
        ;; claims, so there is no tag for it to open — and leaving this guard
        ;; off shipped `<a href>` into the one mode whose contract is sentences,
        ;; unescaped, putting back exactly the characters prose exists to
        ;; remove. Every sibling branch has this guard; this one did not.
        prose? (child-lines (hiccup/kids node) depth)

        :else
        ;; it carries BLOCK content, so it opens out — and it KEEPS ITS TAG,
        ;; like an li, a label and a table cell. Letting it fall through to the
        ;; transparent container below reads as the tidier answer and is the
        ;; dangerous one: `[:a {:aria-hidden "true"} [:pre …]]` would lose the
        ;; anchor and the statement that it is not a control, which is the
        ;; defect the whitelist exists to prevent, arriving by another route.
        ;; Over-separation is ugly and readable; a dropped actionable tag is
        ;; not — the same asymmetry `inline-tags` itself is chosen on.
        (concat [(str (pad depth) (page-tag t a note nil false))]
                (child-lines (hiccup/kids node) (inc depth))
                [(str (pad depth) "</" (name t) ">")]))

      ;; every other tag is a transparent container — unless a handler gives
      ;; it a capability to carry, in which case the tag stays for slopp:on
      :else
      (let [ks (hiccup/kids node)
            nm (name (or t :div))]
        (if (and note (not prose?))
          (if (every? inline? ks)
            [(str (pad depth) (page-tag (or t :div) a note nil false)
                  (run ks) "</" nm ">")]
            (concat [(str (pad depth) (page-tag (or t :div) a note nil false))]
                    (child-lines ks (inc depth))
                    [(str (pad depth) "</" nm ">")]))
          (child-lines ks depth))))))
