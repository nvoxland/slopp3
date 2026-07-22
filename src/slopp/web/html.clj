(ns slopp.web.html
  "Pure hiccup→HTML rendering: escape-by-default (via hiccup 2.x, contract
  pinned by the SECURITY tests in slopp.web.html-test), validated tag and
  attribute names (hiccup renders crafted names VERBATIM — an injection door
  escaping does not cover), refused javascript:/data: URLs, and
  [:html/raw s] as the single raw-HTML door."
  (:require [clojure.string :as str]
            [hiccup2.core :as h]))

(def ^:private url-attrs
  "Attributes whose value is a URL: a javascript:/data: scheme here survives
  escaping, so render refuses them."
  #{:href :src :action :formaction})

(defn- refused-url?
  "True for a string whose effective scheme is javascript: or data: —
  lowercased with whitespace and control characters stripped first, the way
  browsers parse it."
  [v]
  (boolean
   (when (string? v)
     (let [s (-> v str/lower-case (str/replace #"[\p{Cntrl}\s]" ""))]
       (or (str/starts-with? s "javascript:")
           (str/starts-with? s "data:"))))))

(defn- check-tag
  "Refuse a tag keyword hiccup would render verbatim: a plain element name
  (letters, digits, hyphens) optionally carrying #id/.class sugar."
  [tag]
  (when (or (namespace tag)
            (not (re-matches #"[a-zA-Z][a-zA-Z0-9-]*(?:[#.][a-zA-Z0-9_-]+)*" (name tag))))
    (throw (ex-info (str "invalid tag: " (pr-str tag)) {:tag tag}))))

(defn- check-attrs
  "Refuse attribute NAMES hiccup would render verbatim, and URL values whose
  scheme survives escaping (javascript:/data:)."
  [attrs form]
  (doseq [[k v] attrs]
    (when-not (and (keyword? k)
                   (nil? (namespace k))
                   (re-matches #"[a-zA-Z][a-zA-Z0-9-]*(?::[a-zA-Z0-9-]+)*" (name k)))
      (throw (ex-info (str "invalid attribute name: " (pr-str k)) {:attr k :form form})))
    (when (and (url-attrs k) (refused-url? v))
      (throw (ex-info (str "refused URL scheme in " k
                           " — javascript:/data: survive escaping; validate the value or serve a static asset")
                      {:attr k :value v :form form})))))

(defn- prepare
  "Validate hiccup data and convert [:html/raw s] islands for rendering.
  The teaching errors here target the common authoring mistakes: a map in
  child position (attrs go in position 2 only) and a vector used to group
  siblings (a vector is an element; a seq splices)."
  [x]
  (cond
    (vector? x)
    (let [tag (first x)]
      (cond
        (= :html/raw tag)
        (if (and (= 2 (count x)) (string? (second x)))
          (h/raw (second x))
          (throw (ex-info "[:html/raw s] takes exactly ONE string payload — it is the single escaping bypass"
                          {:form x})))

        (keyword? tag)
        (let [attrs    (second x)
              attrs?   (map? attrs)
              children (if attrs? (nnext x) (next x))]
          (check-tag tag)
          (when attrs? (check-attrs attrs x))
          (into (if attrs? [tag attrs] [tag]) (map prepare) children))

        :else
        (throw (ex-info (str "a vector is an ELEMENT and needs a keyword tag; "
                             "group siblings with a seq (for/map/list), got: " (pr-str x))
                        {:form x}))))

    (map? x)
    (throw (ex-info (str "a map in child position is not attributes — attrs go in "
                         "position 2 only; compute conditional attrs with cond->: " (pr-str x))
                    {:form x}))

    (seq? x) (map prepare x)
    :else x))

(defn render
  "Hiccup data → HTML string. Text and attribute values escape by default
  (the hiccup 2.x contract, pinned by slopp.web.html-test); tag and
  attribute names are validated; javascript:/data: URLs in
  href/src/action/formaction are refused; [:html/raw s] is the one
  raw-HTML door."
  [hiccup]
  (str (h/html {:mode :html} (prepare hiccup))))

(defn html-response
  "Ring response serving rendered hiccup as text/html. :web/raw true — both
  adapters write the body verbatim. opts may carry :status and extra
  :headers; Content-Type stays ours."
  ([hiccup] (html-response hiccup nil))
  ([hiccup {:keys [status headers]}]
   {:status  (or status 200)
    :web/raw true
    :headers (merge headers {"Content-Type" "text/html; charset=utf-8"})
    :body    (render hiccup)}))

(defn page
  "A full-page hiccup shell: doctype, charset meta, escaped :title, optional
  :lang and extra :head elements. Returns hiccup DATA (a seq — the doctype
  rides [:html/raw]); emits NO inline script or style, so a strict
  Content-Security-Policy works without carve-outs (apps set their own CSP
  header)."
  [{:keys [title head lang]} & body]
  (list [:html/raw "<!DOCTYPE html>"]
        [:html (cond-> {} lang (assoc :lang lang))
         (into [:head
                [:meta {:charset "utf-8"}]
                (when title [:title title])]
               head)
         (into [:body] body)]))
