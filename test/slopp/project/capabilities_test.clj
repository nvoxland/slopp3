(ns slopp.project.capabilities-test
  "The capability REGISTRY as the single source: that a declared key validates
  at the write, resolves to one effective value, and reports honestly.

  The through-line is that a registered key must never nil-pun and must never
  report a value nothing uses. Both halves have been wrong in production —
  a row deleted from the registry broke no test at all, and `web.port`
  reported 8080 while the dev server bound a derived port. So the tests here
  lean on the DECLARATION — defaults, docs, `stored?` against `effective` —
  rather than on any one consumer's reading of it."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.project.capabilities :as caps] [clojure.string :as str]))

(deftest registry-declares-and-resolves-keys
  (testing "every registry entry carries key, type, default slot, and doc"
    (is (seq caps/registry))
    (doseq [e caps/registry]
      (is (string? (:key e)) (pr-str e))
      (is (vector? (:type e)) (pr-str e))
      (is (string? (:doc e)) (pr-str e))))
  (testing "exact keys resolve"
    (is (= "web.port" (:key (caps/find-entry "web.port"))))
    (is (= "web.enabled" (:key (caps/find-entry "web.enabled")))))
  (testing "wildcard keys: a trailing * is a prefix (one or more segments), a mid * is one segment"
    (is (= "web.static.*" (:key (caps/find-entry "web.static./assets"))))
    (is (= "web.auth.static.*" (:key (caps/find-entry "web.auth.static.users.alice"))))
    (is (= "web.auth.groups.*.members" (:key (caps/find-entry "web.auth.groups.admin.members")))))
  (testing "an unknown key resolves to nothing"
    (is (nil? (caps/find-entry "http.prot")))
    ;; the mid-`*` pattern still demands its tail. Left as `groups.admin.member`
    ;; this would have gone on passing after the family moved under web.auth —
    ;; green because NO `groups.` key resolves any more, which is not what it
    ;; was written to observe.
    (is (nil? (caps/find-entry "web.auth.groups.admin.member")))
    (is (some? (caps/find-entry "web.auth.groups.admin.members")))))

(deftest values-check-and-take-effect
  (testing "check-value: nil when the string suits the type, a teaching string when not"
    (is (nil? (caps/check-value (caps/find-entry "web.port") "8080")))
    (is (string? (caps/check-value (caps/find-entry "web.port") "banana")))
    (is (string? (caps/check-value (caps/find-entry "web.port") "70000")))
    (is (nil? (caps/check-value (caps/find-entry "web.enabled") "true")))
    (is (string? (caps/check-value (caps/find-entry "web.enabled") "yes")))
    (is (nil? (caps/check-value (caps/find-entry "web.adapter") "jdk")))
    (is (string? (caps/check-value (caps/find-entry "web.adapter") "jetty")))
    (is (nil? (caps/check-value (caps/find-entry "app.main") "app.core/-main")))
    (is (string? (caps/check-value (caps/find-entry "app.main") "not a symbol")))
    (is (nil? (caps/check-value (caps/find-entry "web.auth.providers") "static,bearer")))
    (is (string? (caps/check-value (caps/find-entry "web.auth.providers") "static,ldap"))))
  (testing "effective: the declared default when unset, the parsed value when set"
    (let [s0 (store/ingest (store/empty-store) 'app.core "(ns app.core)\n(defn f [x] x)\n")]
      (is (false? (caps/effective s0 "web.enabled")))
      ;; web.port carries no registry default any more — serve! owns the 8080
    ;; and the dev server derives; see
    ;; an-unset-port-does-not-report-a-number-nothing-binds
    (is (nil? (caps/effective s0 "web.port")))
      (is (= :http-kit (caps/effective s0 "web.adapter")))
      (is (= :deny (caps/effective s0 "web.auth.default-policy")))
      (let [s (-> s0
                  (store/record-config-put "capabilities" :manifest "web.enabled" "true") first
                  (store/record-config-put "capabilities" :manifest "web.port" "7357") first
                  (store/record-config-put "capabilities" :manifest "web.auth.providers" "static,bearer") first)]
        (is (true? (caps/effective s "web.enabled")))
        (is (= 7357 (caps/effective s "web.port")))
        (is (= #{:static :bearer} (caps/effective s "web.auth.providers")))
        (testing "an unset key still falls back beside set ones"
          (is (= :http-kit (caps/effective s "web.adapter"))))))))

(deftest report-shows-every-setting-with-provenance
  (let [s0 (store/ingest (store/empty-store) 'app.core "(ns app.core)\n(defn f [x] x)\n")
        s  (-> s0
               (store/record-config-put "capabilities" :manifest "web.enabled" "true") first
               (store/record-config-put "capabilities" :manifest "web.auth.groups.admin.members" "alice,bob") first)
        rep (caps/report s)
        row (fn [k] (some #(when (= k (:key %)) %) (:settings rep)))]
    (testing "every concrete registry key is a row with default, effective, and doc"
      (let [port (row "web.port")]
        (is (nil? (:default port)))
        (is (nil? (:effective port)))
        (is (string? (:doc port)))
        (is (not (:set port)))))
    (testing "a set key carries :set true and the raw stored string"
      (let [en (row "web.enabled")]
        (is (true? (:effective en)))
        (is (true? (:set en)))
        (is (= "true" (:value en)))))
    (testing "a set wildcard-governed key appears as a row"
      (let [g (row "web.auth.groups.admin.members")]
        (is (some? g))
        (is (true? (:set g)))
        (is (= #{"alice" "bob"} (:effective g)))))
    (testing "wildcard patterns are listed as patterns, not as settable rows"
      (is (nil? (row "web.static.*")))
      (is (some #(= "web.static.*" (:key %)) (:patterns rep))))))

(deftest secret-literals-refuse-in-capabilities
  (testing "a literal secret in a web.auth.* credential key refuses"
    (is (re-find #"env:" (str (caps/config-refusal
                               "web.auth.bearer.tokens.ci"
                               "{:secret \"hunter2\" :groups [\"ci\"]}"))))
    (is (re-find #"env:" (str (caps/config-refusal
                               "web.auth.oidc.client-secret" "abc123")))))
  (testing "an env: indirection passes"
    (is (nil? (caps/config-refusal
               "web.auth.bearer.tokens.ci"
               "{:secret \"env:CI_TOKEN\" :groups [\"ci\"]}")))
    (is (nil? (caps/config-refusal "web.auth.oidc.client-secret" "env:OIDC_SECRET"))))
  (testing "a password HASH is the safe form, not a secret literal"
    (is (nil? (caps/config-refusal
               "web.auth.static.users.alice"
               "{:password-hash \"9f86d08...\" :groups [\"admin\"]}"))))
  (testing "the credential check is anchored to the auth family, not to the word"
    ;; it reads `web.auth.` + a token/secret position. The retired `auth.`
    ;; spelling is not a capability at all now, so it refuses for the OTHER
    ;; reason — which is right, and worth pinning so a later widening of the
    ;; prefix match does not quietly re-admit it.
    (is (re-find #"is not a capability"
                 (str (caps/config-refusal "auth.oidc.client-secret" "abc123"))))))

(deftest the-hub-port-is-the-only-port-setting-slopp-itself-owns
  ;; D-hub, then phase 2 (2026-08-03). There used to be two `slopp.*` port
  ;; keys and the pair was the point: one for the project's own API listener,
  ;; one for the hub it registers with. Only the second survives, and the
  ;; asymmetry is the lesson rather than an accident.
  ;;
  ;; `slopp.api.port` was RETIRED. A machine runs many slopp projects, so
  ;; that listener could never have a fixed default — it is derived from the
  ;; store dir, stable across restarts, and nobody has to know it because the
  ;; address a human remembers is the hub's. Once every honest answer came
  ;; from the derivation, the knob was a knob for a number nobody chooses:
  ;; the one external adopter never set it, and `ui_serve {port}` already
  ;; covers wanting a specific address for one run. See
  ;; `slopp.api.server-test/the-preferred-port-is-derived-and-never-configured`.
  (testing "the retired key is governed by nothing — not by a lingering entry"
    ;; a removed capability that still resolves is worse than one that never
    ;; left: `config_file` would go on accepting writes to a key no code reads
    (is (nil? (caps/find-entry "slopp.api.port"))))
  ;; The well-known port belongs to the HUB, and the hub is started by a
  ;; human. This default is the one number both halves read: the project uses
  ;; it to find a hub, the hub CLI uses it to bind. It is configurable
  ;; precisely because it is an INPUT — an address to reach out to, chosen by
  ;; whoever runs the hub — which is the distinction the retired key failed.
  (let [entry (caps/find-entry "slopp.hub.port")]
    (is (= "slopp.hub.port" (:key entry)))
    (is (= 7359 (caps/effective (store/empty-store) "slopp.hub.port")))
    (is (nil? (caps/check-value entry "0"))
        "0 is legal and means: this project registers with no hub")))

(deftest an-unset-port-does-not-report-a-number-nothing-binds
  ;; Measured by slopp-ui, 2026-08-01:
  ;;   query_capabilities → web.port  :effective 8080  (not :set)
  ;;   actual bind                     51614
  ;;   curl 8080                       not listening
  ;;
  ;; `slopp.api.port` got this exactly right — `:effective nil`, and its doc
  ;; said "Unset = DERIVED from the store dir". web.port said 8080 and derived
  ;; anyway, so the one surface whose job is to report configuration reported
  ;; a port nothing was listening on. (That key was RETIRED in phase 2,
  ;; 2026-08-03 — the exemplar is history, the rule it demonstrated is not.
  ;; There was an assertion here comparing web.port against it; it went with
  ;; the key rather than being retargeted, because `caps/effective` on a key
  ;; no registry governs returns nil for the trivial reason and would have
  ;; gone on passing forever while measuring nothing.)
  ;;
  ;; The registry default was the duplicate: `slopp.web/serve!` ALREADY
  ;; defaults `:web/port` to 8080, so declaring it again here resolved
  ;; "unset" into "8080" one layer too early — early enough that the dev
  ;; server's own derivation could no longer be told apart from a pin.
  (let [s0 (store/empty-store)]
    (testing "unset reports UNSET, so nothing downstream is bound by it"
      (is (nil? (caps/effective s0 "web.port")))
      (is (not (caps/stored? s0 "web.port"))))
    (testing "a pin still wins, and is reported as pinned"
      (let [s (first (store/record-config-put s0 "capabilities" :manifest
                                              "web.port" "9000"))]
        (is (= 9000 (caps/effective s "web.port")))
        (is (caps/stored? s "web.port"))))
    (testing "the DOC has to carry what unset means, since the value no
              longer can"
      ;; a nil effective value is only honest if the reader can find out what
      ;; happens instead — otherwise it trades a wrong number for no answer
      (let [doc (:doc (caps/find-entry "web.port"))]
        (is (re-find #"(?i)unset" doc) doc)
        (is (re-find #"8080" doc) doc)))))

(deftest orphaned-stored-keys-are-named-rather-than-dropped
  ;; Found by slopp-ui crossing the http.* -> web.* rename. They ran
  ;; query_capabilities on a store holding three stored values and got back
  ;; ZERO :set true anywhere, with no mention that the three existed — so the
  ;; tool whose job is "what is configured here" reported an unconfigured
  ;; store while the reason its app server would not start sat in the config
  ;; it declined to name.
  ;;
  ;; UNSET and SET-UNDER-A-NAME-THIS-BUILD-NO-LONGER-KNOWS shared one
  ;; representation, at the exact moment the difference IS the diagnosis. The
  ;; join already has both halves; the orphans are the rows that fall off it.
  (let [put (fn [s k v] (first (store/record-config-put s "capabilities" :manifest k v)))
        s   (-> (store/empty-store)
                (put "web.enabled" "true")
                (put "http.enabled" "true")
                (put "http.static./assets" "public"))
        rep (caps/report s)]
    (testing "a stored key with no registry row is reported, with its value"
      (is (= #{"http.enabled" "http.static./assets"}
             (set (map :key (:orphaned rep))))
          (pr-str (:orphaned rep)))
      (is (= "public"
             (->> (:orphaned rep)
                  (filter #(= "http.static./assets" (:key %)))
                  first :value))
          "the VALUE is the migration instruction — naming the key alone
           still makes someone go and look it up"))
    (testing "keys the registry does know are unaffected"
      (let [en (first (filter #(= "web.enabled" (:key %)) (:settings rep)))]
        (is (true? (:set en)) (pr-str en))
        (is (true? (:effective en)) (pr-str en))))
    (testing "nothing orphaned says so by absence, like :debt does"
      (is (nil? (:orphaned (caps/report (put (store/empty-store) "web.enabled" "true"))))))))

(deftest every-capability-key-declares-its-owner
  ;; R6: no slopp surface may assume a project is a web project, and support
  ;; for an app TYPE lives under that type's name. The registry was the
  ;; violation — 14 of 19 entries were web's, and 8 of those sat under `auth.`
  ;; and `groups.`, names that claim to be generic project settings. Measured
  ;; before renaming them: every reader was `slopp.web.auth/config-from-values`
  ;; or the `web-unknown-group` write gate. Nothing generic read them.
  ;;
  ;; The invariant is the key's FIRST SEGMENT, not a declared `:owner` field,
  ;; because a field that restates the name is a second source of truth that
  ;; can disagree with it. So app type #2 adds an owner to `caps/owners` and
  ;; its keys under that segment; a key belonging to nobody fails here.
  (testing "every registry key's first segment is a declared owner"
    (let [segment #(first (str/split (str %) #"\."))
          stray (remove #(contains? caps/owners (segment (:key %))) caps/registry)]
      (is (empty? (map :key stray))
          "a capability key under an undeclared owner — add the owner to caps/owners, or move the key under an existing one")))
  (testing "the owners each say what they are, since query_capabilities shows them"
    (is (every? (comp seq val) caps/owners))
    (is (contains? caps/owners "web") "the web app type owns its own keys")
    (is (contains? caps/owners "slopp") "R1: the framework prefix is reserved"))
  (testing "auth and groups are web's, and the names say so"
    (is (some #(= "web.auth.providers" (:key %)) caps/registry))
    (is (= "web.auth.static.*" (:key (caps/find-entry "web.auth.static.users.alice"))))
    (is (= "web.auth.groups.*.members" (:key (caps/find-entry "web.auth.groups.admin.members"))))
    (is (nil? (caps/find-entry "auth.providers"))
        "the retired spelling resolves to nothing — no backwards compatibility")
    (is (nil? (caps/find-entry "groups.admin.members")))))

(deftest the-report-says-who-owns-each-setting
  ;; The R6 complaint was not only that the names lied — it was that every
  ;; project is shown every key. A store that will never serve HTTP still
  ;; reads `web.auth.oidc.*` and `web.max-body-bytes` as things it could set.
  ;;
  ;; Filtering them OUT is the wrong fix and worth saying why: `web.enabled`
  ;; is itself a web key, so "hide web keys until web is on" hides the switch
  ;; that turns it on. Attribution is the fix — the rows say whose they are,
  ;; and the owner vocabulary says what that means, so fourteen settings read
  ;; as one feature.
  (let [s0 (store/ingest (store/empty-store) 'app.core "(ns app.core)\n(defn f [x] x)\n")
        rep (caps/report s0)
        row (fn [k] (some #(when (= k (:key %)) %) (:settings rep)))]
    (testing "every settings row and every pattern names its owner"
      (is (seq (:settings rep)))
      (is (seq (:patterns rep)))
      (is (every? :owner (:settings rep)))
      (is (every? :owner (:patterns rep))))
    (testing "the owner is the key's first segment, so it cannot disagree with the name"
      (is (= "web" (:owner (row "web.port"))))
      (is (= "app" (:owner (row "app.name"))))
      (is (= "slopp" (:owner (row "slopp.hub.port")))))
    (testing "the report carries the vocabulary, not just the labels"
      (is (= caps/owners (:owners rep)))
      (is (string? (get (:owners rep) "web"))))))
