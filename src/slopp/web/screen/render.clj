(ns slopp.web.screen.render
  "Turning a hiccup tree into lines — the machinery behind
  [[slopp.web.screen/of]], and none of it API.

  Every decision here came from a real view bug that a careful reviewer's own
  assertions missed, which is why each carries its reason in place rather than
  reading as taste: what joins a line and what starts one, why an `:href`
  always travels, why a list is counted before it is capped, why an `<svg>` is
  censused by CLASS and never descended.

  Separate from the face so `slopp.web.screen` stays a surface a reader can
  take in — this is the largest thing in the feature and the least often looked
  at."
  (:require [clojure.string :as str]
            [slopp.web.screen.hiccup :as hiccup]))

(def inline-tags
  "Tags whose content belongs on the SAME line as its neighbours.

  This one set is the difference between a readable screen and a blob. The
  naive readout — every string in tree order, joined by spaces — renders
  `[:h1 \"code\"] [:p \"3 modules…\"]` as `code 3 modules…`, and a wrong
  sentence in the middle of a run-on line is invisible.

  **Everything NOT listed here starts a new line**, and that direction is the
  deliberate one: a tag this set has not heard of is more usefully
  over-separated than silently glued to its neighbour. Over-separation is
  ugly and readable; gluing hides exactly the defect a reader came for.

  **`:label` is NOT here, and it is the one that tests the rule.** It is
  genuinely inline in HTML, so it belonged by the letter — and in a form it is
  almost always a ROW. Left inline it rendered two separate toggles as
  `private definitions · 1state variables · 0`, which is one wrong line
  reporting two controls. Reported from a real screen by the author of this
  set, handing back their own entry. The asymmetry decides it: gluing two
  controls together is a misreading, and the cost of being wrong the other way
  is a spurious newline."
  #{:a :span :small :strong :em :code :b :i :abbr :time :sub :sup :kbd})

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
  "Which attributes each kept tag shows, IN ORDER — attr order is part of the
  format so a whole tag can be asserted as one string.

  The test for membership mirrors the tag whitelist's: an attribute survives
  when it carries something an agent acts on or asserts — an address
  (`name`/`id`/`placeholder`/`aria-label`), a state a browser shows
  (`value`/`checked`/`selected`/`disabled`), a destination (`href`/`action`).
  `style` and `class` say how things LOOK, which a text readout cannot honour
  and must not pretend to — `class` survives only on `<svg>`, where it is the
  census vocabulary. Dropping class everywhere else is also what makes sugar
  verifiable: `:h1.big` and `[:h1 {:class \"big\"}]` must render identically,
  and they can only be seen to when class reaches no output.

  Fields keep every attr [[slopp.web.screen/fill!]] addresses by — the old
  format showed the FIRST addressing attr and the review's lead finding was
  its mirror (a field the screen denied while fill! drove it). All of them
  visible means what you see is always something you can drive."
  {:a        [:href]
   :input    [:type :name :id :placeholder :value :checked :disabled :aria-label]
   :textarea [:name :id :placeholder :disabled :aria-label]
   :select   [:name :id :disabled :aria-label]
   :option   [:value :selected]
   :button   [:type :disabled :aria-label]
   :form     [:action :method]
   :img      [:alt]
   :label    [:for]})

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

