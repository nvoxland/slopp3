(ns slopp.mine
  "Demand mining over provenance journals: find MANUAL workaround shapes for
  refactorings that don't exist yet (discoverability countermeasure — needs
  with workable manual paths never announce themselves; the journal shows
  them anyway).

    clojure -M -m slopp.mine <project-dir> ...

  Shapes detected per store:
  - :sig-change  — a defn replaced with a changed single arg vector; flagged
                   :with-caller-edits when other forms mentioning it were
                   replaced within ±4 deltas (manual change-signature)
  - :inline      — a defn deleted while forms mentioning it were replaced
                   nearby (manual inline)"
  (:require [rewrite-clj.parser :as p]
            [rewrite-clj.node :as n]
            [slopp.db :as db]))

(defn- arglist [src]
  (try (let [sx (n/sexpr (p/parse-string src))]
         (when (and (seq? sx) ('#{defn defn-} (first sx)))
           (first (filter vector? sx))))
       (catch Exception _ nil)))

(defn- fn-name [src]
  (try (let [sx (n/sexpr (p/parse-string src))]
         (when (and (seq? sx) ('#{defn defn-} (first sx)))
           (second sx)))
       (catch Exception _ nil)))

(defn- nearby-caller-edits [ds i skip-fid nm]
  (count (distinct
          (for [j (range (max 0 (- i 4)) (min (count ds) (+ i 5)))
                :when (not= i j)
                :let [d2 (nth ds j)]
                :when (= :replace (:op d2))
                [fid2 src2] (:sources d2)
                :when (and (not= fid2 skip-fid)
                           nm
                           (re-find (re-pattern (str "\\(" nm "[\\s)]")) src2))]
            fid2))))

^:reads (defn mine-store [dir]
  (let [conn (db/open! dir)
        st   (try (db/load-store conn)
                  (finally (.close ^java.sql.Connection conn)))
        ds   (vec (:deltas st))
        sig  (for [[i d] (map-indexed vector ds)
                   :when (= :replace (:op d))
                   [fid src] (:sources d)
                   :let [prev (last (keep #(get (:sources %) fid) (take i ds)))
                         a0 (some-> prev arglist)
                         a1 (arglist src)]
                   :when (and a0 a1 (not= a0 a1))]
               {:shape :sig-change :fn (fn-name src) :old a0 :new a1
                :with-caller-edits (nearby-caller-edits ds i fid (fn-name src))})
        inl  (for [[i d] (map-indexed vector ds)
                   :when (= :delete (:op d))
                   :let [nm (:name d)
                         edits (nearby-caller-edits ds i (:form-id d) nm)]
                   :when (pos? edits)]
               {:shape :inline :fn nm :with-caller-edits edits})]
    {:dir dir :findings (vec (concat sig inl))}))

(defn -main [& dirs]
  (doseq [d dirs]
    (prn (mine-store d))))
