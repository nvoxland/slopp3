(ns slopp.refactor-test
  "The move-forms planner: pure move analysis over a store value — external
  callers, dependency direction, selective requires, refusals with teaching.
  The executor (api/move-forms!) is covered end-to-end in surgeon-test; here
  the PLANS are cheap to assert."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.refactor :as refactor]
            [slopp.store :as store]))
(defn- fixture-store
  "mv.core defines a private util + a public mid + entry; mv.app (another
  module) and mv.core-test both call across; mv.other aliases nothing."
  []
  (-> (store/empty-store)
      (store/ingest 'mv.core
                    (str "(ns mv.core (:require [clojure.string :as str]))\n\n"
                         "(defn- util \"U.\" [x] (str/trim x))\n\n"
                         "(defn mid \"M.\" [x] (util x))\n\n"
                         "(defn entry \"E.\" [x] (mid x))\n"))
      (store/ingest 'mv.app
                    (str "(ns mv.app (:require [mv.core :as core]))\n\n"
                         "(defn go \"G.\" [x] (core/mid x))\n"))
      (store/ingest 'mv.core-test
                    (str "(ns mv.core-test (:require [mv.core :as core]\n"
                         "                           [clojure.test :refer [deftest is]]))\n\n"
                         "(deftest mid-t (is (= \"a\" (core/mid \" a \"))))\n"))))
(deftest plan-rewrites-external-callers-and-selects-requires
  ;; the v1 killer: moving a form with callers in OTHER namespaces. The plan
  ;; must rewrite every caller (prod + test) to the new alias, inject the
  ;; require where missing, and give the new ns ONLY the requires the moved
  ;; forms use.
  (let [st (fixture-store)
        p  (refactor/move-plan st 'mv.core '[util mid] 'mv.helpers {})]
    (is (nil? (:error p)) (pr-str (:error p)))
    (testing "the new ns carries only what the moved forms use"
      (is (:new-ns? p))
      (is (re-find #"\[clojure\.string :as str\]" (:new-src p)))
      (is (not (re-find #"clojure\.test" (:new-src p))))
      (is (re-find #"defn util" (:new-src p)) "privates publicized"))
    (testing "every caller ns is rewritten to the new alias"
      (let [rewritten (set (map :ns (vals (:rewrites p))))]
        (is (contains? rewritten 'mv.core) "entry calls mid")
        (is (contains? rewritten 'mv.app))
        (is (contains? rewritten 'mv.core-test)))
      (is (some #(re-find #"helpers/mid" (:src %)) (vals (:rewrites p)))))
    (testing "callers gain the require; from-ns keeps none it doesn't need"
      (is (= "[mv.helpers :as helpers]"
             (get-in p [:require-adds 'mv.app])
             (get-in p [:require-adds 'mv.core-test])
             (get-in p [:require-adds 'mv.core]))
          (pr-str (:require-adds p))))
    (testing "module rows ride out for the executor's gate check"
      (is (some #(and (= 'mv.app (:from-ns %)) (= 'mv.helpers (:to %)))
                (:module-rows p))))))
(deftest plan-analyzes-dependency-direction
  (let [st (fixture-store)]
    (testing "stay→moved: from-ns requires the new ns back (the v1 case)"
      (let [p (refactor/move-plan st 'mv.core '[util mid] 'mv.helpers {})]
        (is (= "[mv.helpers :as helpers]" (get-in p [:require-adds 'mv.core])))))
    (testing "moved→stay: the new ns requires from-ns; bare refs to PUBLIC
              stay-behinds become qualified"
      (let [p (refactor/move-plan st 'mv.core '[entry] 'mv.front {})]
        (is (nil? (:error p)) (pr-str (:error p)))
        (is (re-find #"\[mv\.core :as core\]" (:new-src p)))
        (is (re-find #"core/mid" (:new-src p))
            "entry's bare (mid x) is qualified in its new home")
        (is (nil? (get-in p [:require-adds 'mv.core]))
            "nothing left behind calls entry — no require back")))
    (testing "moved forms calling PRIVATE stay-behinds refuse with teaching"
      (let [p (refactor/move-plan st 'mv.core '[mid] 'mv.front {})]
        (is (re-find #"util" (str (:error p))))
        (is (re-find #"move|public" (str (:error p))))))
    (testing "a two-way split refuses, naming both directions"
      ;; move util+entry: util is called by staying mid; entry calls staying mid
      (let [p (refactor/move-plan st 'mv.core '[util entry] 'mv.front {})]
        (is (:error p))))))
(deftest plan-handles-existing-targets-refers-and-export
  (let [st (fixture-store)]
    (testing "moving into an EXISTING ns appends there instead of creating"
      (let [st2 (store/ingest st 'mv.extra "(ns mv.extra)\n\n(defn spare \"S.\" [x] x)\n")
            p   (refactor/move-plan st2 'mv.core '[util mid] 'mv.extra {})]
        (is (nil? (:error p)) (pr-str (:error p)))
        (is (not (:new-ns? p)))
        (is (= 2 (count (:append p))) "publicized nodes to append")
        (is (= ["[clojure.string :as str]"] (:to-require-adds p))
            "the existing target gains only what the moved forms need")))
    (testing "a name collision in the target refuses"
      (let [st2 (store/ingest st 'mv.extra "(ns mv.extra)\n\n(defn mid \"S.\" [x] x)\n")
            p   (refactor/move-plan st2 'mv.core '[util mid] 'mv.extra {})]
        (is (re-find #"mid" (str (:error p))))))
    (testing ":refer'd moved names refuse with the exact ns named"
      (let [st2 (store/ingest st 'mv.refuser
                              (str "(ns mv.refuser (:require [mv.core :refer [mid]]))\n\n"
                                   "(defn use-it \"R.\" [x] (mid x))\n"))
            p   (refactor/move-plan st2 'mv.core '[util mid] 'mv.helpers {})]
        (is (re-find #"mv\.refuser" (str (:error p))))
        (is (re-find #"refer" (str (:error p))))))
    (testing "export: true marks moved vars ^:export in the new source"
      (let [p (refactor/move-plan st 'mv.core '[util mid] 'mv.core.impl
                                  {:export true})]
        (is (re-find #"\^:export" (:new-src p)) (:new-src p))))
    (testing "a string export scopes the hoist to that subtree only"
      (let [p (refactor/move-plan st 'mv.core '[util mid] 'mv.core.impl
                                  {:export "mv.app"})]
        (is (re-find #"\{:export \"mv\.app\"\}" (:new-src p)) (:new-src p))))))
