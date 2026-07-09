(ns slopp.merge-test
  "Phase 4 m2: the CRDT merge. Two stores diverge from a common delta-log
  prefix; merge-logs replays theirs' suffix onto ours, form-id-keyed:
  different-form work merges clean, identical changes converge silently,
  same-form divergence = MV conflict (ours kept, theirs surfaced)."
  (:require [clojure.test :refer [deftest is testing]]
            [rewrite-clj.parser :as p]
            [slopp.store :as store]
            [slopp.render :as render]))

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
        r      (store/merge-logs ours theirs)]
    (is (empty? (:conflicts r)))
    (is (= 2 (:merged r)))
    (let [src (render/render-ns (:store r) 'm.core)]
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
        r      (store/merge-logs ours theirs)]
    (is (= 1 (count (:conflicts r))))
    (testing "ours kept; theirs carried in the conflict record"
      (is (re-find #":ours" (render/render-ns (:store r) 'm.core)))
      (is (re-find #":theirs" (:theirs (first (:conflicts r)))))
      (is (= 'm.core/a (:form (first (:conflicts r))))))))

(deftest identical-changes-converge-silently
  (let [b      (base)
        ours   (replace! b 'a "(defn a [x] (inc x))")
        theirs (replace! b 'a "(defn a [x] (inc x))")
        r      (store/merge-logs ours theirs)]
    (is (empty? (:conflicts r)))
    (is (zero? (:merged r)))))

(deftest delete-vs-edit-conflicts
  (let [b      (base)
        ours   (first (store/remove-form b 'm.core 'c :prompt "drop c"))
        theirs (replace! b 'c "(defn c [x] :kept-by-them)")
        r      (store/merge-logs ours theirs)]
    (is (= 1 (count (:conflicts r))))
    (is (not (re-find #"kept-by-them" (render/render-ns (:store r) 'm.core))))))

(deftest add-add-id-collisions-are-remapped
  ;; both sides allocate the same next f<n> — the merge must keep BOTH forms
  (let [b      (base)
        ours   (first (store/append-form b 'm.core
                                         (p/parse-string "(defn ours-new [x] x)")
                                         :prompt "ours"))
        theirs (first (store/append-form b 'm.core
                                         (p/parse-string "(defn theirs-new [x] x)")
                                         :prompt "theirs"))
        r      (store/merge-logs ours theirs)]
    (is (empty? (:conflicts r)))
    (let [src (render/render-ns (:store r) 'm.core)]
      (is (re-find #"ours-new" src))
      (is (re-find #"theirs-new" src)))
    (testing "no duplicate form ids after remap"
      (let [ids (map :id (store/forms (:store r) 'm.core))]
        (is (= (count ids) (count (set ids))))))))

(deftest new-namespace-from-theirs-arrives
  (let [b      (base)
        ours   (replace! b 'a "(defn a [x] (+ x 1))")
        theirs (store/ingest b 'm.extra "(ns m.extra)\n(defn e [x] x)\n")
        r      (store/merge-logs ours theirs)]
    (is (empty? (:conflicts r)))
    (is (contains? (set (:new-nses r)) 'm.extra))
    (is (re-find #"defn e" (render/render-ns (:store r) 'm.extra)))))

(deftest iterated-fork-merges-stay-exact
  ;; the fork keeps working after being merged once; later merges must
  ;; deliver only the NEW work — our copies of THEIR round-1 deltas must not
  ;; masquerade as "our" edits (false conflicts)
  (let [b     (base)
        ;; mainline does its own work — the realistic case (why you forked)
        main0 (replace! b 'b "(defn b [x] :main-work)")
        fork1 (replace! b 'a "(defn a [x] :round-1)")
        m1    (store/merge-logs main0 fork1 :from "fork")
        main1 (first (store/record-merge (:store m1) "fork" m1))
        ;; fork continues on the SAME form
        fork2 (replace! fork1 'a "(defn a [x] :round-2)")
        m2    (store/merge-logs main1 fork2 :from "fork")]
    (testing "round 2 lands cleanly — mainline never touched 'a"
      (is (empty? (:conflicts m2)))
      (is (= 1 (:merged m2)))
      (is (re-find #":round-2" (render/render-ns (:store m2) 'm.core))))
    (testing "a third merge with nothing new is a no-op"
      (let [main2 (first (store/record-merge (:store m2) "fork" m2))
            m3    (store/merge-logs main2 fork2 :from "fork")]
        (is (zero? (:merged m3)))
        (is (empty? (:conflicts m3)))))
    (testing "GENUINE same-form conflict still fires on iterated merges"
      (let [main2  (first (store/record-merge (:store m2) "fork" m2))
            main2' (replace! main2 'a "(defn a [x] :ours-now)")
            fork3  (replace! fork2 'a "(defn a [x] :round-3)")
            m4     (store/merge-logs main2' fork3 :from "fork")]
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
        m1    (store/merge-logs main0 fork1 :from "fork")
        main1 (first (store/record-merge (:store m1) "fork" m1))
        ;; fork edits ITS added form
        fork2 (replace! fork1 'added "(defn added [x] :v2)")
        m2    (store/merge-logs main1 fork2 :from "fork")]
    (is (empty? (:conflicts m1)))
    (testing "merge #2 edits the fork's form, never mainline's collided one"
      (is (empty? (:conflicts m2)))
      (is (= 1 (:merged m2)))
      (let [src (render/render-ns (:store m2) 'm.core)]
        (is (re-find #"added \[x\] :v2" src))
        (is (re-find #"mine \[x\] :mine" src))))))

(deftest causal-state-is-scoped-per-source
  ;; two different forks both have a delta "d5"; fork-A's delivery must not
  ;; mark fork-B's d5 as merged
  (let [b      (base)
        fork-a (replace! b 'a "(defn a [x] :from-a)")
        fork-b (replace! b 'b "(defn b [x] :from-b)")
        m1     (store/merge-logs b fork-a :from "fork-a")
        main1  (first (store/record-merge (:store m1) "fork-a" m1))
        m2     (store/merge-logs main1 fork-b :from "fork-b")]
    (is (= 1 (:merged m2)))
    (let [src (render/render-ns (:store m2) 'm.core)]
      (is (re-find #":from-a" src))
      (is (re-find #":from-b" src)))))

(deftest recreated-fork-path-is-detected-not-swallowed
  ;; rm -rf fork; cp -r base fork AGAIN: the new copy mints the SAME delta
  ;; ids as the merged-and-gone old fork. Its work must NOT be silently
  ;; dropped as "already delivered" — surface an identity error instead.
  (let [b      (base)
        fork-a (replace! b 'a "(defn a [x] :old-fork-work)")
        m1     (store/merge-logs b fork-a :from "the-fork-dir")
        main1  (first (store/record-merge (:store m1) "the-fork-dir" m1))
        ;; the recreated fork: fresh copy of the SAME base, different work,
        ;; colliding delta ids
        fork-b (replace! b 'b "(defn b [x] :new-fork-work)")
        m2     (store/merge-logs main1 fork-b :from "the-fork-dir")]
    (is (:error m2))
    (is (re-find #"recreated" (:error m2)))))
