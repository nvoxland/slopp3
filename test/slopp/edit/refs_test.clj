(ns slopp.edit.refs-test
  "THE reference graph: every kind of reference — kondo-static, carrier
  positions, declarations — as ONE canonical record stream all tools
  consume. Producers normalize here; consumers never re-integrate."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.edit.refs :as refs]
            [slopp.store :as store]))
(deftest the-graph-sees-every-reference-kind
  (let [st (-> (store/empty-store)
               (store/ingest 'g.core
                             (str "(ns g.core)\n\n"
                                  "(defn ^:entry-point serve \"S.\" [x] x)\n\n"
                                  "(defn helper \"H.\" [x] x)\n\n"
                                  "(defn ^:unused-ok spare \"P.\" [x] x)\n"))
               (store/ingest 'g.app
                             (str "(ns g.app (:require [g.core :as core]))\n\n"
                                  "(defn go \"G.\" [x] (core/helper x))\n\n"
                                  "(defn drive \"D.\" [sess]\n"
                                  "  (query-call sess 'g.core/helper 2))\n"))
               (store/ingest 'g.sneak
                             (str "(ns g.sneak)\n\n"
                                  "(defn s \"S.\" [x] (g.core/helper x))\n")))]
    (testing "static (required AND un-required qualified), carrier, declared"
      (let [rs (refs/refs-to st 'g.core/helper)
            by (fn [nsx] (set (map :via (filter #(= nsx (:from-ns %)) rs))))]
        (is (= #{:static :carrier} (by 'g.app)) (pr-str rs))
        (is (= #{:static} (by 'g.sneak)) "the gate-hole class is a static ref")))
    (testing "declarations are edges from the outside world"
      (let [r (first (refs/refs-to st 'g.core/serve))]
        (is (= :declared (:via r)))
        (is (= :entry-point (:marker r)))
        (is (= :external (:from-ns r))))
      (is (= :unused-ok (:marker (first (refs/refs-to st 'g.core/spare))))))
    (testing "records anchor to the owning FORM, not positions"
      (let [r (first (filter #(and (= :static (:via %)) (= 'g.app (:from-ns %)))
                             (refs/refs-to st 'g.core/helper)))]
        (is (= 'go (:name (store/form-by-id st (:from-form r)))))))
    (testing "self-references are excluded; unknown targets empty"
      (is (empty? (refs/refs-to st 'g.core/nope))))))
(deftest the-wire-codec-slims-references
  ;; canonical records are INTERNAL; the wire always carries the compact
  ;; form — grouped by target, self-describing qsyms, via-tagged only when
  ;; not the common :static. Names are the ONLY reference currency on the
  ;; wire (opaque ids fail unsafe: a mistyped id can silently resolve to a
  ;; real other reference; a mistyped name fails loudly).
  (let [st (-> (store/empty-store)
               (store/ingest 'w.core
                             (str "(ns w.core)\n\n"
                                  "(defn ^:entry-point helper \"H.\" [x] x)\n"))
               (store/ingest 'w.app
                             (str "(ns w.app (:require [w.core :as core]))\n\n"
                                  "(defn go \"G.\" [x] (core/helper x))\n\n"
                                  "(defn drive \"D.\" [sess]\n"
                                  "  (query-call sess 'w.core/helper 2))\n")))
        rs (refs/refs-to st 'w.core/helper)
        w  (refs/to-wire rs)]
    (testing "grouped and strictly slimmer"
      (is (= 'w.core/helper (:to w)))
      (is (= '[w.app/drive w.app/go]
             (vec (sort (concat (:from w) (keep :from (:tagged w))))))
          (pr-str w))
      (is (< (count (pr-str w)) (count (pr-str rs)))
          (str (count (pr-str w)) " vs " (count (pr-str rs)))))
    (testing "non-static references carry their tag; declarations show the dial"
      (is (some #(and (= 'w.app/drive (:from %)) (= :carrier (:via %)))
                (:tagged w)))
      (is (some #(= :entry-point (:marker %)) (:tagged w))))
    (testing "records anchor BOTH ends to forms (rewriters and post-conditions)"
      (let [r (first (filter #(= :static (:via %)) rs))]
        (is (:from-form r))
        (is (= 'helper (:name (store/form-by-id st (:to-form r)))))))))
(deftest self-references-never-count-any-producer
  ;; a form referencing ITSELF — statically OR through a carrier — is not
  ;; a reference (replacing the form covers it). The carrier producer
  ;; lacked this exclusion, so a form could keep ITSELF alive through a
  ;; carrier self-ref (eval-reproduced); drop-self is the uniform fix.
  (let [st (-> (store/empty-store)
               (store/ingest 'sr.core
                             (str "(ns sr.core)\n\n"
                                  "(defn loops \"L.\" [s] (query-call s 'sr.core/loops 1))\n\n"
                                  "(defn calls-self \"C.\" [x] (sr.core/calls-self x))\n\n"
                                  "(defn dead \"D.\" [x] x)\n")))]
    (is (empty? (refs/refs-to st 'sr.core/loops)) "carrier self-ref excluded")
    (is (empty? (refs/refs-to st 'sr.core/calls-self)) "qualified self-ref excluded")
    ;; a genuine CROSS-form reference still lands (exclusion is self-only)
    (let [st2 (store/ingest st 'sr.user
                            "(ns sr.user (:require [sr.core :as c]))\n(defn u [x] (c/dead x))\n")]
      (is (seq (refs/refs-to st2 'sr.core/dead))))))
(deftest walk-pruned-is-the-one-quote-aware-traversal
  (let [collect (fn [x] (vec (refs/walk-pruned
                              (fn [n] (when (and (symbol? n) (namespace n)) [n]))
                              x)))]
    (testing "yields qualified symbols, prunes quoted subtrees"
      (is (= '[a.b/c d.e/f]
             (collect '(defn g [] (a.b/c (quote x.y/z)) (when true d.e/f)))))
      (is (= '[]  (collect '(quote (a.b/c d.e/f)))))
      (is (= '[m.n/k m.n/v] (collect '{m.n/k m.n/v}))
          "maps: keys and vals both walked"))
    (testing "callers see SEQ nodes too (carrier-position extraction)"
      (is (= '[(query-call s (quote a.b/f))]
             (vec (refs/walk-pruned
                   (fn [n] (when (and (seq? n) (= 'query-call (first n))) [n]))
                   '(defn g [s] (query-call s (quote a.b/f))))))))
    (testing "and :tag hints on nodes"
      (is (= '[Foo]
             (vec (refs/walk-pruned
                   (fn [n] (when-let [t (:tag (meta n))] [t]))
                   '(defn g [^Foo x] x))))))))
(deftest refs-memoizes-on-the-immutable-store-value
  ;; refs is O(store) — repeatedly rebuilding the whole graph to answer
  ;; refs-to / unused / review on the SAME store value is waste. Memoize
  ;; on value identity (immutable store → a new value only on a write):
  ;; same value returns the identical vector; a changed store rebuilds.
  (let [st  (store/ingest (store/empty-store) 'mm.core
                          "(ns mm.core (:require [clojure.string :as s]))\n(defn f [x] (s/trim x))\n")
        a   (refs/refs st)
        b   (refs/refs st)]
    (is (identical? a b) "same store value → cached, not rebuilt")
    (let [st2 (store/ingest st 'mm.two
                            "(ns mm.two (:require [mm.core :as c]))\n(defn g [x] (c/f x))\n")
          c   (refs/refs st2)]
      (is (not (identical? a c)) "a changed store rebuilds")
      (is (some #(= 'mm.core (:to-ns %)) c) "and reflects the new reference"))))
