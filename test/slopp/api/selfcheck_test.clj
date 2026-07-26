(ns slopp.api.selfcheck-test
  "Invariants about slopp's OWN store — the questions that can only be asked
  of the whole codebase at once.

  These are not ordinary tests of a subject. Each asks something structural
  that no gate sees at write grain: is every public write verb reachable from
  outside, does any prose name a tool that does not exist. They live here
  rather than beside the surfaces they judge because they need the reference
  graph, and `slopp.api` is the module that already declares that dependency.

  **Their characteristic failure is passing on NOTHING**, and it is not
  hypothetical: the first such guard scanned an empty store for its entire
  life, because `open!` in the external tier's build dir hands back a store
  with no code in it. `slopp.api.external/built-store` is the seam that fixed
  that. Every test here asserts its own POPULATION first, and asserts that its
  detector still fires — a whole-store check that has only ever been observed
  green is indistinguishable from one that cannot fail."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [slopp.store :as store]
            [slopp.edit.refs :as refs]
            [slopp.api.external :as external]))

(deftest ^:external every-public-write-verb-is-reachable-from-the-wire
  ;; "The API gained something the wire did not" — a public `!` verb on
  ;; `slopp.api` that no tool, CLI or entrypoint can reach. It is dead surface
  ;; that the dead-surface gate cannot see, because the gate asks whether
  ;; ANYTHING references a var, and a write verb referenced only by another
  ;; write verb clears that bar while being unreachable from outside.
  ;;
  ;; Measured at 0 unreached and never guarded, because the guard needs the
  ;; WHOLE STORE and the external tier had no way to reach one — the temp dir
  ;; `build!` fills has source and no db, so `open!` there hands back an empty
  ;; store and any such assertion passes on nothing. `built-store` is that
  ;; missing seam, and the population assertions below are what stop this
  ;; going the way the prose guard did for its entire life.
  (let [st      (external/built-store)
        prod    (set (remove #(str/ends-with? (str %) "-test")
                             (keys (:namespaces st))))
        ;; THE WRITE SURFACE: public, bang-named defns on the two api faces
        surface (vec (for [nsx  '[slopp.api slopp.api.external]
                           e    (store/forms st nsx)
                           :let [s  (store/form-sexpr (:node e))
                                 nm (store/form-symbol (:node e))]
                           :when (and s nm
                                      (= 'defn (first s))
                                      (str/ends-with? (str nm) "!")
                                      (not (:private (meta (second s)))))]
                       {:sym  (symbol (str nsx) (str nm))
                        ;; the escape, and it is the marker that already means
                        ;; "reached from outside the store" — a runtime-resolved
                        ;; or string-eval'd entry. A blocking check with no way
                        ;; out for a legitimate case is the standing rule this
                        ;; repo set and then had to dial `breaking-changes` back
                        ;; for.
                        :ok?  (boolean (:unused-ok (meta (second s))))}))
        ;; the production call graph, forward
        out     (reduce (fn [m r]
                          (if (and (contains? prod (:from-ns r)) (:from-var r))
                            (update m (symbol (str (:from-ns r)) (str (:from-var r)))
                                    (fnil conj #{})
                                    (symbol (str (:to-ns r)) (str (:to-name r))))
                            m))
                        {} (refs/refs st))
        ;; every var the OUTSIDE can start at: the wire dispatch and the mains
        roots   (into #{'slopp.mcp/call-tool!}
                      (for [nsx  prod
                            e    (store/forms st nsx)
                            :let [nm (store/form-symbol (:node e))]
                            :when (= '-main nm)]
                        (symbol (str nsx) "-main")))
        reached (loop [seen #{} todo (vec roots)]
                  (if-let [v (peek todo)]
                    (if (seen v)
                      (recur seen (pop todo))
                      (recur (conj seen v) (into (pop todo) (get out v))))
                    seen))
        unreached (vec (sort (for [{:keys [sym ok?]} surface
                                   :when (and (not ok?) (not (reached sym)))]
                               sym)))]
    (testing "there is a POPULATION — the failure mode this seam exists to end"
      (is (< 50 (count prod)) (str "expected slopp's namespaces, got " (count prod)))
      (is (< 40 (count surface))
          (str "expected the api write surface, got " (count surface)))
      (is (< 1 (count roots)) (str "expected the wire plus the mains, got " roots)))
    (testing "the reachability actually traverses — a check that cannot fail is not a check"
      (is (contains? reached 'slopp.api/add-form!)
          "the wire dispatch reaches the base write, or the graph is not being walked")
      (is (not (contains? reached 'slopp.api/definitely-not-a-real-verb!))))
    (is (empty? unreached)
        (str "public write verb(s) no wire or CLI entrypoint can reach: "
             (pr-str unreached)
             " — either route it, or mark the name ^:unused-ok saying it is"
             " reached from outside the store"))))
