(ns slopp.web.css-test
  "SECURITY + contract tests for slopp.web.css. Garden renders selector and
  value strings VERBATIM, so an interpolated string is a CSS-injection door
  (a `}` breaks out of the block, `<` breaks out if the CSS is ever inlined
  in <style>). These pin both the refusal and garden 1.3.10's rendering."
  (:require [clojure.test :refer [deftest is testing]]
            [garden.stylesheet :as gs]
            [slopp.web.css :as css]))

(deftest value-breakout-is-refused
  (testing "SECURITY: a } in a value cannot break out of the declaration block"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"break out"
                          (css/render [:p {:width "1} body{background:url(evil)"}]))))
  (testing "SECURITY: a } in a selector string cannot break out either"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"break out"
                          (css/render ["p} body" {:margin 0}]))))
  (testing "SECURITY: < is refused (it breaks out of an inlined <style> block)"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"break out"
                          (css/render [:p {:content "</style>"}]))))
  (testing "a raw CSS string as a rule is refused — serve static .css for raw content"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"break out"
                          (css/render "body { color: red }")))))

(deftest rendering-contract-of-the-dep
  (testing "a rule, nested rules, and multiple rules render minified"
    (is (= "main{margin:0 auto;max-width:60rem}"
           (css/render [:main {:margin "0 auto" :max-width "60rem"}])))
    (is (= "main a{color:#357}" (css/render [:main [:a {:color "#357"}]])))
    (is (= "p{margin:0}a{color:#333}"
           (css/render [:p {:margin 0}] [:a {:color "#333"}]))))
  (testing "numbers, grouped selectors, and a media query"
    (is (= "p{z-index:5;line-height:1.5}" (css/render [:p {:z-index 5 :line-height 1.5}])))
    (is (= "h1,h2{font-weight:600}" (css/render [:h1 :h2 {:font-weight "600"}])))
    (is (= "@media(prefers-color-scheme:dark){body{background:#111}}"
           (css/render (gs/at-media {:prefers-color-scheme :dark} [:body {:background "#111"}])))))
  (testing "a data URI value (legitimate ;) is NOT refused"
    (is (= "i{background:url(data:image/svg+xml;base64,PHN2Zz4=)}"
           (css/render [:i {:background "url(data:image/svg+xml;base64,PHN2Zz4=)"}]))))
  (testing "no rules is the empty stylesheet, not a crash"
    (is (= "" (css/render)))))

(deftest css-response-is-a-raw-css-ring-map
  (is (= {:status 200
          :web/raw true
          :headers {"Content-Type" "text/css; charset=utf-8"}
          :body "p{margin:0}a{color:#333}"}
         (css/css-response [[:p {:margin 0}] [:a {:color "#333"}]])))
  (testing "opts merge status and headers; Content-Type stays ours"
    (let [r (css/css-response [[:p {:margin 0}]]
                              {:status 503 :headers {"X-A" "1" "Content-Type" "nope"}})]
      (is (= 503 (:status r)))
      (is (= "1" (get-in r [:headers "X-A"])))
      (is (= "text/css; charset=utf-8" (get-in r [:headers "Content-Type"]))))))
