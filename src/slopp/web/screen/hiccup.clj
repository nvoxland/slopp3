(ns slopp.web.screen.hiccup
  "Reading hiccup as a STRUCTURE — the accessors every framework reader needs
  and none should own.

  `slopp.web.screen` both renders a tree for a reader and walks one to find
  the node a click or a fill names. All of its questions come through here —
  what is this element's tag ([[tag]], sugar parsed), what are its attributes
  ([[attrs]], sugar merged), what are its children ([[kids]], seqs flattened,
  fragments spliced, components expanded via [[expand]]), what text does it
  show ([[text]], textContent-faithful) — because the answers are subtle
  enough that two copies would drift, and the review measured exactly that
  drift: a second text function made the screen name a button the click could
  not press.

  Not `slopp.web.html`, which is the other direction: that one turns hiccup
  into a string for a browser and never reads it back."
  (:require [clojure.string :as str]))

(defn- sugar
  "Split a possibly-sugared tag keyword: `:input.search#q` → `[:input \"q\" (\"search\")]`.

  Hiccup's id/class shorthand is not an extension — `slopp.web.html/render`
  accepts it, so views legal in this framework use it. A reader that compares
  raw keywords sees `:input.search` as an unknown tag: the review measured a
  classed search box rendering as NOTHING while `fill!` (which reads attrs, not
  tags) happily drove it — a field the screen denied existed. Parsing sugar in
  one private place is what keeps the tag set and the attr map the only two
  vocabularies."
  [head]
  (let [s (name head)
        h (str/index-of s "#")
        d (str/index-of s ".")
        i (cond (and h d) (min h d) h h :else d)]
    (if (nil? i)
      [head nil nil]
      (let [tokens (re-seq #"[#.][^#.]+" (subs s i))]
        [(keyword (subs s 0 i))
         (some #(when (str/starts-with? % "#") (subs % 1)) tokens)
         (seq (keep #(when (str/starts-with? % ".") (subs % 1)) tokens))]))))

(defn tag
  "The element's tag with sugar stripped: `[:input.search#q …]` is an `:input`.

  Nil for a non-keyword head (a component vector before [[expand]] has run).
  Every consumer that switches on \"what kind of element is this\" goes through
  here, so `:h1.title` and `:h1` are one tag everywhere or nowhere."
  [node]
  (when (keyword? (first node))
    (first (sugar (first node)))))

(defn attrs
  "The attribute map of hiccup `node`, or nil — the second element is attrs
  only when it is a map, and `[:li \"text\"]` is ordinary hiccup.

  Tag sugar contributes: `:input#q.big` carries `{:id \"q\" :class \"big\"}`,
  merged UNDER any explicit map — an explicit `:id` wins, classes join. That
  makes the sugar spelling and the map spelling one element to every reader:
  the field finder, the svg census, the renderer's attr whitelist."
  [node]
  (let [a    (second node)
        base (when (map? a) a)]
    (if-not (keyword? (first node))
      base
      (let [[_ id classes] (sugar (first node))]
        (if (and (nil? id) (nil? classes))
          base
          (cond-> (or base {})
            id            (update :id #(or % id))
            (seq classes) (update :class #(if %
                                            (str (str/join " " classes) " " %)
                                            (str/join " " classes)))))))))

(defn expand
  "A component vector, called the way its library would call it.

  `[my-comp arg]` is Reagent's ordinary tree: the fn is APPLIED to the args
  (form-1), and when the result is itself a fn — form-2's setup-then-render
  shape — that fn is applied again with the same args. Loops until the head is
  no longer a fn, so a component returning another component works.

  The alternative was refusing, and the review measured why neither refusing
  nor the old silent drop is right: the client logic is exactly what this
  browser exists to RUN, and a component is client logic with a name. What
  cannot run headlessly is lifecycle interop (form-3, `:cljs` effects) — and
  that code is `:cljs`, which the reach gate already keeps out of a page's
  closure.

  A component that throws is wrapped to say WHICH component, because a bare
  NPE from three frames inside somebody's view reads as a framework bug."
  [x]
  (if (and (vector? x) (fn? (first x)))
    (let [call (fn [f]
                 (try (apply f (rest x))
                      (catch Throwable e
                        (throw (ex-info (str "a component fn in the tree threw while"
                                             " rendering headlessly: "
                                             (or (ex-message e) (str (class e)))
                                             " — args " (pr-str (vec (rest x))))
                                        {:args (vec (rest x))} e)))))
          r    (call (first x))
          r    (if (fn? r) (call r) r)]
      (recur r))
    x))

(defn kids
  "Children of hiccup `node`, with lazy seqs flattened, nils dropped,
  fragments spliced, and component vectors expanded.

  `(into [:ul] (for […]))` splices its items in; `[:div (for […])]` leaves ONE
  seq child; a nested `for` leaves seqs inside seqs; `[:<> a b]` is a FRAGMENT
  whose contents belong to the parent; `[my-comp arg]` is a component the
  library would CALL ([[expand]]). All five are ordinary hiccup and mean the
  same thing to a browser.

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
         (map expand)
         (mapcat (fn [x]
                   (if (and (vector? x) (= :<> (first x)))
                     (kids x)
                     [x])))
         vec)))

(def inline-tags
  "Tags whose content belongs on the SAME line as its neighbours — a fact
  about READING hiccup, which is why it lives here and the renderer consumes
  it: the same split decides what joins a rendered line and which element OWNS
  a label when `:within` scopes to it.

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
  almost always a ROW. Left inline it rendered two separate toggles as one
  wrong line reporting two controls. Reported from a real screen by the author
  of this set, handing back their own entry. The asymmetry decides it: gluing
  two controls together is a misreading, and the cost of being wrong the other
  way is a spurious newline."
  #{:a :span :small :strong :em :code :b :i :abbr :time :sub :sup :kbd})

(defn nodes
  "Every ELEMENT in `node`, depth-first, itself included.

  Descends through [[kids]] and never through an attribute map, which is the
  whole reason this is a function. A naive `(tree-seq coll? seq tree)` walks
  into attrs, and a map entry IS a vector whose first element is usually a
  keyword — so `[:on-click f]` comes back looking exactly like a hiccup
  element, and a click-target search would match handlers."
  [node]
  (filter vector? (tree-seq vector? kids node)))

(defn- raw-text
  "Every string under `node`, concatenated, with NOTHING normalized away.

  Separate from [[text]] because the normalizing has to happen ONCE, at the
  top. Folded into the recursion it trims each child on its own, and a
  whitespace-only child — the text node between two elements, which a browser
  keeps and renders — trims to the empty string before it can be joined to
  anything."
  [node]
  (cond
    (string? node) node
    (number? node) (str node)
    (vector? node) (str/join "" (map raw-text (kids node)))
    :else          ""))

(defn text
  "The visible text of `node`, the way a browser's textContent reads it —
  every string under it, concatenated, then squeezed to single spaces.

  CONCATENATED, not space-joined: two adjacent elements with no whitespace
  between them have none in a browser either, so `[:a \"Review\"] [:a \"Code\"]`
  reads `ReviewCode` — which is the CSS-spacing lesson, and the fix belongs in
  the markup. The review measured the cost of disagreeing with the renderer
  here: the screen showed `foobar` for a button whose click! label was
  `foo bar`, a button the screen named and refused to press. One function,
  one answer.

  The mirror of that, and it shipped for a while: a whitespace-only string
  BETWEEN two elements is a text node a browser keeps, and squeezing inside
  the recursion trimmed it to nothing before it could be joined. `[:h4 \"quote\"
  \" \" [:small \"demo.order\"]]` read `quotedemo.orderstatic` — the run-together
  spelling that this project's own rule about markup spacing exists to catch,
  invisible to the instrument that checks the rule. So the squeeze happens
  ONCE, here, over [[raw-text]]'s concatenation. Reported by slopp-ui against
  headings, where the RENDERER calls this; the other caller is every label
  `locate`/`click!` match on, so `[:button \"Add\" \" \" [:span \"5\"]]` could only
  be pressed as `Add5`.

  This is textContent, not a rendering: no href, no attrs, no line structure.
  The renderer is the one that renders; a click looking for the button
  labelled \"Add\" wants only what the label SAYS."
  [node]
  (-> (raw-text node)
      (str/replace #"\s+" " ")
      str/trim))

(defn handler
  "What handles `kind` (`:click`, `:input`, `:change` …) on this element —
  `[:fn f]`, `[:data d]`, or nil.

  Two idioms, both putting the handler ON THE ELEMENT, and checked against the
  libraries rather than inferred from one app:

  - **Reagent** — `{:on-click (fn [e] …)}`. A function, translated to React's
    `onClick`.
  - **Replicant** — `{:on {:click …}}`, where the value is a function OR DATA.
    Data goes to one global dispatcher registered with
    `replicant.dom/set-dispatch!`, which receives `(event-data handler-data)`.

  Neither library asks for document-level delegation, and that is why nothing
  here emulates `.closest`: an earlier design synthesised an ancestor chain to
  support a hand-rolled delegation pattern, which turned out to be one app's
  workaround for a problem Replicant already solves.

  ONE accessor because three readers ask this question — the click-target
  finder, the renderer that marks `[click]`, and the driver that invokes — and
  a screen naming a button you cannot press is worse than either half."
  [node kind]
  (let [a (attrs node)
        v (or (get a (keyword (str "on-" (name kind))))
              (get (:on a) kind))]
    (cond
      (nil? v) nil
      (fn? v)  [:fn v]
      :else    [:data v])))

(defn- node-paths
  "Every element under `node` (itself included), each paired with its ancestor
  chain nearest-first: `([el (parent grandparent …)] …)`.

  [[nodes]] deliberately flattens ancestry away; a click cannot. A browser
  resolves a click on a text span by walking UP to the nearest handler —
  bubbling — so the finder needs to know what sits above the element a label
  names."
  ([node] (node-paths node ()))
  ([node ancestors]
   (cons [node ancestors]
         (mapcat #(when (vector? %) (node-paths % (cons node ancestors)))
                 (kids node)))))

(defn locate
  "The one element `target` addresses, resolved exactly as a click resolves —
  visible TEXT, `:href`, or `:aria-label`; bubbling to the nearest ancestor
  carrying a `:click` handler, an `:href`, or being a form's SUBMIT control;
  hidden duplicates yielding to the reachable control — or a THROW that says
  why not (nothing says it; more than one distinct control answers).

  This is the ADDRESSING half of [[target-node]], shared so `:within` scoping
  speaks the same vocabulary as clicking: two addressing schemes for one
  document was a reader's burden (slopp-ui), and the click matcher is the one
  proven on real pages. What it deliberately does NOT apply is the click's
  act-gates — a `disabled` or `inert` control refuses to be PRESSED, but it
  is on the screen and can be looked at."
  [t target]
  (let [pairs      (node-paths t)
        ;; A form's SUBMIT control is clickable while carrying no handler and
        ;; no href. The notion of \"control\" here was Replicant's — something
        ;; with an `:on` — rather than HTML's, so the oldest working control on
        ;; the web was invisible to it: slopp-ui's search button lost to its
        ;; own input's placeholder, both saying \"search\", and the refusal
        ;; said none of them was a control.
        ;;
        ;; A `<button>` with NO type is one too. That is HTML's default and
        ;; the spelling most apps write, and requiring `:type \"submit\"` would
        ;; make drivability a markup decision — the same reason a field is
        ;; addressed by whichever of four attributes the author happened to use.
        submit?    (fn [n ancestors]
                     (let [ty (:type (attrs n))]
                       (and (#{:button :input} (tag n))
                            (or (nil? ty) (= "submit" ty))
                            (boolean (some #(= :form (tag %)) ancestors)))))
        clickable? (fn [n ancestors]
                     (and (vector? n)
                          (boolean (or (handler n :click)
                                       (:href (attrs n))
                                       (submit? n ancestors)))))
        hidden?    (fn [n] (let [a (attrs n)]
                             (or (contains? #{"true" true} (:aria-hidden a))
                                 (:inert a))))
        named?     (fn [n] (let [a (attrs n)]
                             (or (= target (text n))
                                 (= target (:href a))
                                 (= target (:aria-label a)))))
        named      (filter (comp named? first) pairs)
        resolved   (distinct (keep (fn [[n ancestors]]
                                     (some #(when (clickable? % ancestors) %)
                                           (cons n ancestors)))
                                   named))
        ;; a hidden duplicate yields to the reachable control — but a hidden
        ;; SOLE match stays, because a mouse reaches what a screen reader
        ;; skips, and refusing it would make the readout deny its own page
        reachable  (remove hidden? resolved)
        resolved   (if (seq reachable) reachable resolved)]
    (cond
      (= 1 (count resolved)) (first resolved)

      (seq resolved)
      (throw (ex-info (str (count resolved) " distinct controls answer to " (pr-str target)
                           " — picking one of them is a guess."
                           " Name an :href instead, or make the labels differ")
                      {:target target :matches (count resolved)}))

      ;; found-but-unhandled: for ADDRESSING this is an answer, not a bug —
      ;; a plain element can be looked at. But two plain elements saying the
      ;; same thing are still a guess; the first-pick this arm briefly held
      ;; is the exact silent choice the region and click arms just lost.
      (seq named)
      (let [bare (distinct (map first named))]
        (if (= 1 (count bare))
          (first bare)
          (throw (ex-info (str (count bare) " elements say " (pr-str target)
                               " and none of them is a control — scoping to"
                               " one of them is a guess. Make the texts"
                               " differ, or address an enclosing control")
                          {:target target :matches (count bare)}))))

      :else
      (throw (ex-info (str "nothing on this screen says " (pr-str target)
                           " — addressable here: "
                           (pr-str (vec (sort (distinct (keep (fn [[n anc]]
                                                                (when (clickable? n anc)
                                                                  (or (not-empty (text n))
                                                                      (:aria-label (attrs n))
                                                                      (:href (attrs n)))))
                                                              pairs))))))
                      {:target target})))))

(defn target-node
  "The one clickable element a click on `target` would reach, or a THROW that
  says why not.

  Resolution is [[locate]]'s — one vocabulary (visible text, `:href`,
  `:aria-label`), one bubbling notion of the owning element, hidden duplicates
  yielding to the reachable control — shared with `:within` scoping so
  looking and pressing cannot drift apart. What this adds is the click's
  ACT-GATES, refusals about pressing rather than finding:

  - the resolved element carries no capability — it is on the screen but
    neither it nor anything above it handles a click
  - the control is DISABLED or INERT — a browser fires nothing

  Two matches that resolve to the SAME control (a label and its href, a span
  and its sibling inside one button) are one click, not an ambiguity."
  [t target]
  (let [n (locate t target)
        a (attrs n)
        ;; a form's SUBMIT control handles a click by submitting it — the same
        ;; standing as an `:href` navigating, and a browser's own behaviour
        ;; rather than the app's. Derived from the DOCUMENT rather than from
        ;; the element, because the relationship that makes it a control is
        ;; the form around it.
        submit? (let [ty (:type a)]
                  (and (#{:button :input} (tag n))
                       (or (nil? ty) (= "submit" ty))
                       (boolean (some (fn [[x anc]]
                                        (and (= x n)
                                             (some #(= :form (tag %)) anc)))
                                      (node-paths t)))))]
    (cond
      (not (or (handler n :click) (:href a) submit?))
      (throw (ex-info (str (pr-str target) " is on this screen but neither it nor"
                           " anything above it handles a click — no :on-click,"
                           " no :on {:click …}, no :href on the element or its"
                           " ancestors. Found in: " (pr-str [(first n)]))
                      {:target target}))

      (:disabled a)
      (throw (ex-info (str (pr-str target) " is disabled — a browser fires"
                           " nothing on a disabled control, so neither does"
                           " this. Whatever disables it in the app's state"
                           " is the thing to change first")
                      {:target target :disabled true}))

      (:inert a)
      (throw (ex-info (str (pr-str target) " is inert — a browser delivers"
                           " no events to an inert element, so neither does"
                           " this. Whatever marks it inert is the thing to"
                           " change first")
                      {:target target :inert true}))

      :else n)))

(defn scope-node
  "The subtree `:within` scopes to: [[locate]]'s element, widened to the
  BLOCK that owns its line.

  Two modes, two notions of ownership, deliberately: a CLICK bubbles to the
  nearest element with a capability (the innermost link wins, as in a
  browser); a LOOK widens to the nearest block-level element, because the
  question behind `:within \"rate\"` is about the ROW that line renders as —
  asking it of the inline anchor would answer with the one word you already
  typed. The block/inline split is [[inline-tags]], the same fact that
  decides what joins a rendered line, so the scope you get is exactly the
  line-owning element you see."
  [t target]
  (let [n (locate t target)]
    (if-not (contains? inline-tags (tag n))
      n
      (let [ancestors (some (fn [[node anc]] (when (identical? node n) anc))
                            (node-paths t))
            ancestors (or ancestors
                          (some (fn [[node anc]] (when (= node n) anc))
                                (node-paths t)))]
        (or (first (remove #(contains? inline-tags (tag %)) ancestors))
            n)))))

(defn region
  "The subtree `name` addresses via `:data-region`, or a THROW naming the
  regions that do exist.

  Cutting the TREE rather than the rendered lines is what makes scoping
  independent of how the screen is being rendered. Line-based scoping looked
  equivalent and was not: it searched for the marker the renderer emits, so it
  worked at one detail level and threw \"no such region\" at another — over a
  screen where the region was plainly present. It also carried the indent the
  region happened to sit at, so an assertion about one pane broke when a
  `<div>` moved around it. Both go away here.

  Refusing is the whole reason this is a function and not a filter. A test
  scoped to a region is ASSERTING that region is on the screen, and a scope
  that quietly returns nothing makes every absence assertion downstream of it
  pass — over a screen that may have rendered nothing at all.

  TWO regions under one name refuse the same way an ambiguous click does. The
  review caught this as the one addressing surface that silently took the
  first match — and it is the surface scoped assertions ride on, where a
  quiet guess makes a test pass against the wrong pane.

  Naming the present regions rather than only the missing one is the courtesy a
  refusal owes anywhere: the list IS the answer to the question behind the
  mistake."
  [node name]
  (let [matches (filter #(= name (:data-region (attrs %))) (nodes node))]
    (cond
      (> (count matches) 1)
      (throw (ex-info (str (count matches) " regions on this screen are named "
                           (pr-str name) " — scoping to one of them is a guess,"
                           " and an assertion against the wrong pane passes over"
                           " the broken one. Make the names differ")
                      {:region name :matches (count matches)}))

      (= 1 (count matches))
      ;; the pane comes back as its OWN screen: it must not re-announce the
      ;; region the caller just named, or every scoped assertion carries a
      ;; header saying only what was asked for
      (let [n (first matches)]
        (if (map? (second n))
          (assoc n 1 (dissoc (second n) :data-region))
          n))

      :else
      (throw (ex-info (str "no region " (pr-str name) " on this screen — "
                           (let [have (vec (distinct (keep #(:data-region (attrs %))
                                                           (nodes node))))]
                             (if (seq have)
                               (str "regions present: " (str/join ", " have))
                               (str "this screen declares NO regions at all;"
                                    " a pane is addressed by :data-region in"
                                    " its attrs"))))
                      {:region name})))))

(defn input-handler
  "What handles typing into this element — `[:fn f]`, `[:data d]`, or nil.

  Two event NAMES because the libraries disagree and both are ordinary:
  Reagent apps overwhelmingly write `:on-change`, and `:input` is the DOM event
  that actually fires per keystroke, which is what a Replicant `:on` map
  usually names. Trying one and not the other would make a field inert for half
  the ecosystem, and inert reads as a bug in the app rather than in the reader."
  [node]
  (or (handler node :change) (handler node :input)))

(defn form-of
  "The `<form>` enclosing `el` within `root`, or nil.

  The relationship that makes a plain HTML control a control. A field carrying
  no handler is not inert — its value travels to its form's `action` when the
  form is SUBMITTED — so the enclosing form is both what a fill remembers
  against and what a submit serialises.

  Matched STRUCTURALLY rather than by identity: [[kids]] expands component
  vectors, so two calls to [[nodes]] over one document yield equal elements
  that are not the same objects. Two genuinely identical forms on one screen
  would be ambiguous, and that is already refused one level up — a name that
  answers twice is a guess, whether the duplication is in the field or in the
  form around it."
  [root el]
  (first (for [f     (nodes root)
               :when (= :form (tag f))
               :when (some #(= el %) (nodes f))]
           f)))

(defn field
  "The one input `name` addresses, or a THROW that says why not.

  A field has no visible TEXT, so it is addressed the way a person names one:
  its `:placeholder`, `:name`, `:id` or `:aria-label`. Whichever the app
  happens to have written is the one that works, because requiring a
  particular attribute would make the framework's testability someone's markup
  decision.

  Fillable means [[input-handler]] finds one, under either idiom.

  Three outcomes, never one shrug: not found (and the message lists what CAN
  be filled), found but inert (no handler — a different bug, and the one that
  wastes an afternoon because the field is visibly right there), or ambiguous."
  [node name]
  (let [addressed? (fn [a] (some #{name} [(:placeholder a) (:name a) (:id a) (:aria-label a)]))
        named      (filter #(addressed? (attrs %)) (nodes node))
        ;; a NAMED field inside a `<form>` is fillable with no handler at all:
        ;; its value does not travel on INPUT, it travels on SUBMIT. Refusing
        ;; it said "typing into it can change nothing", which is a different
        ;; statement and a false one — and it made the oldest working control
        ;; on the web the only undrivable thing in an app.
        fillable   (filter #(or (input-handler %)
                                (and (:name (attrs %)) (form-of node %)))
                           named)]
    (cond
      (= 1 (count fillable)) (first fillable)

      (seq fillable)
      (throw (ex-info (str (count fillable) " fields answer to " (pr-str name)
                           " — filling one of them is a guess")
                      {:field name :matches (count fillable)}))

      (seq named)
      (throw (ex-info (str (pr-str name) " is a field on this screen but has"
                           " no :on-change and no :on {:input …}, so typing"
                           " into it can change nothing")
                      {:field name}))

      :else
      (throw (ex-info (str "no field answers to " (pr-str name)
                           " — fillable here: "
                           (pr-str (vec (sort (distinct (keep #(let [a (attrs %)]
                                                                 (when (input-handler %)
                                                                   (or (:placeholder a) (:name a)
                                                                       (:id a) (:aria-label a))))
                                                              (nodes node)))))))
                      {:field name})))))
