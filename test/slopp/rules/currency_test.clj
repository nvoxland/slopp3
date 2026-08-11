(ns slopp.rules.currency-test
  "Does the drift analysis see each way an image can diverge from its store?

  Every case here is staged as DATA — a store value plus a stamp map — rather
  than by driving a real image, because the interesting divergences are
  precisely the ones that are painful to provoke in a live process and trivial
  to state as a map. That is why `drift-of` takes the stamps as an argument at
  all.

  The cases are the frictions that paid for this namespace: a `def` holding a
  value captured from a form re-evaluated since, var metadata that captured a
  schema, a namespace written to the store and never loaded — and, just as
  load-bearing, the two directions it must stay QUIET about: a clean image,
  and a `defn` whose callee moved (that one resolves through the var and needs
  no reload)."
  (:require [clojure.test :refer [deftest is]]
            [slopp.rules.currency :as rules.currency]
            [slopp.image.currency :as image.currency]
            [slopp.store :as store]
            [rewrite-clj.node :as n]))

(def ^:private src
  ;; `tools` captures `env-tools`'s VALUE at load (friction 1) and `endpoint`
  ;; captures `schema`'s through evaluated var metadata (friction 17). Both
  ;; are invisible to a source comparison: their own source never changes.
  (str "(ns app.core)\n\n"
       "(def env-tools [:a])\n\n"
       "(def tools (vec (concat env-tools [:b])))\n\n"
       "(def schema [:map [:x :string]])\n\n"
       "(defn ^{:web/response schema} endpoint \"E.\" [req] req)\n\n"
       "(defn plain \"P.\" [x] (inc x))\n"))

