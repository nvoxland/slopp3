(ns slopp.evalseed
  "Seed the eval-round-2 template: a known-green ~16-form tasker app, as (a) a
  slopp store and (b) a conventional files project (via build!). Fresh eval
  agents then get IDENTICAL starting codebases for the modify-and-extend task.
  Run: clojure -M -m slopp.evalseed <template-dir>   (see .context/dogfooding.md)"
  (:require [slopp.api :as api]))

(def model-src
  (str "(ns tasker.model\n  (:require [clojure.test :refer [deftest is]]))\n\n"
       "(defn make-task [id title priority due-day]\n"
       "  {:id id :title title :priority priority :due-day due-day :done? false})\n\n"
       "(defn complete [task] (assoc task :done? true))\n\n"
       "(defn overdue? [task today]\n"
       "  (and (not (:done? task)) (< (:due-day task) today)))\n\n"
       "(defn prioritize [tasks]\n"
       "  (vec (sort-by (juxt (comp {:high 0 :med 1 :low 2} :priority) :due-day) tasks)))\n\n"
       "(deftest model-t\n"
       "  (let [t (make-task 1 \"ship\" :high 10)]\n"
       "    (is (false? (:done? t)))\n"
       "    (is (true? (:done? (complete t))))\n"
       "    (is (= [1 2] (mapv :id (prioritize [(make-task 2 \"docs\" :low 5) t]))))))\n\n"
       "(deftest overdue-t\n"
       "  (is (overdue? (make-task 1 \"x\" :low 5) 6))\n"
       "  (is (not (overdue? (complete (make-task 1 \"x\" :low 5)) 6))))\n"))

(def store-src
  (str "(ns tasker.store\n"
       "  (:require [clojure.test :refer [deftest is]]\n"
       "            [tasker.model :as m]\n"
       "            [clojure.edn :as edn]))\n\n"
       "(def registry (atom {}))\n\n"
       "(defn clear! [] (reset! registry {}))\n\n"
       "(defn add-task! [task] (swap! registry assoc (:id task) task))\n\n"
       "(defn remove-task! [id] (swap! registry dissoc id))\n\n"
       "(defn all-tasks [] (vec (vals @registry)))\n\n"
       "(defn save! [path] (spit path (pr-str @registry)))\n\n"
       "(defn load-tasks [path] (edn/read-string (slurp path)))\n\n"
       "(deftest store-t\n"
       "  (clear!)\n"
       "  (add-task! (m/make-task 1 \"ship\" :high 10))\n"
       "  (add-task! (m/make-task 2 \"docs\" :low 5))\n"
       "  (is (= 2 (count (all-tasks))))\n"
       "  (remove-task! 2)\n"
       "  (is (= [1] (mapv :id (all-tasks)))))\n"))

(def report-src
  (str "(ns tasker.report\n"
       "  (:require [clojure.test :refer [deftest is]]\n"
       "            [tasker.model :as m]\n"
       "            [clojure.string :as str]))\n\n"
       "(defn task-line [task]\n"
       "  (str (if (:done? task) \"[x] \" \"[ ] \") (:title task)\n"
       "       \" (\" (name (:priority task)) \")\"))\n\n"
       "(defn overview [tasks today]\n"
       "  (let [overdue (filter #(m/overdue? % today) tasks)]\n"
       "    (str/join \"\\n\"\n"
       "              (concat [(str (count tasks) \" tasks, \" (count overdue) \" overdue\")]\n"
       "                      (map task-line (m/prioritize tasks))))))\n\n"
       "(deftest report-t\n"
       "  (let [ts [(m/make-task 1 \"ship\" :high 10)\n"
       "            (m/complete (m/make-task 2 \"docs\" :low 5))]]\n"
       "    (is (str/starts-with? (overview ts 12) \"2 tasks, 1 overdue\"))\n"
       "    (is (str/includes? (overview ts 12) \"[x] docs\"))))\n"))

