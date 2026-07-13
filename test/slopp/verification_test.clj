(ns slopp.verification-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.repl :as repl]
            [slopp.api :as api]))

(def target
  (str "(ns vdemo\n  (:require [clojure.test :refer [deftest is]]))\n"
       "(defn add [x y] (+ x y))\n"
       "(defn mul [x y] (* x y))\n"
       "(deftest add-t (is (= 5 (add 2 3))))\n"
       "(deftest mul-t (is (= 6 (mul 2 3))))\n"))

(deftest ^:isolated tracing-maps-tests-to-forms
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'vdemo target)
      (let [res (api/test-run! sess 'vdemo)]
        (is (= 2 (:pass res))))
      (testing "each test maps to exactly the forms it exercises (D1 form-granularity)"
        (let [tmap (:test-map @sess)]
          (is (= #{'vdemo/add} (tmap 'vdemo/add-t)))
          (is (= #{'vdemo/mul} (tmap 'vdemo/mul-t)))))
      (finally (api/close! sess)))))

(deftest ^:isolated edit-runs-only-affected-tests
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'vdemo target)
      (api/test-run! sess 'vdemo)                          ; builds the trace map
      (testing "editing mul re-runs only mul-t (add-t is untouched by the edit)"
        (let [r (api/edit-replace! sess 'vdemo 'mul "(defn mul [x y] (* y x))"
                                   :prompt "commute")]
          (is (nil? (:error r)))
          (is (= ['vdemo/mul-t] (:affected r)))
          (is (= 1 (:test (:test r))))
          (is (= 1 (:pass (:test r))))))
      (testing "editing a test itself re-runs exactly that test"
        (let [r (api/edit-replace! sess 'vdemo 'mul-t
                                   "(deftest mul-t (is (= 8 (mul 2 4))))"
                                   :prompt "retarget")]
          (is (= ['vdemo/mul-t] (:affected r)))
          (is (= 1 (:pass (:test r))))))
      (testing "ingest itself seeds the trace map (W1): edits narrow immediately"
        (let [sess2 (api/open!)]
          (try
            (api/ingest! sess2 'vdemo target)
            (let [r (api/edit-replace! sess2 'vdemo 'mul "(defn mul [x y] (* y x))")]
              (is (= ['vdemo/mul-t] (:affected r)))
              (is (= 1 (:test (:test r)))))
            (finally (api/close! sess2)))))
      (finally (api/close! sess)))))

(deftest ^:isolated reload-signature-reds-still-heal              ; D5.1 belt-and-suspenders
  ;; Even when the red IS on an edited path (flip rule says "explained"), an
  ;; unbound-var-style failure smells like staleness and must cross-check.
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'sig.core
                   (str "(ns sig.core (:require [clojure.test :refer [deftest is]]))\n"
                        "(defn helper [x] (* 2 x))\n"
                        "(defn f [x] (helper x))\n"
                        "(deftest f-t (is (= 10 (f 5))))\n"))
      ;; poison: rip helper out of the image behind the store's back
      (repl/eval! (:image @sess) "(ns-unmap 'sig.core 'helper)")
      ;; editing f now hits the compile gate against the stale image; D5.1
      ;; heals it: fresh image, retried load, write proceeds
      (let [r (api/edit-replace! sess 'sig.core 'f "(defn f [x] (helper x))"
                                 :prompt "touch f while helper is stale")]
        (is (nil? (:error r)))
        (is (true? (:image-healed r)))
        (is (zero? (+ (:fail (:test r)) (:error (:test r))))))
      (finally (api/close! sess)))))

(deftest ^:isolated test-run-fresh-forces-a-cross-check
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'fr.core
                   (str "(ns fr.core (:require [clojure.test :refer [deftest is]]))\n"
                        "(defn f [x] x)\n(deftest f-t (is (= 1 (f 1))))\n"))
      (let [before (:port (:image @sess))
            res    (api/test-run! sess 'fr.core :fresh true)]
        (is (zero? (:fail res)))
        (is (not= before (:port (:image @sess)))))   ; image really was replaced
      (finally (api/close! sess)))))

(deftest ^:isolated test-run-only-targets-named-tests
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'vdemo target)
      (let [r (api/test-run! sess 'vdemo :only ['add-t])]
        (is (= 1 (:test r)))
        (is (= 1 (:pass r))))
      (finally (api/close! sess)))))

(deftest ^:isolated red-is-cross-checked-on-a-fresh-image
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'vdemo target)
      (testing "staleness flip: image drifts behind the store's back; NOTHING was
                edited, so the red is unexplained -> restart heals it"
        ;; poison the image only (the store is untouched) — the classic stale state
        (repl/eval! (:image @sess) "(in-ns 'vdemo) (def add (fn [x y] 999))")
        (let [res (api/test-run! sess 'vdemo)]
          (is (zero? (+ (:fail res) (:error res))))
          (is (true? (:staleness-detected res)))))
      (testing "genuine red (D5.1): assertion failure on the just-edited path is
                reported immediately — ONE run, no restart, no cross-check"
        (let [r (api/edit-replace! sess 'vdemo 'add "(defn add [x y] (- x y))"
                                   :prompt "break it")]
          (is (= 1 (:fail (:test r))))
          (is (= :genuine (:diagnosis (:test r))))
          (is (nil? (:fresh-confirmed (:test r))))
          (is (nil? (:staleness-detected (:test r))))
          (testing "the WHY is in the result (F1) — not lost to image stdout"
            (let [f (first (:failures (:test r)))]
              (is (= 'vdemo/add-t (:test f)))
              (is (= :fail (:type f)))
              (is (re-find #"\(= 5 \(add 2 3\)\)" (:expected f)))
              (is (= "(not (= 5 -1))" (:actual f)))))))
      (finally (api/close! sess)))))
(deftest ^:isolated red-results-name-the-implicated-forms
  ;; Rock 2: the system holds the trace map AND the delta — a red write
  ;; result says WHICH changed form each failing test exercises
  (let [sess (api/open!)]
    (try
      (api/create-ns! sess 'im.core :source "(ns im.core (:require [clojure.test :refer [deftest is]]))\n(defn f [x] (inc x))\n(defn g [x] (dec x))\n(deftest f-t (is (= 2 (f 1))))\n(deftest g-t (is (= 0 (g 1))))\n")
      (api/test-run! sess nil)
      (let [r     (api/edit-replace! sess 'im.core 'f "(defn f [x] (+ x 2))"
                                     :prompt "break it" :agent "t")
            fails (get-in r [:test :failures])]
        (is (seq fails))
        (testing "the failing test names the changed form it exercises"
          (is (= ['im.core/f] (:implicated (first fails)))))
        (testing "narrowing kept the untouched test out of the run"
          (is (= 1 (count fails)))))
      (finally (api/close! sess)))))
(deftest ^:isolated trace-map-survives-sessions
  (let [dir (str (java.nio.file.Files/createTempDirectory
                  "slopp-trace"
                  (make-array java.nio.file.attribute.FileAttribute 0)))
        s1  (api/open! {:dir dir})]
    (try
      (api/ingest! s1 'vdemo target)
      (api/test-run! s1 'vdemo)
      (is (seq (:test-map @s1)))
      (finally (api/close! s1)))
    (let [s2 (api/open! {:dir dir})]
      (try
        (testing "a fresh session on the same store starts with the trace warm (Q3)"
          (is (= #{'vdemo/add} (get (:test-map @s2) 'vdemo/add-t))
              (pr-str (:test-map @s2))))
        (testing "…so its first edit narrows instead of running everything"
          (let [r (api/edit-replace! s2 'vdemo 'mul "(defn mul [x y] (* y x))"
                                     :prompt "commute")]
            (is (= ['vdemo/mul-t] (:affected r)) (pr-str (select-keys r [:affected :test])))))
        (finally (api/close! s2))))))
