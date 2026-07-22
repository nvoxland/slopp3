(ns slopp.api.capabilities-test
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
    (is (= "http.port" (:key (caps/find-entry "http.port"))))
    (is (= "http.enabled" (:key (caps/find-entry "http.enabled")))))
  (testing "wildcard keys: a trailing * is a prefix (one or more segments), a mid * is one segment"
    (is (= "http.static.*" (:key (caps/find-entry "http.static./assets"))))
    (is (= "auth.static.*" (:key (caps/find-entry "auth.static.users.alice"))))
    (is (= "groups.*.members" (:key (caps/find-entry "groups.admin.members")))))
  (testing "an unknown key resolves to nothing"
    (is (nil? (caps/find-entry "http.prot")))
    (is (nil? (caps/find-entry "groups.admin.member")))))

(deftest values-check-and-take-effect
  (testing "check-value: nil when the string suits the type, a teaching string when not"
    (is (nil? (caps/check-value (caps/find-entry "http.port") "8080")))
    (is (string? (caps/check-value (caps/find-entry "http.port") "banana")))
    (is (string? (caps/check-value (caps/find-entry "http.port") "70000")))
    (is (nil? (caps/check-value (caps/find-entry "http.enabled") "true")))
    (is (string? (caps/check-value (caps/find-entry "http.enabled") "yes")))
    (is (nil? (caps/check-value (caps/find-entry "http.adapter") "jdk")))
    (is (string? (caps/check-value (caps/find-entry "http.adapter") "jetty")))
    (is (nil? (caps/check-value (caps/find-entry "app.main") "app.core/-main")))
    (is (string? (caps/check-value (caps/find-entry "app.main") "not a symbol")))
    (is (nil? (caps/check-value (caps/find-entry "auth.providers") "static,bearer")))
    (is (string? (caps/check-value (caps/find-entry "auth.providers") "static,ldap"))))
  (testing "effective: the declared default when unset, the parsed value when set"
    (let [s0 (store/ingest (store/empty-store) 'app.core "(ns app.core)\n(defn f [x] x)\n")]
      (is (false? (caps/effective s0 "http.enabled")))
      (is (= 8080 (caps/effective s0 "http.port")))
      (is (= :http-kit (caps/effective s0 "http.adapter")))
      (is (= :deny (caps/effective s0 "auth.default-policy")))
      (let [s (-> s0
                  (store/record-config-put "capabilities" :manifest "http.enabled" "true") first
                  (store/record-config-put "capabilities" :manifest "http.port" "7357") first
                  (store/record-config-put "capabilities" :manifest "auth.providers" "static,bearer") first)]
        (is (true? (caps/effective s "http.enabled")))
        (is (= 7357 (caps/effective s "http.port")))
        (is (= #{:static :bearer} (caps/effective s "auth.providers")))
        (testing "an unset key still falls back beside set ones"
          (is (= :http-kit (caps/effective s "http.adapter"))))))))

(deftest ^:external capabilities-config-validates-at-write
  (let [sess (external/open!)]
    (try
      (testing "an unknown capability key is refused with teaching"
        (let [r (api/config-file! sess "capabilities" :key "http.prot" :value "8080"
                                  :prompt "typo'd key")]
          (is (re-find #"http\.prot" (str (:error r))) (pr-str r))
          (is (re-find #"query_capabilities" (str (:error r))) (pr-str r))))
      (testing "a bad value is refused with the type teaching"
        (let [r (api/config-file! sess "capabilities" :key "http.port" :value "banana"
                                  :prompt "bad port")]
          (is (re-find #"integer" (str (:error r))) (pr-str r))
          (is (nil? (get-in (:store @sess) [:config "capabilities" :values "http.port"]))
              "the refused value never landed")))
      (testing "a good value lands and takes effect"
        (let [r (api/config-file! sess "capabilities" :key "http.port" :value "7357"
                                  :prompt "real port")]
          (is (nil? (:error r)) (pr-str r))
          (is (= 7357 (caps/effective (:store @sess) "http.port")))))
      (testing "a wildcard-governed key is known, not alien"
        (let [r (api/config-file! sess "capabilities" :key "groups.admin.members" :value "alice,bob"
                                  :prompt "a group")]
          (is (nil? (:error r)) (pr-str r))
          (is (= #{"alice" "bob"} (caps/effective (:store @sess) "groups.admin.members")))))
      (testing "unset returns to the default"
        (api/config-file! sess "capabilities" :key "http.port" :unset true
                          :prompt "back to default")
        (is (= 8080 (caps/effective (:store @sess) "http.port"))))
      (finally (api/close! sess)))))

(deftest report-shows-every-setting-with-provenance
  (let [s0 (store/ingest (store/empty-store) 'app.core "(ns app.core)\n(defn f [x] x)\n")
        s  (-> s0
               (store/record-config-put "capabilities" :manifest "http.enabled" "true") first
               (store/record-config-put "capabilities" :manifest "groups.admin.members" "alice,bob") first)
        rep (caps/report s)
        row (fn [k] (some #(when (= k (:key %)) %) (:settings rep)))]
    (testing "every concrete registry key is a row with default, effective, and doc"
      (let [port (row "http.port")]
        (is (= 8080 (:default port)))
        (is (= 8080 (:effective port)))
        (is (string? (:doc port)))
        (is (not (:set port)))))
    (testing "a set key carries :set true and the raw stored string"
      (let [en (row "http.enabled")]
        (is (true? (:effective en)))
        (is (true? (:set en)))
        (is (= "true" (:value en)))))
    (testing "a set wildcard-governed key appears as a row"
      (let [g (row "groups.admin.members")]
        (is (some? g))
        (is (true? (:set g)))
        (is (= #{"alice" "bob"} (:effective g)))))
    (testing "wildcard patterns are listed as patterns, not as settable rows"
      (is (nil? (row "http.static.*")))
      (is (some #(= "http.static.*" (:key %)) (:patterns rep))))))
