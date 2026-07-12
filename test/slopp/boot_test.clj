(ns slopp.boot-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.boot :as boot]))

(deftest ^:isolated dependency-order-is-deps-first
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

(deftest ^:isolated dependency-order-is-deterministic-and-cycle-safe
  (testing "ties break by sorted name (deterministic)"
    (is (= '[app.a app.b app.c]
           (boot/dependency-order {'app.c "(ns app.c)" 'app.a "(ns app.a)"
                                   'app.b "(ns app.b)"}))))
  (testing "a require cycle doesn't hang — the remainder is appended"
    (is (= #{'x 'y}
           (set (boot/dependency-order {'x "(ns x (:require [y :as y]))"
                                        'y "(ns y (:require [x :as x]))"}))))))
