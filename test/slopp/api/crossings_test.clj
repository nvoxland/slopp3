(ns slopp.api.crossings-test
  "Cover for the boundary inventory.

  The subject computes almost nothing, so these are not tests of a
  calculation. Its failure mode is SILENCE — a hole that goes unmentioned, a
  new exit the registry does not notice, a note that appears so often nobody
  reads it. So each test here asserts that something is said, or deliberately
  not said, and the fixtures are the exits slopp's own store actually has.

  Pure over a store value, so in-image and sub-millisecond."
  (:require [clojure.test :refer [deftest testing is]]
            [slopp.store :as store]
            [slopp.api.crossings :as crossings] [slopp.api.external :as external] [slopp.api.rules.markers :as markers]))

(deftest the-inventory-reports-holes-and-refuses-to-miss-a-new-one
  ;; Core 6: slopp models edges INSIDE the store — the reference graph — and
  ;; has no representation for one that LEAVES it. So every exit is unverified
  ;; by construction, and each grows a hand-written check or none. Fifteen of
  ;; the sixteen frictions in the SPA wave landed at a crossing.
  ;;
  ;; This does not verify the far side; nothing here could. It makes the exits
  ;; ENUMERABLE, and makes an exit with no checker say so. The two properties
  ;; that keep it from becoming a document that rots are below.
  (let [st (-> (store/empty-store)
               (store/ingest 'app.api "(ns app.api)
(defn ^{:web/method :get :web/path \"/api/x\" :web/response [:map]} x [_] {})
(defn ^{:web/method :get :web/path \"/\" :web/spa [\"/app\"]} doc [_] {})"))]
    (testing "every exit is listed, with the checker that covers it"
      (let [r (crossings/store-crossings st)
            by (into {} (map (juxt :kind identity)) (:crossings r))]
        (is (contains? by :http/route))
        (is (contains? by :wire/json))
        (is (string? (:checked-by (by :wire/json))))))
    (testing "an exit with NO checker is reported, not omitted"
      ;; the whole point — an absent checker and an absent crossing look
      ;; identical unless one of them is written down
      (let [r  (crossings/store-crossings st)
            un (set (map :kind (:unchecked r)))]
        (is (contains? un :spa/client-routing)
            "declaring :web/spa turns every path under a prefix into a 200 and
             moves not-found into the client, and nothing checks the client
             agrees — that is a hole, and it has to read as one")))
    (testing "a marker NO kind claims is a finding — this is what stops it rotting"
      ;; the failure mode of any inventory: someone adds an exit and the list
      ;; silently does not describe the system any more
      (let [st2 (store/ingest st 'app.socket
                              "(ns app.socket)
(defn ^{:web/websocket \"/feed\"} feed [_] {})")
            r   (crossings/store-crossings st2)]
        (is (= [:web/websocket] (map :marker (:unclassified r)))
            "a slopp-namespaced marker that no crossing kind claims")
        (is (= '[app.socket/feed] (map :at (:unclassified r))))))
    (testing "a store with no exits says so with an empty inventory, not a nil"
      (let [bare (store/ingest (store/empty-store) 'plain "(ns plain)\n(defn f [] 1)")
            r    (crossings/store-crossings bare)]
        (is (= [] (:crossings r)))
        (is (= [] (:unclassified r)))))))

(deftest a-marker-that-is-deliberately-not-an-exit-must-say-so
  ;; Run against slopp's own store the moment it existed, the inventory
  ;; reported five markers as unclassified: :web/auth, :web/client,
  ;; :web/effectful, :rule/applies-to, :rule/severity. None is an exit —
  ;; auth is enforced in-process, :web/client MODIFIES the generated-client
  ;; crossing rather than being one, and :rule/* is the rule registry talking
  ;; to itself.
  ;;
  ;; That is the self-policing working, and it also shows what it needs to
  ;; stay usable: classification has to be TOTAL. With no way to say "not an
  ;; exit", every internal marker reads as a hole and the real finding drowns
  ;; in them — the precision failure that got :positional-form-access
  ;; withdrawn.
  (let [st (store/ingest (store/empty-store) 'app.api
                         "(ns app.api)
(defn ^{:web/method :get :web/path \"/x\" :web/auth :public :web/effectful true} x [_] {})")]
    (testing "a declared-internal marker is not a finding"
      (let [r (crossings/store-crossings st)]
        (is (= [] (:unclassified r))
            "auth is enforced in-process and never leaves — saying nothing
             about it would leave it looking like an unchecked exit")))
    (testing "and the classification is total — every marker slopp owns has a home"
      (is (empty? (crossings/unclassified-markers))
          "a marker in neither list is an exit nobody decided about"))))

(deftest the-inventory-is-a-note-not-a-verdict
  ;; It has to reach a surface someone reads, and `full_check` is the one that
  ;; already answers whole-store questions.
  ;;
  ;; But it must NOT flip the status. Every unchecked exit here is a hole
  ;; someone already knows about and wrote down; failing on a standing,
  ;; documented hole would make full_check red forever, and a check that is
  ;; always red is a check people stop running. The finding earns its place by
  ;; being visible at the moment a whole-store green is about to be believed —
  ;; the same slot :host-stale occupies, and for the same reason.
  (let [st (store/ingest (store/empty-store) 'app.api
                         "(ns app.api)
(defn ^{:web/method :get :web/path \"/\" :web/spa [\"/app\"]} doc [_] {})")]
    (testing "a store with an unchecked exit produces a finding to attach"
      (let [f (crossings/finding st)]
        (is (some? f))
        (is (= [:spa/client-routing] (map :kind (:unchecked f))))
        (is (string? (:note f)))))
    (testing "a store that crosses nothing produces NO finding — silence is correct here"
      ;; the opposite of the usual rule: this is a note about holes, and a
      ;; store with no holes has nothing to say. An empty section would be
      ;; noise on every full_check of every store forever.
      (is (nil? (crossings/finding
                 (store/ingest (store/empty-store) 'plain "(ns plain)\n(defn f [] 1)")))))))

(deftest ^:external the-two-marker-registries-COVER-the-vocabulary
  ;; Two registries describe slopp's markers: this one asks whether a key
  ;; carries data ACROSS the store's edge, `slopp.api.rules.markers` asks
  ;; whether a dial waives a rule and should say why. Splitting them is
  ;; correct — merging would report every escape dial as an unclassified
  ;; crossing — but a split needs something checking the seam.
  ;;
  ;; **The invariant is COVERAGE, not disjointness**, and the first version of
  ;; this test got that wrong. It asserted the two sets do not overlap and
  ;; immediately failed on `:generated`, which is genuinely BOTH: a
  ;; name-metadata dial slopp owns, and the signal `:generated/client` uses to
  ;; find the forms that crossed into generated code. A marker can have two
  ;; properties; requiring the registries not to overlap asserted something the
  ;; system does not have and should not.
  ;;
  ;; What actually rots is a key claimed by NEITHER — it falls down the gap and
  ;; both guards report clean about it, which is indistinguishable from clean.
  (let [st        (external/built-store)
        in-use    (markers/in-use st)
        crossing? (into (set (keys crossings/internal-markers))
                        (mapcat :markers) crossings/kinds)
        dial?     (into #{} (map :marker) markers/marker-registry)
        clojures  #{:private :dynamic :macro :tag :doc :arglists :added
                    :deprecated :const :inline :test :author :since :no-doc}
        orphans   (into #{} (remove #(or (crossing? %) (dial? %) (clojures %))) in-use)]
    (testing "there is a population"
      (is (< 20 (count in-use)) (pr-str in-use)))
    (testing "no marker slopp uses falls between the two registries"
      (is (= #{} orphans)
          (str "these keys are claimed by neither registry, so both report"
               " clean about them: " (pr-str orphans))))
    (testing "and the overlap is the ONE marker that really is both"
      ;; pinned rather than tolerated: a second overlap is a question worth
      ;; being asked, even though overlap per se is legal
      (is (= #{:generated} (set (filter dial? crossing?)))
          "a marker in both registries has two properties — fine, but say
           which one and why rather than letting the set grow quietly"))))
