(ns slopp.kernel.boot-test
  "Cover for the KERNEL's boot path — the one layer with nothing behind it.

  Everywhere else in slopp a mistake is caught by the image, the suite, or a
  gate. `slopp.kernel.boot` is what brings those into existence: it reads a store's
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

  `slopp.kernel.boot` also exists as a hand-maintained FILE, and both copies are
  live. `slopp.kernel.parity` is what keeps them honest; this covers what they
  do."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.kernel.boot :as boot]
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
  ;;
  ;; The probe namespace is FICTIONAL and shares no segment with the namespace
  ;; under test. It used to be `slopp.kernel.boot-reload-probe`, which put the string
  ;; `slopp.kernel.boot` inside three source STRINGS and one quoted symbol — and a
  ;; prose sweep of `slopp.kernel.boot` matches the strings (a `-` bounds a word)
  ;; while leaving the symbol alone, which half-rewrites the fixture into one
  ;; that loads a namespace under one name and looks it up under another.
  (let [nsx  'probe.reload-target
        var! (fn [n] (ns-resolve nsx (symbol n)))]
    (try
      (#'boot/reload-ns! nsx "(ns probe.reload-target)
                              (defn keeper [] 1)
                              (defn doomed [] 2)")
      (is (some? (var! "keeper")))
      (is (some? (var! "doomed")) "both live after the first load")
      (testing "a reload without the second form leaves NOTHING behind"
        (#'boot/reload-ns! nsx "(ns probe.reload-target)
                                (defn keeper [] 99)")
        (is (nil? (var! "doomed"))
            "the deleted form must stop answering — this is the whole bug")
        (is (= 99 ((var! "keeper")))
            "and the surviving form is the NEW definition, not a casualty"))
      (testing "a reload that throws leaves the namespace alone"
        ;; the conservative direction: a stale var beats a gutted namespace
        (is (thrown? Throwable
                     (#'boot/reload-ns!
                      nsx "(ns probe.reload-target) (defn x [] (no-such-thing))")))
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
    (.start (Thread. #(deliver p (try (#'boot/add-libs-here!
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

(deftest ^:external manifest-resolution-gets-a-repository-set
  ;; `add-libs` builds its Maven procurer from the current BASIS's namespaced
  ;; keys, and a `java -jar` process has no basis at all — so :mvn/repos is
  ;; empty, and Maven will neither download an artifact nor TRUST one ~/.m2
  ;; already holds: a cached POM records the repository it came from
  ;; (`jackson-base-2.17.0.pom>central=`), and one it cannot attribute to a
  ;; CONFIGURED repo is reported as "Could not find artifact".
  ;;
  ;; MEASURED: cheshire 5.13.0 failed with "Could not find artifact
  ;; com.fasterxml.jackson:jackson-base:pom:2.17.0" and resolved :ok
  ;; immediately after seeding repos into the basis — same JVM, same ~/.m2,
  ;; nothing else changed. `clojure -Sdeps … -Spath` had always resolved it,
  ;; which is what kept this looking environmental.
  ;;
  ;; The result is reported rather than read back out of the basis, because
  ;; reaching the basis needs `requiring-resolve` and the dialect denylists it
  ;; outside the kernel — the kernel is what may touch this, so the kernel is
  ;; what answers for it.
  (let [r (#'boot/ensure-repos!)]
    (is (seq (:repos r)) "the resolver has somewhere to look")
    (is (#{:seeded :kept} (:action r)) (pr-str r))
    (testing "a second call KEEPS what is already configured"
      ;; a CLI-started process, or one pointed at a private mirror, has already
      ;; been told where to look; overriding that would break the very case the
      ;; default is only guessing at
      (let [r2 (#'boot/ensure-repos!)]
        (is (= :kept (:action r2)) (pr-str r2))
        (is (= (:repos r) (:repos r2)))))))

(deftest the-host-jar-declares-what-it-already-provides
  ;; A `java -jar slopp.jar` process has NO basis, so `add-libs` sees an empty
  ;; `:libs` and believes nothing is present. It then "adds" coords the host
  ;; uberjar already carries — and loses, because it appends to a classloader
  ;; that delegates to its PARENT first and the uberjar is the parent. The
  ;; manifest read as satisfied at a version that was never in force.
  ;;
  ;; Seeding the basis with what the jar bundles fixes that at the source:
  ;; `add-libs` filters a lib already in `:libs` by SYMBOL, ignoring version,
  ;; so a bundled lib is skipped outright instead of falsely added.
  (testing "seeds only into a basis that has no libs of its own"
    (is (= '{metosin/malli {:mvn/version "0.17.0"}}
           (#'boot/basis-libs-to-seed nil '{metosin/malli {:mvn/version "0.17.0"}}))
        "nothing there yet — seed it")
    (is (nil? (#'boot/basis-libs-to-seed
               '{org.clojure/clojure {:mvn/version "1.12.5"}}
               '{metosin/malli {:mvn/version "0.17.0"}}))
        "a CLI-started process already has a real basis; never override it"))
  (testing "nothing to seed is not an error"
    (is (nil? (#'boot/basis-libs-to-seed nil nil))
        "not running from an uberjar — no claim to make")
    (is (nil? (#'boot/basis-libs-to-seed nil {})))))

(deftest a-declared-version-the-host-overrides-is-named-not-hidden
  ;; Seeding the basis stops the FALSE claim, but it does not make the
  ;; declaration govern: a lib the uberjar bundles runs at the uberjar's
  ;; version in this process no matter what the store declares, because a jar
  ;; already loaded by the parent loader cannot be displaced. What changes is
  ;; that the disagreement becomes SAYABLE.
  ;;
  ;; It is a real divergence, not a nicety: the oracle image is a separate
  ;; `clojure -Sdeps` JVM that resolves the manifest properly, so the version
  ;; the tests run against and the version the server runs can differ.
  (testing "a bundled lib at another version is reported"
    (is (= '{metosin/malli {:declared {:mvn/version "0.16.4"}
                            :in-force {:mvn/version "0.17.0"}}}
           (#'boot/host-lib-divergence
            '{metosin/malli {:mvn/version "0.16.4"}}
            '{metosin/malli {:mvn/version "0.17.0"}}))))
  (testing "agreement is silence"
    (is (empty? (#'boot/host-lib-divergence
                 '{metosin/malli {:mvn/version "0.17.0"}}
                 '{metosin/malli {:mvn/version "0.17.0"}}))
        "same version — nothing to say"))
  (testing "a lib the host does not bundle is not its business"
    (is (empty? (#'boot/host-lib-divergence
                 '{org.clojure/data.json {:mvn/version "2.5.0"}}
                 '{metosin/malli {:mvn/version "0.17.0"}}))
        "resolves normally; the host has no copy to win with")
    (is (empty? (#'boot/host-lib-divergence
                 '{metosin/malli {:mvn/version "0.16.4"}} nil))
        "not running from an uberjar — nothing is overridden")))

(deftest ^:external seeding-the-basis-survives-contact-with-a-real-runtime
  ;; The pure half decides WHAT to seed; this covers the plumbing that carries
  ;; it — `requiring-resolve` into `clojure.java.basis.impl` and the arity of
  ;; `update-basis!`, neither of which any pure test touches and both of which
  ;; are exactly what broke the first time the kernel reached for the basis.
  ;;
  ;; It asserts a REPORT rather than a state, because the answer legitimately
  ;; differs by how the process was started: this test runs in the oracle
  ;; image, which the Clojure CLI started with a real basis, so the honest
  ;; outcome here is :kept. A jar-started host is where :seeded happens.
  (let [r (#'boot/ensure-bundled-libs!)]
    (is (#{:seeded :kept} (:action r)) (pr-str r))
    (is (nat-int? (:libs r)) (pr-str r))
    (testing "a second call never seeds over the first"
      (is (= :kept (:action (#'boot/ensure-bundled-libs!)))
          "once the basis names its libs, it is the basis"))))

(deftest a-reload-failure-reports-the-cause-not-the-wrapper
  (testing "a wrapped compiler error surfaces the reason, not just its position"
    ;; the shape actually observed: getMessage stops at the wrapper, and the
    ;; sentence an operator needs ("Unable to resolve symbol: nsfilter") is one
    ;; or more causes down. Reporting only the wrapper makes every distinct
    ;; failure look like the same one.
    (let [root    (RuntimeException. "Unable to resolve symbol: nsfilter")
          wrapped (RuntimeException. "Syntax error macroexpanding at (1:1)." root)
          msg     (boot/failure-message wrapped)]
      (is (re-find #"nsfilter" msg)
          "the root cause's message must reach the report")
      (is (re-find #"macroexpanding" msg)
          "the wrapper's position is still worth keeping — it says WHERE")))

  (testing "a chain deeper than one level walks to the bottom"
    (let [root (RuntimeException. "boom at the bottom")
          mid  (RuntimeException. "middle" root)
          top  (RuntimeException. "top" mid)]
      (is (re-find #"boom at the bottom" (boot/failure-message top)))))

  (testing "an unwrapped throwable reports its message once, not twice"
    (let [msg (boot/failure-message (RuntimeException. "plain"))]
      (is (= "plain" msg))))

  (testing "a throwable with no message at all still names its class"
    (is (re-find #"NullPointerException"
                 (boot/failure-message (NullPointerException.))))))

^:unsafe (deftest a-rename-repoints-an-alias-instead-of-wedging-the-reload
  ;; The live-reload wedge, root-caused after three occurrences and two
  ;; failed investigations. Renaming a namespace rewrites every dependent's
  ;; `ns` form to point the SAME alias at a new target, and
  ;; `Namespace.addAlias` refuses that outright:
  ;;
  ;;   Alias refs already exists in namespace slopp.api, aliasing slopp.edit.refs
  ;;
  ;; It is an IllegalStateException raised while the `ns` form evaluates, so
  ;; the compiler wraps it and `.getMessage` reports only "Syntax error
  ;; macroexpanding at (1:1)." — which is why two occurrences were logged as
  ;; a mystery. Deterministic and permanent: the alias outlives the failed
  ;; load, so every subsequent poll fails identically. A fresh JVM has no
  ;; alias and loads the same source fine, which is why the store stays green
  ;; while the host is stuck, and why only a process restart ever cleared it.
  ;;
  ;; ^:unsafe because reproducing a load is the only honest way to test one:
  ;; the failure lives in the JVM's namespace objects, not in any value a
  ;; pure function could be handed.
  (let [old-ns 'bootprobe.alias-old
        new-ns 'bootprobe.alias-new
        dep-ns 'bootprobe.alias-dep
        src    (fn [target]
                 (str "(ns " dep-ns " (:require [" target " :as t]))\n"
                      "(defn reach [] (t/answer))\n"))]
    (try
      (load-string (str "(ns " old-ns ")\n(defn answer [] :old)\n"))
      (load-string (str "(ns " new-ns ")\n(defn answer [] :new)\n"))

      (testing "the dependent loads against its original target"
        (#'boot/reload-ns! dep-ns (src old-ns))
        (is (= :old ((ns-resolve dep-ns 'reach)))))

      (testing "the same alias, re-pointed by a rename, reloads"
        (#'boot/reload-ns! dep-ns (src new-ns))
        (is (= :new ((ns-resolve dep-ns 'reach)))
            "a reload must clear the stale alias rather than throw on it")
        (is (= new-ns (ns-name (get (ns-aliases (find-ns dep-ns)) 't)))
            "and the alias must end up pointing at the new target"))

      (finally
        (doseq [n [old-ns new-ns dep-ns]]
          (when (find-ns n) (remove-ns n)))))))
