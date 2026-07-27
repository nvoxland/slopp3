(ns slopp.ui.registry-test
  "The hub registry's three promises, each of which a user notices when it
  breaks: a beat both registers and keeps alive, a silent project greys out
  instead of vanishing, and a url handed to a human keeps working."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.ui.registry :as reg]))

(deftest registration-and-keepalive-are-one-idempotent-call
  (let [entry {:name "slopp2" :dir "/w/slopp2" :url "http://127.0.0.1:52104/"
               :pid 41 :version "0.4.0" :status "idle"}]
    (testing "a first beat registers the project"
      (let [ps (reg/projects (reg/beat {} entry 1000) 1000)]
        (is (= 1 (count ps)))
        (is (= "slopp2" (:name (first ps))))
        (is (= "http://127.0.0.1:52104/" (:url (first ps))))))
    (testing "a later beat from the same dir UPDATES rather than duplicates —
              the hub may be restarted, and every project's next beat has to
              re-register it without anyone bouncing an MCP server"
      (let [r  (-> (reg/beat {} entry 1000)
                   (reg/beat (assoc entry :url "http://127.0.0.1:52999/"
                                          :status "working")
                             12000))
            ps (reg/projects r 12000)]
        (is (= 1 (count ps)))
        (is (= "http://127.0.0.1:52999/" (:url (first ps))))
        (is (= "working" (:status (first ps))))))
    (testing "two dirs are two projects, sorted by name"
      (let [ps (-> (reg/beat {} (assoc entry :name "zebra" :dir "/w/z") 1000)
                   (reg/beat entry 1000)
                   (reg/projects 1000))]
        (is (= ["slopp2" "zebra"] (mapv :name ps)))))))

(deftest a-silent-project-goes-STALE-and-stays-listed
  (let [entry {:name "slopp2" :dir "/w/slopp2" :url "http://127.0.0.1:52104/"}
        r     (reg/beat {} entry 1000)]
    (testing "a project beating on schedule is available"
      (is (:available? (first (reg/projects r 1000))))
      (is (:available? (first (reg/projects r (+ 1000 reg/beat-ms))))))
    (testing "three missed beats is stale — but it stays LISTED, because a
              project you were just looking at should not disappear from the
              picker the moment its editor closes"
      (let [ps (reg/projects r (+ 1001 reg/stale-after-ms))]
        (is (= 1 (count ps)))
        (is (not (:available? (first ps))))
        (is (= "/w/slopp2" (:dir (first ps))))))
    (testing "a later beat revives it — no separate re-registration"
      (let [r2 (reg/beat r entry (+ 100000 reg/stale-after-ms))]
        (is (:available? (first (reg/projects r2 (+ 100000 reg/stale-after-ms)))))))
    (testing "a clean shutdown deregisters outright rather than waiting to go stale"
      (is (empty? (reg/projects (reg/forget r "/w/slopp2") 1000))))))

(deftest a-slug-is-an-address-and-the-dir-is-the-identity
  (testing "the slug is the project's name made url-safe"
    (let [r (reg/beat {} {:name "My Store" :dir "/w/a"} 1000)]
      (is (= "my-store" (:slug (first (reg/projects r 1000)))))
      (is (= "/w/a" (:dir (reg/find-slug r "my-store"))))
      (is (nil? (reg/find-slug r "nope")))))
  (testing "two dirs with the SAME name get distinct slugs — a machine running
            ~/a/web and ~/b/web is the ordinary case, and the incumbent must
            keep the address it was already handed"
    (let [r (-> (reg/beat {} {:name "web" :dir "/a/web"} 1000)
                (reg/beat {:name "web" :dir "/b/web"} 1000))
          [s1 s2] (mapv :slug (reg/projects r 1000))]
      (is (= "web" s1))
      (is (not= s1 s2))
      (is (= "/a/web" (:dir (reg/find-slug r s1))))
      (is (= "/b/web" (:dir (reg/find-slug r s2))))))
  (testing "the slug is minted once and survives every later beat, so a url
            handed out stays the url that works"
    (let [r  (reg/beat {} {:name "web" :dir "/a/web"} 1000)
          r2 (reg/beat r {:name "renamed" :dir "/a/web"} 2000)]
      (is (= "web" (:slug (first (reg/projects r2 2000)))))
      (is (= "renamed" (:name (first (reg/projects r2 2000))))))))
