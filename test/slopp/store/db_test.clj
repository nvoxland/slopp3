(ns slopp.store.db-test
  "Tests for the SQLite layer: what the journal keeps, and what it costs.

  The store is a delta log, so these tests are about durability rather than
  behaviour — a store that round-trips wrong loses work, and one that round-
  trips right but grows without bound eventually stops opening. Both failures
  have happened here, which is why both are pinned: byte-exactness of what is
  written and read back, and the SHAPE of what gets written in the first place.

  The recurring lesson is that a store can rot by GROWING. A byte-exact tree
  snapshot in every milestone reached 94% of a 344MB journal, unnoticed across
  239 of them, and was re-parsed at every session open. What came of that —
  the tree in its own column, read on demand, stored as a diff against the
  previous milestone — is most of what is tested here."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.store.render :as render]
            [slopp.store.db :as db]
            [slopp.api :as api] [slopp.api.query :as query] [slopp.api.external :as external] [clojure.java.io :as io] [next.jdbc :as jdbc] [rewrite-clj.node :as n])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir []
  (str (Files/createTempDirectory "slopp-db-test" (make-array FileAttribute 0))))

(def corpus
  ["(ns foo)\n\n(defn add [x y]\n  (+ x y))\n\n;; a comment\n(def z 1)\n"
   "(ns bar\n  (:require [clojure.string :as str]))\n\n(def ^:private secret 42)\n"
   ";; leading comment\n(def a 1)(def b 2)\n\n\n"])

