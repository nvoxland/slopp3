(ns slopp.unsafe-test
  "External deps M2: the `^:unsafe` per-form dialect opt-out — a greppable,
  human-discharged escape from the D3/D4 ban (the Rust-`unsafe` proof
  obligation). Needed for self-host: the ~12 of slopp's own forms that use
  binding/alter-var-root/read-string are otherwise un-editable."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [slopp.api :as api]
            [slopp.edit :as edit]
            [slopp.store :as store] [slopp.api.query :as query] [slopp.api.external :as external]))

;; ---------------------------------------------------------------------------
;; form-symbol must see through ^:unsafe (else the form is un-addressable)

(deftest form-symbol-unwraps-meta
  (testing "a plain def is named"
    (is (= 'f (store/name-of-source "(defn f [x] x)"))))
  (testing "an ^:unsafe def keeps its name (the load-bearing unwrap)"
    (is (= 'g (store/name-of-source
               "^:unsafe (defn g [x] (binding [*out* *out*] x))")))))

;; ---------------------------------------------------------------------------
;; the dialect gate opt-out

(deftest unsafe-bypasses-dialect-bans
  (testing "a plain banned form is rejected (D3)"
    (is (:error (edit/parse-form "(defn h [x] (binding [*out* *out*] x))"))))
  (testing "^:unsafe bypasses D3 (denylisted symbol)"
    (is (nil? (:error (edit/parse-form
                       "^:unsafe (defn h [x] (binding [*out* *out*] x))")))))
  (testing "^:unsafe bypasses D4 (user macro)"
    (is (nil? (:error (edit/parse-form "^:unsafe (defmacro m [x] x)")))))
  (testing "map metadata form also works"
    (is (nil? (:error (edit/parse-form
                       "^{:unsafe true} (defn h [] (read-string \"1\"))")))))
  (testing "a NON-unsafe banned form still rejects"
    (is (:error (edit/parse-form "(defn h [] (eval '(+ 1 1)))")))))

(deftest reads-marker-is-orthogonal-to-the-dialect-gate
  ;; ^:reads suppresses the !-effect naming warning (a read takes no bang);
  ;; it is NOT ^:unsafe and does NOT relax the D3/D4 dialect ban.
  (testing "edit/reads? detects the ^:reads marker"
    (is (edit/reads? (:node (edit/parse-form "^:reads (defn f [c] (q c))"))))
    (is (not (edit/reads? (:node (edit/parse-form "(defn f [c] (q c))"))))))
  (testing "^:reads does NOT bypass the dialect ban (only ^:unsafe does)"
    (is (:error (edit/parse-form "^:reads (defn f [a] (binding [*out* *out*] a))"))))
  (testing "^:reads composes with ^:unsafe when a form is both"
    (let [n (:node (edit/parse-form
                    "^:unsafe ^:reads (defn f [a] (binding [*out* *out*] @a))"))]
      (is (edit/reads? n))
      (is (edit/unsafe? n)))))

;; ---------------------------------------------------------------------------
;; end-to-end: an ^:unsafe form loads, is addressable, and round-trips

(deftest ^:external unsafe-form-ingests-loads-and-is-addressable
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'us.core
                   (str "(ns us.core)\n\n"
                        "(def ^:dynamic *tap* nil)\n\n"
                        "^:unsafe\n(defn with-tap [f] (binding [*tap* f] (f 1)))\n"))
      (testing "the ^:unsafe form loaded into the image and works"
        (is (= [2] (api/query-eval sess "(us.core/with-tap inc)"))))
      (testing "it is addressable by name (form-symbol saw through :meta)"
        (is (str/includes? (query/query-source sess 'us.core) "with-tap"))
        (let [r (api/edit-replace! sess 'us.core 'with-tap
                                   "^:unsafe (defn with-tap [f] (binding [*tap* f] (f 10)))"
                                   :prompt "bump" :agent "a")]
          (is (nil? (:error r)) (pr-str r))
          (is (= [11] (api/query-eval sess "(us.core/with-tap inc)")))))
      (testing "the ^:unsafe marker round-trips through render"
        (is (str/includes? (query/query-source sess 'us.core) "^:unsafe")))
      (testing "query_symbol surfaces :unsafe? (greppable/visible)"
        (is (:unsafe? (query/query-symbol sess 'us.core 'with-tap)))
        (is (not (:unsafe? (query/query-symbol sess 'us.core '*tap*)))))
      (finally (api/close! sess)))))

(deftest ^:external unsafe-survives-done-normalize
  ;; done runs slopp.normalize over changed forms — the ^:unsafe marker
  ;; (and the form's addressability) must survive that node-level rewrite
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'us.chk
                   (str "(ns us.chk)\n\n"
                        "^:unsafe\n(defn q [x] (binding [*out* *out*] (inc x)))\n"))
      (external/done! sess :label "cp" :agent "a")
      (is (str/includes? (query/query-source sess 'us.chk) "^:unsafe"))
      (is (:unsafe? (query/query-symbol sess 'us.chk 'q)))
      (is (= [2] (api/query-eval sess "(us.chk/q 1)")))
      (finally (api/close! sess)))))

(deftest ^:external import-path-gates-dialect-like-edit
  ;; the import path (ingest! / ns_create {:source}) must run the SAME dialect
  ;; gate the edit path does. Otherwise host forms enter the store UNMARKED and
  ;; become frozen — the edit path later refuses to modify them (their own body
  ;; contains a denylisted symbol) — and import silently swallows the warnings
  ;; the edit path surfaces. (Self-host dogfooding, 2026-07.)
  (let [sess (api/open!)]
    (try
      (testing "an un-^:unsafe host form is REJECTED on import (nothing commits)"
        (let [r (api/ingest! sess 'ig.bad
                             "(ns ig.bad)\n(defn f [a] (alter-var-root a (constantly 1)))\n")]
          (is (:error r))
          (is (re-find #"unsafe" (str (:error r))))
          (is (nil? (get-in (:store @sess) [:namespaces 'ig.bad]))
              "the rejected namespace must not have committed")))
      (testing "ALL offending forms are reported at once, not just the first"
        ;; else a whole-ns import must be re-sent once per host form, discovering
        ;; them one rejection at a time (brutal for a big namespace)
        (let [r (api/ingest! sess 'ig.many
                             (str "(ns ig.many)\n"
                                  "(defn a [x] (alter-var-root x (constantly 1)))\n"
                                  "(defn b [y] (binding [*out* *out*] y))\n"
                                  "(defn c [z] (read-string z))\n"))
              e (str (:error r))]
          (is (:error r))
          (doseq [nm ["a" "b" "c"]]
            (is (re-find (re-pattern (str "\\b" nm "\\b")) e)
                (str "expected form " nm " named in: " e)))
          (doseq [sym ["alter-var-root" "binding" "read-string"]]
            (is (re-find (re-pattern sym) e)
                (str "expected symbol " sym " named in: " e)))
          (is (nil? (get-in (:store @sess) [:namespaces 'ig.many]))
              "the rejected namespace must not have committed")))
      (testing "the same form marked ^:unsafe imports cleanly (never frozen)"
        (let [r (api/ingest! sess 'ig.ok
                             "(ns ig.ok)\n^:unsafe\n(defn f [a] (alter-var-root a (constantly 1)))\n")]
          (is (nil? (:error r)) (pr-str r))
          (is (= 2 (:forms r)))))
      (testing "import now RETURNS the !-warnings it used to swallow"
        (let [r (api/ingest! sess 'ig.warn
                             "(ns ig.warn)\n(def s (atom 0))\n(defn bump [] (swap! s inc))\n")]
          (is (contains? r :warnings))
          (is (some #(re-find #"bump" (str %)) (:warnings r))
              (str "expected a !-naming warning for the swap!-ing fn: "
                   (pr-str (:warnings r))))))
      (finally (api/close! sess)))))

(deftest ^:external unsafe-does-not-relax-effect-warnings
  ;; ^:unsafe opts out of the DIALECT ban only — honest !-labeling is orthogonal
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'us.eff
                   (str "(ns us.eff)\n\n(def s (atom 0))\n\n"
                        "^:unsafe\n(defn bump [] (binding [*out* *out*] (reset! s 1)))\n"))
      (let [ws (edit/ns-warnings (:store @sess) 'us.eff)]
        (is (some #(re-find #"bump" (str %)) ws)
            (str "expected a !-naming warning for the reset!-ing fn: "
                 (pr-str ws))))
      (finally (api/close! sess)))))
(deftest ^:external resolvers-are-denylisted-outside-carriers
  ;; the enforcement point of the reference-carrier decision: a string or
  ;; quoted symbol is INERT unless something resolves it — so the gate
  ;; blocks the resolvers, not the mentions. Docstrings may name vars;
  ;; tests may hold quoted symbols; nothing un-carried may BECOME a var.
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'rz.core "(ns rz.core)\n\n(defn f \"F.\" [x] x)\n")
      (testing "naked requiring-resolve is refused with carrier teaching"
        (let [r (api/add-form! sess 'rz.core
                               (str "(defn sneak \"S.\" [q]\n"
                                    "  ((requiring-resolve q) 1))\n"))]
          (is (re-find #"late-ref" (str (:error r))) (pr-str r))))
      (testing "resolve and ns-resolve too"
        (is (:error (api/add-form! sess 'rz.core
                                   "(defn peek* \"P.\" [q] (resolve q))")))
        (is (:error (api/add-form! sess 'rz.core
                                   "(defn peek2 \"P.\" [q] (ns-resolve 'rz.core q))"))))
      (testing "^:unsafe is the marked escape — the author takes the obligation"
        (let [r (api/add-form! sess 'rz.core
                               (str "^:unsafe (defn bridge \"B.\" [q]\n"
                                    "  ((requiring-resolve q) 1))\n"))]
          (is (nil? (:error r)) (pr-str r))))
      (finally (api/close! sess)))))
