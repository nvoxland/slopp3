(ns slopp.edit.modules-test
  "Gates exercised as PURE FUNCTIONS of a store value.

  Every check in `slopp.edit.modules` takes a candidate store and returns a
  teaching string or nil, so these tests build a small store with
  `store/ingest`, flip the capabilities that arm a rule, and call the gate
  directly — no write path, no image, no server. That is what keeps them fast
  and what makes a refusal's WORDING testable: several assertions here check
  the teaching, not just that something fired, because the string is the
  entire user experience of a gate.

  The write path itself — that a refusal actually blocks a write, and that
  the per-store dial downgrades it — is `slopp.modules-test`'s job."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.edit.web :as web]))

(deftest web-gates-guard-the-declared-surface
  (let [src (str "(ns shop.api)\n\n"
                 "(defn ^{:web/method :get :web/path \"/api/users/:id\"\n"
                 "        :web/auth [:group \"admin\"]} get-user \"U.\" [req] req)\n\n"
                 "(defn ^{:web/effect :user/insert} insert-user! \"I.\" [ctx row] row)\n")
        s0  (store/ingest (store/empty-store) 'shop.api src)
        on  (first (store/record-config-put s0 "capabilities" :manifest "web.enabled" "true"))
        land (fn [st form-src]
               (store/ingest st 'shop.more (str "(ns shop.more)\n\n" form-src "\n")))]
    (testing "OFF: no web gate fires while web.enabled is absent"
      (let [s (land s0 "(defn ^{:web/method :get :web/path \"/x\"} bare \"B.\" [req] req)")]
        (is (nil? (web/web-auth-refusal s 'shop.more 'bare)))))
    (testing "web-auth-refusal: an endpoint with no :web/auth refuses with teaching"
      (let [s (land on "(defn ^{:web/method :get :web/path \"/x\"} bare \"B.\" [req] req)")]
        (is (re-find #":web/auth" (str (web/web-auth-refusal s 'shop.more 'bare))))
        (testing "a declared :public discharges it — deny is the default, not the ceiling"
          (let [s2 (land on "(defn ^{:web/method :get :web/path \"/x\" :web/auth :public} open \"O.\" [req] req)")]
            (is (nil? (web/web-auth-refusal s2 'shop.more 'open)))))
        (testing "a non-endpoint never trips it"
          (is (nil? (web/web-auth-refusal s 'shop.api 'insert-user!))))))
    (testing "web-route-collision: a second claim on method+path refuses; the same form re-landing does not"
      (let [s (land on "(defn ^{:web/method :get :web/path \"/api/users/:id\" :web/auth :public} dupe \"D.\" [req] req)")]
        (is (re-find #"/api/users/:id" (str (web/web-route-collision s 'shop.more 'dupe))))
        (is (nil? (web/web-route-collision on 'shop.api 'get-user))
            "a form is never its own collision (the re-land/replace case)")
        (testing "same path, different method, no collision"
          (let [s2 (land on "(defn ^{:web/method :post :web/path \"/api/users/:id\" :web/auth :public} other \"O.\" [req] req)")]
            (is (nil? (web/web-route-collision s2 'shop.more 'other)))))))
    (testing "web-undeclared-effect: a declared kind needs a marked performer"
      (let [s (land on (str "(defn ^{:web/method :post :web/path \"/y\" :web/auth :public\n"
                            "        :web/effects [:user/insert :email/welcome]} mk \"M.\" [req] req)"))]
        (is (re-find #":email/welcome" (str (web/web-undeclared-effect s 'shop.more 'mk))))
        (testing "every kind covered → clean"
          (let [s2 (land on (str "(defn ^{:web/method :post :web/path \"/y\" :web/auth :public\n"
                                 "        :web/effects [:user/insert]} mk2 \"M.\" [req] req)"))]
            (is (nil? (web/web-undeclared-effect s2 'shop.more 'mk2)))))))
    (testing "web-unsafe-get: a GET declaring effect kinds refuses; a POST doing the same is fine"
      (let [s (land on (str "(defn ^{:web/method :get :web/path \"/z\" :web/auth :public\n"
                            "        :web/effects [:user/insert]} gz \"G.\" [req] req)"))]
        (is (re-find #"GET" (str (web/web-unsafe-get s 'shop.more 'gz))))
        (let [s2 (land on (str "(defn ^{:web/method :post :web/path \"/z\" :web/auth :public\n"
                               "        :web/effects [:user/insert]} pz \"P.\" [req] req)"))]
          (is (nil? (web/web-unsafe-get s2 'shop.more 'pz))))))
    (testing "web-unsafe-get: a GET whose handler reaches a mutation refuses"
      (let [s (land on (str "(def store-atom (atom {}))\n\n"
                            "(defn ^{:web/method :get :web/path \"/w\" :web/auth :public} gw \"G.\" [req]\n"
                            "  (swap! store-atom assoc :hit req))"))]
        (is (re-find #"mutation" (str (web/web-unsafe-get s 'shop.more 'gw))))))))

(deftest web-unknown-group-guards-the-policy-vocabulary
  (let [s0 (store/ingest (store/empty-store) 'shop.api "(ns shop.api)\n\n(defn seed \"S.\" [x] x)\n")
        on (-> s0
               (store/record-config-put "capabilities" :manifest "web.enabled" "true") first
               (store/record-config-put "capabilities" :manifest "web.auth.groups.admin.members" "alice") first)
        land (fn [st form-src]
               (store/ingest st 'shop.more (str "(ns shop.more)\n\n" form-src "\n")))]
    (testing "a policy naming a configured group lands"
      (let [s (land on "(defn ^{:web/method :get :web/path \"/a\" :web/auth [:group \"admin\"]} a \"A.\" [req] req)")]
        (is (nil? (web/web-unknown-group s 'shop.more 'a)))))
    (testing "a typo'd group refuses with the configured vocabulary in the teaching"
      (let [s (land on "(defn ^{:web/method :get :web/path \"/b\" :web/auth [:group \"admn\"]} b \"B.\" [req] req)")]
        (is (re-find #"admn" (str (web/web-unknown-group s 'shop.more 'b))))
        (is (re-find #"admin" (str (web/web-unknown-group s 'shop.more 'b))))))
    (testing "composite policies are walked"
      (let [s (land on "(defn ^{:web/method :get :web/path \"/c\" :web/auth [:any :authenticated [:group \"ghost\"]]} c \"C.\" [req] req)")]
        (is (re-find #"ghost" (str (web/web-unknown-group s 'shop.more 'c))))))
    (testing "inert until web.enabled"
      (let [s (land s0 "(defn ^{:web/method :get :web/path \"/d\" :web/auth [:group \"ghost\"]} d \"D.\" [req] req)")]
        (is (nil? (web/web-unknown-group s 'shop.more 'd)))))))

(deftest generated-ns-gate-refuses-hand-edits
  ;; the second duty of ^:generated (D-web-contracts part 2): a write gate
  ;; refuses HAND edits to a generated form. Regeneration rewrites the ns
  ;; wholesale through store/ingest (below the gate layer), so the generator
  ;; itself is unaffected — only edit-tool writes reach this gate.
  (let [st (-> (store/empty-store)
               (store/ingest 'gc.client
                             (str "(ns gc.client)\n\n"
                                  "(defn ^{:generated \"app.orders/create-order\"} create-order! [x] x)\n\n"
                                  "(defn hand [x] x)\n")))]
    (testing "editing a ^:generated form refuses, teaching to regenerate instead"
      (let [t (web/generated-ns st 'gc.client 'create-order!)]
        (is (string? t) (pr-str t))
        (is (re-find #"generate_client" t))))
    (testing "a normal form is untouched by the gate"
      (is (nil? (web/generated-ns st 'gc.client 'hand))))))

(deftest client-signature-tracks-endpoint-contracts
  ;; the fingerprint behind the generated-client staleness advisory
  ;; (D-web-contracts part 2): it changes iff an endpoint's declared contract
  ;; changes, so the advisory can nudge "run generate_client" without re-rendering.
  (let [mk (fn [resp] (store/ingest (store/empty-store) 'sig.api
                                    (str "(ns sig.api)\n\n"
                                         "(defn ^{:web/method :post :web/path \"/o\""
                                         " :web/request sig.c/a :web/response " resp "} make [r] r)\n")))]
    (testing "stable for identical contracts"
      (is (= (web/client-signature (mk "sig.c/a"))
             (web/client-signature (mk "sig.c/a")))))
    (testing "changes when a response contract changes"
      (is (not= (web/client-signature (mk "sig.c/a"))
                (web/client-signature (mk "sig.c/b")))))
    (testing "an endpointless store still fingerprints (a stable string)"
      (is (string? (web/client-signature (store/empty-store)))))))

(deftest the-deps-a-handler-reads-must-have-a-declared-source
  (let [s0   (store/ingest (store/empty-store) 'shop.api "(ns shop.api)\n")
        on   (first (store/record-config-put s0 "capabilities" :manifest "web.enabled" "true"))
        with-builder (store/ingest on 'shop.sys
                                   (str "(ns shop.sys)\n\n"
                                        "(defn ^{:web/context true} app-context \"C.\" [] {:registry :r})\n"))
        land (fn [st form-src]
               (store/ingest st 'shop.more (str "(ns shop.more)\n\n" form-src "\n")))
        endpoint (fn [body]
                   (str "(defn ^{:web/method :get :web/path \"/x\" :web/auth :public} h \"H.\" [req] "
                        body ")"))]
    (testing "OFF: inert while web.enabled is absent"
      (is (nil? (web/web-undeclared-context (land s0 (endpoint "(:web/deps req)"))
                                                'shop.more 'h))))
    (testing "an endpoint reading :web/deps with no builder refuses, naming the marker"
      (let [teach (str (web/web-undeclared-context (land on (endpoint "(:web/deps req)"))
                                                       'shop.more 'h))]
        (testing "the fix is a LITERAL FORM, not a description of one — cold-read
                  evidence says that is what made it actionable without the skill:
                  the marker spelling, the arity, defn-not-def and the return
                  shape all come off it at once"
          (is (re-find #"\(defn \^\{:web/context true\}" teach) teach)
          (is (re-find #"ONE" teach) "and that a second is not allowed"))
        (testing "it does NOT argue that the context cannot be a performer"
          ;; the right sentence in the wrong room. It answers a DESIGN question
          ;; to a reader in fix-it mode who has already been handed the form,
          ;; and it is the one clause that needs knowing what a performer IS.
          ;; It lives in api.web/context-builder's docstring and the SKILL,
          ;; where someone DECIDING meets it.
          (is (not (re-find #"performer" teach)) teach))
        (testing "and the lifecycle line is true for the store being refused"
          ;; this gate fires on any web.enabled store, including one with
          ;; dev.server false where no managed server boots at all. A clause
          ;; phrased around done points asserts, to that reader, a behaviour
          ;; that does not happen to them — a general truth in this store's
          ;; voice, which is the shape that keeps costing us.
          (is (not (re-find #"done point|managed server" teach)) teach)
          (is (re-find #"new each time" teach) teach))))
    (testing ":web/keys destructuring is the same read"
      (let [s (land on (str "(defn ^{:web/method :get :web/path \"/x\" :web/auth :public} h \"H.\"\n"
                            "  [{:web/keys [deps]}] deps)"))]
        (is (re-find #":web/context" (str (web/web-undeclared-context s 'shop.more 'h))))))
    (testing "a declared builder discharges it"
      (is (nil? (web/web-undeclared-context (land with-builder (endpoint "(:web/deps req)"))
                                                'shop.more 'h))))
    (testing "an endpoint that never reads deps is not asked to declare a source"
      (is (nil? (web/web-undeclared-context (land on (endpoint "req")) 'shop.more 'h))))
    (testing "a NON-endpoint naming :web/deps is the framework's own dispatcher, not an app handler"
      (let [s (land on "(defn dispatch! \"D.\" [ctx req] (assoc req :web/deps (:web/perform-ctx ctx)))")]
        (is (nil? (web/web-undeclared-context s 'shop.more 'dispatch!)))))))
