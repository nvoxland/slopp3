(ns slopp.web.auth-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.web.auth :as auth] [cheshire.core :as cjson]))

(deftest providers-resolve-identity
  (let [config {:auth/providers [:bearer :static :proxy-header]
                :auth/bearer {"ci" {:secret "env:T_CI_TOKEN" :groups ["ci"]}}
                :auth/static {"alice" {:password-hash (slopp.web.auth/sha256-hex "s3cret")
                                       :groups ["admin"]}}
                :auth/proxy {:trusted #{"10.0.0.1"}
                             :user-header "x-forwarded-user"
                             :groups-header "x-forwarded-groups"}
                :auth/groups {"admin" #{"alice" "root"}
                              "ops" #{"alice"}}}
        getenv {"T_CI_TOKEN" "tok-123"}
        rid (fn [req] (auth/resolve-identity config req :getenv getenv))]
    (testing "a bearer token resolves through the env-indirect secret"
      (let [id (rid {:headers {"authorization" "Bearer tok-123"}})]
        (is (= "ci" (:web/sub id)))
        (is (contains? (:web/groups id) "ci"))))
    (testing "a wrong bearer token is anonymous, never an error"
      (is (nil? (rid {:headers {"authorization" "Bearer nope"}}))))
    (testing "static basic-auth verifies the sha-256 hash and augments groups from config"
      (let [creds (str "Basic " (.encodeToString (java.util.Base64/getEncoder)
                                                 (.getBytes "alice:s3cret" "UTF-8")))
            id (rid {:headers {"authorization" creds}})]
        (is (= "alice" (:web/sub id)))
        (is (= #{"admin" "ops"} (:web/groups id))))
      (testing "a wrong password is anonymous"
        (let [creds (str "Basic " (.encodeToString (java.util.Base64/getEncoder)
                                                   (.getBytes "alice:wrong" "UTF-8")))]
          (is (nil? (rid {:headers {"authorization" creds}}))))))
    (testing "proxy headers count ONLY from a trusted remote"
      (let [req {:remote-addr "10.0.0.1"
                 :headers {"x-forwarded-user" "root"
                           "x-forwarded-groups" "dev,sre"}}
            id (rid req)]
        (is (= "root" (:web/sub id)))
        (is (= #{"dev" "sre" "admin"} (:web/groups id))))
      (is (nil? (rid {:remote-addr "203.0.113.9"
                      :headers {"x-forwarded-user" "root"}}))))
    (testing "no credentials → nil (anonymous), and policy's default-deny takes it from there"
      (is (nil? (rid {:headers {}}))))))

(deftest capabilities-values-parse-into-auth-config
  (let [values {"auth.providers" "bearer,proxy-header"
                "auth.bearer.tokens.ci" "{:secret \"env:CI\" :groups [\"ci\"]}"
                "auth.static.users.alice" "{:password-hash \"abc\" :groups [\"admin\"]}"
                "auth.proxy.trusted" "10.0.0.1,10.0.0.2"
                "auth.proxy.user-header" "x-forwarded-user"
                "auth.proxy.groups-header" "x-forwarded-groups"
                "groups.admin.members" "alice,bob"
                "http.port" "7357"}
        cfg (auth/config-from-values values)]
    (testing "the provider list parses in declared order"
      (is (= [:bearer :proxy-header] (:auth/providers cfg))))
    (testing "per-provider entries parse their EDN values under their name key"
      (is (= {:secret "env:CI" :groups ["ci"]}
             (get-in cfg [:auth/bearer "ci"])))
      (is (= "abc" (get-in cfg [:auth/static "alice" :password-hash]))))
    (testing "proxy settings collect"
      (is (= #{"10.0.0.1" "10.0.0.2"} (get-in cfg [:auth/proxy :trusted])))
      (is (= "x-forwarded-user" (get-in cfg [:auth/proxy :user-header]))))
    (testing "group membership collects"
      (is (= #{"alice" "bob"} (get-in cfg [:auth/groups "admin"]))))
    (testing "non-auth keys are ignored"
      (is (nil? (:http.port cfg))))))

(deftest oidc-verifies-rs256-bearer-jwts
  (let [kp   (.generateKeyPair (doto (java.security.KeyPairGenerator/getInstance "RSA")
                                 (.initialize 2048)))
        pub  ^java.security.interfaces.RSAPublicKey (.getPublic kp)
        b64u (fn [^bytes bs] (.encodeToString (.withoutPadding (java.util.Base64/getUrlEncoder)) bs))
        enc  (fn [m] (b64u (.getBytes (cheshire.core/generate-string m) "UTF-8")))
        sign (fn [claims]
               (let [h (enc {:alg "RS256" :typ "JWT" :kid "k1"})
                     p (enc claims)
                     body (str h "." p)
                     sig (doto (java.security.Signature/getInstance "SHA256withRSA")
                           (.initSign (.getPrivate kp))
                           (.update (.getBytes body "UTF-8")))]
                 (str body "." (b64u (.sign sig)))))
        jwk  {:kty "RSA" :kid "k1"
              :n (b64u (.toByteArray (.getModulus pub)))
              :e (b64u (.toByteArray (.getPublicExponent pub)))}
        now  1784700000
        config {:auth/providers [:oidc]
                :auth/oidc {:issuer "https://idp.test"
                            :jwks [jwk]
                            :groups-claim "roles"}}
        rid  (fn [token]
               (auth/resolve-identity config
                                      {:headers {"authorization" (str "Bearer " token)}}
                                      :now now))]
    (testing "a valid token resolves: sub + the configured groups claim"
      (let [id (rid (sign {:iss "https://idp.test" :sub "ada"
                           :exp (+ now 3600) :roles ["admin" "dev"]}))]
        (is (= "ada" (:web/sub id)) (pr-str id))
        (is (= #{"admin" "dev"} (:web/groups id)))
        (is (= :oidc (:web/provider id)))))
    (testing "expiry, wrong issuer, tampering, and garbage are all anonymous"
      (is (nil? (rid (sign {:iss "https://idp.test" :sub "ada" :exp (- now 10)}))))
      (is (nil? (rid (sign {:iss "https://evil.test" :sub "ada" :exp (+ now 3600)}))))
      (is (nil? (rid (str (sign {:iss "https://idp.test" :sub "ada"
                                 :exp (+ now 3600)}) "tampered"))))
      (is (nil? (rid "not-a-jwt"))))))