(defn seed!
  "Build the template store under `dir` (a slopp session dir) and the matching
  conventional project under `<dir>-files`. Throws unless everything is green."
  [dir]
  (let [sess (api/open! {:dir dir})]
    (try
      (doseq [[ns-sym src] [['tasker.model model-src]
                            ['tasker.store store-src]
                            ['tasker.report report-src]]]
        (let [r (api/ingest! sess ns-sym src)]
          (when (:error r) (throw (ex-info (str "seed ingest failed: " (:error r)) r)))))
      (doseq [ns-sym '[tasker.model tasker.store tasker.report]]
        (let [r (api/test-run! sess ns-sym)]
          (when-not (zero? (+ (:fail r) (:error r)))
            (throw (ex-info (str "seed not green in " ns-sym) r)))))
      (api/done! sess :label "seed: tasker v1")
      (api/build! sess (.getAbsolutePath (clojure.java.io/file (str dir "-files"))))
      (finally (api/close! sess)))))

;; --- round 3: the SCALE seed (12 interconnected namespaces) ---

(defn- padding
  "Deterministic filler fns + a trivial test — the 'rest of the codebase'
  noise real repos have; orientation means finding the relevant code among it."
  [ns-sym n]
  (let [base (clojure.string/replace (name ns-sym) #"\." "-")]
    (str (apply str
                (for [i (range 1 (inc n))]
                  (str "(defn " base "-util-" i "\n"
                       "  \"Support helper " i " for " ns-sym " (see module docs).\"\n"
                       "  [x]\n  (+ x " i "))\n\n")))
         "(deftest " base "-utils-t\n"
         "  (is (= " (inc 1) " (" base "-util-1 1)))\n"
         "  (is (= " (+ 5 n) " (" base "-util-" n " 5))))\n")))

(def large-namespaces
  [['orders.money
    (str "(ns orders.money\n  (:require [clojure.test :refer [deftest is]]))\n\n"
         "(defn add-cents [& cs] (reduce + 0 cs))\n\n"
         "(defn mul-rate\n  \"Scale a cent amount by a rate, rounded to the nearest cent.\"\n"
         "  [cents rate]\n  (long (Math/round (double (* cents rate)))))\n\n"
         "(defn format-money [cents]\n  (format \"$%d.%02d\" (quot cents 100) (rem cents 100)))\n\n"
         "(deftest money-t\n"
         "  (is (= 300 (add-cents 100 200)))\n"
         "  (is (= 70 (mul-rate 1000 0.07)))\n"
         "  (is (= \"$12.34\" (format-money 1234))))\n")]
   ['orders.entity
    (str "(ns orders.entity\n  (:require [clojure.test :refer [deftest is]]))\n\n"
         "(defn make-item [sku qty price-cents weight-g]\n"
         "  {:sku sku :qty qty :price-cents price-cents :weight-g weight-g})\n\n"
         "(defn make-order [id items member?]\n"
         "  {:id id :items items :member? member?})\n\n"
         "(defn item-count [order] (reduce + 0 (map :qty (:items order))))\n\n"
         "(deftest entity-t\n"
         "  (let [o (make-order 1 [(make-item :a 2 100 50) (make-item :b 3 200 80)] true)]\n"
         "    (is (= 5 (item-count o)))\n"
         "    (is (true? (:member? o)))))\n")]
   ['orders.catalog
    (str "(ns orders.catalog\n  (:require [clojure.test :refer [deftest is]]))\n\n"
         "(def products {:widget \"Widget\" :gadget \"Gadget\" :gizmo \"Gizmo\"})\n\n"
         "(defn product-name [sku] (get products sku (name sku)))\n\n"
         "(defn known? [sku] (contains? products sku))\n\n"
         "(deftest catalog-t\n"
         "  (is (= \"Widget\" (product-name :widget)))\n"
         "  (is (known? :gadget))\n"
         "  (is (= \"custom\" (product-name :custom))))\n")]
   ['orders.validate
    (str "(ns orders.validate\n  (:require [clojure.test :refer [deftest is]]\n"
         "            [orders.entity :as e]))\n\n"
         "(defn item-valid? [item]\n  (and (pos? (:qty item)) (pos? (:price-cents item))))\n\n"
         "(defn order-valid? [order]\n"
         "  (and (seq (:items order)) (every? item-valid? (:items order))))\n\n"
         "(deftest validate-t\n"
         "  (is (order-valid? (e/make-order 1 [(e/make-item :a 1 100 10)] false)))\n"
         "  (is (not (order-valid? (e/make-order 2 [] false)))))\n")]
   ['orders.pricing
    (str "(ns orders.pricing\n  (:require [clojure.test :refer [deftest is]]\n"
         "            [orders.entity :as e]))\n\n"
         "(defn item-subtotal [item] (* (:qty item) (:price-cents item)))\n\n"
         "(defn order-subtotal [order]\n"
         "  (reduce + 0 (map item-subtotal (:items order))))\n\n"
         "(deftest pricing-t\n"
         "  (let [o (e/make-order 1 [(e/make-item :a 2 100 50) (e/make-item :b 3 200 80)] false)]\n"
         "    (is (= 800 (order-subtotal o)))))\n")]
   ['orders.discount
    (str "(ns orders.discount\n  (:require [clojure.test :refer [deftest is]]\n"
         "            [orders.money :as m]))\n\n"
         "(defn bulk-rate [item-count] (if (>= item-count 10) 0.10 0.0))\n\n"
         "(defn member-rate [member?] (if member? 0.05 0.0))\n\n"
         "(defn discount-cents [subtotal rate] (m/mul-rate subtotal rate))\n\n"
         "(deftest discount-t\n"
         "  (is (= 0.10 (bulk-rate 12)))\n"
         "  (is (= 0.0 (bulk-rate 9)))\n"
         "  (is (= 100 (discount-cents 1000 0.10))))\n")]
   ['orders.tax
    (str "(ns orders.tax\n  (:require [clojure.test :refer [deftest is]]\n"
         "            [orders.money :as m]))\n\n"
         "(def region-rates {:us 0.07 :eu 0.20 :none 0.0})\n\n"
         "(defn rate-for [region] (get region-rates region 0.0))\n\n"
         "(defn tax-cents [amount region] (m/mul-rate amount (rate-for region)))\n\n"
         "(deftest tax-t\n"
         "  (is (= 70 (tax-cents 1000 :us)))\n"
         "  (is (= 0 (tax-cents 1000 :none))))\n")]
   ['orders.shipping
    (str "(ns orders.shipping\n  (:require [clojure.test :refer [deftest is]]\n"
         "            [orders.entity :as e]\n"
         "            [orders.money :as m]))\n\n"
         "(defn order-weight-g [order]\n"
         "  (reduce + 0 (map (fn [i] (* (:qty i) (:weight-g i))) (:items order))))\n\n"
         "(defn ship-cents\n  \"Base 500 + 0.1 cents per gram.\"\n  [order]\n"
         "  (+ 500 (m/mul-rate (order-weight-g order) 0.1)))\n\n"
         "(deftest shipping-t\n"
         "  (let [o (e/make-order 1 [(e/make-item :a 2 100 500)] false)]\n"
         "    (is (= 1000 (order-weight-g o)))\n"
         "    (is (= 600 (ship-cents o)))))\n")]
   ['orders.inventory
    (str "(ns orders.inventory\n  (:require [clojure.test :refer [deftest is]]))\n\n"
         "(def stock (atom {}))\n\n"
         "(defn set-stock! [sku n] (swap! stock assoc sku n))\n\n"
         "(defn available? [sku n] (>= (get @stock sku 0) n))\n\n"
         "(defn reserve! [item]\n"
         "  (if (available? (:sku item) (:qty item))\n"
         "    (do (swap! stock update (:sku item) - (:qty item)) true)\n"
         "    false))\n\n"
         "(deftest inventory-t\n"
         "  (set-stock! :a 5)\n"
         "  (is (available? :a 3))\n"
         "  (is (true? (reserve! {:sku :a :qty 3})))\n"
         "  (is (= 2 (get @stock :a)))\n"
         "  (is (false? (reserve! {:sku :a :qty 3}))))\n")]
   ['orders.report
    (str "(ns orders.report\n  (:require [clojure.test :refer [deftest is]]\n"
         "            [clojure.string :as str]\n"
         "            [orders.catalog :as c]\n"
         "            [orders.entity :as e]\n"
         "            [orders.money :as m]))\n\n"
         "(defn order-line [item]\n"
         "  (str \"  \" (:qty item) \"x \" (c/product-name (:sku item))\n"
         "       \" @ \" (m/format-money (:price-cents item))))\n\n"
         "(defn order-summary [order]\n"
         "  (str/join \"\\n\"\n"
         "            (into [(str \"Order \" (:id order) \" (\" (count (:items order)) \" lines)\")]\n"
         "                  (map order-line (:items order)))))\n\n"
         "(deftest report-t\n"
         "  (let [o (e/make-order 7 [(e/make-item :widget 2 100 50)] false)]\n"
         "    (is (str/starts-with? (order-summary o) \"Order 7 (1 lines)\"))\n"
         "    (is (str/includes? (order-summary o) \"2x Widget @ $1.00\"))))\n")]
   ['orders.stats
    (str "(ns orders.stats\n  (:require [clojure.test :refer [deftest is]]\n"
         "            [orders.entity :as e]))\n\n"
         "(defn quantity-by-sku [orders]\n"
         "  (reduce (fn [acc i] (update acc (:sku i) (fnil + 0) (:qty i)))\n"
         "          {}\n          (mapcat :items orders)))\n\n"
         "(defn top-skus [orders n]\n"
         "  (vec (take n (map key (sort-by (comp - val) (quantity-by-sku orders))))))\n\n"
         "(deftest stats-t\n"
         "  (let [os [(e/make-order 1 [(e/make-item :a 2 100 10) (e/make-item :b 5 100 10)] false)]]\n"
         "    (is (= {:a 2 :b 5} (quantity-by-sku os)))\n"
         "    (is (= [:b :a] (top-skus os 2)))))\n")]
   ['orders.workflow
    (str "(ns orders.workflow\n  (:require [clojure.test :refer [deftest is]]\n"
         "            [orders.discount :as d]\n"
         "            [orders.entity :as e]\n"
         "            [orders.inventory :as i]\n"
         "            [orders.pricing :as p]\n"
         "            [orders.shipping :as s]\n"
         "            [orders.tax :as t]\n"
         "            [orders.validate :as v]))\n\n"
         "(defn process-order!\n"
         "  \"Validate, price, discount, tax, ship, and reserve stock for an order.\"\n"
         "  [order region]\n"
         "  (if-not (v/order-valid? order)\n"
         "    {:ok false :reason :invalid}\n"
         "    (let [sub  (p/order-subtotal order)\n"
         "          disc (+ (d/discount-cents sub (d/bulk-rate (e/item-count order)))\n"
         "                  (d/discount-cents sub (d/member-rate (:member? order))))\n"
         "          base (- sub disc)\n"
         "          tax  (t/tax-cents base region)\n"
         "          ship (s/ship-cents order)\n"
         "          okay (every? true? (mapv i/reserve! (:items order)))]\n"
         "      (if okay\n"
         "        {:ok true :subtotal sub :discount disc :tax tax :shipping ship\n"
         "         :total (+ base tax ship)}\n"
         "        {:ok false :reason :stock}))))\n\n"
         "(deftest workflow-t\n"
         "  (i/set-stock! :a 10)\n"
         "  (i/set-stock! :b 10)\n"
         "  (let [o (e/make-order 1 [(e/make-item :a 2 100 100) (e/make-item :b 3 200 200)] true)\n"
         "        r (process-order! o :none)]\n"
         "    (is (:ok r))\n"
         "    (is (= 800 (:subtotal r)))\n"
         "    (is (= 40 (:discount r)))\n"
         "    (is (= 580 (:shipping r)))\n"
         "    (is (= 1340 (:total r))))\n"
         "  (is (= {:ok false :reason :invalid}\n"
         "         (process-order! (e/make-order 2 [] false) :none))))\n")]])

(defn seed-large!
  "Round-3 scale seed: 12 interconnected namespaces + deterministic padding
  (6 filler fns/ns), as a slopp store under `dir` and files under `<dir>-files`."
  [dir]
  (let [sess (api/open! {:dir dir})]
    (try
      (doseq [[ns-sym src] large-namespaces]
        (let [r (api/ingest! sess ns-sym (str src "\n" (padding ns-sym 6)))]
          (when (:error r) (throw (ex-info (str ns-sym ": " (:error r)) r)))
          (when-not (zero? (+ (get-in r [:test :fail]) (get-in r [:test :error])))
            (throw (ex-info (str ns-sym " not green") r)))))
      (api/done! sess :label "seed: orders v1")
      (api/build! sess (.getAbsolutePath (clojure.java.io/file (str dir "-files"))))
      (finally (api/close! sess)))))

(defn -main [& [which dir]]
  (case which
    "large" (do (seed-large! (or dir "eval-templates/orders"))
                (println "seeded green: orders (12 ns)"))
    (do (seed! (or which "eval-templates/tasker"))
        (println "seeded green: tasker")))
  (shutdown-agents)
  (System/exit 0))
