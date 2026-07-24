(ns slopp.ui.client.nsview
  "Thin :cljs DOM glue for the store-browser namespace filter. All the testable
  logic lives in the .cljc slopp.client.nsfilter/matches? (JVM-verified); this
  namespace only touches the DOM — the genuinely browser-bound edge. Compiled
  to JS by compile_client and served as a blob; never loaded into the JVM
  oracle. D-web-cljs dogfood."
  (:require [slopp.ui.client.nsfilter :as nsf] [slopp.ui.client.nsschema :as schema]))

(defn apply-filter!
  "Show each row whose namespace matches `needle`, hide the rest. Each row's
  cell is parsed with the SHARED schema and, when it validates, matched on the
  clean namespace name; a row that doesn't parse falls back to its raw text."
  [needle rows]
  (.forEach rows
            (fn [row]
              (let [text   (.-textContent row)
                    parsed (schema/parse-ns-cell text)
                    target (if (and parsed (schema/valid-ns-row? parsed))
                             (:ns parsed)
                             text)]
                (set! (.. row -style -display)
                      (if (nsf/matches? needle target) "" "none"))))))

(defn ^:export init
  "Wire the search box to the namespace rows. No-op if the input is absent."
  []
  (when-let [input (js/document.getElementById "ns-filter")]
    (let [rows (js/document.querySelectorAll ".ns-row")]
      (.addEventListener input "input"
                         (fn [_] (apply-filter! (.-value input) rows))))))

(defn ^:export main
  "Entry point: run init once the DOM is ready."
  []
  (if (= "loading" js/document.readyState)
    (js/document.addEventListener "DOMContentLoaded" (fn [_] (init)))
    (init)))

(defonce ^:unused-ok bootstrap
  ;; compile_client emits a :simple (self-contained) bundle whose top-level
  ;; forms run when the <script> loads, so the page starts the filter with NO
  ;; inline JS — the store browser stays script-src-only. defonce guards against
  ;; a double-wire if the bundle is evaluated twice.
  (main))
