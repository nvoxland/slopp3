(ns build
  "Uberjar for slopp-the-tool (kernel-side, like boot.clj — not store code).

  The jar bundles slopp's full source (every store namespace, materialized)
  plus all deps; :main is clojure.main, so nothing is AOT'd and the store
  loader keeps its runtime `load-string` semantics:

    java -jar slopp.jar -m slopp.boot <dir> [--snapshot|--live]
                                            [--main slopp.sync/-main ...]

  Local flow (fileless tree): materialize the store first (the `build` MCP
  tool → target/jar-src), then `clojure -T:build uber`. CI flow (a checkout
  of the published repo already has src/): `clojure -T:build uber :src src`."
  (:require [clojure.tools.build.api :as b]))

(def class-dir "target/classes")
(def jar-file "target/slopp.jar")

(defn uber
  "Build target/slopp.jar. :src = the source tree to bundle (default
  target/jar-src/src, the local materialization; pass \"src\" on a checkout)."
  [{:keys [src] :or {src "target/jar-src/src"}}]
  (b/delete {:path class-dir})
  (b/copy-dir {:src-dirs [(str src)] :target-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file jar-file
           :basis     (b/create-basis {:project "deps.edn"})
           :main      'clojure.main})
  (println "built" jar-file))