(defn- inline-str
  "An inline node as its piece of the line — escaped text for the text-only
  tags, a real `<a>` for links, a real tag wherever a handler needs its
  `slopp:on` to ride. Pieces CONCATENATE (a browser inserts nothing between
  adjacent nodes), so spacing comes from the markup — the CSS-margin lesson,
  kept on purpose. Whitespace squeezes but edges survive: a trailing space in
  `\"docs: \"` is the markup's own gap.

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
          (= :a tag)  (str (open-tag "a" [[:href (:href a)]
                                          [:slopp:on (handler-note x)]] false)
                           inner "</a>")
          (handler-note x)
          (str (open-tag (name tag) [[:aria-label (:aria-label a)]
                                     [:slopp:on (handler-note x)]] false)
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
  exactly this reason: here it is capability vocabulary, not styling."
  [node prose?]
  (let [a      (hiccup/attrs node)
        own    (:class a)
        census (->> (hiccup/nodes node)
                    (keep #(:class (hiccup/attrs %)))
                    (remove #{own})
                    frequencies
                    (sort-by (juxt (comp - val) key)))]
    (if prose?
      (str "<svg " (or own "—") ">")
      (if (seq census)
        (str (open-tag "svg" [[:class own]] false)
             (escape (str/join ", " (for [[c n] census] (str n " " c))))
             "</svg>")
        (open-tag "svg" [[:class own]] true)))))

(defn emit
  "Hiccup → lines, in the v2 format: plain escaped text by default, a tag kept
  only where it carries a fact an agent acts on or asserts — interactive
  controls, enumeration (`slopp:count`/`slopp:elided`), structure (headings,
  tables, `pre`, `img`, the svg census) — and the provenance rule over all of
  it: an UNPREFIXED tag or attr was really on the page, `slopp:*` is derived
  by this reader, everything else is words.

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
        inline?  (fn [x] (or (string? x) (number? x)
                             (and (vector? x) (contains? inline-tags (hiccup/tag x)))))
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
                           (into (emit (first ks) d opts))))))))
        pairs    (fn [tag-key extra a*]
                   (concat (for [k (kept-attrs tag-key)] [k (get a* k)]) extra))]
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
      (let [s (hiccup/text node)]
        [(str (pad depth) (if prose? s (str "<" (name t) ">" (escape s) "</" (name t) ">")))])

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
            [(str (pad depth) (open-tag (name t) [[:slopp:count 0] [:slopp:on note]] true))]
            (concat [(str (pad depth) (open-tag (name t) [[:slopp:count n] [:slopp:on note]] false))]
                    (mapcat #(emit % (inc depth) opts) shown)
                    (when over [(str (pad (inc depth)) "<slopp:elided count=\"" over "\"/>")])
                    [(str (pad depth) "</" (name t) ">")]))))

      (= :li t)
      (let [ks (hiccup/kids node)]
        (cond
          prose?              (child-lines ks depth)
          (every? inline? ks) [(str (pad depth) (open-tag "li" [[:slopp:on note]] false)
                                    (run ks) "</li>")]
          :else               (concat [(str (pad depth) (open-tag "li" [[:slopp:on note]] false))]
                                      (child-lines ks (inc depth))
                                      [(str (pad depth) "</li>")])))

      (= :input t)
      (if prose?
        [(str (pad depth) (or (:placeholder a) (:name a) (:id a) (:aria-label a) "input"))]
        (let [a* (-> a
                     (update :value #(or % (:default-value a)))
                     (update :type #(some-> % name)))]
          [(str (pad depth) (open-tag "input" (pairs :input [[:slopp:on note]] a*) true))]))

      (= :textarea t)
      (let [content (str (or (:value a) (:default-value a)
                             (apply str (filter string? (hiccup/kids node)))))
            ls      (when (seq content) (str/split-lines content))]
        (if prose?
          [(str (pad depth) (or (:name a) (:id a) (:placeholder a) (:aria-label a) "textarea"))]
          (let [ps (pairs :textarea [[:slopp:on note]] a)]
            (cond
              (empty? content) [(str (pad depth) (open-tag "textarea" ps true))]
              (= 1 (count ls)) [(str (pad depth) (open-tag "textarea" ps false)
                                     (escape content) "</textarea>")]
              :else            (concat [(str (pad depth) (open-tag "textarea" ps false))]
                                       (map escape ls)
                                       [(str (pad depth) "</textarea>")])))))

      (= :select t)
      (if prose?
        [(str (pad depth) (or (:name a) (:id a) (:aria-label a) "select"))]
        (let [options (filterv #(= :option (hiccup/tag %)) (hiccup/kids node))]
          (concat [(str (pad depth) (open-tag "select" (pairs :select [[:slopp:on note]] a) false))]
                  (for [o options]
                    (let [oa  (hiccup/attrs o)
                          sel (or (:selected oa)
                                  (and (some? (:value a)) (= (:value oa) (:value a))))]
                      (str (pad (inc depth))
                           (open-tag "option" [[:value (:value oa)] [:selected (boolean sel)]] false)
                           (escape (hiccup/text o)) "</option>")))
                  [(str (pad depth) "</select>")])))

      (= :button t)
      (let [label (run (hiccup/kids node))]
        (if prose?
          (when (seq label) [(str (pad depth) label)])
          (let [a* (update a :type #(when (= "submit" (some-> % name)) (some-> % name)))]
            [(str (pad depth) (open-tag "button" (pairs :button [[:slopp:on note]] a*) false)
                  label "</button>")])))

      (= :form t)
      (let [ks (hiccup/kids node)]
        (if prose?
          (child-lines ks depth)
          (let [a* (update a :method #(some-> % name))]
            (concat [(str (pad depth) (open-tag "form" (pairs :form [[:slopp:on note]] a*) false))]
                    (child-lines ks (inc depth))
                    [(str (pad depth) "</form>")]))))

      (= :table t)
      (let [trs (filter #(= :tr (hiccup/tag %)) (hiccup/nodes node))
            row (fn [tr d]
                  (let [cells (filter #(#{:th :td} (hiccup/tag %)) (hiccup/kids tr))]
                    (if prose?
                      (str (pad d) (str/join " " (map hiccup/text cells)))
                      (str (pad d) "<tr>"
                           (apply str (for [c cells]
                                        (str "<" (name (hiccup/tag c)) ">"
                                             (escape (hiccup/text c))
                                             "</" (name (hiccup/tag c)) ">")))
                           "</tr>"))))]
        (if prose?
          (map #(row % depth) trs)
          (concat [(str (pad depth) "<table>")]
                  (map #(row % (inc depth)) trs)
                  [(str (pad depth) "</table>")])))

      (= :pre t)
      (let [raw (fn raw [n] (cond (string? n) n
                                  (number? n) (str n)
                                  (vector? n) (apply str (map raw (hiccup/kids n)))
                                  :else ""))
            ls  (str/split-lines (raw node))]
        (if prose?
          ls
          (concat [(str (pad depth) "<pre>")]
                  (map escape ls)
                  [(str (pad depth) "</pre>")])))

      (= :img t)
      (if prose?
        (when-let [alt (:alt a)] [(str (pad depth) alt)])
        [(str (pad depth) (open-tag "img" [[:alt (:alt a)] [:slopp:on note]] true))])

      (= :label t)
      (let [content (run (hiccup/kids node))]
        (if prose?
          (when (seq content) [(str (pad depth) content)])
          [(str (pad depth) (open-tag "label" (pairs :label [[:slopp:on note]] a) false)
                content "</label>")]))

      ;; an inline element standing at block position is its own one-line run
      (contains? inline-tags t)
      (let [s (str/trim (inline-str node opts))]
        (when (seq s) [(str (pad depth) s)]))

      ;; every other tag is a transparent container — unless a handler gives
      ;; it a capability to carry, in which case the tag stays for slopp:on
      :else
      (let [ks (hiccup/kids node)
            nm (name (or t :div))]
        (if (and note (not prose?))
          (if (every? inline? ks)
            [(str (pad depth) (open-tag nm [[:aria-label (:aria-label a)] [:slopp:on note]] false)
                  (run ks) "</" nm ">")]
            (concat [(str (pad depth) (open-tag nm [[:aria-label (:aria-label a)] [:slopp:on note]] false))]
                    (child-lines ks (inc depth))
                    [(str (pad depth) "</" nm ">")]))
          (child-lines ks depth))))))
