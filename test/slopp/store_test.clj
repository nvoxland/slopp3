(ns slopp.store-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store] [rewrite-clj.parser :as p] [rewrite-clj.node :as n]))

(def src "(ns foo)\n\n(defn add [x y]\n  (+ x y))\n\n;; a comment\n(def z 1)\n")

(deftest ingest-extracts-forms
  (let [s (store/ingest (store/empty-store) 'foo src)
        forms (store/forms s 'foo)]
    (testing "one Form per top-level sexpr (whitespace/comments are separators)"
      (is (= 3 (count forms))))
    (testing "names derived from def*/ns head"
      (is (= '[foo add z] (mapv :name forms))))
    (testing "every form gets a unique synthetic id (C2)"
      (is (every? :id forms))
      (is (apply distinct? (map :id forms))))))

(deftest ingest-appends-a-delta
  (let [s (store/ingest (store/empty-store) 'foo src)
        ds (store/deltas s)]
    (is (= 1 (count ds)))
    (is (= :ingest (:op (first ds))))
    (is (= 'foo (:ns (first ds))))
    (is (= 3 (count (:form-ids (first ds)))))))

(deftest form-lookup-by-name
  (let [s (store/ingest (store/empty-store) 'foo src)]
    (is (= 'add (:name (store/form-named s 'foo 'add))))
    (is (nil? (store/form-named s 'foo 'missing)))))
(deftest anchored-add-inserts-before-the-anchor
  (let [base (store/ingest (store/empty-store) 'an.core
                           "(ns an.core)\n(defn early [] 1)\n(defn late [] 2)\n")
        [st d] (store/append-form base 'an.core
                                  (rewrite-clj.parser/parse-string "(defn mid [] 3)")
                                  :prompt "anchored" :before 'late)]
    (testing "lands between early and late (element order IS the store truth)"
      (is (= '[an.core early mid late]
             (mapv :name (store/forms st 'an.core)))))
    (testing "the delta records the anchor's form-id for replay"
      (is (= (:id (store/form-named base 'an.core 'late)) (:before d))))
    (testing "a foreign store replays the add into the SAME position"
      (is (= '[an.core early mid late]
             (mapv :name (store/forms (store/replay-delta base d) 'an.core)))))
    (testing "a missing anchor name returns nil (caller errors)"
      (is (nil? (store/append-form base 'an.core
                                   (rewrite-clj.parser/parse-string "(defn x [] 4)")
                                   :before 'nope))))))
(deftest reorder-to-realizes-a-target-order
  (let [base (store/ingest (store/empty-store) 'ro.core
                           "(ns ro.core)\n(defn c [] 3)\n(defn a [] 1)\n(defn b [] 2)\n")
        names #(mapv :name (store/forms % 'ro.core))]
    (testing "reorders to the requested sequence, ns decl first"
      (let [[st n] (store/reorder-to base 'ro.core '[ro.core a b c])]
        (is (= '[ro.core a b c] (names st)))
        (is (pos? n) "some moves happened")))
    (testing "an already-correct order needs no moves"
      (let [[st n] (store/reorder-to base 'ro.core '[ro.core c a b])]
        (is (= '[ro.core c a b] (names st)))
        (is (zero? n))))
    (testing "the moves are ordinary :move deltas (replay-tested in multiproc)"
      (let [[st n] (store/reorder-to base 'ro.core '[ro.core a b c])]
        (is (= n (count (filter #(= :move (:op %))
                                (drop (count (:deltas base)) (:deltas st)))))
            "each move is one :move delta")))))
(deftest form-symbols-reports-what-a-form-actually-defines
  ;; The store's premise was "one form ↔ one name", via form-symbol's (second s).
  ;; Probed against kondo 2026-07-17, it is wrong in BOTH directions:
  ;;
  ;;   (defmethod area :square ..)  defines NOTHING — `area` is its TARGET, and
  ;;                                forcing it into that name put three forms
  ;;                                named `area` in one ns. form-named returns
  ;;                                the FIRST, so the methods were unreachable by
  ;;                                every name-keyed tool, and
  ;;                                refs/cold-load-order silently DROPPED them.
  ;;   (defrecord R [x] ..)         defines R, ->R AND map->R — so ->R/map->R
  ;;                                were real public vars with no form: invisible
  ;;                                to form-named, :covered and the unused gate.
  ;;
  ;; No compound name can fix the first half: `:` is legal in a symbol, so
  ;; (defn area:square ..) is a real fn a user can write — and every ASCII
  ;; punctuation separator is likewise legal. The name space is flat and
  ;; user-owned; you cannot reserve a corner of it. The fix is to stop inventing
  ;; names for forms that define none.
  (let [syms #(store/form-symbols (p/parse-string %))]
    (testing "definitions — one name each"
      (is (= '#{f}    (syms "(defn f \"F.\" [x] x)")))
      (is (= '#{area} (syms "(defmulti area :shape)"))))
    (testing "REGISTRATIONS define nothing — the collision was invented, not inherent"
      (is (= #{} (syms "(defmethod area :square [s] (:side s))")))
      (is (= #{} (syms "(extend-type String P (m [_] 1))"))))
    (testing "and some definitions define SEVERAL — these vars had no form at all"
      (is (= '#{R ->R map->R} (syms "(defrecord R [x])")))
      (is (= '#{T ->T}        (syms "(deftype T [x])")))
      (is (= '#{P m n}        (syms "(defprotocol P \"P.\" (m [_] \"M.\") (n [_] \"N.\"))"))))
    (testing "metadata is seen through, as form-symbol already does"
      (is (= '#{f} (syms "^:unsafe (defn f \"F.\" [x] x)"))))))
(deftest names-address-a-form-and-registrations-collide-with-nothing
  ;; The payoff of form-symbols. Two bugs, both live before #128:
  ;;  1. ingest of a defmulti + 2 defmethods produced THREE forms named `area`.
  ;;     form-named returns the first, so the methods were unreachable — and the
  ;;     EDIT layer refuses duplicate names outright, so ingest was admitting a
  ;;     state the rest of slopp considered illegal.
  ;;  2. `dm.core/f4` printed in output (qform's (or (:name e) (:id e))) and
  ;;     query_source {name "f4"} could not fetch it back, because form-named
  ;;     filtered on :name. The id-fallback labelled forms it could not address.
  (let [dm (store/ingest (store/empty-store) 'dm.core
                         (str "(ns dm.core)\n\n(defmulti area :shape)\n\n"
                              "(defmethod area :square [s] 1)\n\n"
                              "(defmethod area :circle [c] 2)\n"))
        rc (store/ingest (store/empty-store) 'r.core
                         "(ns r.core)\n\n(defrecord R [x])\n")]
    (testing "the defmulti keeps `area` — it IS area, and callers reference it"
      (is (= '#{area} (:names (store/form-named dm 'dm.core 'area)))))
    (testing "the methods define nothing, so there is nothing to collide"
      (is (= ['#{dm.core} '#{area} #{} #{}]
             (mapv :names (store/forms dm 'dm.core)))))
    (testing "a registration is addressable by its form id — its only handle"
      (let [m (nth (store/forms dm 'dm.core) 2)]
        (is (= (:id m) (:id (store/form-named dm 'dm.core (symbol (:id m))))))))
    (testing "->R and map->R reach R's form — they are real public vars"
      (let [r (store/form-named rc 'r.core 'R)]
        (is (= (:id r) (:id (store/form-named rc 'r.core '->R))))
        (is (= (:id r) (:id (store/form-named rc 'r.core 'map->R))))))))
(deftest appended-forms-are-blank-line-separated
  (let [base   (store/ingest (store/empty-store) 't.core
                             "(ns t.core)\n\n(defn a [] 1)\n")
        [s-b d-b] (store/append-form base 't.core (p/parse-string "(defn b [] 2)"))
        [s-c _]   (store/append-form s-b 't.core (p/parse-string "(defn c [] 3)") :before 'b)
        render (fn [st] (apply str (map (comp n/string :node)
                                        (get-in st [:namespaces 't.core :elements]))))]
    (testing "a tail-appended form gets a blank line before it (top-level convention)"
      (is (= "(ns t.core)\n\n(defn a [] 1)\n\n(defn b [] 2)\n" (render s-b))))
    (testing "an anchored insert is blank-line separated on both sides"
      (is (= "(ns t.core)\n\n(defn a [] 1)\n\n(defn c [] 3)\n\n(defn b [] 2)\n" (render s-c))))
    (testing "journal replay of the :add renders identically to the live append"
      (is (= (render s-b) (render (store/replay-delta base d-b)))))))
