(ns slopp.edit-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.render :as render]
            [slopp.repl :as repl]
            [slopp.image :as image]
            [slopp.edit :as edit]))

(def src "(ns demo)\n(defn add [x y]\n  (+ x y))\n(def z 1)\n")

(defn- ingest [] (store/ingest (store/empty-store) 'demo src))

^:unsafe (deftest ^:isolated strip-image-reload-removes-reload-only-inside-requires
  (testing ":reload / :reload-all are stripped from require/use forms"
    (is (not (re-find #":reload" (edit/strip-image-reload "(require 'foo :reload)"))))
    (is (not (re-find #":reload" (edit/strip-image-reload "(require '[a :as b] :reload-all)"))))
    (is (not (re-find #":reload" (edit/strip-image-reload "(use 'foo :reload)"))))
    (testing "the require itself survives (still evaluable)"
      (is (re-find #"require" (edit/strip-image-reload "(require 'foo :reload)")))
      (is (re-find #"foo" (edit/strip-image-reload "(require 'foo :reload)")))))
  (testing "nested inside a (do ...) is still reached"
    (is (not (re-find #":reload"
                      (edit/strip-image-reload "(do (require 'foo :reload) (foo/bar))")))))
  (testing "a :reload keyword OUTSIDE a require is preserved (no over-stripping)"
    (is (re-find #":reload" (edit/strip-image-reload "{:reload true}")))
    (is (re-find #":reload" (edit/strip-image-reload "(assoc m :reload 1)"))))
  (testing "code with nothing to strip is returned intact"
    (is (= [1 2] (read-string (str "[" (edit/strip-image-reload "1 2") "]"))))))

(deftest ^:isolated replace-form-happy-path
  (let [s (ingest)
        r (edit/replace-form s 'demo 'add "(defn add [x y] (* x y))"
                             :prompt "make it multiply")]
    (testing "no error; delta recorded with prompt (provenance)"
      (is (nil? (:error r)))
      (is (= :replace (:op (:delta r))))
      (is (= "make it multiply" (:prompt (:delta r)))))
    (testing "rendered source reflects the change; other forms untouched"
      (is (re-find #"\(\* x y\)" (render/render-ns (:store r) 'demo)))
      (is (re-find #"\(def z 1\)" (render/render-ns (:store r) 'demo))))
    (testing "form identity is stable across the edit (C2)"
      (is (= (:id (store/form-named s 'demo 'add))
             (:id (store/form-named (:store r) 'demo 'add)))))))

(deftest ^:isolated replace-form-rejects-non-dialect
  (let [s (ingest)]
    (testing "D4: user macros banned"
      (is (:error (edit/replace-form s 'demo 'add "(defmacro add [x] x)"))))
    (testing "D3: denylisted forms rejected"
      (is (:error (edit/replace-form s 'demo 'add "(defn add [x] (eval x))"))))
    (testing "must be exactly one top-level form"
      (is (:error (edit/replace-form s 'demo 'add "(defn add [x] x) (def oops 1)"))))
    (testing "unknown form name"
      (is (:error (edit/replace-form s 'demo 'nope "(defn nope [] 1)"))))))

(deftest ^:isolated replace-form-flags-effect-violation
  (let [s (ingest)
        r (edit/replace-form s 'demo 'add "(defn add [a] (swap! a inc))")]
    (testing "D6: effectful body under a non-! name -> warning + suggested fix"
      (is (nil? (:error r)))
      (is (some #(= 'demo/add (:var %)) (:warnings r)))
      (is (some #(= "add!" (:suggest %)) (:warnings r))))))

(deftest ^:isolated apply-replace-hot-reloads
  ;; red -> edit -> hot-reload -> green in the image + a provenance delta.
  ;; (Verification orchestration — affected tests, diagnostics — is api-level;
  ;; see slopp.verification-test.)
  (let [target (str "(ns demo2\n"
                    "  (:require [clojure.test :refer [deftest is]]))\n"
                    "(defn add [x y] (+ x y))\n"
                    "(deftest t (is (= 6 (add 2 3))))\n")  ; expects 6, add gives 5 -> red
        s (store/ingest (store/empty-store) 'demo2 target)
        h (repl/start!)]
    (try
      (image/load-ns! h s 'demo2)
      (testing "initially red (add 2 3 = 5, test expects 6)"
        (is (= 1 (:fail (image/test-run h 'demo2)))))
      (let [r (edit/apply-replace! {:store s :image h} 'demo2 'add
                                   "(defn add [x y] (+ x y 1))" :prompt "off-by-one")]
        (testing "the edit hot-reloads: image reflects the redefinition, tests green"
          (is (nil? (:error r)))
          (is (= [6] (repl/eval! h "(demo2/add 2 3)")))
          (is (= 0 (:fail (image/test-run h 'demo2)))))
        (testing "the :replace delta is recorded with its prompt"
          (let [d (last (store/deltas (:store (:system r))))]
            (is (= :replace (:op d)))
            (is (= "off-by-one" (:prompt d))))))
      (finally (repl/stop! h)))))
(deftest compile-errors-speak-anchors
  ;; agents can't consume file:line (reads are name-addressed, edits are
  ;; anchor-addressed) — the boundary translates VFS coordinates into the
  ;; owning FORM plus a match-ready snippet.
  (let [st (store/ingest (store/empty-store) 'an.err
                         (str "(ns an.err)\n"
                              "(defn ok \"O.\" [x] x)\n"
                              "(defn broken \"B.\" [x]\n"
                              "  (nope-not-a-fn x))\n"))]
    (testing "a located error resolves to form + snippet"
      (let [a (edit/anchor-error st "Syntax error compiling at (an/err.clj:4:3).\nUnable to resolve symbol: nope-not-a-fn")]
        (is (= 'an.err/broken (:form a)) (pr-str a))
        (is (= "(nope-not-a-fn x))" (:at a)))))
    (testing "unlocatable text stays nil — the caller keeps the raw message"
      (is (nil? (edit/anchor-error st "something exploded, no coordinates")))
      (is (nil? (edit/anchor-error st nil))))))
(deftest compile-error-anchors-or-falls-back
  (let [st (store/ingest (store/empty-store) 'ce.core
                         "(ns ce.core)\n(defn f \"F.\" [x] (boom x))\n")]
    (testing "resolvable coordinate → form + snippet, coordinate stripped"
      (let [r (edit/compile-error
               st "Syntax error compiling boom at (ce/core.clj:2:18).\nUnable to resolve symbol: boom"
               "form failed to compile: ")]
        (is (= 'ce.core/f (:form r)))
        (is (re-find #"boom" (:at r)))
        (is (re-find #"Unable to resolve" (:error r)) "message survives")
        (is (not (re-find #"\.clj:\d" (:error r))) "coordinate gone")
        (is (not (re-find #" at\b" (:error r))) "the dangling 'at' goes too")))
    (testing "no coordinate → raw fallback keeps the message"
      (let [r (edit/compile-error st "something broke, no location"
                                  "form failed to compile: ")]
        (is (nil? (:form r)))
        (is (= "form failed to compile: something broke, no location" (:error r)))))))
