(ns slopp.web.screen.hiccup
  "Reading hiccup as a STRUCTURE — the accessors two framework readers need
  and neither should own.

  `slopp.web.screen` renders a tree for a reader; `slopp.web.browser` walks one
  to find the node a click names. Both have to answer the same three questions —
  what are this node's attributes, what are its children, what text does it show
  — and the answers are subtle enough that two copies would drift: the second
  element is attributes only when it is a MAP, and a seq child is a FRAGMENT
  whose contents splice in.

  Not `slopp.web.html`, which is the other direction: that one turns hiccup into
  a string for a browser and never reads it back."
  (:require [clojure.string :as str]))

(defn attrs
  "The attribute map of hiccup `node`, or nil — the second element is attrs
  only when it is a map, and `[:li \"text\"]` is ordinary hiccup."
  [node]
  (let [a (second node)]
    (when (map? a) a)))

(defn kids
  "Children of hiccup `node`, with lazy seqs flattened and nils dropped.

  `(into [:ul] (for […]))` splices its items in; `[:div (for […])]` leaves ONE
  seq child; a nested `for` leaves seqs inside seqs. All three are ordinary
  hiccup and mean the same thing to a browser.

  A reader that handles only the first is WORSE than useless, because a dropped
  section reads exactly like a section that rendered nothing — and \"the page is
  empty\" is a conclusion an agent will act on."
  [node]
  (let [[_ a & more] node
        body         (if (map? a) more (cons a more))]
    (->> body
         (tree-seq seq? seq)
         (remove seq?)
         (remove nil?)
         vec)))

(defn nodes
  "Every ELEMENT in `node`, depth-first, itself included.

  Descends through [[kids]] and never through an attribute map, which is the
  whole reason this is a function. A naive `(tree-seq coll? seq tree)` walks
  into attrs, and a map entry IS a vector whose first element is usually a
  keyword — so `[:on-click f]` comes back looking exactly like a hiccup
  element, and a click-target search would match handlers."
  [node]
  (filter vector? (tree-seq vector? kids node)))

(defn text
  "The visible text of `node`, the way a reader would say it out loud —
  every string under it, joined and squeezed to single spaces.

  This is textContent, not a rendering: no href, no class, no line structure.
  [[slopp.web.screen]] is the one that renders; a click looking for the button
  labelled \"Add\" wants only what the label SAYS."
  [node]
  (-> (cond
        (string? node) node
        (number? node) (str node)
        (vector? node) (str/join " " (map text (kids node)))
        :else "")
      (str/replace #"\s+" " ")
      str/trim))

(defn target-node
  "The one clickable element `target` names, or a THROW that says why not.

  `target` matches an element's visible TEXT or its `:href` — the two things a
  person actually says when they mean \"click that\". Clickable means it has an
  `:on-click` or an `:href`; a `[:p \"Save\"]` that merely SAYS the word is
  reported as found-but-not-clickable rather than silently missed, because
  those are different bugs and the second one wastes an afternoon.

  Ambiguity throws too, naming the count. Two buttons reading \"Delete\" is a
  real page and picking the first is the kind of quiet guess that makes a
  passing test mean nothing."
  [t target]
  (let [els   (nodes t)
        named (filter #(or (= target (text %))
                           (= target (:href (attrs %))))
                      els)
        hits  (filter #(let [a (attrs %)] (or (:on-click a) (:href a))) named)]
    (cond
      (= 1 (count hits)) (first hits)

      (seq hits)
      (throw (ex-info (str (count hits) " elements match " (pr-str target)
                           " — a click that picks one of them is a guess."
                           " Name an :href instead, or make the labels differ")
                      {:target target :matches (count hits)}))

      (seq named)
      (throw (ex-info (str (pr-str target) " is on this screen but nothing"
                           " clickable says it — the element carries no"
                           " :on-click and no :href. Found in: "
                           (pr-str (mapv first named)))
                      {:target target}))

      :else
      (throw (ex-info (str "nothing on this screen says " (pr-str target)
                           " — clickable text here: "
                           (pr-str (vec (sort (distinct (keep #(let [a (attrs %)]
                                                                 (when (or (:on-click a) (:href a))
                                                                   (or (not-empty (text %))
                                                                       (:href a))))
                                                              els))))))
                      {:target target})))))

(defn region
  "The subtree `name` addresses via `:data-region`, or a THROW naming the
  regions that do exist.

  Cutting the TREE rather than the rendered lines is what makes scoping
  independent of how the screen is being rendered. Line-based scoping looked
  equivalent and was not: it searched for the `§` marker the renderer emits,
  so it worked at one detail level and threw \"no such region\" at another —
  over a screen where the region was plainly present. It also carried the
  indent the region happened to sit at, so an assertion about one pane broke
  when a `<div>` moved around it. Both go away here.

  Refusing is the whole reason this is a function and not a filter. A test
  scoped to a region is ASSERTING that region is on the screen, and a scope
  that quietly returns nothing makes every absence assertion downstream of it
  pass — over a screen that may have rendered nothing at all.

  Naming the present regions rather than only the missing one is the courtesy a
  refusal owes anywhere: the list IS the answer to the question behind the
  mistake."
  [node name]
  (or (when-let [n (first (filter #(= name (:data-region (attrs %))) (nodes node)))]
        ;; the pane comes back as its OWN screen: it must not re-announce the
        ;; region the caller just named, or every scoped assertion carries a
        ;; header saying only what was asked for
        (if (map? (second n))
          (assoc n 1 (dissoc (second n) :data-region))
          n))
      (throw (ex-info (str "no region " (pr-str name) " on this screen — "
                           (let [have (vec (distinct (keep #(:data-region (attrs %))
                                                           (nodes node))))]
                             (if (seq have)
                               (str "regions present: " (str/join ", " have))
                               (str "this screen declares NO regions at all;"
                                    " a pane is addressed by :data-region in"
                                    " its attrs"))))
                      {:region name}))))

(defn field
  "The one input `name` addresses, or a THROW that says why not.

  A field has no visible TEXT, so it is addressed the way a person names one:
  its `:placeholder`, `:name`, `:id` or `:aria-label`. Whichever the app
  happens to have written is the one that works, because requiring a
  particular attribute would make the framework's testability someone's markup
  decision.

  Three outcomes, never one shrug: not found (and the message lists what CAN
  be filled), found but inert (no `:on-change` — a different bug, and the one
  that wastes an afternoon because the field is visibly right there), or
  ambiguous."
  [node name]
  (let [addressed? (fn [a] (some #{name} [(:placeholder a) (:name a) (:id a) (:aria-label a)]))
        named      (filter #(addressed? (attrs %)) (nodes node))
        fillable   (filter #(:on-change (attrs %)) named)]
    (cond
      (= 1 (count fillable)) (first fillable)

      (seq fillable)
      (throw (ex-info (str (count fillable) " fields answer to " (pr-str name)
                           " — filling one of them is a guess")
                      {:field name :matches (count fillable)}))

      (seq named)
      (throw (ex-info (str (pr-str name) " is a field on this screen but has"
                           " no :on-change, so typing into it can change"
                           " nothing")
                      {:field name}))

      :else
      (throw (ex-info (str "no field answers to " (pr-str name)
                           " — fillable here: "
                           (pr-str (vec (sort (distinct (keep #(let [a (attrs %)]
                                                                 (when (:on-change a)
                                                                   (or (:placeholder a) (:name a)
                                                                       (:id a) (:aria-label a))))
                                                              (nodes node)))))))
                      {:field name})))))
