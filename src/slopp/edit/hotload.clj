(ns slopp.edit.hotload
  "Where a PURE edit meets the LIVE image.

  `slopp.edit` computes a new store value and touches nothing; the image is a
  running process with vars already interned. This namespace is the seam, and
  it exists separately because the ORDERING across it is load-bearing:
  hot-load into the image FIRST, commit only if it compiled. A form that fails
  to compile rejects the whole edit, so there is never a committed store the
  image cannot run.

  Getting that backwards is the failure mode the whole design is arranged
  against — a store the oracle disagrees with makes every verification result
  a claim about the wrong code."
  (:require [rewrite-clj.node :as n]
            [slopp.edit :as edit]
            [slopp.store.render :as render]
            [slopp.image.repl :as repl]
            [slopp.store :as store]))

(defn ^:export hot-load-form!
  "Hot-reload one form (from a store VALUE — commit only on success, S1) into
  the image, padded with newlines to its VFS row and attributed to its VFS
  path so stack traces cite the exact lines `query-source` shows (F6).
  Returns nil on success, or the compile/load error message. A `form-id`
  that no longer resolves in `store` (deleted since it was collected) loads
  nothing and returns nil — a vanished form is not a compile error."
  [image store form-id]
  (let [ns-sym  (store/ns-of-form-id store form-id)
        elems   (when ns-sym (store/elements store ns-sym))
        idx     (when elems
                  (first (keep-indexed
                          (fn [i e] (when (= form-id (:id e)) i)) elems)))]
    (when idx
      (let [[row _] (nth (render/element-offsets store ns-sym) idx)
            src     (n/string (:node (nth elems idx)))
            padded  (if (>= row 2)
                      (str "(in-ns '" ns-sym ")\n"
                           (apply str (repeat (- row 2) "\n")) src)
                      (str "(in-ns '" ns-sym ") " src))]
        (:err (repl/load-checked! image padded (render/ns-path ns-sym)))))))

(defn ^:export apply-replace!
  "Pipeline through hot-reload over `system` {:store store :image handle}:
  `replace-form`, then redefine the form in the live image (D5) — a form that
  fails to COMPILE rejects the whole edit (S1; nothing to commit). Returns
  {:system {:store ...} :delta :warnings} or {:error msg}."
  [system ns-sym form-name new-source & {:keys [prompt]}]
  (let [r (edit/replace-form (:store system) ns-sym form-name new-source :prompt prompt)]
    (cond
      (:error r) r

      :else
      (if-let [err (hot-load-form! (:image system) (:store r)
                                   (:form-id (:delta r)))]
        (edit/compile-error (:store r) err "form failed to compile: " ns-sym)
        {:system   (assoc system :store (:store r))
         :delta    (:delta r)
         :warnings (:warnings r)}))))