(defn- ids [st]
  (into {} (map (juxt (comp str :name) :id)) (store/elements st 'app.core)))

(defn- stamp-all
  "Everything in the store, loaded in file order — a clean, fully current image."
  [st]
  (reduce (fn [m e] (assoc m (:id e) {:hash (image.currency/hash-of (n/string (:node e)))
                                      :seq (inc (count m))}))
          {} (store/elements st 'app.core)))

(deftest a-current-image-reports-no-drift
  (let [st (store/ingest (store/empty-store) 'app.core src)]
    (is (empty? (rules.currency/drift-of st (stamp-all st)))
        "the false-alarm direction: a clean image must say nothing at all")))

(deftest a-form-the-image-never-loaded-is-named
  (let [st (store/ingest (store/empty-store) 'app.core src)
        i  (ids st)
        d  (rules.currency/drift-of st (dissoc (stamp-all st) (get i "schema")))]
    (is (= [{:ns 'app.core :form 'schema :why :never-loaded}]
           (mapv #(select-keys % [:ns :form :why]) d)))))

(deftest a-form-whose-source-moved-on-is-named
  (let [st (store/ingest (store/empty-store) 'app.core src)
        i  (ids st)
        d  (rules.currency/drift-of st (assoc-in (stamp-all st)
                                           [(get i "plain") :hash] 123456))]
    (is (= [{:ns 'app.core :form 'plain :why :superseded}]
           (mapv #(select-keys % [:ns :form :why]) d)))))

(deftest a-value-captured-from-a-form-re-evaluated-since-is-named
  ;; THE class a source comparison cannot see. Re-evaluating `env-tools` alone
  ;; leaves `tools` holding the old value while both sources are current.
  (let [st    (store/ingest (store/empty-store) 'app.core src)
        i     (ids st)
        after (assoc-in (stamp-all st) [(get i "env-tools") :seq] 999)
        d     (rules.currency/drift-of st after)]
    (is (= [{:ns 'app.core :form 'tools :why :derived-stale}]
           (mapv #(select-keys % [:ns :form :why]) d)))
    (is (= 'app.core/env-tools (:behind (first d)))
        "naming what it is behind is the difference between a warning and a fix — QUALIFIED, because it is usually in another namespace")))

(deftest evaluated-var-metadata-goes-stale-the-same-way
  ;; friction 17: `^{:web/response schema}` captured schema's VALUE at load, so
  ;; editing the schema left the published contract advertising the old shape
  ;; while the store, the tests and done all agreed it had changed.
  (let [st    (store/ingest (store/empty-store) 'app.core src)
        i     (ids st)
        after (assoc-in (stamp-all st) [(get i "schema") :seq] 999)
        d     (rules.currency/drift-of st after)]
    (is (= [{:ns 'app.core :form 'endpoint :why :derived-stale}]
           (mapv #(select-keys % [:ns :form :why]) d)))))

(deftest a-defn-body-is-not-stale-when-its-callee-moves
  ;; the discriminator that keeps this from crying wolf on every write: a defn
  ;; body resolves through the VAR at call time, so a re-evaluated callee is
  ;; picked up for free. Only load-time capture goes stale.
  (let [st    (store/ingest (store/empty-store) 'app.core
                            (str "(ns app.core)\n\n"
                                 "(defn helper \"H.\" [] 1)\n\n"
                                 "(defn caller \"C.\" [] (helper))\n"))
        i     (into {} (map (juxt (comp str :name) :id)) (store/elements st 'app.core))
        base  (reduce (fn [m e] (assoc m (:id e)
                                       {:hash (image.currency/hash-of (n/string (:node e)))
                                        :seq (inc (count m))}))
                      {} (store/elements st 'app.core))
        after (assoc-in base [(get i "helper") :seq] 999)]
    (is (empty? (rules.currency/drift-of st after)))))

(deftest a-derived-stale-row-names-the-edit-that-re-evaluated-it
  ;; The measured shape of this repo's most frequent friction: `slopp.mcp/env-handlers!`
  ;; captures `slopp.mcp.tools/cheat-sheet`, and what stales it is a write to a
  ;; SIBLING form in that other namespace — a write reloads its whole namespace,
  ;; so the form you are told you are behind may never have been edited. "Which
  ;; form" was always answerable and was never the question; "which edit" is, and
  ;; the store holds it.
  (let [st0   (store/ingest (store/empty-store) 'app.reg
                            "(ns app.reg)\n\n(def sheet \"v1\")\n\n(def sibling 1)\n")
        st1   (store/ingest st0 'app.core
                            (str "(ns app.core (:require [app.reg :as reg]))\n\n"
                                 "(def tools {:sheet reg/sheet})\n"))
        sib   (:id (store/form-named st1 'app.reg 'sibling))
        [st]  (store/apply-changeset st1 :replace 'app.reg
                                     {sib (n/coerce '(def sibling 2))}
                                     :prompt "bump the sibling, which reloads app.reg")
        stamp (reduce (fn [m e] (assoc m (:id e)
                                       {:hash (image.currency/hash-of (n/string (:node e)))
                                        :seq  (inc (count m))}))
                      {} (concat (store/elements st 'app.reg)
                                 (store/elements st 'app.core)))
        ;; app.reg re-evaluated AFTER app.core captured from it
        stamp (assoc-in stamp [(:id (store/form-named st 'app.reg 'sheet)) :seq] 999)
        row   (first (rules.currency/drift-of st stamp))]
    (is (= {:ns 'app.core :form 'tools :why :derived-stale}
           (select-keys row [:ns :form :why]))
        (pr-str row))
    ;; QUALIFIED: the form it is behind lives in another namespace, and the
    ;; bare symbol cost a grep every time this fired
    (is (= 'app.reg/sheet (:behind row)) (pr-str row))
    (is (= (:id (last (store/deltas st))) (:delta (:behind-edit row)))
        (str "the most recent write to that namespace is what reloaded it: "
             (pr-str row)))
    (is (= "bump the sibling, which reloads app.reg" (:prompt (:behind-edit row)))
        (str "the prompt is what makes it click — a delta id alone is a lookup: "
             (pr-str row)))))
