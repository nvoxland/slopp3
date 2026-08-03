(ns slopp.addelete-test
  "Adding and removing a top-level form — the two writes where the store, the
  running image and the reference graph must all end up agreeing.

  Removal is the harder half and most of this file is about it, because a
  delete can succeed in the store and still be wrong everywhere else: a
  defmethod whose registration lives in the multi's method table keeps
  answering after `ns-unmap`, a name that addresses two elements would take
  whichever came first, and a form something still CALLS takes the namespace's
  next reload down with it. Each of those was a real failure before it was a
  test here."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.ops :as api]
            [slopp.ops.external :as external]
            [slopp.read.query :as query]
            [slopp.store :as store] [slopp.read.history :as history]))

(def target
  (str "(ns adm\n  (:require [clojure.test :refer [deftest is]]))\n"
       "(defn add [x y] (+ x y))\n"
       "(deftest add-t (is (= 5 (add 2 3))))\n"))

(deftest ^:external add-form-grows-the-namespace
  (let [sess (external/open!)]
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
          (let [src (query/query-source sess 'adm)]
            (is (re-find #"\(defn triple \[x\] \(\* 3 x\)\)" src))
            (is (not (re-find #"\n\n\n" src)))))
        (testing "the new form is live in the image"
          (is (= [12] (api/query-eval sess "(adm/triple 4)"))))
        (testing "its lineage starts at the :add delta"
          (is (= [:add] (mapv :op (history/query-lineage sess 'adm 'triple)))))
        (testing "verification ran and was recorded"
          (is (zero? (+ (:fail (:test r)) (:error (:test r)))))))
      (testing "effect warnings surface at add time (D6)"
        (let [r (api/add-form! sess 'adm "(defn stash [a v] (reset! a v))")]
          (is (some #(= "stash!" (:suggest %)) (:warnings r)))))
      (finally (api/close! sess)))))

(deftest ^:external delete-form-removes-everywhere
  (let [sess (external/open!)]
    (try
      (api/ingest! sess 'adm target)
      (api/add-form! sess 'adm "(defn triple [x] (* 3 x))")
      (is (:error (api/delete-form! sess 'adm 'nope)))
      (let [r (api/delete-form! sess 'adm 'triple :prompt "unused")]
        (is (nil? (:error r)))
        (is (= :delete (:op (:delta r))))
        (testing "gone from the source and from the live image"
          (is (not (re-find #"triple" (query/query-source sess 'adm))))
          (is (= [nil] (api/query-eval sess "(resolve 'adm/triple)"))))
        (testing "remaining tests still verify green"
          (is (= 1 (:pass (:test r))))))
      (finally (api/close! sess)))))

(deftest ^:external deleting-a-defmethod-unregisters-it
  ;; ns-unmap was the delete path's only image effect — a no-op for a
  ;; defmethod, whose name is its form id and whose registration lives in the
  ;; MULTI's method table. So the deleted method KEPT ANSWERING in the image:
  ;; tests exercising it stayed green after the delete, and green-when-red is
  ;; the one direction the D5.1 staleness diagnostics never cross-check
  ;; (suspicious-red? fires on reds). The delete must remove-method.
  (let [sess (external/open!)]
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
          (is (not (re-find #":square" (query/query-source sess 'dmz)))))
        (testing "…and gone from the IMAGE — dispatch falls to :default"
          (is (= [:unknown]
                 (api/query-eval sess "(dmz/area {:shape :square :side 2})")))))
      (finally (api/close! sess)))))

(deftest ^:external replacing-a-defmethods-dispatch-unregisters-the-old
  ;; Hot-load of the replacement evals the NEW defmethod — registering :sq —
  ;; but nothing removed :square, so the image answered BOTH dispatches while
  ;; the store said only :sq exists. Tests exercising the old dispatch stayed
  ;; green: the same green-when-red direction as the delete case, reached
  ;; through replace. The old dispatch must be unregistered when it changes.
  (let [sess (external/open!)]
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

(deftest ^:external ambiguous-form-names-refuse-instead-of-guessing
  ;; A legacy `(declare b)` and `(defn b …)` are TWO store elements answering
  ;; to the same name. `store/form-named` returns the FIRST match, so a
  ;; destructive write silently hit whichever happened to come first — that is
  ;; how a live definition got deleted and 13 tests went red. A write that
  ;; cannot tell which element you meant must refuse and show both.
  (let [sess (external/open!)]
    (try
      (api/ingest! sess 'amb.core
                   (str "(ns amb.core)\n\n"
                        "(declare b)\n\n"
                        "(defn b [] 2)\n\n"
                        "(defn a [] (b))\n"))
      (testing "delete refuses and names the candidates"
        (let [r (api/delete-form! sess 'amb.core 'b)]
          (is (re-find #"ambiguous" (str (:error r))) (pr-str r))
          (is (= 2 (count (:candidates r))) (pr-str r))))
      (testing "replace refuses too"
        (let [r (api/edit-replace! sess 'amb.core 'b "(defn b [] 3)"
                                   :prompt "x")]
          (is (re-find #"ambiguous" (str (:error r))) (pr-str r))))
      (testing "the definition is untouched — nothing was guessed away"
        (is (= [2] (api/query-eval sess "(amb.core/b)"))))
      (testing "cleanup resolves the ambiguity, and then writes land normally"
        (api/cleanup! sess 'amb.core :prompt "retire the legacy declare")
        (let [r (api/edit-replace! sess 'amb.core 'b "(defn b [] 3)"
                                   :prompt "now unambiguous")]
          (is (nil? (:error r)) (pr-str r)))
        (is (= [3] (api/query-eval sess "(amb.core/b)"))))
      (finally (api/close! sess)))))

(deftest ^:external deleting-a-form-with-a-live-caller-is-refused
  ;; friction 3f/19, the ROOT. The catastrophe was fixed by making boot
  ;; best-effort, so a broken namespace costs itself instead of every tool in
  ;; every process — but the delete that breaks it is still ACCEPTED. Its own
  ;; docstring claimed "tests that exercised it will go red — the honest signal
  ;; if it was still referenced". That signal never arrives: the reload fails
  ;; first, so what you get is a store that boots nowhere and an error that
  ;; reads like a compile problem somewhere else.
  ;;
  ;; The information was always there — `query_depends {on "ns/name"}` answers
  ;; exactly this, and the skill teaches asking it before every delete. Making
  ;; the agent remember a check the write path could run is the shape Core 1
  ;; names. `ns_delete` has refused on this shape all along, naming the
  ;; requirers; there is no reason for the form-level delete to differ.
  (let [sess (external/open!)]
    (try
      (api/ingest! sess 'dl.core
                   (str "(ns dl.core)\n"
                        "(defn helper [x] (inc x))\n"
                        "(defn ^:unused-ok use-it [x] (helper x))\n"))
      (testing "a live caller in the SAME namespace blocks it — 3f's actual shape"
        ;; the delete that bricked the store was slopp.ui.pages/form-doc, used
        ;; by read performers in its OWN namespace; a cross-ns-only check would
        ;; have missed it entirely
        (let [r (api/delete-form! sess 'dl.core 'helper)]
          (is (:error r) (pr-str r))
          (is (re-find #"use-it" (str (:error r))) (pr-str r))
          (is (re-find #"query_depends" (str (:error r)))
              "name the call that answers this")))
      (testing "the form is still THERE — a refusal that half-applied would be worse"
        (is (some? (store/form-named (:store @sess) 'dl.core 'helper))))
      (testing "delete the caller first, and the callee goes"
        (is (nil? (:error (api/delete-form! sess 'dl.core 'use-it))) )
        (is (nil? (:error (api/delete-form! sess 'dl.core 'helper)))))
      (testing "a form calling ITSELF is not its own live caller"
        (api/ingest! sess 'dl.rec
                     (str "(ns dl.rec)\n"
                          "(defn ^:unused-ok countdown [n]"
                          " (if (pos? n) (countdown (dec n)) :done))\n"))
        (is (nil? (:error (api/delete-form! sess 'dl.rec 'countdown)))))
      (finally (api/close! sess)))))
