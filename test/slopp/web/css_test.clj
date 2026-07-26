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

(deftest a-function-in-a-rule-is-refused-not-stringified
  ;; This one SHIPPED. `[:.app > :nav {:width "16rem"}]` reads as a vector
  ;; containing clojure.core/> — the numeric comparison fn — because a bare
  ;; `>` is not garden's child combinator. Garden then rendered the function
  ;; OBJECT into the output:
  ;;
  ;;   .app{width:16rem}                        <- the > :nav simply vanished
  ;;   clojure.core$_GT_@185af676 nav aside{…}  <- and reappeared here
  ;;
  ;; So a rule meant for the left pane silently applied to the whole app
  ;; container, which was 16rem wide in a browser for a whole wave. Nothing
  ;; failed: the endpoint returned 200 and valid-looking CSS.
  ;;
  ;; A function in CSS data is ALWAYS a mistake — there is no rule under
  ;; which one is meaningful — so it is refused rather than rendered.
  (testing "a fn in selector position is refused, and the message teaches"
    (let [e (try (css/render [:.app > :nav {:width "16rem"}])
                 nil
                 (catch clojure.lang.ExceptionInfo ex ex))]
      (is (some? e) "garden would otherwise stringify the function object")
      (is (re-find #"(?i)function" (ex-message e)) (ex-message e))
      (is (re-find #"\.app>nav|combinator" (ex-message e))
          (str "the message has to name the fix, not just the sin: "
               (ex-message e)))))
  (testing "the ordinary rules this guard sits in front of still render"
    ;; the two combinators, pinned because getting them backwards is exactly
    ;; what produced the shipped bug:
    (testing "a combinator inside ONE keyword is the child selector"
      (is (= ".app>nav{width:16rem}"
             (css/render [:.app>nav {:width "16rem"}]))))
    (testing "NESTING is the descendant selector"
      (is (= "header ul{margin:0}"
             (css/render [:header [:ul {:margin 0}]]))))
    (testing "and sibling keywords are a GROUP, not a descendant"
      ;; `[:.app :nav {…}]` looks like \".app nav\" and means \".app, nav\" —
      ;; it styles every nav on the page, which is how a breadcrumb inside
      ;; main picks up left-pane styling
      (is (= ".app,nav{width:16rem}"
             (css/render [:.app :nav {:width "16rem"}]))))))
