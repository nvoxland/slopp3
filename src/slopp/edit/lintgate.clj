(ns slopp.edit.lintgate
  "The lint gate on a WRITE: which new kondo findings a candidate store would
  introduce, and which of those are worth stopping for.

  **What blocks is a SET of finding types, never a severity level.** A write is
  mid-work by definition, so the only question askable here is whether the FORM
  is internally incoherent right now — an unresolved symbol, a wrong arity.
  Whether the CODEBASE is finished is `done`'s question. Gating writes on
  severity instead killed red-first TDD, the module lifecycle and carried-lint
  compression, and refused `(if x y)` written on the way to adding its else
  branch.

  **Your own form refuses; someone else's carries.** An error inside a form
  being written comes back as a refusal, because you can fix it now and the
  types involved are almost never false positives. A NEW error in some other
  form — a stale caller after an incremental signature change — comes back as
  `:carried` instead, so the REPL flow keeps moving and the done point
  re-checks it hard. Pre-existing findings never block, so legacy cannot
  deadlock a store.

  Refusals here also teach, because the commonest cause of an own-form error
  is an edit that was too NARROW: a binding and its use, a loop and its recur,
  an arglist and its body must change together — and they are one form, so it
  is one edit. Agents reliably misread that as needing atomicity ACROSS forms
  and reach for a batch primitive that does not exist."
  (:require [clojure.string :as str]
            [slopp.edit :as edit]
            [slopp.index :as index]
            [slopp.store.render :as store.render] [slopp.store :as store]))

(defn ^:export lint-refusals
  "NEW error-level kondo findings a candidate store would introduce over
  its base. An error IN one of the forms being written (`written-fids`)
  returns {:refuse msg} — your own form must be well-formed. New errors in
  OTHER forms (stale callers after an incremental signature change) don't
  block the REPL flow: they return as {:carried [{:form :type :message}]}
  and the done-point re-checks them hard. nil when clean.

  WHAT blocks here is `write-coherence-lint`, NOT a severity level. A write is
  mid-work by definition, so this asks only whether the FORM is internally
  incoherent right now — whether the CODEBASE is finished is `done`'s
  question, decided by `index/kondo-config`'s `:level`. Gating writes on
  severity instead killed red-first TDD, the module lifecycle and carried-lint
  compression (13 assertions, 7 tests), and refused `(if x y)` written on the
  way to adding an else branch.

  These types are ~never false positives (two 'invalid-arity' errors once
  dismissed as noise were real ArityExceptions in shipped handlers);
  pre-existing findings never block (no deadlock on legacy)."
  [base cand ns-syms written-fids]
  (let [written (set written-fids)
        errs    (fn [store ns-sym]
                  (when (get-in store [:namespaces ns-sym])
                    (->> (index/lint (store.render/render-ns store ns-sym)
                                    (store/kondo-lang store ns-sym))
                         (filter #(contains? edit/write-coherence-lint (:type %)))
                         (map #(assoc % :ns ns-sym)))))
        key*    (juxt :ns :type :message)
        news    (mapcat (fn [ns-sym]
                          (let [old (set (map key* (errs base ns-sym)))]
                            (remove #(old (key* %)) (errs cand ns-sym))))
                        (distinct ns-syms))
        located (map (fn [f]
                       (let [e (store.render/owner-form cand (:ns f) (:row f) (:col f))]
                         (assoc f :form-id (:id e)
                                :form (when e
                                        (symbol (str (:ns f))
                                                (str (or (:name e) (:id e))))))))
                     news)
        [own carried] ((juxt filter remove) #(contains? written (:form-id %)) located)
;; A test naming an ARITY that does not exist yet is the same statement
        ;; as a test naming a VAR that does not exist, and `:unresolved-var`
        ;; already left this set for exactly that reason. `:invalid-arity`
        ;; IMPLIES the callee is known — an unknown one lints as
        ;; `:unresolved-var` — so the only condition added here is that the
        ;; caller is a test. Deferred, never silently allowed: the call still
        ;; fails, as an ArityException at run time, which IS the red the test
        ;; was written to see. Production keeps refusing, because there the
        ;; same finding is a real ArityException in shipped code.
        [defer own] ((juxt filter remove)
                     #(and (= :invalid-arity (:type %)) (store.render/test-ns? (:ns %)))
                     own)]
    (cond
      (seq own)
      {:refuse (str "lint ERROR in the form you are writing: "
                    (str/join "; " (map #(str (:ns %) ": " (name (:type %))
                                              " — " (:message %))
                                        own))
                    " — error-level kondo findings are almost never false"
                    " positives; fix the form before sending it"
                    (when (some #(= :invalid-arity (:type %)) own)
                      " (changing a signature? change_signature rewrites the defn AND its call sites as one intent)")
                    ;; the commonest cause of BOTH types on your own form: the
                    ;; edit was too narrow. A binding and its use, a loop and
                    ;; its recur, an arglist and its body must change together
                    ;; — and they are ONE form, so it is ONE edit. Agents
                    ;; reliably misread this as needing atomicity ACROSS forms
                    ;; and reach for a batch primitive that does not exist.
                    (when (some #(#{:unresolved-symbol :invalid-arity} (:type %)) own)
                      (str " — if this was a targeted subform edit, the change"
                           " spans MORE of this form than you matched (a binding"
                           " and its use, a loop and its recur). Widen the match"
                           " to the enclosing form, or edit_replace_form the"
                           " whole thing: two edits to ONE form is ONE edit.")))}

      ;; reported, never silent — an agent told nothing would read the
      ;; ArityException the test then throws as a BUG rather than as the red
      ;; it asked for, which is the failure this defer would otherwise create
      (seq defer)
      (cond-> {:red-first-arity (vec (for [f defer]
                                       {:form (:form f) :message (:message f)}))}
        (seq carried)
        (assoc :carried (vec (for [f carried]
                               {:form (:form f) :type (:type f) :message (:message f)}))))

      (seq carried)
      {:carried (vec (for [f carried]
                       {:form (:form f) :type (:type f) :message (:message f)}))}

      :else nil)))
