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
  ugly and readable; gluing hides exactly the defect a reader came for."
  #{:a :span :small :strong :em :code :b :i :abbr :time :label :sub :sup :kbd})

(def heading-levels
  "Heading tag → its level, so `#`/`##` carries the structure a reader uses to
  find their place on a page they cannot see."
  {:h1 1 :h2 2 :h3 3 :h4 4 :h5 5 :h6 6})

(defn inline-node?
  "Whether `x` joins the line in progress rather than starting one."
  [x]
  (or (string? x)
      (number? x)
      (and (vector? x) (contains? inline-tags (first x)))))

(defn inline-text
  "Inline node `x` as a single string.

  **An `:href` always travels with its text** — at `:structured` detail. Half
  of what anyone checks on a page is where something POINTS: that an outline
  row links to a form and not to source, that a breadcrumb re-centres, that a
  summary row reaches the thing it summarises. A readout that drops addresses
  answers the easier half of every question and looks complete doing it.

  At `:prose` it does not, because an assertion that only asks whether the page
  SAYS something is paying for every address it will never read.

  A `:class` travels only when `opts` asks. Reading a screen it is noise on
  every element; checking an overlay it is the entire content of the check."
  [x opts]
  (cond
    (string? x) x
    (number? x) (str x)
    (vector? x)
    (let [a      (hiccup/attrs x)
          prose? (= :prose (:detail opts))
          inner  (str/join "" (map #(inline-text % opts) (hiccup/kids x)))]
      (cond-> inner
        (and (:href a) (not prose?))
        (str " [" (:href a) "]")

        ;; a handler with no href — an :href already says "clickable" by being
        ;; one, and two markers on the same element is noise
        ;; a handler with no href — an :href already says "clickable" by being
        ;; one, and two markers on the same element is noise. The DATA form
        ;; names its action, because a serializable handler answers what a
        ;; click DOES and not merely that one is possible: `[click :like-video]`
        ;; is the whole reason to prefer data in the tree.
        (and (hiccup/handler x :click) (not (:href a)) (not prose?))
        (str (let [[how v] (hiccup/handler x :click)]
               (if (and (= :data how) (coll? v) (keyword? (first v)))
                 (str " [click " (first v) "]")
                 " [click]")))

        (and ((:attrs opts) :class) (:class a))
        (str " {" (:class a) "}")))
    :else ""))

(defn svg-summary
  "An `<svg>` as ONE line: its own class, then a census of what is inside it
  BY CLASS.

  A diagram is coordinates. Dumping it costs a screen of context and answers
  nothing — nobody reads `M 0 0 C 12 40, 88 60, 88 100` and learns where the
  arrow went. What a reader wants from a picture they cannot see is how many of
  each KIND of thing is in it, which is a census.

  **By class, not by tag**, and this is the part worth keeping if nothing else
  survives: a `path` is an edge in one place and a sketched box in another, so
  the tag says nothing and the class says everything. It also makes an overlay
  legible for free — `18 module-node` becoming `1 gap-w2, 17 gap-w0` IS the
  tint check, with no pixels and no browser."
  [node]
  (let [own    (:class (hiccup/attrs node))
        census (->> (hiccup/nodes node)
                    (keep #(:class (hiccup/attrs %)))
                    (remove #{own})
                    frequencies
                    (sort-by (juxt (comp - val) key)))]
    (str "<svg " (or own "—")
         (when (seq census)
           (str " — " (str/join ", " (for [[c n] census] (str n " " c)))))
         ">")))

(def field-tags
  "Tags that hold a VALUE rather than children — the elements a reader types
  into or toggles.

  They have no CHILDREN, so without a case of their own they render as the
  empty string: a screen with a search box reads as a screen without one. That
  is the same \"renders as nothing\" failure a dropped fragment causes, and it
  is worse than showing a field unmarked, because an agent deciding what to do
  next concludes the app has no filter."
  #{:input :textarea :select})

(defn field-text
  "A form field as one line: what you would call it, what it holds, and whether
  typing can change anything.

  Addressed by `:placeholder` / `:name` / `:id` / `:aria-label`, whichever the
  app happens to have written. The relationship to [[slopp.web.screen/fill!]]
  is not identity and the difference is deliberate: `fill!` accepts ANY of the
  four, this shows the FIRST present. So the name a reader sees is always one
  that fills, while a field with both a placeholder and a name can still be
  addressed by either. What must never happen is the reverse — a screen naming
  a field you cannot then address — and showing a subset of what is accepted is
  what guarantees it.

  `[fill]` only when an `:on-change` exists. An inert field showing WITHOUT it
  is the finding, not a gap — a box you can type into that changes nothing is
  exactly the defect worth seeing."
  [node opts]
  (let [a      (hiccup/attrs node)
        addr   (or (:placeholder a) (:name a) (:id a) (:aria-label a)
                   (name (first node)))
        typ    (:type a)]
    (if (= :prose (:detail opts))
      addr
      (str addr
           (when (and typ (not= "text" typ)) (str " (" typ ")"))
           (when-let [v (not-empty (str (:value a)))] (str "=" (pr-str v)))
           (when (hiccup/input-handler node) " [fill]")))))

(defn emit
  "Hiccup `node` at nesting `depth` as a seq of text lines.

  One function rather than five, because it is one traversal and splitting it
  would need mutual recursion for no reading benefit. The cases, in order:

  - **inline** — a string or an inline tag, trimmed onto one line
  - **`:svg`** — censused, never descended ([[svg-summary]])
  - **heading** — `#`/`##` by level, so a reader can find their place
  - **`:ul`/`:ol`** — counted, then capped at `:list-head`
  - **anything else** — a block: a `:data-region` opens a `§` section, and
    children are gathered so consecutive INLINE children share one line while
    each block child starts its own

  That last clause is the whole point, and [[inline-tags]] says why.

  **`:detail :prose` drops every structure MARKER and keeps every line
  BREAK.** No `§`, no `#`, no `<ul ×N>`, no indent, no census — because the
  common assertion is \"does it say X\" and pays for none of that. What it must
  never become is the naive flatten this whole namespace exists to replace, so
  the boundaries stay: a block still owns its line, since a wrong sentence in a
  run-on line is invisible. Two things survive on purpose — `+N more`, because
  a truncation that goes quiet is a report lying about its own scope, and a
  bare `<svg class>`, because a picture that renders as nothing reads as a
  section that failed."
  [node depth opts]
  (let [prose? (= :prose (:detail opts))]
    (letfn [(pad [d s] (if prose? s (str (str/join (repeat d "  ")) s)))
            (run-lines [cs d]
              (->> cs
                   (partition-by inline-node?)
                   (mapcat (fn [run]
                             (if (inline-node? (first run))
                               (let [t (str/trim (str/join "" (map #(inline-text % opts) run)))]
                                 (when (seq t) [(pad d t)]))
                               (mapcat #(emit % d opts) run))))))]
      (cond
        (inline-node? node)
        (let [t (str/trim (inline-text node opts))]
          (when (seq t) [(pad depth t)]))

        (not (vector? node)) nil

        :else
        (let [tag (first node)
              a   (hiccup/attrs node)
              cs  (hiccup/kids node)]
          (cond
            (= :svg tag)
            [(pad depth (if prose?
                          (str "<svg " (or (:class a) "—") ">")
                          (svg-summary node)))]

            (field-tags tag)
            [(pad depth (field-text node opts))]

            ;; A CLICKABLE block reads as one line, marker and all. A button is
            ;; a leaf to a reader however it is built, and descending would
            ;; drop the very attribute that says it can be clicked — which is
            ;; what made `[:button {:on-click f} "Add"]` render as bare `Add`,
            ;; identical to a paragraph. The limit, stated: a clickable element
            ;; wrapping headings and lists collapses to one line too.
            (or (hiccup/handler node :click) (:href a))
            (let [t (str/trim (inline-text node opts))]
              (when (seq t) [(pad depth t)]))

            (heading-levels tag)
            (let [t (str/trim (str/join "" (map #(inline-text % opts) cs)))]
              [(pad depth (if prose?
                            t
                            (str (str/join (repeat (heading-levels tag) "#")) " " t)))])

            (#{:ul :ol} tag)
            ;; The COUNT is the finding, and it survives the cap: 32 rows is a
            ;; wall a reader skims past, `×32` is one line they cannot — and an
            ;; abbreviated rendering still supports an exact assertion. The
            ;; `+N more` line is what keeps the cap honest; a truncation that
            ;; did not say so would be a report lying about its own scope.
            (let [items (filterv vector? cs)
                  head  (:list-head opts)
                  shown (if head (take head items) items)
                  left  (- (count items) (count shown))]
              (concat
               (when-not prose?
                 [(pad depth (str "<" (name tag) " ×" (count items) ">"))])
               (mapcat #(emit % (inc depth) opts) shown)
               (when (pos? left) [(pad (inc depth) (str "+" left " more"))])))

            :else
            (let [region (:data-region a)
                  cls    (when ((:attrs opts) :class) (:class a))
                  header (when-not prose?
                           (cond region (str "§ " region)
                                 cls    (str "<" (name tag) " {" cls "}>")))
                  d      (if header (inc depth) depth)]
              (concat (when header [(pad depth header)])
                      (run-lines cs d)))))))))
