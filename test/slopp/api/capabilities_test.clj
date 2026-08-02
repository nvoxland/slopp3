(ns slopp.api.capabilities-test
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
            [slopp.api.capabilities :as caps] [slopp.api :as api] [slopp.api.external :as external]))

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
    (is (= "auth.static.*" (:key (caps/find-entry "auth.static.users.alice"))))
    (is (= "groups.*.members" (:key (caps/find-entry "groups.admin.members")))))
  (testing "an unknown key resolves to nothing"
    (is (nil? (caps/find-entry "http.prot")))
    (is (nil? (caps/find-entry "groups.admin.member")))))

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
    (is (nil? (caps/check-value (caps/find-entry "auth.providers") "static,bearer")))
    (is (string? (caps/check-value (caps/find-entry "auth.providers") "static,ldap"))))
  (testing "effective: the declared default when unset, the parsed value when set"
    (let [s0 (store/ingest (store/empty-store) 'app.core "(ns app.core)\n(defn f [x] x)\n")]
      (is (false? (caps/effective s0 "web.enabled")))
      ;; web.port carries no registry default any more — serve! owns the 8080
    ;; and the dev server derives; see
    ;; an-unset-port-does-not-report-a-number-nothing-binds
    (is (nil? (caps/effective s0 "web.port")))
      (is (= :http-kit (caps/effective s0 "web.adapter")))
      (is (= :deny (caps/effective s0 "auth.default-policy")))
      (let [s (-> s0
                  (store/record-config-put "capabilities" :manifest "web.enabled" "true") first
                  (store/record-config-put "capabilities" :manifest "web.port" "7357") first
                  (store/record-config-put "capabilities" :manifest "auth.providers" "static,bearer") first)]
        (is (true? (caps/effective s "web.enabled")))
        (is (= 7357 (caps/effective s "web.port")))
        (is (= #{:static :bearer} (caps/effective s "auth.providers")))
        (testing "an unset key still falls back beside set ones"
          (is (= :http-kit (caps/effective s "web.adapter"))))))))

(deftest ^:external capabilities-config-validates-at-write
  (let [sess (external/open!)]
    (try
      (testing "an unknown capability key is refused with teaching"
        (let [r (api/config-file! sess "capabilities" :key "web.prot" :value "8080"
                                  :prompt "typo'd key")]
          (is (re-find #"web\.prot" (str (:error r))) (pr-str r))
          (is (re-find #"query_capabilities" (str (:error r))) (pr-str r))))
      (testing "a bad value is refused with the type teaching"
        (let [r (api/config-file! sess "capabilities" :key "web.port" :value "banana"
                                  :prompt "bad port")]
          (is (re-find #"integer" (str (:error r))) (pr-str r))
          (is (nil? (get-in (:store @sess) [:config "capabilities" :values "web.port"]))
              "the refused value never landed")))
      (testing "a good value lands and takes effect"
        (let [r (api/config-file! sess "capabilities" :key "web.port" :value "7357"
                                  :prompt "real port")]
          (is (nil? (:error r)) (pr-str r))
          (is (= 7357 (caps/effective (:store @sess) "web.port")))))
      (testing "a wildcard-governed key is known, not alien"
        (let [r (api/config-file! sess "capabilities" :key "groups.admin.members" :value "alice,bob"
                                  :prompt "a group")]
          (is (nil? (:error r)) (pr-str r))
          (is (= #{"alice" "bob"} (caps/effective (:store @sess) "groups.admin.members")))))
      (testing "unset returns to the default"
        (api/config-file! sess "capabilities" :key "web.port" :unset true
                          :prompt "back to default")
        ;; web.port's declared default is nil now — serve! owns the 8080 and the
      ;; dev server derives, so "returns to the default" means returns to unset
      (is (nil? (caps/effective (:store @sess) "web.port"))))
      (finally (api/close! sess)))))

(deftest report-shows-every-setting-with-provenance
  (let [s0 (store/ingest (store/empty-store) 'app.core "(ns app.core)\n(defn f [x] x)\n")
        s  (-> s0
               (store/record-config-put "capabilities" :manifest "web.enabled" "true") first
               (store/record-config-put "capabilities" :manifest "groups.admin.members" "alice,bob") first)
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
      (let [g (row "groups.admin.members")]
        (is (some? g))
        (is (true? (:set g)))
        (is (= #{"alice" "bob"} (:effective g)))))
    (testing "wildcard patterns are listed as patterns, not as settable rows"
      (is (nil? (row "web.static.*")))
      (is (some #(= "web.static.*" (:key %)) (:patterns rep))))))

(deftest secret-literals-refuse-in-capabilities
  (testing "a literal secret in an auth.* credential key refuses"
    (is (re-find #"env:" (str (caps/config-refusal
                               "auth.bearer.tokens.ci"
                               "{:secret \"hunter2\" :groups [\"ci\"]}"))))
    (is (re-find #"env:" (str (caps/config-refusal
                               "auth.oidc.client-secret" "abc123")))))
  (testing "an env: indirection passes"
    (is (nil? (caps/config-refusal
               "auth.bearer.tokens.ci"
               "{:secret \"env:CI_TOKEN\" :groups [\"ci\"]}")))
    (is (nil? (caps/config-refusal "auth.oidc.client-secret" "env:OIDC_SECRET"))))
  (testing "a password HASH is the safe form, not a secret literal"
    (is (nil? (caps/config-refusal
               "auth.static.users.alice"
               "{:password-hash \"9f86d08...\" :groups [\"admin\"]}")))))

(deftest ui-ports-are-two-settings-and-the-project-one-defaults-to-derived
  ;; D-hub. A machine runs many slopp projects, so the port a project's own
  ;; UI listener binds cannot have a fixed default — that is a guaranteed
  ;; collision. Unset means DERIVED from the store dir: stable across
  ;; restarts, conflict-free, and nobody has to know it, because the address a
  ;; human remembers is the hub's.
  (let [entry (caps/find-entry "slopp.api.port")]
    (is (= "slopp.api.port" (:key entry)))
    (is (nil? (caps/effective (store/empty-store) "slopp.api.port"))
        "unset = derive from the dir; an explicit value is for someone who wants a fixed address")
    (is (nil? (caps/check-value entry "7400")))
    (is (string? (caps/check-value entry "not-a-port"))
        "a bad port is refused at the config write, not at bind time"))
  ;; The well-known port belongs to the HUB now, and the hub is started by a
  ;; human. This default is the one number both halves read: the project uses
  ;; it to find a hub, the hub CLI uses it to bind.
  (let [entry (caps/find-entry "slopp.hub.port")]
    (is (= "slopp.hub.port" (:key entry)))
    (is (= 7359 (caps/effective (store/empty-store) "slopp.hub.port")))
    (is (nil? (caps/check-value entry "0"))
        "0 is legal and means: this project registers with no hub")))

(deftest ^:external config-writes-say-whether-anything-validated-them
  ;; `capabilities` is the ONLY path with a registry behind it. Every other
  ;; path — rules, gates, client — records the key and value as given and
  ;; returns a result indistinguishable from a validated one. Saying which is
  ;; which is D-surface-honesty at config grain.
  (let [sess (external/open!)]
    (try
      (testing "a capabilities write was checked against the registry"
        (let [r (api/config-file! sess "capabilities" :key "web.port" :value "7357"
                                  :prompt "real port")]
          (is (= [:registry] (:verified r)) (pr-str r))
          (is (= [] (:unverified r)) (pr-str r))))
      (testing "any other path is recorded UNVALIDATED, and says so"
        (let [r (api/config-file! sess "rules" :key "key-typos" :value "off"
                                  :prompt "quiet that rule")]
          (is (= [] (:verified r)) (pr-str r))
          (is (= [:schema] (:unverified r)) (pr-str r))
          (is (re-find #"capabilities" (str (:note r)))
              "the note must name the one path that IS validated")))
      (finally (api/close! sess)))))

(deftest an-unset-port-does-not-report-a-number-nothing-binds
  ;; Measured by slopp-ui, 2026-08-01:
  ;;   query_capabilities → web.port  :effective 8080  (not :set)
  ;;   actual bind                     51614
  ;;   curl 8080                       not listening
  ;;
  ;; `slopp.api.port` gets this exactly right — `:effective nil`, and its doc says
  ;; "Unset = DERIVED from the store dir". web.port said 8080 and derived
  ;; anyway, so the one surface whose job is to report configuration reported
  ;; a port nothing was listening on.
  ;;
  ;; The registry default was the duplicate: `slopp.web/serve!` ALREADY
  ;; defaults `:web/port` to 8080, so declaring it again here resolved
  ;; "unset" into "8080" one layer too early — early enough that the dev
  ;; server's own derivation could no longer be told apart from a pin.
  (let [s0 (store/empty-store)]
    (testing "unset reports UNSET, so nothing downstream is bound by it"
      (is (nil? (caps/effective s0 "web.port")))
      (is (not (caps/stored? s0 "web.port"))))
    (testing "and the same shape slopp.api.port already had"
      (is (nil? (caps/effective s0 "slopp.api.port"))))
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
