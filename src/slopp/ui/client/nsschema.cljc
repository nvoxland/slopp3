(ns slopp.ui.client.nsschema
  "A SHARED (.cljc) contract for a store-browser namespace row: one malli
  schema, validated on the JVM (the existing oracle verifies it here) AND
  compiled to JS so the browser validates the identical shape with the
  identical code — the Locality-of-Behaviour payoff. `parse-ns-cell` turns a
  rendered index cell (\"slopp.http.browse (12)\") back into that shape, so the
  client can validate what the server rendered. Pure and portable — no
  platform interop. D-web-cljs dogfood (b)."
  (:require [malli.core :as m]))

(def ns-row
  "A namespace-index row: its fully-qualified name and its form count."
  [:map
   [:ns :string]
   [:forms [:int {:min 0}]]])

(defn valid-ns-row?
  "True when `row` conforms to `ns-row`. Same result on the JVM (where this
  suite verifies it) and in the browser (malli is `.cljc`)."
  [row]
  (m/validate ns-row row))

(defn parse-ns-cell
  "Parse a rendered index cell — \"slopp.http.browse (12)\" — into an `ns-row`
  map {:ns :forms}, or nil if it doesn't match the shape (the caller falls back
  to the raw text). Surrounding whitespace is tolerated. Pure and portable —
  identical on the JVM and in the browser."
  [text]
  (when-let [[_ nm n] (re-matches #"\s*(\S+)\s+\((\d+)\)\s*" (str text))]
    {:ns nm :forms (parse-long n)}))
