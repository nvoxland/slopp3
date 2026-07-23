(ns slopp.client.nsview
  "Thin :cljs DOM glue for the store-browser namespace filter. All the testable
  logic lives in the .cljc slopp.client.nsfilter/matches? (JVM-verified); this
  namespace only touches the DOM — the genuinely browser-bound edge. Compiled
  to JS by compile_client and served as a blob; never loaded into the JVM
  oracle. D-web-cljs dogfood."
  (:require [slopp.client.nsfilter :as nsf]))

(defn apply-filter!
  "Show each row whose text matches `needle`, hide the rest."
  [needle rows]
  (.forEach rows
            (fn [row]
              (set! (.. row -style -display)
                    (if (nsf/matches? needle (.-textContent row)) "" "none")))))

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