(deftest ^:external db-round-trip-is-exact
  (testing "persist -> load reconstructs the store exactly (render + deltas + ids)"
    (doseq [src corpus]
      (let [dir  (temp-dir)
            conn (db/open! dir)
            s    (store/ingest (store/empty-store) 'ns src)]
        (db/persist! conn s (last (store/deltas s)))
        (.close conn)
        (let [conn2  (db/open! dir)
              loaded (db/load-store conn2)]
          ;; against the STORE's render, not the raw source: spacing is normalized
          ;; at ingest now, so comparing to `src` would be testing the
          ;; renderer's retired byte-exact contract rather than persistence
          (is (= (render/render-ns s 'ns) (render/render-ns loaded 'ns))
              (str "render round-trip failed for: " (pr-str src)))
          (is (= (store/deltas s) (store/deltas loaded)))
          (is (= (map :id (store/forms s 'ns)) (map :id (store/forms loaded 'ns))))
          (is (= (:next-id s) (:next-id loaded)))
          (.close conn2))))))

(deftest ^:external session-survives-restart
  (let [dir (temp-dir)
        target (str "(ns demo\n  (:require [clojure.test :refer [deftest is]]))\n"
                    "(defn add [x y] (+ x y))\n"
                    "(deftest t (is (= 6 (add 2 3))))\n")
        sess (external/open! {:slopp.api/dir dir})]
    (try
      (api/ingest! sess 'demo target)
      (api/edit-replace! sess 'demo 'add "(defn add [x y] (+ x y 1))" :prompt "off-by-one")
      (api/test-run! sess 'demo)
      (finally (api/close! sess)))
    ;; process "restarts": a brand-new session over the same dir
    (let [sess2 (external/open! {:slopp.api/dir dir})]
      (try
        (testing "source is reconstructed from the db"
          (is (re-find #"\(\+ x y 1\)" (query/query-source sess2 'demo))))
        (testing "the image was reloaded from the store"
          (is (= [6] (api/query-eval sess2 "(demo/add 2 3)"))))
        (testing "lineage (incl. prompt and verification) survives"
          (let [lin (query/query-lineage sess2 'demo 'add)]
            (is (some #(= "off-by-one" (:prompt %)) lin))
            (is (contains? (set (map :op lin)) :ingest)))
          (is (= :verify (:op (last (store/deltas (:store @sess2)))))))
        (testing "new edits continue cleanly (no id collisions with history)"
          (let [r (api/edit-replace! sess2 'demo 'add "(defn add [x y] (* x y))"
                                     :prompt "mul")]
            (is (nil? (:error r)))
            (is (= [6] (api/query-eval sess2 "(demo/add 2 3)")))))
        (finally (api/close! sess2))))))

(deftest ^:external module-tiers-survive-persist-and-reload
  (testing "declared purity tiers reconstruct through persist! -> load-store"
    (let [dir     (temp-dir)
          conn    (db/open! dir)
          [s1 d1] (store/record-module-tier (store/empty-store) "app.core" :pure
                                             :prompt "core is pure")]
      (db/persist! conn s1 d1)
      (.close conn)
      (let [conn2  (db/open! dir)
            loaded (db/load-store conn2)]
        (is (= {"app.core" :pure} (:module-tiers loaded)))
        (.close conn2)))))

(deftest ^:external a-storeless-dir-materializes-on-the-first-write
  ;; The MCP server is launched in whatever dir the editor has open, so
  ;; opening a session must not COLONISE a project that never asked for
  ;; slopp. The store appears on the first real write and not before —
  ;; which is what the slopp-setup skill has always promised.
  (let [dir  (temp-dir)
        sdir (io/file dir ".slopp")
        sess (external/open! {:slopp.api/dir dir})]
    (try
      (testing "opening a session on a storeless dir writes nothing to disk"
        (is (not (.exists sdir))
            ".slopp/ must not be created just by serving a dir"))
      (testing "the first real write materializes the store"
        (api/ingest! sess 'demo "(ns demo)\n(defn add [x y] (+ x y))\n")
        (is (.exists (io/file sdir "store.db"))))
      (finally (api/close! sess)))
    (testing "and that write is durable — a fresh session reads it back"
      (let [sess2 (external/open! {:slopp.api/dir dir})]
        (try
          (is (re-find #"\(\+ x y\)" (query/query-source sess2 'demo)))
          (finally (api/close! sess2)))))))

(deftest ^:external legacy-tier-spellings-normalize-at-load
  (testing "a pre-canonicalization db row (:effects) loads as :external"
    (let [dir     (temp-dir)
          conn    (db/open! dir)
          [s1 d1] (store/record-module-tier (store/empty-store) "app.core" :pure
                                            :prompt "core is pure")]
      (db/persist! conn s1 d1)
      ;; simulate an old store: the meta row carries a retired spelling
      (jdbc/execute! conn ["INSERT INTO meta (k,v) VALUES ('module-tiers', ?)
                            ON CONFLICT(k) DO UPDATE SET v = excluded.v"
                           (pr-str {"app.core" :pure "app.shell" :effects})])
      (.close conn)
      (let [conn2  (db/open! dir)
            loaded (db/load-store conn2)]
        (is (= {"app.core" :pure "app.shell" :external} (:module-tiers loaded)))
        (.close conn2)))))

(deftest commit-tree-lives-outside-the-parsed-payload
  ;; 94% of this store's journal is :commit :tree snapshots (~1.35MB each, 239
  ;; of them) — re-parsed on EVERY session open even though only the git
  ;; projection reads them (measured: 7.4s of a 9.4s load-store). The tree now
  ;; lives in its own column: load-store never selects or parses it, and
  ;; db/delta-tree fetches it on demand. Byte-exactness matters — git shas are
  ;; content-addressed off this tree.
  (let [dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-tree" (make-array java.nio.file.attribute.FileAttribute 0)))
        conn (db/open! dir)
        tree {'a.core "(ns a.core)\n\n;; trivia survives\n(defn f [] 1)\n"}
        d    {:id "d1" :op :commit :ns '*session*
              :description "m" :target "d0" :tree tree}]
    (try
      (is (true? (db/append! conn (store/empty-store) [d] [] nil)))
      (testing "a loaded delta carries no :tree — the bytes are never parsed at open"
        (let [ld (first (filter #(= "d1" (:id %)) (:deltas (db/load-store conn))))]
          (is (some? ld))
          (is (nil? (:tree ld)))
          (is (= "m" (:description ld)) "the small payload fields still round-trip")
          (is (= "d0" (:target ld)))))
      (testing "the tree round-trips byte-exactly, on demand"
        (is (= tree (db/delta-tree conn "d1"))))
      (testing "a non-commit delta has no tree and asking is nil, not an error"
        (is (nil? (db/delta-tree conn "nope"))))
      (finally (.close conn)))))

(deftest blobs-are-not-pulled-into-memory-at-open
  ;; The same "don't read it at open" lever as the commit trees: all-blobs
  ;; pulled EVERY blob's bytes into the store value at every session open, and
  ;; a compiled JS bundle is ~1.8MB. Nothing at open needs them. This is safe by
  ;; construction, not by luck: :blobs is a PARTIAL cache by design (file-content
  ;; documents the miss and defers to the db), and put-blobs! is INSERT OR
  ;; IGNORE, so an empty cache on the next write is a no-op and can never prune.
  (let [dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-blobs" (make-array java.nio.file.attribute.FileAttribute 0)))
        conn (db/open! dir)
        png  (byte-array [(byte -119) 80 78 71 13 10 26 10])
        b64  (.encodeToString (java.util.Base64/getEncoder) png)
        [s1 _] (store/record-file-put (store/empty-store) "public/logo.png" b64
                                      :encoding "base64" :content-type "image/png")
        sha  (get-in s1 [:files "public/logo.png" :sha])]
    (try
      (is (true? (db/append! conn s1 [] [] nil)))
      (let [loaded (db/load-store conn)]
        (testing "the manifest entry loads, the BYTES do not"
          (is (contains? (:files loaded) "public/logo.png"))
          (is (empty? (:blobs loaded))))
        (testing "and a later write cannot prune what was never loaded"
          (is (true? (db/append! conn loaded [] [] nil)))
          (is (java.util.Arrays/equals png ^bytes (db/get-blob conn sha)))))
      (testing "the bytes are still there, on demand"
        (is (java.util.Arrays/equals png ^bytes (db/get-blob conn sha))))
      (finally (.close conn)))))

(deftest a-bad-statement-surfaces-instead-of-looking-like-contention
  ;; append! caught EVERY SQLException and returned false, which the caller's
  ;; rebase loop reads as "the head moved, retry" — so a malformed statement
  ;; (a column that does not exist, a constraint violation) was reported as
  ;; "commit contention: too many concurrent writes". That happened for real:
  ;; a write-path change referencing a not-yet-migrated column killed every
  ;; write, and the message sent the diagnosis hunting phantom writers while
  ;; the store was unwritable. Only a genuine writer collision is retryable;
  ;; anything else must SURFACE.
  (let [dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-append" (make-array java.nio.file.attribute.FileAttribute 0)))
        conn (db/open! dir)]
    (try
      (testing "a constraint violation throws rather than masquerading as contention"
        (is (thrown? java.sql.SQLException
                     (db/append! conn (store/empty-store)
                                 [{:id nil :op :add :ns 'x.core}] [] nil))))
      (testing "a genuine writer collision is still a retryable false"
        (is (false? (db/append! conn (store/empty-store)
                                [{:id "d1" :op :add :ns 'x.core}] []
                                "a-head-that-never-existed"))))
      (finally (.close conn)))))

(deftest journal-stats-reports-what-the-store-carries
  ;; Nothing measured the COST of what the store holds, so a byte-exact :tree
  ;; snapshot inline in every :commit payload grew to 94% of a 344MB journal —
  ;; unnoticed across 239 milestones, against a design note that estimated
  ;; "tens of KB". full_check counts namespaces and tests; nothing counted
  ;; bytes. The cheapest guard against the next one is a number nobody has to
  ;; go looking for.
  (let [dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-health" (make-array java.nio.file.attribute.FileAttribute 0)))
        conn (db/open! dir)
        st   (store/ingest (store/empty-store) 'sh.core
                           "(ns sh.core)\n\n(defn f \"F.\" [x] x)\n")]
    (try
      (is (true? (db/append! conn st
                             [{:id "d1" :op :ingest :ns 'sh.core :prompt "seed"}
                              {:id "d2" :op :commit :ns '*session* :description "m"
                               :target "d1"
                               :tree {'sh.core "(ns sh.core)\n(defn f [x] x)\n"}}]
                             ['sh.core] nil)))
      (let [s (db/journal-stats conn)]
        (testing "the journal is measured, payload and tree counted APART"
          (is (= 2 (get-in s [:deltas :n])))
          (is (pos? (get-in s [:deltas :payload-bytes])))
          (is (pos? (get-in s [:deltas :tree-bytes]))
              "commit trees are the thing that grew unwatched — count them alone"))
        (testing "per-op rows, heaviest first, so the outlier is the first thing read"
          (let [ops (map :op (get-in s [:deltas :by-op]))]
            (is (= #{"ingest" "commit"} (set ops)))
            (is (= "commit" (first ops)) "the tree-carrying op leads")))
        (testing "state is measured too, so history-vs-state is visible"
          (is (pos? (get-in s [:elements :n])))
          (is (pos? (get-in s [:elements :source-bytes])))
          (is (= 0 (get-in s [:blobs :n])))))
      (finally (.close conn)))))

(deftest tree-diffs-round-trip-against-their-base
  ;; 388MB — 75% of this journal — is :tree snapshots, and almost every entry
  ;; in one is byte-identical to the milestone before it: median 5 namespaces
  ;; change out of 191. The tree cannot be RE-DERIVED (trivia lives in the
  ;; elements table, which holds only current state), so it has to be stored —
  ;; but it does not have to be stored whole.
  (let [base {'a.core "(ns a.core)\n\n;; trivia\n(defn f [] 1)\n"
              'b.core "(ns b.core)\n(defn g [] 2)\n"
              'c.core "(ns c.core)\n(defn h [] 3)\n"}
        next {'a.core "(ns a.core)\n\n;; trivia\n(defn f [] 1)\n"   ; unchanged
              'b.core "(ns b.core)\n(defn g [] 22)\n"              ; changed
              'd.core "(ns d.core)\n(defn i [] 4)\n"}]             ; added, c removed
    (testing "a diff carries only what moved"
      (let [[tag df] (db/tree-diff base next "d100" 0)]
        (is (= :slopp.store.db/tree-diff tag)
            "TAGGED, not key-discriminated: a tree is a symbol-keyed sorted-map,
             so looking up a keyword in one throws instead of returning nil")
        (is (= "d100" (:base df)))
        (is (= #{'b.core 'd.core} (set (keys (:changed df))))
            "an unchanged namespace must not be re-stored — that is the whole point")
        (is (= #{'c.core} (set (:removed df))))))
    (testing "applying it reproduces the tree BYTE-exactly — a git sha depends on it"
      (is (= next (db/tree-apply base (db/tree-diff base next "d100" 0)))))
    (testing "trivia survives the round trip"
      (is (= (get next 'a.core)
             (get (db/tree-apply base (db/tree-diff base next "d100" 0)) 'a.core))))
    (testing "no base means a full snapshot, which is what an old marker already holds"
      (let [df (db/tree-diff nil next nil 0)]
        (is (map? df) "a full tree stays a plain {ns source} map — old rows stay readable")
        (is (= next df))
        (is (= next (db/tree-apply nil df)) "and applying one is the identity")))
    (testing "depth counts the chain, so reconstruction can be bounded"
      (is (= 1 (:depth (second (db/tree-diff base next "d100" 0)))))
      (is (= 4 (:depth (second (db/tree-diff base next "d100" 3))))
          "one more link than the diff it is based on"))
    (testing "at the bound the chain RESTARTS with a full snapshot"
      (is (vector? (db/tree-diff base next "d100" (dec db/max-tree-chain)))
          "one short of the bound still diffs")
      (is (= next (db/tree-diff base next "d100" db/max-tree-chain))
          "an unbounded chain makes projecting a fresh repo quadratic")
      (is (= next (db/tree-diff base next "d100" (inc db/max-tree-chain)))))))

(deftest tree-at-resolves-a-diff-chain-back-to-a-full-tree
  (let [dir  (str (Files/createTempDirectory
                   "slopp-treechain" (make-array FileAttribute 0)))
        conn (db/open! dir)
        t1   {'a.core "(ns a.core)\n\n;; trivia\n(defn f [] 1)\n"
              'b.core "(ns b.core)\n(defn g [] 2)\n"}
        t2   (assoc t1 'b.core "(ns b.core)\n(defn g [] 22)\n")
        t3   (-> t2 (dissoc 'a.core) (assoc 'c.core "(ns c.core)\n(defn h [] 3)\n"))
        mk   (fn [id tree] {:id id :op :commit :ns '*session*
                            :description (str "m" id) :target "d0" :tree tree})]
    (try
      (is (true? (db/append! conn (store/empty-store)
                             [(mk "d1" t1)
                              (mk "d2" (db/tree-diff t1 t2 "d1" 0))
                              (mk "d3" (db/tree-diff t2 t3 "d2" 1))]
                             [] nil)))
      (testing "every marker resolves to its FULL tree, whatever it stores"
        (is (= t1 (db/tree-at conn "d1")) "a full snapshot")
        (is (= t2 (db/tree-at conn "d2")) "one link back")
        (is (= t3 (db/tree-at conn "d3")) "two links back, through an add and a remove"))
      (testing "trivia is intact at the end of the chain — the reason this is stored at all"
        (is (= (get t2 'a.core) (get (db/tree-at conn "d2") 'a.core))))
      (testing "a marker with no tree is still nil, not an error"
        (is (nil? (db/tree-at conn "nope"))))
      (finally (.close conn)))))

(deftest a-forms-comment-survives-persist-and-reload
  ;; A comment is CONTENT owned by its form. If it lives only in memory the
  ;; store forgets it on restart, which is the same failure as storing it
  ;; positionally — just later.
  (let [dir  (str (Files/createTempDirectory
                   "slopp-comment" (make-array FileAttribute 0)))
        conn (db/open! dir)
        st   (store/ingest (store/empty-store) 'cmt.core
                           "(ns cmt.core)\n\n(defn f [] 1)\n")
        [st' d] (store/set-comment st 'cmt.core 'f
                                   ";; --- section divider ---\n;; second line")]
    (try
      (db/persist! conn st' d)
      (let [back (db/load-store conn)
            f-el (first (filter #(= 'f (:name %))
                                (get-in back [:namespaces 'cmt.core :elements])))]
        (testing "the comment comes back attached to its form"
          (is (= ";; --- section divider ---\n;; second line" (:comment f-el))))
        (testing "and renders identically to before the round trip"
          (is (= (render/render-ns st' 'cmt.core)
                 (render/render-ns back 'cmt.core)))))
      (finally (.close conn)))))

(deftest loading-folds-a-positional-comment-onto-the-form-it-describes
  ;; Migration, at load, so it applies to every store rather than being a
  ;; one-off. Idempotent: after folding there are no comment seps left.
  ;;
  ;; The blank line BETWEEN comment and form has to be absorbed. In this
  ;; store, 0 of 67 comments sit directly above their form — every one is
  ;; followed by a "\n" sep — so a fold that only removes the comment leaves
  ;; a stray gap where there used to be none.
  (let [dir  (str (Files/createTempDirectory
                   "slopp-fold" (make-array FileAttribute 0)))
        conn (db/open! dir)
        src  (str "(ns fc.core)\n\n"
                  ";; --- section ---\n"
                  ";; second line\n"
                  "\n"
                  "(defn f [] 1)\n")
        st   (store/ingest (store/empty-store) 'fc.core src)]
    (try
      (db/persist! conn st (last (store/deltas st)))
      (let [back  (db/load-store conn)
            elems (get-in back [:namespaces 'fc.core :elements])
            f-el  (first (filter #(= 'f (:name %)) elems))]
        (testing "the comment is now owned by the form below it"
          (is (= ";; --- section ---\n;; second line" (:comment f-el))))
        (testing "and no comment-carrying sep survives"
          (is (not-any? #(and (= :sep (:kind %))
                              (re-find #"\S" (n/string (:node %))))
                        elems)))
        (testing "rendering keeps the gap ABOVE the comment and drops the one below"
          (is (= "(ns fc.core)\n\n;; --- section ---\n;; second line\n(defn f [] 1)\n"
                 (render/render-ns back 'fc.core))))
        (testing "it is idempotent — loading again changes nothing"
          (is (= (render/render-ns back 'fc.core)
                 (render/render-ns (db/load-store conn) 'fc.core)))))
      (finally (.close conn)))))
