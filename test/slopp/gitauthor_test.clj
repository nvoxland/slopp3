(ns slopp.gitauthor-test
  "G5: milestone commits carry a configurable author identity, captured INTO
  the marker at milestone time (projection determinism — config changes must
  never re-mint old shas). commit-author resolves it per marker."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.git :as git]))

(deftest author-captured-on-the-marker-wins
  (is (= {:name "Nathan Voxland" :email "nathan@voxland.net"}
         (git/commit-author {:agent "claude"
                             :author {:name "Nathan Voxland"
                                      :email "nathan@voxland.net"}}))))

(deftest legacy-markers-keep-the-agent-identity
  (testing "pre-G5 markers re-mint byte-identically"
    (is (= {:name "alice" :email "alice@slopp"}
           (git/commit-author {:agent "alice"})))
    (is (= {:name "slopp" :email "slopp@slopp"}
           (git/commit-author {})))))