(ns slopp.api.currency-test
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
            [slopp.api.currency :as currency]
            [slopp.image.currency :as reg]
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
  (reduce (fn [m e] (assoc m (:id e) {:hash (reg/hash-of (n/string (:node e)))
                                      :seq (inc (count m))}))
          {} (store/elements st 'app.core)))

(deftest a-current-image-reports-no-drift
  (let [st (store/ingest (store/empty-store) 'app.core src)]
    (is (empty? (currency/drift-of st (stamp-all st)))
        "the false-alarm direction: a clean image must say nothing at all")))

(deftest a-form-the-image-never-loaded-is-named
  (let [st (store/ingest (store/empty-store) 'app.core src)
        i  (ids st)
        d  (currency/drift-of st (dissoc (stamp-all st) (get i "schema")))]
    (is (= [{:ns 'app.core :form 'schema :why :never-loaded}]
           (mapv #(select-keys % [:ns :form :why]) d)))))

(deftest a-form-whose-source-moved-on-is-named
  (let [st (store/ingest (store/empty-store) 'app.core src)
        i  (ids st)
        d  (currency/drift-of st (assoc-in (stamp-all st)
                                           [(get i "plain") :hash] 123456))]
    (is (= [{:ns 'app.core :form 'plain :why :superseded}]
           (mapv #(select-keys % [:ns :form :why]) d)))))

(deftest a-value-captured-from-a-form-re-evaluated-since-is-named
  ;; THE class a source comparison cannot see. Re-evaluating `env-tools` alone
  ;; leaves `tools` holding the old value while both sources are current.
  (let [st    (store/ingest (store/empty-store) 'app.core src)
        i     (ids st)
        after (assoc-in (stamp-all st) [(get i "env-tools") :seq] 999)
        d     (currency/drift-of st after)]
    (is (= [{:ns 'app.core :form 'tools :why :derived-stale}]
           (mapv #(select-keys % [:ns :form :why]) d)))
    (is (= 'env-tools (:behind (first d)))
        "naming what it is behind is the difference between a warning and a fix")))

(deftest evaluated-var-metadata-goes-stale-the-same-way
  ;; friction 17: `^{:web/response schema}` captured schema's VALUE at load, so
  ;; editing the schema left the published contract advertising the old shape
  ;; while the store, the tests and done all agreed it had changed.
  (let [st    (store/ingest (store/empty-store) 'app.core src)
        i     (ids st)
        after (assoc-in (stamp-all st) [(get i "schema") :seq] 999)
        d     (currency/drift-of st after)]
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
                                       {:hash (reg/hash-of (n/string (:node e)))
                                        :seq (inc (count m))}))
                      {} (store/elements st 'app.core))
        after (assoc-in base [(get i "helper") :seq] 999)]
    (is (empty? (currency/drift-of st after)))))
