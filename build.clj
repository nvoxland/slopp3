(ns build
  "Uberjar for slopp-the-tool (kernel-side, like boot.clj — not store code).

  The jar's entry point is CONFIG, not code: the store tracks
  META-INF/MANIFEST.MF (a plain file on the files manifest, full history like
  any form). `Main-Class` names the launcher class this script GENERATES and
  AOT-compiles at build time (host scaffolding, same standing as the O4
  native launcher — gen-class never enters the store); `X-Slopp-Main` names
  the slopp entry fn it delegates to via requiring-resolve, so nothing else
  is AOT'd and the store loader keeps runtime load-string. Result:

    java -jar slopp.jar                    ; boots the store in the CWD
    java -jar slopp.jar <dir> --live
    java -jar slopp.jar --main slopp.sync/-main push <dir> <url>

  Local flow (fileless tree): materialize the store (the `build` MCP tool →
  target/jar-src) then `clojure -T:build uber`. CI flow (checkout of the
  published repo): `clojure -T:build uber :src src`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.build.api :as b]))

(def class-dir "target/classes")
(def jar-file "target/slopp.jar")

(defn- parse-manifest
  "{attr value} from <root>/META-INF/MANIFEST.MF, or nil when untracked."
  [root]
  (let [f (io/file root "META-INF" "MANIFEST.MF")]
    (when (.exists f)
      (into {}
            (keep (fn [line]
                    (let [[_ k v] (re-matches #"([A-Za-z0-9-]+):\s*(.*)"
                                              (str/trim line))]
                      (when k [k v]))))
            (str/split-lines (slurp f))))))

(defn- gen-launcher!
  "Write the delegating launcher ns for `main-class` under target/launcher."
  [main-class slopp-main]
  (let [path (str "target/launcher/"
                  (-> (str main-class)
                      (str/replace "." "/")
                      (str/replace "-" "_"))
                  ".clj")]
    (io/make-parents path)
    (spit path (str "(ns " main-class " (:gen-class))\n"
                    "(defn -main [& args]\n"
                    "  (apply (requiring-resolve '" slopp-main ") args))\n"))))

(defn uber
  "Build target/slopp.jar. :src = the source tree to bundle (default
  target/jar-src/src, the local materialization; pass \"src\" on a checkout).
  Entry point comes from the tracked META-INF/MANIFEST.MF next to :src's
  parent; without one the jar falls back to clojure.main (-m slopp.boot)."
  [{:keys [src] :or {src "target/jar-src/src"}}]
  (b/delete {:path class-dir})
  (b/delete {:path "target/launcher"})
  (let [root  (or (.getParent (io/file (str src))) ".")
        mf    (or (parse-manifest root) {})
        main  (get mf "Main-Class" "clojure.main")
        smain (get mf "X-Slopp-Main")
        extra (not-empty (dissoc mf "Main-Class" "Manifest-Version"))
        basis (b/create-basis {:project "deps.edn"})]
    (b/copy-dir {:src-dirs [(str src)] :target-dir class-dir})
    ;; the tracked manifest is build INPUT — b/uber generates the real one
    (b/delete {:path (str class-dir "/META-INF/MANIFEST.MF")})
    (when (and smain (not= main "clojure.main"))
      (gen-launcher! main smain)
      ;; the launcher dir must be ON the compile basis classpath (src-dirs
      ;; alone isn't, in this tools.build)
      (b/compile-clj {:basis      (b/create-basis
                                   {:project "deps.edn"
                                    :extra   {:paths ["target/launcher"]}})
                      :src-dirs   ["target/launcher"]
                      :class-dir  class-dir
                      :ns-compile [(symbol main)]}))
    (b/uber (cond-> {:class-dir class-dir
                     :uber-file jar-file
                     :basis     basis
                     :main      (symbol main)}
              extra (assoc :manifest extra)))
    (println "built" jar-file "Main-Class:" main)))
