(ns slopp.merge-test
  "Phase 4 m2: the CRDT merge. Two stores diverge from a common delta-log
  prefix; merge-logs replays theirs' suffix onto ours, form-id-keyed:
  different-form work merges clean, identical changes converge silently,
  same-form divergence = MV conflict (ours kept, theirs surfaced)."
  (:require [clojure.test :refer [deftest is testing]]
            [rewrite-clj.parser :as p]
            [slopp.store :as store]
            [slopp.store.render :as store.render] [slopp.store.merge :as merge] [clojure.string :as str]))

(def base-src "(ns m.core)\n(defn a [x] x)\n(defn b [x] x)\n(defn c [x] x)\n")

(defn- base [] (store/ingest (store/empty-store) 'm.core base-src))

(defn- replace! [st nm src]
  (first (store/replace-node st 'm.core nm (p/parse-string src)
                             :prompt (str "edit " nm))))

(deftest different-form-divergence-merges-clean
  (let [b      (base)
        ours   (replace! b 'a "(defn a [x] (+ x 1))")
        theirs (-> b
                   (replace! 'b "(defn b [x] (+ x 2))")
                   (store/append-form 'm.core (p/parse-string "(defn d [x] (* x 4))")
                                      :prompt "new fn" :agent "them")
                   first)
        r      (merge/merge-logs ours theirs)]
    (is (empty? (:conflicts r)))
    (is (= 2 (:merged r)))
    (let [src (store.render/render-ns (:store r) 'm.core)]
      (testing "both sides' work present"
        (is (re-find #"\(\+ x 1\)" src))
        (is (re-find #"\(\+ x 2\)" src))
        (is (re-find #"\(\* x 4\)" src))))
    (testing "provenance survives the merge (their agent, their prompt)"
      (let [merged-add (->> (store/deltas (:store r))
                            (filter #(= :add (:op %))) last)]
        (is (= "them" (:agent merged-add)))))))

(deftest same-form-divergence-is-an-mv-conflict
  (let [b      (base)
        ours   (replace! b 'a "(defn a [x] :ours)")
        theirs (replace! b 'a "(defn a [x] :theirs)")
        r      (merge/merge-logs ours theirs)]
    (is (= 1 (count (:conflicts r))))
    (testing "ours kept; theirs carried in the conflict record"
      (is (re-find #":ours" (store.render/render-ns (:store r) 'm.core)))
      (is (re-find #":theirs" (:theirs (first (:conflicts r)))))
      (is (= 'm.core/a (:form (first (:conflicts r))))))))

(deftest identical-changes-converge-silently
  (let [b      (base)
        ours   (replace! b 'a "(defn a [x] (inc x))")
        theirs (replace! b 'a "(defn a [x] (inc x))")
        r      (merge/merge-logs ours theirs)]
    (is (empty? (:conflicts r)))
    (is (zero? (:merged r)))))

(deftest delete-vs-edit-conflicts
  (let [b      (base)
        ours   (first (store/remove-form b 'm.core 'c :prompt "drop c"))
        theirs (replace! b 'c "(defn c [x] :kept-by-them)")
        r      (merge/merge-logs ours theirs)]
    (is (= 1 (count (:conflicts r))))
    (is (not (re-find #"kept-by-them" (store.render/render-ns (:store r) 'm.core))))))

(deftest add-add-id-collisions-are-remapped
  ;; both sides allocate the same next f<n> — the merge must keep BOTH forms
  (let [b      (base)
        ours   (first (store/append-form b 'm.core
                                         (p/parse-string "(defn ours-new [x] x)")
                                         :prompt "ours"))
        theirs (first (store/append-form b 'm.core
                                         (p/parse-string "(defn theirs-new [x] x)")
                                         :prompt "theirs"))
        r      (merge/merge-logs ours theirs)]
    (is (empty? (:conflicts r)))
    (let [src (store.render/render-ns (:store r) 'm.core)]
      (is (re-find #"ours-new" src))
      (is (re-find #"theirs-new" src)))
    (testing "no duplicate form ids after remap"
      (let [ids (map :id (store/forms (:store r) 'm.core))]
        (is (= (count ids) (count (set ids))))))))

(deftest new-namespace-from-theirs-arrives
  (let [b      (base)
        ours   (replace! b 'a "(defn a [x] (+ x 1))")
        theirs (store/ingest b 'm.extra "(ns m.extra)\n(defn e [x] x)\n")
        r      (merge/merge-logs ours theirs)]
    (is (empty? (:conflicts r)))
    (is (contains? (set (:new-nses r)) 'm.extra))
    (is (re-find #"defn e" (store.render/render-ns (:store r) 'm.extra)))))

(deftest iterated-fork-merges-stay-exact
  ;; the fork keeps working after being merged once; later merges must
  ;; deliver only the NEW work — our copies of THEIR round-1 deltas must not
  ;; masquerade as "our" edits (false conflicts)
  (let [b     (base)
        ;; mainline does its own work — the realistic case (why you forked)
        main0 (replace! b 'b "(defn b [x] :main-work)")
        fork1 (replace! b 'a "(defn a [x] :round-1)")
        m1    (merge/merge-logs main0 fork1 :from "fork")
        main1 (first (merge/record-merge (:store m1) "fork" m1))
        ;; fork continues on the SAME form
        fork2 (replace! fork1 'a "(defn a [x] :round-2)")
        m2    (merge/merge-logs main1 fork2 :from "fork")]
    (testing "round 2 lands cleanly — mainline never touched 'a"
      (is (empty? (:conflicts m2)))
      (is (= 1 (:merged m2)))
      (is (re-find #":round-2" (store.render/render-ns (:store m2) 'm.core))))
    (testing "a third merge with nothing new is a no-op"
      (let [main2 (first (merge/record-merge (:store m2) "fork" m2))
            m3    (merge/merge-logs main2 fork2 :from "fork")]
        (is (zero? (:merged m3)))
        (is (empty? (:conflicts m3)))))
    (testing "GENUINE same-form conflict still fires on iterated merges"
      (let [main2  (first (merge/record-merge (:store m2) "fork" m2))
            main2' (replace! main2 'a "(defn a [x] :ours-now)")
            fork3  (replace! fork2 'a "(defn a [x] :round-3)")
            m4     (merge/merge-logs main2' fork3 :from "fork")]
        (is (= 1 (count (:conflicts m4))))
        (is (re-find #":round-3" (:theirs (first (:conflicts m4)))))))))

(deftest cross-merge-id-remapping-persists
  ;; THE corruption case: the fork ADDS a form (remapped on merge #1), then
  ;; EDITS it; mainline meanwhile added its own form under the SAME original
  ;; id. Without a persisted id-map, merge #2 lands the fork's edit on the
  ;; WRONG form.
  (let [b     (base)
        fork1 (-> b
                  (store/append-form 'm.core (p/parse-string "(defn added [x] :v1)")
                                     :prompt "fork adds")
                  first)
        ;; mainline's own add mints the SAME form id as the fork's add
        main0 (-> b
                  (store/append-form 'm.core (p/parse-string "(defn mine [x] :mine)")
                                     :prompt "main adds")
                  first)
        m1    (merge/merge-logs main0 fork1 :from "fork")
        main1 (first (merge/record-merge (:store m1) "fork" m1))
        ;; fork edits ITS added form
        fork2 (replace! fork1 'added "(defn added [x] :v2)")
        m2    (merge/merge-logs main1 fork2 :from "fork")]
    (is (empty? (:conflicts m1)))
    (testing "merge #2 edits the fork's form, never mainline's collided one"
      (is (empty? (:conflicts m2)))
      (is (= 1 (:merged m2)))
      (let [src (store.render/render-ns (:store m2) 'm.core)]
        (is (re-find #"added \[x\] :v2" src))
        (is (re-find #"mine \[x\] :mine" src))))))

(deftest causal-state-is-scoped-per-source
  ;; two different forks both have a delta "d5"; fork-A's delivery must not
  ;; mark fork-B's d5 as merged
  (let [b      (base)
        fork-a (replace! b 'a "(defn a [x] :from-a)")
        fork-b (replace! b 'b "(defn b [x] :from-b)")
        m1     (merge/merge-logs b fork-a :from "fork-a")
        main1  (first (merge/record-merge (:store m1) "fork-a" m1))
        m2     (merge/merge-logs main1 fork-b :from "fork-b")]
    (is (= 1 (:merged m2)))
    (let [src (store.render/render-ns (:store m2) 'm.core)]
      (is (re-find #":from-a" src))
      (is (re-find #":from-b" src)))))

(deftest recreated-fork-path-is-detected-not-swallowed
  ;; rm -rf fork; cp -r base fork AGAIN: the new copy mints the SAME delta
  ;; ids as the merged-and-gone old fork. Its work must NOT be silently
  ;; dropped as "already delivered" — surface an identity error instead.
  (let [b      (base)
        fork-a (replace! b 'a "(defn a [x] :old-fork-work)")
        m1     (merge/merge-logs b fork-a :from "the-fork-dir")
        main1  (first (merge/record-merge (:store m1) "the-fork-dir" m1))
        ;; the recreated fork: fresh copy of the SAME base, different work,
        ;; colliding delta ids
        fork-b (replace! b 'b "(defn b [x] :new-fork-work)")
        m2     (merge/merge-logs main1 fork-b :from "the-fork-dir")]
    (is (:error m2))
    (is (re-find #"recreated" (:error m2)))))

(deftest move-deltas-replay-order-across-the-merge
  (let [b      (base)
        ours   (replace! b 'a "(defn a [x] (+ x 1))")
        theirs (-> b
                   (store/move-form 'm.core 'c 'a :prompt "c precedes a" :agent "them")
                   first)
        r      (merge/merge-logs ours theirs)]
    (is (empty? (:conflicts r)))
    (let [src (store.render/render-ns (:store r) 'm.core)]
      (testing "their reordering lands: c is defined before a"
        (is (< (.indexOf src "defn c") (.indexOf src "defn a")) src))
      (testing "our same-file divergence still merges clean beside it"
        (is (re-find #"\(\+ x 1\)" src))))
    (testing "the replay is applied, not skipped"
      (is (not-any? #(= :move (:skipped %)) (:notes r)) (pr-str (:notes r))))))

(deftest iterated-merge-with-id-collisions-keeps-adds-and-order
  (let [add     (fn [st src agent]
                  (first (store/append-form st 'm.core (p/parse-string src)
                                            :prompt "t" :agent agent)))
        b       (base)
        theirs1 (add b "(defn p1 [x] x)" "them")
        m1      (merge/merge-logs b theirs1 :from "web")
        ours1   (:store m1)
        ours2   (add ours1 "(defn mine [x] x)" "us")
        theirs2 (-> theirs1
                    (add "(defn prim [x] x)" "them")
                    (add "(defn gate [x] (prim x))" "them")
                    (store/move-form 'm.core 'gate 'c :prompt "order" :agent "them") first
                    (store/move-form 'm.core 'prim 'gate :prompt "order" :agent "them") first)
        r       (merge/merge-logs ours2 theirs2 :from "web")
        src     (store.render/render-ns (:store r) 'm.core)]
    (testing "their adds survive the cross-line id collision"
      (is (re-find #"defn prim" src) src)
      (is (re-find #"defn gate" src) src))
    (testing "our colliding add survives beside them"
      (is (re-find #"defn mine" src) src))
    (testing "their move-fixed order replays through the remap"
      (is (< (.indexOf src "defn prim") (.indexOf src "defn gate")) src)
      (is (< (.indexOf src "defn gate") (.indexOf src "defn c")) src))
    (testing "no conflicts — different work, the granularity dodge"
      (is (empty? (:conflicts r)) (pr-str (:conflicts r))))))

(deftest empty-changeset-replay-skips-instead-of-nil-store
  (let [b      (base)
        [t1 _] (store/apply-changeset b :normalize 'm.core
                                      {(:id (store/form-named b 'm.core 'c))
                                       (p/parse-string "(defn c [x] (inc x))")}
                                      :prompt "their normalize")
        ours   (first (store/remove-form b 'm.core 'c :prompt "we deleted c"))
        r      (merge/merge-logs ours t1)]
    (is (some? (:next-id (:store r)))
        "the store survives an all-absent changeset replay")
    (is (re-find #"defn a" (store.render/render-ns (:store r) 'm.core)))))

(deftest replace-aliasing-a-foreign-ns-form-skips-not-nils
  (let [b     (base)
        ours  (store/ingest b 'o.x "(ns o.x)\n(defn ox [] 1)\n")
        fid   (:id (store/form-named ours 'm.core 'b))
        theirs (update b :deltas conj
                       {:id "d9000" :parent (:id (last (:deltas b)))
                        :op :replace :ns 't.y :form-id fid
                        :sources {fid "(defn ty [] 3)"}
                        :prompt "an unmapped cross-line id alias" :at 1})
        r     (merge/merge-logs ours theirs)]
    (is (some? (:next-id (:store r)))
        "the store survives an aliased :replace")
    (testing "our aliased form is untouched"
      (is (re-find #"defn b" (store.render/render-ns (:store r) 'm.core))))
    (testing "the skip is noted, not silent"
      (is (some #(= :replace (:skipped %)) (:notes r)) (pr-str (:notes r))))))

(deftest config-and-files-cross-the-merge
  (let [b      (base)
        png    (byte-array [(byte -119) 80 78 71 1 2 3])
        b64    (.encodeToString (java.util.Base64/getEncoder) png)
        theirs (-> b
                   (store/record-config-put "capabilities" :manifest
                                            "web.enabled" "true") first
                   (store/record-file-put "public/a.png" b64
                                          :encoding "base64"
                                          :content-type "image/png") first
                   (store/record-file-put "NOTES.md" "hello\n") first)
        ours   (store/ingest b 'o.side "(ns o.side)\n(defn ^:unused-ok f [x] x)\n")
        r      (merge/merge-logs ours theirs)]
    (testing "config crosses"
      (is (= "true" (get-in (:store r)
                            [:config "capabilities" :values "web.enabled"]))))
    (testing "a text file crosses"
      (is (= "hello\n" (get-in (:store r) [:files "NOTES.md"]))))
    (testing "a binary file crosses WITH its bytes"
      (let [{:keys [content content-type]}
            (store/file-content (:store r) "public/a.png")]
        (is (= "image/png" content-type))
        (is (java.util.Arrays/equals png ^bytes content))))
    (testing "nothing about them is 'skipped'"
      (is (not-any? #(#{:config-put :file-put} (:skipped %)) (:notes r))
          (pr-str (:notes r))))))

(deftest round-tripped-copies-converge-instead-of-conflicting
  (let [b     (base)
        web1  (replace! b 'a "(defn a [x] :v2)")
        r1    (merge/merge-logs b web1 :from "branch:web#W")
        main1 (first (merge/record-merge (:store r1) "branch:web#W" r1))
        web2  (replace! web1 'a "(defn a [x] :v3)")
        r2    (merge/merge-logs web2 main1 :from "branch:main#M")]
    (testing "the returning copy of our own v2 is recognized, never a conflict"
      (is (empty? (:conflicts r2)) (pr-str (:conflicts r2))))
    (testing "our newer v3 stands"
      (is (re-find #":v3" (store.render/render-ns (:store r2) 'm.core))))
    (testing "main's own :merge marker rides through without effect"
      (is (some? (:store r2))))))

(deftest poisoned-idmap-falls-back-to-the-live-form
  (let [b    (base)
        fa   (:id (store/form-named b 'm.core 'a))
        ;; a PRIOR merge from this source left a stale mapping: their fa →
        ;; our f999, a form that no longer exists (merge ping-pong)
        ours (update b :deltas conj
                     {:id "d800" :parent (:id (last (:deltas b)))
                      :op :merge :ns '*session* :from "branch:web#W"
                      :applied [] :id-map {fa "f999"} :at 1 :merged 0})
        theirs (replace! b 'a "(defn a [x] :their-edit)")
        r    (merge/merge-logs ours theirs :from "branch:web#W")]
    (testing "their edit lands on the LIVE original instead of dropping"
      (is (empty? (:conflicts r)) (pr-str (:conflicts r)))
      (is (re-find #":their-edit" (store.render/render-ns (:store r) 'm.core))))))

(deftest duplicate-name-candidates-refuse-the-merge
  (let [b    (base)
        fa   (:id (store/form-named b 'm.core 'a))
        ;; their rename-shaped changeset rewrites OUR form `a` (by id) into a
        ;; form NAMED `b` — which m.core already defines elsewhere
        theirs (update b :deltas conj
                       {:id "d900" :parent (:id (last (:deltas b)))
                        :op :rename :ns 'm.core
                        :form-ids [fa]
                        :sources {fa "(defn b [x] :usurper)"}
                        :old 'a :new 'b :at 1})
        r    (merge/merge-logs b theirs :from "branch:web#W")]
    (testing "the merge refuses rather than landing two forms named b"
      (is (some? (:error r)) (pr-str (dissoc r :store)))
      (is (re-find #"duplicate" (str (:error r)))))))

(deftest partial-replay-copies-are-not-imposters
  (let [b   (base)
        fa  (:id (store/form-named b 'm.core 'a))
        fb  (:id (store/form-named b 'm.core 'b))
        ;; theirs: a two-form changeset delta
        theirs (update b :deltas conj
                       {:id "d900" :parent (:id (last (:deltas b)))
                        :op :rename :ns 'm.core :form-ids [fa fb]
                        :sources {fa "(defn a2 [x] x)" fb "(defn b2 [x] (a2 x))"}
                        :old 'a :new 'a2 :at 1})
        ;; ours: a PRIOR merge delivered d900, but the replay was PARTIAL —
        ;; our copy carries only ONE of the two sources
        ours (-> b
                 (update :deltas conj
                         {:id "d800" :parent (:id (last (:deltas b)))
                          :op :rename :ns 'm.core :form-ids [fa]
                          :sources {fa "(defn a2 [x] x)"}
                          :merged-from "d900" :at 2})
                 (update :deltas conj
                         {:id "d801" :parent "d800"
                          :op :merge :ns '*session* :from "branch:t#T"
                          :applied ["d900"] :at 3 :merged 1}))
        r    (merge/merge-logs ours theirs :from "branch:t#T")]
    (testing "the partial copy is delivered history, not an identity mismatch"
      (is (nil? (:error r)) (pr-str (:error r)))))
  (testing "a TRUE imposter (copy content the original never had) still errors, with :fork-point"
    (let [b   (base)
          fa  (:id (store/form-named b 'm.core 'a))
          theirs (update b :deltas conj
                         {:id "d900" :parent (:id (last (:deltas b)))
                          :op :rename :ns 'm.core :form-ids [fa]
                          :sources {fa "(defn recreated [x] :new-line)"}
                          :at 1})
          ours (-> b
                   (update :deltas conj
                           {:id "d800" :parent (:id (last (:deltas b)))
                            :op :rename :ns 'm.core :form-ids [fa]
                            :sources {fa "(defn old-work [x] :dead-line)"}
                            :merged-from "d900" :at 2})
                   (update :deltas conj
                           {:id "d801" :parent "d800"
                            :op :merge :ns '*session* :from "branch:t#T"
                            :applied ["d900"] :at 3 :merged 1}))
          r    (merge/merge-logs ours theirs :from "branch:t#T")]
      (is (some? (:error r)))
      (is (some? (:fork-point r)) "the error result names the fork point so callers never mask it"))))

(deftest edits-to-their-copies-land-on-our-originals
  (let [b     (base)
        ours  (store/ingest b 'w.core "(ns w.core)\n(defn ^:unused-ok orig [x] :v1)\n")
        fx    (:id (store/form-named ours 'w.core 'orig))
        ;; main is BUSY first, so the replay of our ingest REMAPS ids
        main0 (store/ingest b 'm.busy "(ns m.busy)\n(defn ^:unused-ok mb [x] x)\n")
        r1    (merge/merge-logs main0 ours :from "branch:web#W")
        main1 (first (merge/record-merge (:store r1) "branch:web#W" r1))
        fy    (:id (store/form-named main1 'w.core 'orig))
        main2 (first (store/replace-node main1 'w.core 'orig
                                         (p/parse-string "(defn ^:unused-ok orig [x] :v2)")
                                         :prompt "their evolution"))
        r2    (merge/merge-logs ours main2 :from "branch:main#M")]
    (testing "the ids genuinely bifurcated (the replay remapped)"
      (is (not= fx fy) (str fx " vs " fy)))
    (testing "their edit resolves through the INVERSE of their recorded id-map"
      (is (empty? (:conflicts r2)) (pr-str (:conflicts r2)))
      (is (re-find #":v2" (store.render/render-ns (:store r2) 'w.core))))))

(deftest successive-edits-to-a-diverged-form-coalesce-into-one-conflict
  (let [b      (base)
        ours   (replace! b 'a "(defn a [x] :ours)")
        theirs (-> b
                   (replace! 'a "(defn a [x] :t1)")
                   (replace! 'a "(defn a [x] :t2)")
                   (replace! 'a "(defn a [x] :t3)"))
        r      (merge/merge-logs ours theirs)]
    (testing "one conflict, not three"
      (is (= 1 (count (:conflicts r))) (pr-str (:conflicts r))))
    (testing "it carries the NEWEST theirs"
      (is (re-find #":t3" (str (:theirs (first (:conflicts r)))))))
    (testing "ours stays live"
      (is (re-find #":ours" (store.render/render-ns (:store r) 'm.core))))))

(deftest merged-state-deltas-get-fresh-ids-not-verbatim
  ;; the durable-merge corruption: both lines do config/deps/tier work from
  ;; the SAME fork → the same next-id → the merge landed theirs' delta
  ;; VERBATIM, minting a duplicate delta id that db/append!'s UNIQUE
  ;; constraint rejects — a permanent, misleading "store changed during
  ;; merge — retry" on every real branch that touched capabilities/deps/
  ;; tiers on both sides. Ephemeral merge tests never reached append!, so
  ;; nothing caught it. The merge must re-mint state deltas with a fresh id.
  (let [b      (base)
        ours   (first (store/record-config-put b "capabilities" :manifest "web.port" "8080"))
        theirs (first (store/record-config-put b "capabilities" :manifest "web.enabled" "true"))
        {:keys [store]} (merge/merge-logs ours theirs :from "branch:web#x")
        ids    (mapv :id (store/deltas store))
        base-ids (set (map :id (store/deltas ours)))
        tail   (drop (count (store/deltas ours)) ids)]
    (testing "the merged journal has NO duplicate delta ids"
      (is (= (count ids) (count (distinct ids))) (pr-str ids)))
    (testing "the appendable tail (what db/append! inserts) collides with nothing already committed"
      (is (empty? (filter base-ids tail)) (pr-str tail)))
    (testing "the crossed config still converges (state, not just ids)"
      (is (= "true" (get-in store [:config "capabilities" :values "web.enabled"])))
      (is (= "8080" (get-in store [:config "capabilities" :values "web.port"]))))
    (testing "the re-minted delta keeps provenance to theirs"
      (is (some :merged-from (drop (count (store/deltas ours)) (store/deltas store)))))))

(deftest text-merge-is-three-way-and-knows-nothing-about-git
  ;; A tracked file used to be absorbed whole: ours-unchanged took theirs
  ;; wholesale, and all-three-differ was a CONFLICT — so two people editing
  ;; different paragraphs of one README handed an agent a manual merge that a
  ;; three-way would have done. That is work pushed onto an agent for no
  ;; reason.
  ;;
  ;; PURE, and deliberately not in the git namespace: it takes three strings
  ;; and returns one. The implementation borrows jgit's merge algorithm — the
  ;; same one git itself uses, already on the classpath because the projection
  ;; needs jgit — but it constructs no Repository and touches no disk, so an
  ;; import from a directory that was never in git uses this unchanged.
  (testing "edits to DIFFERENT lines both survive"
    (let [r (merge/merge-text "a\nb\nc\n" "A\nb\nc\n" "a\nb\nC\n")]
      (is (= "A\nb\nC\n" (:merged r)) (pr-str r))
      (is (nil? (:conflict r)) (pr-str r))))

  (testing "edits to the SAME line conflict, and the conflict SHOWS the hunk"
    (let [r (merge/merge-text "a\nb\nc\n" "a\nX\nc\n" "a\nY\nc\n")]
      (is (nil? (:merged r)) (pr-str r))
      (is (string? (:conflict r)))
      (testing "the unaffected lines are not part of the conflict — the agent
                is shown the overlap, not the whole file"
        (is (clojure.string/includes? (str (:conflict r)) "X") (pr-str r))
        (is (clojure.string/includes? (str (:conflict r)) "Y") (pr-str r))
        (is (clojure.string/starts-with? (str (:conflict r)) "a\n") (pr-str r)))))

  (testing "one side unchanged is not a conflict in either direction"
    (is (= "A\nb\n" (:merged (merge/merge-text "a\nb\n" "A\nb\n" "a\nb\n"))))
    (is (= "a\nB\n" (:merged (merge/merge-text "a\nb\n" "a\nb\n" "a\nB\n")))))

  (testing "a missing base is treated as empty rather than throwing — a file
            the remote ADDED has no base to merge against"
    (let [r (merge/merge-text nil "" "new\n")]
      (is (= "new\n" (:merged r)) (pr-str r)))))
