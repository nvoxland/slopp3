(ns slopp.api.cljs-test
  "Tests for the ClojureScript path — the one place slopp's oracle cannot
  reach.

  Everything else here is verified by RUNNING it in the image. Client code
  cannot be: there is no JS runtime in the loop, so the COMPILER stands in as
  the oracle, and these tests exist to hold that substitute honest. They
  check what a compile produces, that a failure anchors to a real form rather
  than to a line number in generated output, where the bytes land, and that
  the loop around it — recompile on write, a mount that actually serves the
  result — behaves.

  They are `^:external` and genuinely slow: each shells a fresh JVM and runs
  a real compile. That cost is the point. A faked compiler would leave the
  only unverifiable layer in slopp verified by something that cannot fail."
  (:require [clojure.test :refer [deftest testing is]]
            [slopp.api.cljs :as cljs]
            [slopp.store :as store] [slopp.api :as api] [slopp.api.external :as external] [slopp.store.artifacts :as artifacts] [slopp.store.render :as render] [clojure.string :as str] [slopp.web.client :as client]))

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
  (let [st   (store/ingest (store/empty-store) 'app.widget
                           (str "(ns app.widget)\n"
                                "(defn greet [n] (undeclared-thing n))\n"))
        ;; derived: rendering synthesizes the space between forms, so a
        ;; literal line here encodes one renderer version and goes red on the
        ;; next. The compiler reports against render output, so ask it.
        line (->> (str/split-lines (render/render-ns st 'app.widget))
                  (keep-indexed (fn [i l]
                                  (when (str/includes? l "undeclared-thing") (inc i))))
                  first)]
    (testing "a finding at a form's line anchors to that form + a snippet"
      (let [[a] (cljs/anchor-warnings
                 st [{:type :undeclared-var :line line :ns "app.widget"
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
          ;; derived, so it lands in :artifacts — sha and recipe in the store, bytes
          ;; on disk. The old assertion looked in :files, where a 2MB bundle used
          ;; to sit inline in a delta on every compile.
          (let [entry (get-in @sess [:store :artifacts "public/tc.js"])]
            (is (string? (:sha entry)) (pr-str entry))
            (is (= {:kind :build :tool "compile_client"} (:recipe entry)))
            (is (nil? (get-in @sess [:store :files "public/tc.js"]))
                "and NOT on the files manifest — one path, one manifest")
            (is (.exists (artifacts/cache-file (:dir @sess) (:sha entry)))
                "the bytes are on disk under their sha")))
        (testing "recompiling RECLAIMS the bundle it supersedes"
          (let [old (get-in @sess [:store :artifacts "public/tc.js" :sha])]
            (api/add-form! sess 'tc.client "(defn shout [n] (str \"HI \" n))")
            (cljs/compile-client! sess :output "public/tc.js")
            (let [new-sha (get-in @sess [:store :artifacts "public/tc.js" :sha])]
              (is (not= old new-sha) "the bundle really changed")
              (is (not (.exists (artifacts/cache-file (:dir @sess) old)))
                  "the superseded bytes are gone — else the cache grows by a bundle per compile")
              (is (.exists (artifacts/cache-file (:dir @sess) new-sha)))))))
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
          (is (nil? (get-in @sess [:store :artifacts "public/cljs/main.js"]))
              "no bundle written yet")))
      (testing "auto-compile ON: a client write schedules an ASYNC recompile"
        (api/config-file! sess "client" :key "auto-compile" :value "true"
                          :prompt "dev loop")
        (let [r (api/add-form! sess 'ac.client "(defn b [] (js/alert \"b\"))")]
          (is (true? (:client-recompiling r)) (pr-str r))
          ;; async: the background compile registers the artifact shortly after.
          ;; The bundle is DERIVED now, so it lands in :artifacts as a sha and a
          ;; recipe — polling :files would wait out the full timeout forever.
          (let [entry (loop [n 0]
                        (or (get-in @sess [:store :artifacts "public/cljs/main.js"])
                            (when (< n 120)
                              (Thread/sleep 500)
                              (recur (inc n)))))]
            (is (string? (:sha entry)) "fresh bundle registered within timeout")
            (is (.exists (artifacts/cache-file (:dir @sess) (:sha entry)))
                "and its bytes are in the cache"))))
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
      (is (= 2 (count (re-seq #"\(defn \^\{:generated " src)))
          "one WRAPPER per endpoint — counted by its provenance marker, so the
           namespace's own helpers do not read as endpoints")
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
      (is (re-find #"\(str \"/api/orders/\" \(:id params\)\)" src) "path param interpolated"))
    (testing "every fetch goes through a BASE the app can set, so a slopp app
              can be served under a path prefix (D-hub part 2). Default \"\"
              is exactly today's behaviour — an app served at the root emits
              the same urls it always did"
      (is (re-find #"\(defonce \^:export base \(atom \"\"\)\)" src)
          "an exported base the mounting app sets once")
      (is (re-find #"\(defn \^:export set-base! \[b\] \(reset! base b\)\)" src)
          "set through a FN, not by reaching into the atom — a defn is the
           surface a generated namespace should offer, and it is also the
           thing clj-kondo resolves across a cljs namespace boundary")
      (is (= 2 (count (re-seq #"js/fetch \(url " src)))
          "EVERY wrapper routes through it — one that did not would 404 under a prefix"))))

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
  ;; web.static.* mount and nothing said so. Serving it IS one config line —
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
          (is (re-find #"web\.static\." (str (:serve-with r)))
              (str "expected the mount line: " (pr-str r)))
          (is (re-find #"config_file" (str (:serve-with r))) (pr-str r))))
      (testing "once a mount covers it, the hint goes away"
        ;; repeating advice already taken is how a result becomes noise
        (api/config-file! sess "capabilities" :key "web.static./js"
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

(deftest foreign-libs-translate-only-the-formats-that-can-be-concatenated
  (let [st {:js-deps {"roughjs" {:format :iife :global "rough"
                                 :file "public/js/roughjs-4.6.6.js"}
                      "excalidraw" {:format :esm :global "ExcalidrawLib"
                                    :file "public/js/excalidraw.js"}}}
        fl (cljs/foreign-libs-for st)]
    (testing "an :iife library becomes a foreign lib mapped to its global"
      (is (= [{:file "public/js/roughjs-4.6.6.js"
               :provides ["roughjs"]
               :global-exports {'roughjs 'rough}}]
             fl)))
    (testing ":esm is skipped — the page loads it, and concatenating an ES module
              yields a bundle that fails at runtime with nothing to point at"
      (is (not-any? #(= "public/js/excalidraw.js" (:file %)) fl)))
    (testing "a store that vendors nothing produces nothing, not an empty declaration"
      (is (empty? (cljs/foreign-libs-for {}))))))

(deftest a-published-contract-becomes-a-client-plan
  ;; The consuming half of contract publication. A contract is plain DATA, so
  ;; this needs no server, no store and no fixtures — which is the property
  ;; that makes generating against someone else's API cheap to test at all.
  (let [document {:slopp/contract-version 1
                  :endpoints [{:method :get :path "/api/things" :name 'things
                               :request nil :response [:sequential :string]}
                              {:method :post :path "/api/things" :name 'create!
                               :request [:map [:name :string]]
                               :response [:map [:id :int]]}]}
        plan (cljs/contract->plan document 'demo.client.contracts)
        by-fn (into {} (map (juxt (comp str :fn-name) identity)) (:wrappers plan))
        defs  (into {} (map (juxt :name :schema)) (:defs plan))]

    (testing "one wrapper per endpoint, named the way local generation names them"
      ;; create! already carries the bang the dialect asks of a mutating verb,
      ;; so the generator must not add a second one.
      (is (= #{"things" "create!"} (set (keys by-fn)))))

    (testing "each schema becomes a def named from its endpoint, since the author's names did not survive publication"
      (is (= {'things-response [:sequential :string]
              'create-request  [:map [:name :string]]
              'create-response [:map [:id :int]]}
             defs))
      (is (not (contains? defs 'create!-request))
          "the bang belongs to the wrapper, not to a schema's name"))

    (testing "wrappers reference those defs as vars, so the generated client reads like a local one"
      (is (= {:kind :var :sym 'demo.client.contracts/things-response
              :ns 'demo.client.contracts}
             (:response (by-fn "things"))))
      (is (= {:kind :var :sym 'demo.client.contracts/create-request
              :ns 'demo.client.contracts}
             (:request (by-fn "create!")))))

    (testing "a verb with no body carries no request schema at all"
      (is (= {:kind :none} (:request (by-fn "things")))))

    (testing "an unknown contract version is refused rather than guessed at"
      (let [p (cljs/contract->plan (assoc document :slopp/contract-version 99)
                                   'demo.client.contracts)]
        (is (empty? (:wrappers p)))
        (is (seq (:problems p))
            "a consumer that silently generated from a shape it does not know
             would fail later, further away, and with no clue why")))))

(deftest a-generated-contracts-namespace-is-ordinary-verified-source
  ;; The schemas land as SOURCE in the consuming store, not as data parsed at
  ;; runtime. That is what makes the round trip "print a form, read a form" —
  ;; the thing the store already does on every write — instead of a schema
  ;; importer nobody can be sure of.
  (let [src (cljs/render-contracts-ns
             'demo.client.contracts
             [{:name 'things-response :schema [:sequential :string] :endpoint 'things}
              {:name 'create-request :schema [:map [:name :string]] :endpoint 'create!}])]

    (testing "a real ns form, so the JVM oracle verifies it like any other namespace"
      (is (str/starts-with? src "(ns demo.client.contracts")))

    (testing "each schema is a plain def of the published value"
      ;; a large schema gets its own line, so pin NAME PAIRED WITH VALUE
      ;; rather than their layout — the bug worth catching is a def bound to
      ;; the wrong schema, which a whitespace-sensitive match would miss.
      (is (str/includes? src "things-response"))
      (is (str/includes? src "[:sequential :string]")))

    (testing "every def says which endpoint it came from — generated, not hand-written"
      (is (str/includes? src "{:generated \"things\"}"))
      (is (str/includes? src "{:generated \"create!\"}")))

    (testing "the store parses it into exactly the defs it claims"
      ;; the wire format's safety argument, checked rather than asserted: if
      ;; a published schema could not survive as source, ingest is where that
      ;; shows up.
      (let [st   (store/ingest (store/empty-store) 'demo.client.contracts src)
            form (fn [n] (str (store/form-named st 'demo.client.contracts n)))]
        (is (str/includes? (form 'things-response) "[:sequential :string]"))
        (is (str/includes? (form 'create-request) "[:map [:name :string]]"))
        (is (not (str/includes? (form 'things-response) "[:map [:name :string]]"))
            "each def carries ITS OWN schema — a renderer that paired names with
             values by position would pass every presence check above")))))

(deftest ^:external a-cljs-namespace-does-not-silence-the-whole-project-run
  ;; Found reviewing slopp-ui, which has four :cljs namespaces: `test_run
  ;; {all true}` answered `{:external-pending {…} :ms 43}` — no :test, no
  ;; :pass, 43ms for 32 tests. Nothing ran, and nothing said so.
  ;;
  ;; `slopp.rt/traced-run` maps `ns-interns` over every namespace it is handed,
  ;; and the whole-project path hands it EVERY namespace in the store. A :cljs
  ;; namespace does not exist in the JVM image, so `ns-interns` throws "No
  ;; namespace: … found" — lazily, inside the run. The throw crosses the eval
  ;; boundary as text, `{:keys [summary trace]}` destructures to nil, and the
  ;; caller's cond-> builds a map with no counts in it.
  ;;
  ;; Which is the worst shape available: `full_check` — the gate you run before
  ;; a commit you want to stand behind — reports its in-image tier green having
  ;; run nothing, and the warranty trace never fills, so `review_scan` calls
  ;; well-tested forms :untested. Every store with client code, not just this
  ;; one.
  (let [sess (external/open!)]
    (try
      (api/create-ns! sess 'wp.core :source "(ns wp.core)\n(defn f [x] (* 2 x))\n")
      (api/create-ns! sess 'wp.core-test
                      :source (str "(ns wp.core-test\n"
                                   "  (:require [clojure.test :refer [deftest is]]\n"
                                   "            [wp.core :as c]))\n"
                                   "(deftest doubles-it (is (= 4 (c/f 2))))\n"))
      (testing "the whole-project run reports what it ran, with no cljs present"
        (let [r (api/test-run! sess nil)]
          (is (= 1 (:test r)) (pr-str r))
          (is (= 1 (:pass r)) (pr-str r))))
      (api/create-ns! sess 'wp.client :source "(ns wp.client)\n"
                      :platform :cljs :prompt "browser code")
      (testing "and a :cljs namespace in the store does not change that — it
                cannot run in the image, which is a reason to leave it out of
                the run, never a reason for the run to vanish"
        (let [r (api/test-run! sess nil)]
          (is (= 1 (:test r)) (pr-str r))
          (is (= 1 (:pass r)) (pr-str r))
          (is (zero? (+ (:fail r 0) (:error r 0))) (pr-str r))))
      (testing "and the trace still lands, which is what review_scan reads"
        (is (contains? (get (:test-map @sess) 'wp.core-test/doubles-it) 'wp.core/f)
            (pr-str (:test-map @sess))))
      (finally (api/close! sess)))))

(deftest a-published-contract-is-READ-and-never-evaluated
  ;; The docstring's central claim is a SECURITY one: this is data off a network
  ;; boundary, so it goes through `clojure.edn/read-string`, which evaluates
  ;; nothing. Nothing checked it. A fake transport can serve a payload that
  ;; would prove the difference — `#=(…)` is read-eval, which `read-string`
  ;; honours and the EDN reader refuses — and that is a far better test than any
  ;; real server, because no real server would ever send it.
  (let [at (fn [body] (client/fake-requester
                       "http://pub.test/"
                       {[:get "/contract"] (fn [_] {:status 200 :body body})}))]
    (testing "an ordinary contract round-trips as data"
      (is (= [:map [:id :int]]
             (cljs/fetch-contract "http://pub.test/contract"
                                  (at "[:map [:id :int]]")))))
    (testing "a payload carrying read-eval does NOT evaluate — it refuses. If
              this ever passes by returning a value, the reader was swapped for
              one that runs whatever a contract server sends"
      (is (thrown? Exception
                   (cljs/fetch-contract "http://pub.test/contract"
                                        (at "#=(java.lang.System/getProperty \"user.name\")")))))
    (testing "a non-200 fails instead of being parsed as though it were a
              contract — an error page is not a schema"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"404"
           (cljs/fetch-contract "http://pub.test/contract"
                                (client/fake-requester "http://pub.test/" {})))))))
