(ns slopp.webdev.screen
  "The `screen` TOOL — opening this store's app and driving it, so an agent can
  look at a screen with no code written and no browser opened.

  The framework half is `slopp.web.screen`, which SHIPS: any project can open a
  page, click, fill and read. This is the half that does not ship — finding the
  app's `^:web/page` entry and running the driving where the app's vars live,
  which is slopp's own tooling and belongs beside `slopp.webdev.live`.

  **It drives in the VERIFICATION image, not the served app.** Looking at a
  screen to decide what to write next has to show the code you are writing; the
  served app can be behind, and `session_brief`'s `:app {:behind N}` is the
  surface for that question instead.

  Deliberately thin. The steps travel as EDN and `slopp.web.screen/drive!`
  interprets them, so the tool and a test run the same interpreter rather than
  two producers of one behaviour."
  (:require [slopp.image.repl :as repl]))

(defn drive-code
  "The script that opens the app's declared page and drives it, as source to
  eval INSIDE the verification image. `opts`: `:region`, `:detail`,
  `:list-head` (the tool's cap — the test path's default is nil, but a LOOK
  is skimmed, so the tool caps and the elision is a machine-visible tag),
  `:trace` (one screen per step).

  A string rather than a call, for the same reason the schema oracle is one:
  the app's code lives in that image and nowhere else, so the driving has to
  happen where the vars are.

  **The `^:web/page` entry is found by scanning the image's own vars**, which
  needs no store analysis and cannot disagree with what is actually loaded —
  a store-side scan would answer for source the image may not have reloaded.

  Deliberately THIN: the steps travel as EDN and
  [[slopp.web.screen/drive!]] interprets them. A generated call chain would be
  a second producer of the driving behaviour, free to drift from the one a
  test exercises — which is why `:trace` drives ONE session one step at a
  time through the same `drive!` rather than unrolling a loop of its own. Not
  prefix re-drives either, deliberately: an app whose state outlives an open
  (a def'd atom) would re-run every non-idempotent step per prefix, and the
  trace's last screen would disagree with a plain run of the same script.

  The catch renders the CAUSE CHAIN, conditional separator and all, in parity
  with `slopp.read.query/cause-chain` — the two cannot be one fn because this
  code runs in a user's image where the only slopp on the classpath is the
  vendored `slopp.web.*`, but they must not drift: a message-less cause with
  a trailing colon is how parity dies one cosmetic notch at a time."
  [steps {:keys [region detail list-head trace]}]
  (let [shot-opts (pr-str {:detail detail :list-head list-head})]
    (str "(let [pv (first (for [n (all-ns) [_ v] (ns-publics n)"
         "                      :when (:web/page (meta v))] v))]"
         "  (if-not pv"
         "    {:error \"no ^:web/page in this store — mark the zero-arg fn that"
         " builds your app (a :web/routes ctx, or {:state :view}) and slopp can"
         " open it; nothing else has to change\"}"
         ;; fully qualified, NOT an alias: a `require` inside this form runs at
         ;; runtime while the body compiles at read time, so an :as here is a
         ;; "No such namespace" every time.
         "    (try"
         "      (let [open  (requiring-resolve 'slopp.web.screen/open)"
         "            drive (requiring-resolve 'slopp.web.screen/drive!)"
         "            text  (requiring-resolve 'slopp.web.screen/text)"
         "            steps " (pr-str (vec steps))
         "            shot  (fn [s] (text s " (pr-str region) " " shot-opts "))]"
         (if trace
           (str "        (let [s (open ((var-get pv)))]"
                "          {:screens (mapv (fn [step]"
                "                            (drive s [step])"
                "                            {:step step :screen (shot s)})"
                "                          steps)"
                "           :entry (str pv)}))")
           (str "        {:screen (shot (drive (open ((var-get pv))) steps))"
                "         :entry (str pv)})"))
         "      (catch Throwable e"
         "        {:error (clojure.string/join \" <- \""
         "                  (take 4 (map #(let [m (.getMessage %)]"
         "                                  (cond-> (.getSimpleName (class %))"
         "                                    (seq (str m)) (str \": \" m)))"
         "                               (take-while some? (iterate #(.getCause %) e)))))"
         "         :entry (str pv)}))))")))

(defn ^:export screen!
  "Look at a screen of THIS store's app, driven headlessly. The `screen` tool.

  `steps` is an ordered script of `{:visit path}` / `{:click label}` /
  `{:fill field :value v}`; `region` scopes the answer to one pane; `detail`
  is `\"structured\"` (default) or `\"prose\"`; `trace` returns one screen per
  step (prefix re-drives — cheap, and it keeps `drive!` the one interpreter).

  Runs in the VERIFICATION image, so what comes back is built from the code
  the store currently holds — the same oracle every write is checked against,
  and deliberately not the SERVED app, which can be behind. Looking at a
  screen to decide what to write next must show the code you are writing.

  Returns `{:screen <text> :entry <the ^:web/page var>}` (`:screens [{:step
  :screen} …]` under trace). The entry travels on purpose: a screen is only as
  trustworthy as the app it came from, and a store with two marked pages would
  otherwise answer from whichever the scan reached first without ever saying
  which.

  Input it cannot mean is refused HERE, before the image: a `detail` outside
  its two values used to silently render structured (a wrong default reported
  as success), and a malformed one corrupted the GENERATED code, whose read
  failure came back blamed on the image's answer. An eval-level error string
  now IS `:error` — it is the diagnosis, not an unreadable answer — and only a
  genuinely unreadable non-map still lands under `:raw`."
  [session & {:keys [steps region detail trace]}]
  (let [d (or detail "structured")]
    (cond
      (not (contains? #{"structured" "prose"} d))
      {:error (str "detail must be \"structured\" or \"prose\" — got "
                   (pr-str detail) ". A silent default over a typo would"
                   " answer the wrong question confidently")}

      (and steps (not (and (sequential? steps) (every? map? steps))))
      {:error (str "steps must be an array of step objects — {visit …} /"
                   " {click …} / {fill … value …} — got " (pr-str steps))}

      :else
      (let [r (first (repl/eval! (:image @session)
                                 (drive-code (or steps [])
                                             {:region    region
                                              :detail    (keyword d)
                                              :list-head 3
                                              :trace     (boolean trace)})))]
        (cond
          (map? r)    r
          (nil? r)    {:error "the image returned nothing for this screen"}
          (string? r) {:error r}
          :else       {:error "the image's answer was not readable as data"
                       :raw (str r)})))))
