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
