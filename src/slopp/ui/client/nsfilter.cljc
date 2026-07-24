(ns slopp.ui.client.nsfilter
  "Platform-neutral (.cljc) predicate for the store-browser namespace filter:
  does a namespace row match the current search box text? Pure string logic,
  verified by the JVM oracle (slopp.client.nsfilter-test) AND compiled to JS
  for the browser (slopp.client.nsview calls it). D-web-cljs dogfood."
  (:require [clojure.string :as str]))

(defn matches?
  "True when `needle` (the search box text) is a case-insensitive substring of
  `text` (a namespace name). A blank needle matches everything — nothing typed
  shows every row. The needle is trimmed first. Pure string logic, so it runs
  identically on the JVM (where this test suite verifies it) and in the browser
  (where slopp.client.nsview calls the compiled form)."
  [needle text]
  (let [needle (str/trim (str needle))]
    (or (str/blank? needle)
        (str/includes? (str/lower-case (str text))
                       (str/lower-case needle)))))
