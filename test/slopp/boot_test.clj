(ns slopp.boot-test
  "Cover for the KERNEL's boot path — the one layer with nothing behind it.

  Everywhere else in slopp a mistake is caught by the image, the suite, or a
  gate. `slopp.boot` is what brings those into existence: it reads a store's
  source out of sqlite and loads it into a bare JVM. A bug here does not fail
  a test, it fails to start.

  So the tests aim at the decisions rather than the plumbing — dependency
  order, which namespaces are JVM-loadable, what a reload leaves behind. Those
  are cheap, pure, and each one has an expensive failure mode: the wrong order
  is an unbootable store, and the wrong reload is a server answering from code
  that no longer exists.

  One test reaches for a real database, and the reason is worth stating: the
  module gate REFUSES to let this namespace require `slopp.store`, and it is
  right to — the kernel's whole property is that it boots a store with no
  slopp code loaded. So `store-sources` cannot be compared against `render-ns`
  by calling it. It is pinned against the same literal instead, and the rule
  it encodes is written out where both can be read together.

  `slopp.boot` also exists as a hand-maintained FILE, and both copies are
  live. `slopp.store.kernel` is what keeps them honest; this covers what they
  do."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.boot :as boot]
            [next.jdbc :as jdbc]))

(deftest dependency-order-is-deps-first
  (let [sources {'app.a "(ns app.a)\n(defn f [] 1)\n"
                 'app.b "(ns app.b\n  (:require [app.a :as a]))\n(defn g [] (a/f))\n"
                 'app.c (str "(ns app.c\n  (:require [app.b :as b]\n"
                             "            [clojure.string :as s]))\n(defn h [] (b/g))\n")}
        order   (boot/dependency-order sources)]
    (testing "every internal namespace is present; external requires are ignored"
      (is (= #{'app.a 'app.b 'app.c} (set order))))
    (testing "dependencies come before their dependents"
      (is (< (.indexOf ^java.util.List order 'app.a)
             (.indexOf ^java.util.List order 'app.b)))
      (is (< (.indexOf ^java.util.List order 'app.b)
             (.indexOf ^java.util.List order 'app.c))))))

(deftest dependency-order-is-deterministic-and-cycle-safe
  (testing "ties break by sorted name (deterministic)"
    (is (= '[app.a app.b app.c]
           (boot/dependency-order {'app.c "(ns app.c)" 'app.a "(ns app.a)"
                                   'app.b "(ns app.b)"}))))
  (testing "a require cycle doesn't hang — the remainder is appended"
    (is (= #{'x 'y}
           (set (boot/dependency-order {'x "(ns x (:require [y :as y]))"
                                        'y "(ns y (:require [x :as x]))"}))))))

(deftest parse-args-trampolines-main-args
  (testing "default: mcp main, dir as the only arg"
    (is (= {:dir "." :live? false :main 'slopp.mcp/-main :args ["."]}
           (boot/parse-args ["." "--snapshot"]))))
  (testing "--main with NO extra args keeps the dir-arg convention"
    (is (= {:dir "/p" :live? true :main 'app.core/-main :args ["/p"]}
           (boot/parse-args ["/p" "--live" "--main" "app.core/-main"]))))
  (testing "--main passes everything after the symbol through verbatim"
    (is (= {:dir "." :live? false :main 'slopp.sync/-main
            :args ["push" "." "https://x/y.git"]}
           (boot/parse-args ["." "--main" "slopp.sync/-main"
                             "push" "." "https://x/y.git"]))))
  (testing "--call is sugar for --main slopp.mcp/call-main! dir tool [args]"
    (is (= {:dir "." :live? false :main 'slopp.mcp/call-main!
            :args ["." "query_project"]}
           (boot/parse-args ["." "--call" "query_project"])))
    (is (= {:dir "/p" :live? false :main 'slopp.mcp/call-main!
            :args ["/p" "edit_replace_form" "@/tmp/a.json"]}
           (boot/parse-args ["/p" "--call" "edit_replace_form" "@/tmp/a.json"])))))

(deftest jvm-loadable-skips-cljs-namespaces
  ;; F5: the kernel boot path must NEVER JVM-load a :cljs namespace — it
  ;; references js/* and its libs are not on the boot classpath, so loading it
  ;; makes any store carrying client code unbootable (java -jar slopp.jar <dir>,
  ;; --call, --main, serving). Most-specific declared path wins, mirroring
  ;; slopp.store/platform-for.
  (let [pf {"app.client" :cljs, "app.client.shared" :cljc, "app.core" :jvm}]
    (is (false? (boot/jvm-loadable? pf 'app.client.view))
        "a namespace under a :cljs module inherits :cljs")
    (is (true? (boot/jvm-loadable? pf 'app.client.shared))
        "a more specific :cljc declaration wins over the :cljs module")
    (is (true? (boot/jvm-loadable? pf 'app.core)))
    (is (true? (boot/jvm-loadable? pf 'other.thing))
        "undeclared defaults to :jvm")
    (is (true? (boot/jvm-loadable? {} 'anything))
        "no register at all — everything loads, as before the client wave")))

(deftest a-reload-must-drop-the-vars-the-new-source-no-longer-defines
  ;; `load-string` of a namespace's new source re-defines every form it
  ;; contains and says nothing about the ones it does not. So a DELETE
  ;; propagates as "still there" into every --live host: the store is correct,
  ;; the suite is green, and the running server keeps answering from a var
  ;; whose definition no longer exists.
  ;;
  ;; Cost, when it happened: six deleted page endpoints kept serving, and
  ;; because a stale route SHADOWS the SPA fallback, the symptom pointed at
  ;; the feature just written rather than at the reload. The worst kind of
  ;; misleading evidence.
  ;;
  ;; This is the decision half — which names departed — kept pure so it can be
  ;; tested here instead of against a running daemon.
  (testing "a deleted form is departed; a changed one and a new one are not"
    (is (= '#{gone}
           (#'boot/departed-vars '#{keep changed gone}
                                 "(ns a) (defn keep [] 1) (defn changed [] 99) (defn fresh [] 2)"))))
  (testing "every shape a namespace defines counts, or the check deletes live code"
    ;; a name it fails to see reads as departed, and unmapping a var that IS
    ;; defined is strictly worse than leaving a stale one
    (is (= #{} (#'boot/departed-vars
                '#{f g h k m}
                (str "(ns a)\n"
                     "^:unsafe (defn f [] 1)\n"        ; metadata-wrapped
                     "(defn- g [] 2)\n"                ; private
                     "(def h 3)\n"
                     "(defmulti k :x)\n"
                     "(defmacro m [] nil)\n")))))
  (testing "unreadable source departs NOTHING — a stale var beats a deleted one"
    ;; the conservative direction: if the new source cannot be read, keep
    ;; today's behaviour rather than guessing
    (is (= #{} (#'boot/departed-vars '#{f} "(ns a) (defn f [] (this is not"))))
  (testing "and it never reports a name the namespace did not have"
    (is (= #{} (#'boot/departed-vars #{} "(ns a) (defn f [] 1)")))))

^:unsafe (deftest ^:external reloading-a-namespace-drops-what-the-new-source-deleted
  ;; `departed-vars` decides; this is the seam that ACTS on it. A rule
  ;; implemented correctly and called wrongly is Pattern 2 in the failure log,
  ;; four instances, and the tell is always that each side looks right alone.
  ;;
  ;; External because it interns real vars in a real namespace: it is the
  ;; reload, not a model of one. ^:unsafe because asking "is this var still
  ;; there?" IS the assertion, so it must resolve by name — the carrier forms
  ;; the dialect prefers all capture the var and would answer about a binding
  ;; that no longer exists.
  (let [nsx  'slopp.boot-reload-probe
        var! (fn [n] (ns-resolve nsx (symbol n)))]
    (try
      (#'boot/reload-ns! nsx "(ns slopp.boot-reload-probe)
                              (defn keeper [] 1)
                              (defn doomed [] 2)")
      (is (some? (var! "keeper")))
      (is (some? (var! "doomed")) "both live after the first load")
      (testing "a reload without the second form leaves NOTHING behind"
        (#'boot/reload-ns! nsx "(ns slopp.boot-reload-probe)
                                (defn keeper [] 99)")
        (is (nil? (var! "doomed"))
            "the deleted form must stop answering — this is the whole bug")
        (is (= 99 ((var! "keeper")))
            "and the surviving form is the NEW definition, not a casualty"))
      (testing "a reload that throws leaves the namespace alone"
        ;; the conservative direction: a stale var beats a gutted namespace
        (is (thrown? Throwable
                     (#'boot/reload-ns!
                      nsx "(ns slopp.boot-reload-probe) (defn x [] (no-such-thing))")))
        (is (some? (var! "keeper"))
            "a failed reload must not take the working code with it"))
      (finally (remove-ns nsx)))))

(deftest the-kernel-synthesizes-the-space-between-forms
  ;; `store-sources` used to CONCATENATE element rows, which was byte-exact
  ;; for exactly as long as the rows carried the whitespace. Once rendering
  ;; started supplying it, concatenation became a second, wrong answer — forms
  ;; jammed together and every comment dropped — and the kernel is where a
  ;; wrong answer costs the most: a store whose boot source is malformed does
  ;; not fail a test, it fails to start.
  ;;
  ;; The rule it must reproduce, which `slopp.store.render/render-ns` also
  ;; implements and this namespace is forbidden to call:
  ;;
  ;;   forms joined by ONE BLANK LINE, a form's comment directly above it,
  ;;   one trailing newline, `sep` rows ignored entirely.
  ;;
  ;; The sep rows in this fixture are the point: a store mid-migration still
  ;; has them, and a kernel that reads them produces different source than the
  ;; server it is booting.
  (let [dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-boot-render"
                   (make-array java.nio.file.attribute.FileAttribute 0)))
        _    (.mkdirs (java.io.File. (str dir "/.slopp")))
        conn (jdbc/get-connection
              (jdbc/get-datasource
               {:dbtype "sqlite" :dbname (str dir "/.slopp/store.db")}))
        row! (fn [pos kind form-id nm src cmt]
               (jdbc/execute! conn ["INSERT INTO elements
                                     (ns,pos,kind,form_id,name,source,comment)
                                     VALUES ('bk.core',?,?,?,?,?,?)"
                                    pos kind form-id nm src cmt]))]
    (try
      (jdbc/execute! conn ["CREATE TABLE elements (ns TEXT, pos INTEGER,
                            kind TEXT, form_id TEXT, name TEXT, source TEXT,
                            comment TEXT)"])
      (row! 0 "form" "f1" "bk.core" "(ns bk.core)" nil)
      (row! 1 "sep" nil nil "\n" nil)
      (row! 2 "form" "f2" "f" "(defn f [] 1)" ";; why this exists")
      (row! 3 "sep" nil nil "\n" nil)
      (row! 4 "form" "f3" "g" "(defn g [] (f))" nil)
      (testing "one blank line between forms, the comment above its own form"
        (is (= {'bk.core (str "(ns bk.core)\n\n"
                              ";; why this exists\n(defn f [] 1)\n\n"
                              "(defn g [] (f))\n")}
               (boot/store-sources conn))))
      (finally (.close conn)))))

(deftest ^:external manifest-deps-resolve-off-a-repl-thread
  ;; MEASURED on slopp's own jar: `java -jar slopp.jar <dir>` logged "could
  ;; not add 13 of 13 manifest deps", every one of them failing with "Can't
  ;; change/establish root binding of *data-readers* with set" — and the
  ;; store booted and ran anyway, off whatever the HOST uberjar happened to
  ;; carry, at whatever version it carried. That is precisely the failure
  ;; add-manifest-libs!'s own docstring exists to prevent: the manifest
  ;; becomes decoration, and an app asking whether it depends only on what it
  ;; DECLARES gets told yes when the answer is no.
  ;;
  ;; The cause is a THREAD binding, not a bad coord. `add-libs` refreshes the
  ;; data-reader table with `set!`, which needs *data-readers* thread-bound;
  ;; clojure.main establishes one, an AOT `java -jar` main does not. A fresh
  ;; Thread has those bindings stripped the same way, so it reproduces the
  ;; boot thread's world without spawning a JVM — and a coord already IN the
  ;; manifest keeps the check off the network.
  (let [p (promise)]
    (.start (Thread. #(deliver p (try (#'boot/add-libs!*
                                       '{rewrite-clj/rewrite-clj {:mvn/version "1.1.48"}})
                                      :ok
                                      (catch Throwable t (str (.getMessage t)))))))
    (is (= :ok (deref p 120000 :timed-out))
        "a manifest coord must resolve on a thread carrying no REPL bindings")))

(deftest host-staleness-is-a-comparison-not-a-counter
  ;; The host half of the currency work. What this process HOLDS is recorded
  ;; at each successful load; what the store SAYS is `store-sources`. Both
  ;; sides are kernel-rendered, and that is the whole point — this namespace's
  ;; own docstring records that `store-sources` cannot be compared against
  ;; `render-ns`, because the kernel has to render with no slopp code loaded.
  ;; Comparing across the two renderings would report every namespace stale
  ;; the first time they differed by a space, which is the proxy-instead-of-
  ;; measurement failure the whole currency thread exists to end.
  ;;
  ;; `now` arrives already filtered to what a JVM may load: a :cljs namespace
  ;; is never loaded here by design, so counting it as stale would be a
  ;; permanent false positive.
  (let [a-src  "(ns a.core)\n(def x 1)\n"
        b-src  "(ns b.core)\n"
        now    {'a.core a-src 'b.core b-src}
        loaded {'a.core (hash a-src) 'b.core (hash b-src)}]
    (is (= [] (boot/host-stale-of loaded now))
        "a process holding exactly what the store says is current")
    (testing "a namespace whose source moved on is named"
      (is (= '[a.core]
             (boot/host-stale-of (assoc loaded 'a.core (hash "(ns a.core)\n")) now))))
    (testing "a namespace this process never loaded is stale, not absent"
      (is (= '[b.core] (boot/host-stale-of (dissoc loaded 'b.core) now))))
    (testing "a namespace the store no longer has is not this measure's business"
      (is (= [] (boot/host-stale-of (assoc loaded 'gone.ns 123) now))))
    (testing "several are reported in a stable order, not just the first"
      (is (= '[a.core b.core] (boot/host-stale-of {} now))))))

(deftest host-currency-separates-not-measured-from-measured-clean
  ;; nil and [] are different claims and only one is a promise. The watcher's
  ;; `:failed` map could say "stale" forever (friction 20a: a rename left it
  ;; retrying a namespace that no longer existed), because a failure record is
  ;; a memory of an event rather than a statement about the present. A
  ;; comparison cannot outlive its condition: re-measure and it clears.
  ;;
  ;; The atom is process-global, so this saves and restores it — a test that
  ;; left it mutated would corrupt the very record it is checking.
  (let [saved @boot/host-loaded]
    (try
      (reset! boot/host-loaded {:armed? false :nses {} :stale nil})
      (is (nil? (boot/host-drift))
          "nobody looked, which is not the same as looked and found nothing")
      (#'boot/record-loaded! 'a.core "(ns a.core)\n")
      (is (nil? (boot/host-drift))
          "recording a load is not measuring — arming is the comparison's job")
      (#'boot/measure-host! '{a.core "(ns a.core)\n"})
      (is (= [] (boot/host-drift)) "measured, and this process is current")
      (testing "the store moving on shows up at the next measure"
        (#'boot/measure-host! '{a.core "(ns a.core)\n(def x 1)\n"})
        (is (= '[a.core] (boot/host-drift))))
      (testing "and a successful reload CLEARS it"
        (#'boot/record-loaded! 'a.core "(ns a.core)\n(def x 1)\n")
        (#'boot/measure-host! '{a.core "(ns a.core)\n(def x 1)\n"})
        (is (= [] (boot/host-drift))
            "a comparison reports the present; it cannot get stuck like a failure record"))
      (finally (reset! boot/host-loaded saved)))))
