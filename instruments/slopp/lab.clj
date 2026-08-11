(ns slopp.lab
  "DESIGN.md §10 Phase-1 metric: the payload an agent must consume to answer
  'where is ns/sym referenced, and show its definition' via slopp's form-addressed
  queries vs. a grep-and-read-the-file baseline. Chars are a token proxy (~chars/4).

  The win is structural (CODESTRUCT-style): slopp returns exactly the references +
  the one defining form, whereas grep-and-read forces reading the surrounding file
  to understand context. It grows with the size of the surrounding code."
  (:require [clojure.string :as str]
            [slopp.store.render :as store.render] [slopp.read.query :as query] [slopp.read.graph :as graph]))

(defn reference-query-cost
  "Compare payloads for a 'where is `sym` referenced + show its def' query."
  [session ns-sym sym]
  (let [refs   (vec (graph/query-references session ns-sym sym))
        symi   (query/query-symbol session ns-sym sym)
        source (store.render/render-ns (:store @session) ns-sym)
        grep   (->> (str/split-lines source)
                    (filter #(str/includes? % (str sym)))
                    (str/join "\n"))
        slopp  (str (pr-str refs) "\n" (:source symi))]
    {:slopp-chars      (count slopp)
     :grep-lines-chars (count grep)
     :read-file-chars  (count source)
     :ratio-vs-read    (double (/ (count slopp) (count source)))}))
