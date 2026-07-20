(ns slopp.git-embedded-test
  "P4-m8 M7: the MCP server the agent starts also LISTENS for git — an
  in-process port on the same store dir, no external daemon. The port is
  derived from the dir (stable across restarts, so a saved git remote keeps
  working), falling back to ephemeral on collision. `query_git` tells the
  agent the URL to hand to `git remote add`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [slopp.api :as api]
            [slopp.mcp :as mcp] [slopp.git.server :as server] [slopp.api.query :as query])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [org.eclipse.jgit.api Git]))

(defn- temp-dir [nm]
  (str (Files/createTempDirectory nm (make-array FileAttribute 0))))

(def seed
  (str "(ns ge.core (:require [clojure.test :refer [deftest is]]))\n"
       "\n"
       "(defn f [x] (+ x 10))\n"
       "\n"
       "(deftest f-t (is (= 11 (f 1))))\n"))

(deftest derived-port-is-stable-and-in-range
  (let [d "/some/store/dir"]
    (testing "same dir → same port, always in the private range"
      (is (= (server/derived-port d) (server/derived-port d)))
      (is (<= 49152 (server/derived-port d) 65535)))
    (testing "different dirs generally differ"
      (is (not= (server/derived-port "/a/b") (server/derived-port "/c/d"))))))

(deftest ^:external start-server-reports-actual-bound-port-and-falls-back
  (let [dir (temp-dir "slopp-embed-port")
        p   (server/derived-port dir)
        s1  (server/start-server! p {:dir dir})]
    (try
      (testing "the requested derived port is what got bound"
        (is (= p (:port s1))))
      (testing "a second server on the SAME derived port falls back, still serves"
        (let [s2 (server/start-server! p {:dir dir})]
          (try
            (is (not= p (:port s2)))
            (is (<= 1024 (:port s2) 65535))
            (finally (server/stop-server! s2)))))
      (finally (server/stop-server! s1)))))

(deftest ^:external query-git-surfaces-the-url-over-mcp
  (let [dir  (temp-dir "slopp-embed-mcp")
        sess (api/open! {:slopp.api/dir dir})
        call (fn [tool args]
               (get-in (mcp/handle! sess {:id 1 :method "tools/call"
                                         :params {:name tool :arguments args}})
                       [:result :content 0 :text]))]
    (try
      (api/ingest! sess 'ge.core seed)
      (api/commit-point! sess "v1: f ships" :agent "alice")
      (testing "with no listener, query_git says so (no crash)"
        (is (str/includes? (call "query_git" {}) "no git")))
      ;; simulate what mcp/-main does for a durable session
      (let [srv (server/start-server! (server/derived-port dir) {:dir dir})
            url (str "http://127.0.0.1:" (:port srv) "/slopp.git")]
        (try
          (swap! sess assoc :git-url url :git-server srv)
          (testing "query_git now hands back the remote URL + git command"
            (let [r (call "query_git" {})]
              (is (str/includes? r url))
              (is (str/includes? r "git remote add"))))
          (testing "the advertised URL actually clones this store's milestone"
            (let [clone-dir (temp-dir "slopp-embed-clone")]
              (with-open [g (-> (Git/cloneRepository) (.setURI url)
                                (.setDirectory (io/file clone-dir)) (.call))]
                (is (str/starts-with?
                     (.getFullMessage (first (-> g (.log) (.call))))
                     "v1: f ships"))
                (is (= (query/query-source sess 'ge.core)
                       (slurp (io/file clone-dir "src" "ge" "core.clj")))))))
          (finally (server/stop-server! srv))))
      (testing "query_git is a READ — allowed with no open turn (write-gated server)"
        (swap! sess assoc :require-turns? true)
        (is (not (str/includes? (call "query_git" {}) "turn_begin"))))
      (finally (api/close! sess)))))
