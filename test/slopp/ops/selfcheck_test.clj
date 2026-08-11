(ns slopp.ops.selfcheck-test
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
  with no code in it. `slopp.ops.external/built-store` is the seam that fixed
  that. Every test here asserts its own POPULATION first, and asserts that its
  detector still fires — a whole-store check that has only ever been observed
  green is indistinguishable from one that cannot fail."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [slopp.store :as store]
            [slopp.index.refs :as refs]
            [slopp.ops.external :as external] [slopp.project.capabilities :as capabilities]))

(deftest ^:external
  ^{:correspondence "the public WRITE verbs in the operation surface vs what the MCP wire dispatches — a verb nobody can reach is surface an agent is told about and cannot use"}
  every-public-write-verb-is-reachable-from-the-wire
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
        surface (vec (for [nsx  '[slopp.ops slopp.ops.external]
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
      (is (contains? reached 'slopp.ops/add-form!)
          "the wire dispatch reaches the base write, or the graph is not being walked")
      (is (not (contains? reached 'slopp.ops/definitely-not-a-real-verb!))))
    (is (empty? unreached)
        (str "public write verb(s) no wire or CLI entrypoint can reach: "
             (pr-str unreached)
             " — either route it, or mark the name ^:unused-ok saying it is"
             " reached from outside the store"))))

(deftest ^:external
  ^{:correspondence "every key in project.capabilities/registry vs the production forms that MENTION it — a registry row nothing reads is either dead or an unimplemented feature, and today the two are indistinguishable: query_capabilities advertises it to every project and setting it does nothing"}
  no-capability-key-goes-unmentioned-by-production-code
  ;; A registry row nothing reads is worse than an absent one: absent prompts a
  ;; question, present-and-inert produces silence. `query_capabilities`
  ;; advertises every key to every project as something it can set.
  ;;
  ;; **This measures MENTIONS, not reads, and the name says so.** The right-hand
  ;; side is a PROXY: a key named only in a docstring counts as mentioned, and a
  ;; key assembled at runtime would be missed. A check computed over a proxy
  ;; must be NAMED for the proxy rather than for the thing it stands in for —
  ;; `every-capability-is-read` would make a claim this cannot support.
  ;;
  ;; The obvious stronger fix — route every read through a declared accessor, so
  ;; the readers become a derivable population — is REFUTED by measurement and
  ;; must not be re-attempted without reading this: `web.auth/config-from-values`
  ;; is a `reduce-kv` over the WHOLE values map by design, because one parser
  ;; serves both slopp's serving (store values) and a built app (the rendered
  ;; capabilities file). EIGHT of the keys are read that way. A per-key accessor
  ;; would break the built-app path or need a fake lookup existing only to
  ;; satisfy a check.
  ;;
  ;; Matching is by BASE for a pattern key (`web.auth.static.*` → the literal
  ;; prefix `web.auth.static.` the parser actually spells), which is why a
  ;; family read by prefix is correctly seen. It lives here rather than beside
  ;; the registry because `project.capabilities` requires only `clojure.string`,
  ;; and a store-wide check there costs a slopp edge out of a low-layer module.
  (let [st       (external/built-store)
        base     (fn [k] (str/replace k #"\.\*.*$" ""))
        prod     (vec (for [n (keys (:namespaces st))
                            :when (not (str/ends-with? (str n) "-test"))
                            :when (not= 'slopp.project.capabilities n)
                            f (store/forms st n)]
                        (str (:node f))))
        mentions (fn [k] (boolean (some #(str/includes? % (base k)) prod)))
        orphans  (vec (remove mentions (map :key capabilities/registry)))]
    (testing "there is a population on BOTH sides"
      (is (< 10 (count capabilities/registry)) "the registry")
      (is (< 100 (count prod)) "the production forms this scans"))
    (testing "the detector bites — an empty orphan list must not be its only mode"
      (is (not (mentions "web.nosuch.invented.key")))
      (is (mentions "web.port") "and a key that IS mentioned is seen"))
    (testing "every declared capability is mentioned by some production form"
      (is (= [] orphans)
          (str "declared and mentioned nowhere in production code: "
               (pr-str orphans)
               " — either it is an unimplemented feature advertised as a"
               " setting (delete the row, or implement it), or it is read by a"
               " key assembled at runtime, which this cannot see: say so in the"
               " reader's docstring and it will be counted")))))

(deftest ^:external no-form-cites-a-document-that-does-not-ship
  ;; The helper directories that exist for whoever works ON slopp are NOT part
  ;; of the product, and store prose must not lean on them.
  ;;
  ;; The store SHIPS: every form is materialized into `src/` and jarred, so a
  ;; docstring is read by people who have neither directory. One of the two is
  ;; gitignored, so its paths resolve for literally nobody else. A docstring
  ;; that defers its reasoning to an unreachable file has no reasoning in it.
  ;;
  ;; **This is not hypothetical and the evidence is the reorganization that
  ;; prompted the guard.** Every such path in the store was ALREADY DEAD when
  ;; this was written — four docstrings citing files that had been moved into
  ;; subdirectories, pointing at nothing, with no tool able to notice because
  ;; nothing connects a stored form to an untracked local directory.
  ;;
  ;; The numbered-principle tags are the same defect one notch softer: the
  ;; sentence beside them usually says the thing, so the tag is a dangling
  ;; label rather than lost reasoning — but it still means nothing to a reader
  ;; without the file, and "say it or drop it" costs nothing either way.
  ;;
  ;; The patterns are BUILT rather than spelled, so this form does not report
  ;; itself. That is cheaper and more honest than an exemption list, which
  ;; would be a hand-kept carve-out in a check whose whole point is that prose
  ;; and code drift apart when nothing holds them together.
  (let [st       (external/built-store)
        dirs     [(str "." "context" "/") (str "idea" "s" "/")]
        tag-rx   (re-pattern (str "\\b" "Cor" "e \\d+"))
        cites    (fn [src]
                   (vec (distinct (concat (filter #(str/includes? src %) dirs)
                                          (re-seq tag-rx src)))))
        rows     (vec (for [n (keys (:namespaces st))
                            f (store/forms st n)
                            :let [hits (cites (str (:node f)))]
                            :when (seq hits)]
                        {:form (symbol (str n) (str (:name f))) :cites hits}))]
    (testing "there is a population — the scan reached real source"
      (is (< 2000 (count (for [n (keys (:namespaces st))
                               f (store/forms st n)] f)))))
    (testing "the detector bites, on both shapes"
      (is (= [(first dirs)] (cites (str "see " (first dirs) "architecture.md"))))
      (is (seq (cites (str "the " "Cor" "e 1 rule"))))
      (is (= [] (cites "an ordinary docstring naming no helper document"))))
    (testing "no stored form cites a document that does not ship with it"
      (is (= [] rows)
          (str (count rows) " form(s) cite a helper doc the reader will not have"
               " — state the reasoning inline instead: "
               (pr-str (mapv :form (take 8 rows))))))))
