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
  eval INSIDE the verification image.

  A string rather than a call, for the same reason the schema oracle is one:
  the app's code lives in that image and nowhere else, so the driving has to
  happen where the vars are.

  **The `^:web/page` entry is found by scanning the image's own vars**, which
  needs no store analysis and cannot disagree with what is actually loaded —
  a store-side scan would answer for source the image may not have reloaded.

  Deliberately THIN: the steps travel as EDN and
  [[slopp.web.screen/drive!]] interprets them. A generated call chain would be
  a second producer of the driving behaviour, free to drift from the one a
  test exercises, which is the failure this whole feature exists to remove."
  [steps region detail]
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
       "            s     (drive (open ((var-get pv))) " (pr-str (vec steps)) ")]"
       "        {:screen (text s " (pr-str region) " {:detail " (pr-str detail) "})"
       "         :entry (str pv)})"
       ;; the CAUSE, not just the message: opening an app loads the framework and
       ;; then the app's own code, so the top frame is routinely "Syntax error
       ;; macroexpanding at." while the sentence a reader needs is three causes
       ;; down. A tool that hands back the outermost message has answered
       ;; nothing and looks like it answered.
       "      (catch Throwable e"
       "        {:error (clojure.string/join \" <- \" (take 4 (map #(str (.getSimpleName (class %)) \": \" (.getMessage %))"
       "                                              (take-while some? (iterate #(.getCause %) e)))))"
       "         :entry (str pv)}))))"))

(defn ^:export screen!
  "Look at a screen of THIS store's app, driven headlessly. The `screen` tool.

  `steps` is an ordered script of `{:visit path}` / `{:click label}` /
  `{:fill field :value v}`; `region` scopes the answer to one pane; `detail`
  is `\"structured\"` (default) or `\"prose\"`.

  Runs in the VERIFICATION image, so what comes back is built from the code
  the store currently holds — the same oracle every write is checked against,
  and deliberately not the SERVED app, which can be behind. Looking at a
  screen to decide what to write next must show the code you are writing.

  Returns `{:screen <text> :entry <the ^:web/page var>}`. The entry travels on
  purpose: a screen is only as trustworthy as the app it came from, and a
  store with two marked pages would otherwise answer from whichever the scan
  reached first without ever saying which.

  **A non-map answer comes back as `:raw` rather than as an empty screen.**
  `eval!` hands back data when the printed value is readable and the raw
  string when it is not, so a result carrying anything unreadable would
  otherwise arrive as a blank page with no error — which reads as an app that
  rendered nothing, and sends the reader to the wrong bug entirely."
  [session & {:keys [steps region detail]}]
  (let [d (or detail "structured")
        r (first (repl/eval! (:image @session)
                             (drive-code (or steps []) region (keyword d))))]
    (cond
      (map? r) r
      (nil? r) {:error "the image returned nothing for this screen"}
      :else    {:error "the image's answer was not readable as data" :raw (str r)})))
