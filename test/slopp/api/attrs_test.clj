(ns slopp.api.attrs-test
  (:require [clojure.test :refer [deftest testing is]]
            [slopp.api.attrs :as attrs]
            [slopp.store :as store] [slopp.api :as api]))

(deftest keyword-inventory-collects-namespaced-domain-keys
  (let [s (store/ingest (store/empty-store) 'app.core
                        (str "(ns app.core)\n\n"
                             "(defn a [{:user/keys [email]}] {:user/email email :order/id 1})\n\n"
                             "(defn b [x] {:order/id x :plain 2})\n"))
        inv (attrs/keyword-inventory s)]
    (testing "namespaced domain keywords are collected"
      (is (contains? inv :user/email))
      (is (contains? inv :order/id)))
    (testing "unqualified keys and destructuring specials (:keys/:as/:or) are excluded"
      (is (not (contains? inv :plain)))
      (is (not (contains? inv :user/keys))))
    (testing "each keyword maps to the SET of forms using it (derived index)"
      (is (= 2 (count (:order/id inv))))
      (is (= 1 (count (:user/email inv)))))))

(deftest near-duplicate-keys-flags-typos-not-legit-keys
  (let [base (str "(ns app.core)\n\n"
                  "(defn a [m] {:user/email (:x m)})\n\n"
                  "(defn b [m] {:user/email (:y m)})\n\n"
                  "(defn c [m] {:order/total (:z m)})\n\n"
                  "(defn d [m] {:order/total (:w m)})\n")
        typo (store/ingest (store/empty-store) 'app.core
                           (str base "\n(defn e [m] {:user/emial (:q m)})\n"))
        typo-fid (:id (store/form-named typo 'app.core 'e))]
    (testing "a transposition of an established same-ns key is flagged"
      (is (= [{:used :user/emial :suggest :user/email :seen 2}]
             (attrs/near-duplicate-keys typo #{typo-fid}))))
    (testing "an established key (also used by unchanged forms) is NOT a typo"
      (let [reuse (store/ingest (store/empty-store) 'app.core
                                (str base "\n(defn e [m] {:user/email (:q m)})\n"))
            fid   (:id (store/form-named reuse 'app.core 'e))]
        (is (empty? (attrs/near-duplicate-keys reuse #{fid})))))
    (testing "a genuinely-new key with no near neighbor is NOT flagged"
      (let [novel (store/ingest (store/empty-store) 'app.core
                                (str base "\n(defn e [m] {:user/phone (:q m)})\n"))
            fid   (:id (store/form-named novel 'app.core 'e))]
        (is (empty? (attrs/near-duplicate-keys novel #{fid})))))))

(deftest ^:isolated done-surfaces-key-typos-as-advisory
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'kt.core
                   (str "(ns kt.core)\n"
                        "(defn a [m] {:user/email (:x m)})\n"
                        "(defn b [m] {:user/email (:y m)})\n"))
      (api/done! sess :label "baseline")
      (api/add-form! sess 'kt.core "(defn c [m] {:user/emial (:z m)})"
                     :prompt "typo the boundary key")
      (let [r (api/done! sess :label "typo")]
        (testing "the typo'd key is flagged with the established key it resembles"
          (is (= [{:used :user/emial :suggest :user/email :seen 2}]
                 (get-in r [:findings :key-typos])) (pr-str (:findings r)))))
      (testing "advisory only — a key typo does NOT flip test-status red"
        (api/add-form! sess 'kt.core "(defn d [m] {:order/idd (:z m)})"
                       :prompt "another typo, but there is no established :order key")
        (let [r (api/done! sess :label "no-neighbor")]
          (is (nil? (get-in r [:findings :key-typos])) (pr-str (:findings r)))
          (is (not= :red (get-in r [:findings :test-status])) (pr-str (:findings r)))))
      (finally (api/close! sess)))))
