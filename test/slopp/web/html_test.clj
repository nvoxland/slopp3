(ns slopp.web.html-test
  "SECURITY tests: these pin the rendering contract of slopp.web.html — the
  wrapper's refusals AND the hiccup dep's escaping behavior. A red here on a
  hiccup upgrade means the escaping contract changed underneath us; treat it
  as a security event, not a formatting nit."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.web.html :as html]))

(deftest text-escaping-blocks-injection
  (testing "SECURITY: text children are escaped by default"
    (is (= "<p>&lt;script&gt;alert(1)&lt;/script&gt; &amp; it&apos;s</p>"
           (html/render [:p "<script>alert(1)</script> & it's"])))))

(deftest attribute-escaping-blocks-breakout
  (testing "SECURITY: attribute values cannot close their own quoting"
    (is (= "<div title=\"&quot; onmouseover=&quot;x()\"></div>"
           (html/render [:div {:title "\" onmouseover=\"x()"}])))
    (is (= "<div title=\"it&apos;s\"></div>"
           (html/render [:div {:title "it's"}])))))

(deftest attr-and-tag-names-are-validated
  (testing "SECURITY: hiccup renders crafted tag/attr NAMES verbatim — the wrapper refuses them"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid tag"
                          (html/render [(keyword "div onload=x") "hi"])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid attribute name"
                          (html/render [:div {(keyword "onload=x") "y"}])))))

(deftest url-attrs-refuse-script-schemes
  (testing "SECURITY: escaping cannot neutralize a javascript:/data: URL — refuse at render"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"refused URL scheme"
                          (html/render [:a {:href "javascript:alert(1)"} "x"])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"refused URL scheme"
                          (html/render [:a {:href " jAvaScript:alert(1)"} "x"])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"refused URL scheme"
                          (html/render [:img {:src "data:text/html,<script>"}])))
    (is (= "<a href=\"/store\">x</a>" (html/render [:a {:href "/store"} "x"])))
    (is (= "<a href=\"https://example.com\">x</a>"
           (html/render [:a {:href "https://example.com"} "x"])))))

(deftest raw-island-is-verbatim-and-string-only
  (testing "SECURITY: [:html/raw s] is the ONE escaping bypass; string payload only"
    (is (= "<div><b>x</b></div>" (html/render [:div [:html/raw "<b>x</b>"]])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ONE string payload"
                          (html/render [:div [:html/raw {:a 1}]])))))

(deftest rendering-contract-of-the-dep
  (testing "id/class sugar"
    (is (= "<div class=\"b c\" id=\"a\">t</div>" (html/render [:div#a.b.c "t"]))))
  (testing "boolean attrs: true renders bare, false/nil omit"
    (is (= "<input disabled>"
           (html/render [:input {:disabled true :checked false :value nil}]))))
  (testing "void tags render without a closer"
    (is (= "<br>" (html/render [:br]))))
  (testing "nil children vanish, numbers render, seqs splice"
    (is (= "<div>x5</div>" (html/render [:div nil "x" 5])))
    (is (= "<ul><li>1</li><li>2</li></ul>"
           (html/render [:ul (for [i [1 2]] [:li i])]))))
  (testing "style maps serialize"
    (is (= "<div style=\"color:red;margin:0;\">s</div>"
           (html/render [:div {:style {:color "red" :margin "0"}} "s"])))))

(deftest teaching-errors-name-the-fix
  (testing "a map in child position teaches the cond-> idiom"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cond->"
                          (html/render [:div "text" {:class "x"}]))))
  (testing "a vector used to group siblings teaches seqs"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"group siblings with a seq"
                          (html/render [:div [["a"] ["b"]]])))))

(deftest html-response-is-a-raw-html-ring-map
  (is (= {:status 200
          :web/raw true
          :headers {"Content-Type" "text/html; charset=utf-8"}
          :body "<p>hi</p>"}
         (html/html-response [:p "hi"])))
  (testing "opts merge status and headers; Content-Type stays ours"
    (let [r (html/html-response [:p "x"] {:status 404
                                          :headers {"X-A" "1"
                                                    "Content-Type" "nope"}})]
      (is (= 404 (:status r)))
      (is (= "1" (get-in r [:headers "X-A"])))
      (is (= "text/html; charset=utf-8" (get-in r [:headers "Content-Type"]))))))

(deftest page-shell-renders-a-full-document
  (testing "doctype, charset meta, escaped title; NO inline script or style"
    (is (= (str "<!DOCTYPE html><html>"
                "<head><meta charset=\"utf-8\"><title>T &amp; t&apos;s</title></head>"
                "<body><main>b</main></body></html>")
           (html/render (html/page {:title "T & t's"} [:main "b"])))))
  (testing ":lang and extra :head elements ride the shell"
    (is (= (str "<!DOCTYPE html><html lang=\"en\">"
                "<head><meta charset=\"utf-8\"><title>T</title>"
                "<link href=\"/assets/app.css\" rel=\"stylesheet\">"
                "</head>"
                "<body><p>x</p></body></html>")
           (html/render
            (html/page {:title "T" :lang "en"
                        :head [[:link {:rel "stylesheet" :href "/assets/app.css"}]]}
                       [:p "x"]))))))
