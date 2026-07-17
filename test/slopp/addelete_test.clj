(ns slopp.addelete-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.api :as api] [slopp.store :as store]))

(def target
  (str "(ns adm\n  (:require [clojure.test :refer [deftest is]]))\n"
       "(defn add [x y] (+ x y))\n"
       "(deftest add-t (is (= 5 (add 2 3))))\n"))

(deftest ^:isolated add-form-grows-the-namespace
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'adm target)
      (testing "validation"
        (is (:error (api/add-form! sess 'adm "(defn add [x] x)")))     ; name taken
        (is (:error (api/add-form! sess 'adm "(defmacro m [x] x)")))   ; dialect (D4)
        (is (:error (api/add-form! sess 'adm "(def a 1) (def b 2)"))) ; one form only
        (is (:error (api/add-form! sess 'nope "(def a 1)"))))          ; unknown ns
      (let [r (api/add-form! sess 'adm "(defn triple [x] (* 3 x))" :prompt "new helper")]
        (is (nil? (:error r)))
        (is (= :add (:op (:delta r))))
        (testing "rendered source contains the new form, tidily separated"
          (let [src (api/query-source sess 'adm)]
            (is (re-find #"\(defn triple \[x\] \(\* 3 x\)\)" src))
            (is (not (re-find #"\n\n\n" src)))))
        (testing "the new form is live in the image"
          (is (= [12] (api/query-eval sess "(adm/triple 4)"))))
        (testing "its lineage starts at the :add delta"
          (is (= [:add] (mapv :op (api/query-lineage sess 'adm 'triple)))))
        (testing "verification ran and was recorded"
          (is (zero? (+ (:fail (:test r)) (:error (:test r)))))))
      (testing "effect warnings surface at add time (D6)"
        (let [r (api/add-form! sess 'adm "(defn stash [a v] (reset! a v))")]
          (is (some #(= "stash!" (:suggest %)) (:warnings r)))))
      (finally (api/close! sess)))))

(deftest ^:isolated delete-form-removes-everywhere
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'adm target)
      (api/add-form! sess 'adm "(defn triple [x] (* 3 x))")
      (is (:error (api/delete-form! sess 'adm 'nope)))
      (let [r (api/delete-form! sess 'adm 'triple :prompt "unused")]
        (is (nil? (:error r)))
        (is (= :delete (:op (:delta r))))
        (testing "gone from the source and from the live image"
          (is (not (re-find #"triple" (api/query-source sess 'adm))))
          (is (= [nil] (api/query-eval sess "(resolve 'adm/triple)"))))
        (testing "remaining tests still verify green"
          (is (= 1 (:pass (:test r))))))
      (finally (api/close! sess)))))
(deftest ^:isolated deleting-a-defmethod-unregisters-it
  ;; ns-unmap was the delete path's only image effect — a no-op for a
  ;; defmethod, whose name is its form id and whose registration lives in the
  ;; MULTI's method table. So the deleted method KEPT ANSWERING in the image:
  ;; tests exercising it stayed green after the delete, and green-when-red is
  ;; the one direction the D5.1 staleness diagnostics never cross-check
  ;; (suspicious-red? fires on reds). The delete must remove-method.
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'dmz
                   (str "(ns dmz)\n\n(defmulti area :shape)\n\n"
                        "(defmethod area :square [s] (* (:side s) (:side s)))\n\n"
                        "(defmethod area :default [_] :unknown)\n"))
      (let [meth-id (->> (store/forms (:store @sess) 'dmz)
                         (filter #(nil? (:name %)))
                         first :id)
            _ (is (= [4] (api/query-eval sess "(dmz/area {:shape :square :side 2})")))
            r (api/delete-form! sess 'dmz (symbol meth-id) :prompt "drop :square")]
        (is (nil? (:error r)) (pr-str r))
        (testing "the method is gone from the SOURCE"
          (is (not (re-find #":square" (api/query-source sess 'dmz)))))
        (testing "…and gone from the IMAGE — dispatch falls to :default"
          (is (= [:unknown]
                 (api/query-eval sess "(dmz/area {:shape :square :side 2})")))))
      (finally (api/close! sess)))))
(deftest ^:isolated replacing-a-defmethods-dispatch-unregisters-the-old
  ;; Hot-load of the replacement evals the NEW defmethod — registering :sq —
  ;; but nothing removed :square, so the image answered BOTH dispatches while
  ;; the store said only :sq exists. Tests exercising the old dispatch stayed
  ;; green: the same green-when-red direction as the delete case, reached
  ;; through replace. The old dispatch must be unregistered when it changes.
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'dmr
                   (str "(ns dmr)\n\n(defmulti area :shape)\n\n"
                        "(defmethod area :square [s] (* (:side s) (:side s)))\n\n"
                        "(defmethod area :default [_] :unknown)\n"))
      (let [meth-id (->> (store/forms (:store @sess) 'dmr)
                         (filter #(nil? (:name %)))
                         first :id)
            r (api/edit-replace! sess 'dmr (symbol meth-id)
                                 "(defmethod area :sq [s] (* (:side s) (:side s)))"
                                 :prompt "rename the dispatch")]
        (is (nil? (:error r)) (pr-str r))
        (testing "the new dispatch answers"
          (is (= [4] (api/query-eval sess "(dmr/area {:shape :sq :side 2})"))))
        (testing "the OLD dispatch no longer does — store and image agree"
          (is (= [:unknown]
                 (api/query-eval sess "(dmr/area {:shape :square :side 2})")))))
      (finally (api/close! sess)))))
