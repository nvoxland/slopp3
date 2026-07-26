(ns slopp.api.cljs-test
  (:require [clojure.test :refer [deftest testing is]]
            [slopp.api.cljs :as cljs]
            [slopp.store :as store] [slopp.api :as api] [slopp.api.external :as external]))

(deftest parse-result-extracts-the-marked-edn
  (testing "reads the EDN after the SLOPP-CLJS-RESULT marker, ignoring other output"
    (let [out (str "Compiling client...\n"
                   "WARNING: abs already refers to ...\n"
                   "SLOPP-CLJS-RESULT {:warnings [{:type :undeclared-var :line 2 :ns \"app.widget\" :symbol \"foo\"}] :error nil}\n"
                   "done\n")]
      (is (= {:warnings [{:type :undeclared-var :line 2 :ns "app.widget" :symbol "foo"}]
              :error nil}
             (cljs/parse-result out)))))
  (testing "nil when the marker is absent (the runner JVM crashed before printing)"
    (is (nil? (cljs/parse-result "boom, no marker here\n")))
    (is (nil? (cljs/parse-result "")))))

(deftest anchor-warnings-names-the-owning-form
  (let [st (store/ingest (store/empty-store) 'app.widget
                         (str "(ns app.widget)\n"
                              "(defn greet [n] (undeclared-thing n))\n"))]
    (testing "a finding at a form's line anchors to that form + a snippet"
      (let [[a] (cljs/anchor-warnings
                 st [{:type :undeclared-var :line 2 :ns "app.widget"
                      :message "Use of undeclared Var app.widget/undeclared-thing"}])]
        (is (= 'app.widget/greet (:form a)) (pr-str a))
        (is (= "(defn greet [n] (undeclared-thing n))" (:at a)))
        (is (= "Use of undeclared Var app.widget/undeclared-thing" (:message a)))))
    (testing "an unresolvable ns/line keeps the message but carries no form anchor"
      (let [[a] (cljs/anchor-warnings st [{:type :x :line 99 :ns "nope.gone" :message "m"}])]
        (is (nil? (:form a)))
        (is (= "m" (:message a)))))))

(deftest ^:external compiles-a-clean-cljs-namespace-to-a-served-blob
  (let [sess (external/open!)]
    (try
      (api/deps-add! sess 'org.clojure/clojurescript {:mvn/version "1.11.132"}
                     :client true :prompt "the cljs compiler")
      (api/module-platform! sess "tc.client" :cljs :prompt "browser code")
      (api/ingest! sess 'tc.client
                   (str "(ns tc.client)\n"
                        "(defn greet [n] (str \"Hi \" n))\n"))
      (let [r (cljs/compile-client! sess :output "public/tc.js")]
        (testing "the client namespace compiles to a served JS blob (no Node)"
          (is (= 1 (:compiled r)) (pr-str r))
          (is (nil? (:error r)) (pr-str r))
          (is (pos? (or (:bytes r) 0)) (pr-str r))
          (is (string? (get-in @sess [:store :files "public/tc.js"])))))
      (finally (api/close! sess)))))

(deftest ^:external a-cljs-write-lands-unverified-not-refused
  (let [sess (external/open!)]
    (try
      ;; CONTROL — a :jvm namespace: js/* is genuinely unresolvable on the JVM,
      ;; so the write is REFUSED (the oracle cannot load it). This is the
      ;; behaviour that must stay for ordinary Clojure.
      (api/ingest! sess 'wp.server "(ns wp.server)\n(defn ok [] 1)\n")
      (is (:error (api/add-form! sess 'wp.server "(defn boom [] (js/alert \"hi\"))"))
          "a js/* form in a :jvm ns fails to load — refused")
      ;; a :cljs namespace: the SAME form LANDS, its verification deferred to
      ;; the cljs compiler (compile_client), reported :unverified with a reason.
      (api/module-platform! sess "wp.client" :cljs :prompt "browser code")
      (api/ingest! sess 'wp.client "(ns wp.client)\n")
      (let [r (api/add-form! sess 'wp.client "(defn boom [] (js/alert \"hi\"))"
                             :prompt "client click handler")]
        (is (nil? (:error r)) (pr-str r))
        (is (some? (:delta r)) (pr-str r))
        (is (some? (store/form-named (:store @sess) 'wp.client 'boom))
            "the js/* form is really in the store")
        (is (= :unverified (:status (:test r))) (pr-str (:test r)))
        (is (= :cljs-deferred-to-compile (:reason (:test r))) (pr-str (:test r))))
      ;; ingest! of a :cljs ns whose body ALREADY uses js/* also lands
      (api/module-platform! sess "wp.widget" :cljs :prompt "browser code")
      (let [r (api/ingest! sess 'wp.widget
                           "(ns wp.widget)\n(defn go [] (js/console.log \"x\"))\n")]
        (is (nil? (:error r)) (pr-str r))
        (is (= :cljs-deferred-to-compile (:reason (:test r))) (pr-str (:test r))))
      (finally (api/close! sess)))))

(deftest ^:external ns-create-with-a-platform-is-born-there
  (let [sess (external/open!)]
    (try
      (api/create-ns! sess 'wc.client :source "(ns wc.client)\n"
                      :platform :cljs :prompt "browser code")
      (testing "the platform is declared at creation, at the namespace grain"
        (is (= :cljs (store/platform-for (:store @sess) 'wc.client))))
      (testing "born :cljs, a js/* form lands straight away — no separate decl"
        (let [r (api/add-form! sess 'wc.client "(defn boom [] (js/alert \"hi\"))")]
          (is (nil? (:error r)) (pr-str r))
          (is (= :cljs-deferred-to-compile (:reason (:test r))) (pr-str (:test r)))))
      (finally (api/close! sess)))))

(deftest ^:external auto-compile-recompiles-the-client-bundle-on-write
  (let [sess (external/open!)]
    (try
      (api/deps-add! sess 'org.clojure/clojurescript {:mvn/version "1.11.132"}
                     :client true :prompt "the cljs compiler")
      (api/module-platform! sess "ac.client" :cljs :prompt "browser code")
      (api/ingest! sess 'ac.client "(ns ac.client)\n")
      (testing "auto-compile OFF (default): a client write does NOT recompile"
        (let [r (api/add-form! sess 'ac.client "(defn a [] (js/alert \"a\"))")]
          (is (nil? (:client-recompiling r)) (pr-str r))
          (is (nil? (get-in @sess [:store :files "public/cljs/main.js"]))
              "no bundle written yet")))
      (testing "auto-compile ON: a client write schedules an ASYNC recompile"
        (api/config-file! sess "client" :key "auto-compile" :value "true"
                          :prompt "dev loop")
        (let [r (api/add-form! sess 'ac.client "(defn b [] (js/alert \"b\"))")]
          (is (true? (:client-recompiling r)) (pr-str r))
          ;; async: the background compile commits the served bundle shortly after
          (let [blob (loop [n 0]
                       (or (get-in @sess [:store :files "public/cljs/main.js"])
                           (when (< n 120)
                             (Thread/sleep 500)
                             (recur (inc n)))))]
            (is (string? blob) "fresh bundle served within timeout"))))
      (finally (api/close! sess)))))

(deftest ^:external edit-rename-handles-a-cljs-form
  (let [sess (external/open!)]
    (try
      (api/create-ns! sess 'rf.client
                      :source "(ns rf.client)\n(defn boom [] (js/alert \"hi\"))\n"
                      :platform :cljs :prompt "browser code")
      (testing "edit_rename renames a :cljs form (js/* — never loaded on the JVM)"
        (let [r (api/rename! sess 'rf.client 'boom 'kaboom)]
          (is (nil? (:error r)) (pr-str r))
          (is (some? (store/form-named (:store @sess) 'rf.client 'kaboom))
              "renamed form is present")
          (is (nil? (store/form-named (:store @sess) 'rf.client 'boom))
              "old name is gone")))
      (finally (api/close! sess)))))

(deftest ^:external edit-move-forms-handles-cljs-forms
  (let [sess (external/open!)]
    (try
      (api/create-ns! sess 'mvc.a
                      :source "(ns mvc.a)\n(defn ping [] (js/alert \"a\"))\n"
                      :platform :cljs :prompt "browser code")
      (api/create-ns! sess 'mvc.b
                      :source "(ns mvc.b)\n(defn other [] (js/console.log \"b\"))\n"
                      :platform :cljs :prompt "browser code")
      (testing "edit_move_forms moves a :cljs form between :cljs namespaces"
        (let [r (api/move-forms! sess 'mvc.a '[ping] 'mvc.b)]
          (is (nil? (:error r)) (pr-str r))
          (is (some? (store/form-named (:store @sess) 'mvc.b 'ping))
              "moved into the target")
          (is (nil? (store/form-named (:store @sess) 'mvc.a 'ping))
              "gone from the source")))
      (finally (api/close! sess)))))

(deftest client-wrapper-specs-resolves-endpoints-and-schemas
  (let [st (-> (store/empty-store)
               (store/ingest 'shop.contracts
                             "(ns shop.contracts)\n\n(def order [:map [:item :string] [:qty :int]])\n"))
        st (first (store/record-module-platform st "shop.contracts" :cljc))
        st (store/ingest st 'shop.api
                         (str "(ns shop.api)\n\n"
                              "(defn ^{:web/method :post :web/path \"/api/orders\""
                              " :web/request shop.contracts/order :web/response shop.contracts/order}"
                              " create-order [req] req)\n\n"
                              "(defn ^{:web/method :get :web/path \"/api/orders/:id\""
                              " :web/response shop.contracts/order} get-order [req] req)\n"))
        {:keys [wrappers problems]} (cljs/client-wrapper-specs st)]
    (testing "one wrapper per endpoint; mutating verbs get a ! suffix"
      (is (= '[create-order! get-order] (mapv :fn-name wrappers)))
      (is (= [:post :get] (mapv :method wrappers))))
    (testing "an endpoint already named with ! does not get a second one"
      ;; slopp's own store hit this: POST /call is `call-endpoint!` and
      ;; generated `call-endpoint!!`. Naming a mutating endpoint with a bang
      ;; is the DIALECT'S OWN convention, so the generator meeting that
      ;; convention with a double bang is it fighting the house style.
      (let [st2 (store/ingest st 'shop.api2
                              (str "(ns shop.api2)\n\n"
                                   "(defn ^{:web/method :post :web/path \"/api/pay\""
                                   " :web/request shop.contracts/order"
                                   " :web/response shop.contracts/order}"
                                   " pay! [req] req)\n"))
            names (mapv :fn-name (:wrappers (cljs/client-wrapper-specs st2)))]
        (is (some #{'pay!} names) (pr-str names))
        (is (not (some #{'pay!!} names)) (pr-str names))))
    (testing "schema refs resolve to fully-qualified vars in the :cljc contracts ns"
      (is (= 'shop.contracts/order (get-in (first wrappers) [:request :sym])))
      (is (= 'shop.contracts/order (get-in (first wrappers) [:response :sym])))
      (is (= :none (get-in (second wrappers) [:request :kind])) "a GET has no request schema"))
    (testing "the source endpoint rides each spec as provenance"
      (is (= 'shop.api/create-order (:endpoint (first wrappers)))))
    (testing "a clean fixture yields no problems"
      (is (empty? problems) (pr-str problems)))))

(deftest client-wrapper-specs-flags-non-cljc-schemas
  (let [st (-> (store/empty-store)
               (store/ingest 'shop.contracts
                             "(ns shop.contracts)\n\n(def order [:map [:item :string]])\n")
               ;; platform LEFT :jvm — a jvm-only schema cannot ship to the client
               (store/ingest 'shop.api
                             (str "(ns shop.api)\n\n"
                                  "(defn ^{:web/method :post :web/path \"/api/orders\""
                                  " :web/request shop.contracts/order :web/response shop.contracts/order}"
                                  " create-order [req] req)\n")))
        {:keys [wrappers problems]} (cljs/client-wrapper-specs st)]
    (testing "an endpoint whose schema ns is not :cljc becomes a problem; its wrapper is skipped"
      (is (empty? wrappers) (pr-str wrappers))
      (is (= 1 (count problems)) (pr-str problems))
      (is (= :not-cljc (:issue (first problems))))
      (is (= 'shop.contracts/order (:schema-ref (first problems)))))))

(deftest render-client-ns-emits-typed-wrappers
  (let [src (cljs/render-client-ns
             'shop.client.api
             [{:fn-name 'create-order! :method :post :path "/api/orders"
               :endpoint 'shop.api/create-order
               :request  {:kind :var :sym 'shop.contracts/order :ns 'shop.contracts}
               :response {:kind :var :sym 'shop.contracts/order :ns 'shop.contracts}}
              {:fn-name 'get-order :method :get :path "/api/orders/:id"
               :endpoint 'shop.api/get-order
               :request  {:kind :none}
               :response {:kind :var :sym 'shop.contracts/order :ns 'shop.contracts}}])]
    (testing "one ns form plus one defn per wrapper (structural parse is checked end-to-end at ingest)"
      (is (re-find #"\(ns shop\.client\.api" src) src)
      (is (= 2 (count (re-seq #"\(defn " src))))
      (is (= (count (re-seq #"\(" src)) (count (re-seq #"\)" src))) "balanced parens"))
    (testing "the ns requires malli + the schema's contracts ns"
      (is (re-find #"malli\.core" src))
      (is (re-find #"malli\.transform" src))
      (is (re-find #"shop\.contracts" src)))
    (testing "each wrapper carries its ^:generated provenance, is ^:export, and fetches"
      (is (re-find #"\^\{:generated \"shop\.api/create-order\"\}" src))
      (is (re-find #"\^\{:generated \"shop\.api/get-order\"\}" src))
      (is (re-find #":export" src))
      (is (re-find #"js/fetch" src)))
    (testing "request validation on a body verb; response validation both; path param substituted"
      (is (re-find #"m/validate shop\.contracts/order params" src) "request validated out")
      (is (re-find #"m/decode shop\.contracts/order" src) "response decoded in")
      (is (re-find #"\(str \"/api/orders/\" \(:id params\)\)" src) "path param interpolated"))))

(deftest ^:external generate-client-writes-a-protected-cljs-namespace
  (let [sess (external/open!)]
    (try
      (api/ingest! sess 'shopg.contracts
                   "(ns shopg.contracts)\n\n(def order [:map [:item :string] [:qty :int]])\n")
      (api/module-platform! sess "shopg.contracts" "cljc" :prompt "shared contract")
      (api/ingest! sess 'shopg.api
                   (str "(ns shopg.api)\n\n"
                        "(defn ^{:web/method :post :web/path \"/api/orders\""
                        " :web/request shopg.contracts/order :web/response shopg.contracts/order}"
                        " create-order \"Create an order.\" [req] req)\n"))
      (let [r (cljs/generate-client! sess :ns 'shopg.client.api)]
        (testing "one wrapper per endpoint, written into a stored :cljs namespace"
          (is (= 'shopg.client.api (:generated r)) (pr-str r))
          (is (= 1 (:endpoints r)))
          (is (= ["create-order!"] (:wrappers r)))
          (is (= :cljs (store/platform-for (:store @sess) 'shopg.client.api)))
          (is (some? (store/form-named (:store @sess) 'shopg.client.api 'create-order!))))
        (testing "no shippable-schema problems for a clean :cljc contract"
          (is (nil? (:problems r)) (pr-str (:problems r)))))
      (testing "the generated form refuses a hand edit — the protection gate is wired end to end"
        (let [r (api/edit-replace! sess 'shopg.client.api 'create-order!
                                   (str "(defn ^{:generated \"shopg.api/create-order\"} ^:export create-order!"
                                        " \"x\" [params] :hacked)")
                                   :prompt "try to hand-edit the generated wrapper")]
          (is (re-find #"generate_client" (str (:error r))) (pr-str r))))
      (finally (api/close! sess)))))

(deftest client-wrapper-specs-honors-the-client-opt-out
  ;; Dogfood finding: an HTML page is a :web/path form like any other, so
  ;; generate_client emitted a typed fetch wrapper for it — one whose
  ;; (.json resp) can never succeed on HTML. Sniffing the response schema would
  ;; be the wrong fix (:string is a legitimate JSON response), so the endpoint
  ;; declares it: ^{:web/client false} opts out of client generation.
  (let [st (-> (store/empty-store)
               (store/ingest 'pg.api
                             (str "(ns pg.api)\n\n"
                                  "(defn ^{:web/method :get :web/path \"/\" :web/auth :public"
                                  " :web/response :string :web/client false}"
                                  " home \"The page.\" [r] r)\n\n"
                                  "(defn ^{:web/method :get :web/path \"/api/x\" :web/auth :public"
                                  " :web/response :map} data \"Data.\" [r] r)\n")))
        {:keys [wrappers problems]} (cljs/client-wrapper-specs st)]
    (testing "the page opts out; the JSON endpoint still gets its wrapper"
      (is (= '[data] (mapv :fn-name wrappers))))
    (testing "opting out is not a problem to report — it is a declaration"
      (is (empty? problems) (pr-str problems)))))

(deftest ^:external compiling-a-bundle-says-how-to-serve-it
  ;; The bundle existed in the files manifest from the wave that added it, and
  ;; every page 404'd on it for two more, because serving it needs an
  ;; http.static.* mount and nothing said so. Serving it IS one config line —
  ;; the gap was never capability, it was that the line was undiscoverable.
  ;;
  ;; Discoverability lives in the RESULT, not in a doc someone might read:
  ;; the tool that wrote the file names the mount that would serve it, and
  ;; says nothing once one exists.
  (let [sess (external/open!)]
    (try
      (api/deps-add! sess 'org.clojure/clojurescript {:mvn/version "1.11.132"}
                     :client true :prompt "the cljs compiler")
      (api/module-platform! sess "sv.client" :cljs :prompt "browser code")
      (api/ingest! sess 'sv.client "(ns sv.client)\n(defn greet [n] (str \"Hi \" n))\n")
      (testing "nothing serves the output yet, so the result says how"
        (let [r (cljs/compile-client! sess :output "public/cljs/main.js")]
          (is (nil? (:error r)) (pr-str r))
          (is (re-find #"http\.static\." (str (:serve-with r)))
              (str "expected the mount line: " (pr-str r)))
          (is (re-find #"config_file" (str (:serve-with r))) (pr-str r))))
      (testing "once a mount covers it, the hint goes away"
        ;; repeating advice already taken is how a result becomes noise
        (api/config-file! sess "capabilities" :key "http.static./js"
                          :value "public/cljs" :prompt "serve the bundle")
        (let [r (cljs/compile-client! sess :output "public/cljs/main.js")]
          (is (nil? (:serve-with r)) (pr-str r))))
      (finally (api/close! sess)))))

(deftest a-hard-compile-error-anchors-like-a-warning
  ;; Core 6: verification stops at the boundary. Analyzer WARNINGS cross the
  ;; cljs compile beautifully — anchor-warnings turns {:ns :line} into a form
  ;; and a snippet, so a cljs warning reads like a clj compile error. A hard
  ;; FAILURE crossed as `failed compiling file:cljs-src/slopp/ui/client/app.cljs`:
  ;; a path into a temp directory the agent never created, with no message, no
  ;; form and no line. It also breaks slopp's own standing invariant that no
  ;; file:line ever reaches the agent.
  ;;
  ;; The information exists — ClojureScript throws ex-data carrying the file
  ;; and line. The runner was dropping it on the floor.
  (let [st (-> (store/empty-store)
               (store/ingest 'app.view
                             "(ns app.view)\n\n(defn a [] 1)\n\n(defn b [] (a))\n"))
        st (first (store/record-module-platform st "app.view" :cljs))]
    (testing "a located error becomes a form anchor, not a path"
      (let [r (cljs/anchor-error st "Wrong number of args passed to defonce"
                                 {:file "cljs-src/app/view.cljs" :line 5})]
        (is (= 'app.view/b (:form r)) (pr-str r))
        (is (= "(defn b [] (a))" (:at r)))
        (is (= "Wrong number of args passed to defonce" (:error r)))
        (testing "and no file path survives into the result"
          (is (not (re-find #"cljs-src|\.cljs" (pr-str r))) (pr-str r)))))
    (testing "the compiler's own message is stripped of paths and line numbers"
      ;; this is what a real one looks like, and it repeats the temp-dir path
      ;; TWICE plus a line number — all of which :form and :at now carry
      ;; properly. Leaving them keeps slopp's no-file:line invariant broken in
      ;; the one field the agent actually reads.
      (let [r (cljs/anchor-error
               st
               (str "failed compiling file:cljs-src/app/view.cljs"
                    " / Wrong number of args (3) passed to: cljs.core/defonce"
                    " at line 5 cljs-src/app/view.cljs")
               {:file "cljs-src/app/view.cljs" :line 5})]
        (is (= 'app.view/b (:form r)))
        (is (not (re-find #"cljs-src|\.cljs|at line" (:error r))) (:error r))
        (testing "while the part that says what is WRONG survives intact"
          (is (re-find #"Wrong number of args \(3\) passed to: cljs\.core/defonce"
                       (:error r))))))
    (testing "an underscore in a path is a hyphen in a namespace"
      ;; the munging is the whole reason this needs a function rather than a
      ;; string replace at the call site
      (let [st2 (-> (store/empty-store)
                    (store/ingest 'app.my-view "(ns app.my-view)\n\n(defn a [] 1)\n"))
            st2 (first (store/record-module-platform st2 "app.my-view" :cljs))]
        (is (= 'app.my-view/a
               (:form (cljs/anchor-error st2 "boom"
                                         {:file "cljs-src/app/my_view.cljs" :line 3}))))))
    (testing "an unlocatable error still carries its message, and says no more"
      ;; Core 1: never let \"could not anchor\" and \"anchored fine\" look alike
      (let [r (cljs/anchor-error st "something went wrong" nil)]
        (is (= "something went wrong" (:error r)))
        (is (nil? (:form r)))
        (is (nil? (:at r))))
      (let [r (cljs/anchor-error st "boom" {:file "cljs-src/nope/gone.cljs" :line 2})]
        (is (= "boom" (:error r)))
        (is (nil? (:form r)) "a file with no matching store namespace anchors nothing")))))
