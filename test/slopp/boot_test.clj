(ns slopp.boot-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.boot :as boot]))

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
