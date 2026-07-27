(ns slopp.ui.basepath
  "Adding and removing the path prefix a slopp app is served under.

  A slopp app emits root-absolute urls — `/js/main.js`, `/api/…`, the paths
  its client pushes into history. Behind a reverse proxy that mounts it at
  `/p/<slug>/`, every one of those resolves at the PROXY instead, and the page
  arrives and does nothing. The UI hub is the first thing to hit this
  (D-ui-hub) but the defect is general: a slopp app could not be served under
  a prefix at all.

  `:cljc` because both ends need the same rule — the server builds urls with
  [[prefixed]] and the browser router takes them apart with [[strip]] — and a
  second implementation of a rule like this is how the two quietly disagree.
  Pure, so the browser half is covered by ordinary in-image tests rather than
  only by looking at a screen."
  (:require [clojure.string :as str]))

(defn normalize
  "A base as this namespace uses it: `\"\"` for nothing, otherwise a leading
  slash and no trailing one.

  It arrives over an HTTP header, so it may be nil, blank, `\"/\"`, or written
  with a trailing slash by whoever configured the proxy. Normalising once here
  is what lets [[prefixed]] and [[strip]] be two lines each instead of two
  lines of logic wrapped in four of defensive string work."
  [base]
  (let [b (str/replace (str base) #"/+$" "")]
    (if (or (str/blank? b) (= "/" b)) "" b)))

(defn prefixed
  "`path` as the BROWSER should see it when this app is served under `base`.

  Used for every root-absolute url the server emits — assets, and the links
  the client pushes into history. With no base it is the identity, which is
  the default case and the one that must never regress."
  [base path]
  (str (normalize base) path))

(defn strip
  "`path` as the APP should see it, with `base` removed — the inverse of
  [[prefixed]], and what the client router parses.

  The match is on a SEGMENT boundary, not a bare prefix: `/p/slopp22/x` does
  not begin with the base `/p/slopp2` in any sense a router should honour, and
  a `starts-with?` would quietly hand one project's url to another's screen.

  A path outside the base comes back UNTOUCHED. It is not ours to rewrite, and
  a silent mangle here surfaces three layers away as a routing bug."
  [base path]
  (let [b (normalize base)
        p (str path)]
    (cond
      (= "" b)                    p
      (= p b)                     "/"
      (str/starts-with? p (str b "/")) (subs p (count b))
      :else                       p)))
