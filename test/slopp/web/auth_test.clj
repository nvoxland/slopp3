(ns slopp.web.auth-test
  "Identity, and the config that produces it.

  Two halves that fail differently. The first is CONFIG PARSING — the
  `auth.*` capability family becomes an auth config, and a store's typed
  values are the only input, so a rename or a re-typing of that family shows
  up here before it shows up in a request. That test also pins the negative:
  a non-auth key in the same map is IGNORED rather than absorbed.

  The second is the PROVIDERS themselves — static passwords, bearer tokens,
  a trusted proxy header, OIDC — and each is tested against the way it is
  attacked rather than the way it is used: passwords salted and iterated, a
  proxy header matched case-insensitively because a proxy may send any
  casing, an RS256 JWT verified against a key pair generated in the test, and
  an OIDC config REFUSED when it names no audience. The signing helpers are
  local on purpose; a fake signer would only prove the fake agrees with
  itself.

  That is the shape to keep when adding a provider: the assertion that
  matters is usually the one expecting anonymity, because an auth check that
  silently accepts is indistinguishable from one that works."
  (:require [clojure.test :refer [deftest testing is]]
            [slopp.web.auth :as auth]
            [cheshire.core]))

(deftest providers-resolve-identity
  (let [config {:auth/providers [:bearer :static :proxy-header]
                :auth/bearer {"ci" {:secret "env:T_CI_TOKEN" :groups ["ci"]}}
                :auth/static {"alice" {:password-hash (auth/hash-password "s3cret")
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
                "web.port" "7357"}
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
      (is (nil? (:web.port cfg))))))

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
                            :audience "slopp-app"      ; audience is mandatory (W2)
                            :jwks [jwk]
                            :groups-claim "roles"}}
        rid  (fn [token]
               (auth/resolve-identity config
                                      {:headers {"authorization" (str "Bearer " token)}}
                                      :now now))]
    (testing "a valid token (matching aud) resolves: sub + the configured groups claim"
      (let [id (rid (sign {:iss "https://idp.test" :sub "ada" :aud "slopp-app"
                           :exp (+ now 3600) :roles ["admin" "dev"]}))]
        (is (= "ada" (:web/sub id)) (pr-str id))
        (is (= #{"admin" "dev"} (:web/groups id)))
        (is (= :oidc (:web/provider id)))))
    (testing "expiry, wrong issuer, wrong audience, tampering, and garbage are all anonymous"
      (is (nil? (rid (sign {:iss "https://idp.test" :sub "ada" :aud "slopp-app" :exp (- now 10)}))))
      (is (nil? (rid (sign {:iss "https://evil.test" :sub "ada" :aud "slopp-app" :exp (+ now 3600)}))))
      (is (nil? (rid (sign {:iss "https://idp.test" :sub "ada" :aud "OTHER-app" :exp (+ now 3600)}))))
      (is (nil? (rid (str (sign {:iss "https://idp.test" :sub "ada" :aud "slopp-app"
                                 :exp (+ now 3600)}) "tampered"))))
      (is (nil? (rid "not-a-jwt"))))))

(deftest proxy-header-lookup-is-case-insensitive
  ;; review W7: adapters lowercase all request header names, but an operator
  ;; naturally configures `auth.proxy.user-header = X-Forwarded-User`, stored
  ;; verbatim — so the lookup missed the lowercased key and the trusted-proxy
  ;; provider was silently non-functional (fails closed, but broken).
  (let [config {:auth/providers [:proxy-header]
                :auth/proxy {:trusted #{"10.0.0.1"}
                             ;; CANONICAL casing, as a human writes it
                             :user-header "X-Forwarded-User"
                             :groups-header "X-Forwarded-Groups"}}
        req    {:remote-addr "10.0.0.1"
                ;; adapters deliver header names lowercased
                :headers {"x-forwarded-user" "alice"
                          "x-forwarded-groups" "dev,sre"}}
        id     (auth/resolve-identity config req)]
    (testing "a canonically-cased header config still resolves the lowercased request header"
      (is (= "alice" (:web/sub id)) (pr-str id))
      (is (= #{"dev" "sre"} (:web/groups id))))))

(deftest passwords-are-salted-and-iterated
  ;; review W6: static passwords were unsalted single-round SHA-256, stored
  ;; as password-hash in the git-projected capabilities config — trivially
  ;; crackable offline. A salted, iterated KDF (PBKDF2, JDK/native-safe) is
  ;; the real fix; the same password hashes DIFFERENTLY each time.
  (testing "the same password yields DIFFERENT hashes (random per-hash salt)"
    (is (not= (auth/hash-password "s3cret") (auth/hash-password "s3cret"))))
  (testing "the stored format is not a bare sha-256 hex (48/64-char) digest"
    (is (re-find #"^pbkdf2\$" (auth/hash-password "s3cret"))))
  (testing "verify-password round-trips the right password and rejects the wrong one"
    (let [h (auth/hash-password "s3cret")]
      (is (auth/verify-password "s3cret" h))
      (is (not (auth/verify-password "wrong" h)))
      (is (not (auth/verify-password "s3cret" "pbkdf2$1$AAAA$BBBB")))))
  (testing "a malformed stored hash rejects, never throws"
    (is (not (auth/verify-password "x" "garbage")))
    (is (not (auth/verify-password "x" nil)))))

(deftest oidc-requires-a-configured-audience
  ;; review W2: when auth.oidc.audience was unset, verify-jwt accepted ANY
  ;; validly-signed unexpired token from the issuer — incl. one minted for a
  ;; different client (confused-deputy / cross-audience replay). Audience
  ;; validation is mandatory for a resource server: unset → deny; set → the
  ;; token's aud must match.
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
        rid  (fn [config token]
               (auth/resolve-identity config
                                      {:headers {"authorization" (str "Bearer " token)}}
                                      :now now))
        base {:auth/providers [:oidc]
              :auth/oidc {:issuer "https://idp.test" :jwks [jwk]}}
        tok  (fn [aud] (sign {:iss "https://idp.test" :sub "ada"
                              :exp (+ now 3600) :aud aud}))]
    (testing "audience UNSET → a valid, correctly-signed token is DENIED"
      (is (nil? (rid base (tok "any-app")))))
    (testing "audience SET → only a matching aud resolves"
      (let [config (assoc-in base [:auth/oidc :audience] "my-app")]
        (is (some? (rid config (tok "my-app"))))
        (is (nil? (rid config (tok "other-app"))))))))
