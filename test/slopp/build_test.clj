(ns slopp.build-test
  "Cover for `slopp.build`, the pure generators that emit a project's build
  inputs — `deps.edn`, the native-image scripts. Checked by GENERATING and
  reading the text, because a generator's only contract is what a build tool
  later reads: nothing here mocks a build, and nothing here can, since the
  failure these prevent happens on someone else's machine."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.build :as build]))

(deftest native-script-carries-assets
  (testing "asset paths copy into classes/ and ride -H:IncludeResources"
    (let [s (build/native-script "myapp" ["public/logo.png" "public/app.css"])]
      (is (re-find #"mkdir -p \"classes/public\"" s))
      (is (re-find #"cp \"public/logo.png\" \"classes/public/logo.png\"" s))
      (is (re-find #"IncludeResources='public/app\\\.css\|public/logo\\\.png'" s) s)))
  (testing "no assets, no extra lines"
    (let [s (build/native-script "myapp" [])]
      (is (not (re-find #"IncludeResources" s)))
      (is (not (re-find #"\ncp \"" s))))))
