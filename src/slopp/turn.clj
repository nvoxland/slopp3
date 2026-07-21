(ns slopp.turn
  "One-shot turn markers for Claude Code hooks: append a :turn-begin /
  :turn-end delta DIRECTLY to a project's journal, out-of-band — the agent's
  own server absorbs it via journal sync (m5b). This is how the VERBATIM
  user prompt reaches provenance without the model relaying it.

  Hook wiring (per agent workspace, .claude/settings.json):
    UserPromptSubmit -> cd <slopp-repo> && clojure -M -m slopp.turn <dir> hook-begin <agent>
    Stop             -> cd <slopp-repo> && clojure -M -m slopp.turn <dir> hook-end <agent>
  The hook-* modes read Claude Code's hook JSON from stdin ({\"prompt\": ...})
  so the intent recorded is the user's exact words. Manual modes:
    clojure -M -m slopp.turn <dir> begin <agent> <prompt words...>
    clojure -M -m slopp.turn <dir> end <agent>"
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [slopp.db :as db]
            [slopp.store :as store]))

^:unsafe (defn- append-marker! [dir kind agent intent]
  ;; {:create? false}: the hooks fire this in whatever dir the session
  ;; happens to be in. A marker is provenance ABOUT a store — where there
  ;; is none, it is a no-op, never an adoption.
  (if-let [conn (db/open! dir {:create? false})]
    (try
      (loop [n 0]
        (let [st (or (db/load-store conn) (store/empty-store))
              [st' _] (store/record-turn st kind :agent agent :intent intent)
              head (:id (last (store/deltas st)))]
          (if (db/append! conn st'
                          (drop (count (store/deltas st)) (store/deltas st'))
                          [] head)
            (println "turn" (name kind) "recorded for" agent)
            (if (< n 10)
              (recur (inc n))
              (binding [*out* *err*] (println "contention — giving up"))))))
      (finally (.close ^java.sql.Connection conn)))
    (binding [*out* *err*]
      (println "no slopp store at" dir "— turn" (name kind) "not recorded"))))

^:unsafe (defn -main "CLI: append a turn marker to `dir`'s journal — the identity boundary an
  agent's episodes hang off.

  `begin`/`end` take the intent as trailing words. `hook-begin`/`hook-end` are
  the Claude Code hook modes: the payload arrives as JSON on stdin, and
  UserPromptSubmit carries the user's exact words in `:prompt`, so the turn
  records what was actually asked rather than a paraphrase.

  `clojure -M -m slopp.turn <dir> begin|end|hook-begin|hook-end <agent> [intent...]`"
  [dir kind agent & intent-words]
  (case kind
    "begin"      (append-marker! dir :turn-begin agent
                                 (when (seq intent-words)
                                   (str/join " " intent-words)))
    "end"        (append-marker! dir :turn-end agent nil)
    ;; hook modes: Claude Code pipes its hook payload as JSON on stdin;
    ;; UserPromptSubmit carries the user's exact words in :prompt
    "hook-begin" (let [payload (try (json/parse-string (slurp *in*) true)
                                    (catch Exception _ nil))]
                   (append-marker! dir :turn-begin agent (:prompt payload)))
    "hook-end"   (do (try (slurp *in*) (catch Exception _))
                     (append-marker! dir :turn-end agent nil))
    (binding [*out* *err*]
      (println "usage: slopp.turn <dir> begin|end|hook-begin|hook-end <agent> [intent...]"))))