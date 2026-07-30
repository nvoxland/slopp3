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
  ;; The tree is FILELESS: `src` is a MATERIALIZATION of the store, produced by
  ;; the `build` tool. `uber` alone re-jars whatever is sitting there — which
  ;; may be days old — and still prints "built target/slopp.jar" in a few
  ;; seconds, so a stale jar ships silently (it did: a whole debugging cycle
  ;; chasing a fix that was never in the artifact). Refuse instead. CI passes an
  ;; explicit :src from a real checkout and is unaffected.
  (when (= src "target/jar-src/src")
    (let [srcd (io/file src)]
      ;; MISSING is unambiguous — refuse.
      (when-not (.exists srcd)
        (throw (ex-info (str "no materialized source at " src " — materialize the"
                             " store first (the `build` MCP tool, or:"
                             " slopp --call build '{\"dir\":\""
                             (.getAbsolutePath (io/file "target/jar-src")) "\"}')")
                        {:src src})))
      ;; STALE can only be a hint, never a refusal: a live session touches
      ;; store.db constantly, so "db is newer" is often off by seconds and
      ;; failing on it would block legitimate builds. Compare the newest FILE
      ;; under src — a directory's mtime does NOT move when nested files are
      ;; rewritten, which is what made the first version of this check misfire.
      (let [newest (->> (file-seq srcd)
                        (filter #(.isFile ^java.io.File %))
                        (map #(.lastModified ^java.io.File %))
                        (reduce max 0))
            db     (io/file ".slopp" "store.db")
            stamp  (io/file (or (.getParent srcd) ".") ".slopp-head")
            fmt    #(.format (java.text.SimpleDateFormat. "HH:mm:ss") (java.util.Date. ^long %))]
        ;; NEVER be silent about what is being shipped. `build!` stamps the
        ;; materialization with the head delta it was built from; print it so a
        ;; stale jar is visible rather than inferred. (This tool runs under -T,
        ;; whose deps replace the project's, so it has no sqlite driver to read
        ;; the current head itself — comparing is the caller's one glance.)
        (println (str "jarring a materialization of "
                      (if (.exists stamp) (str "head " (slurp stamp)) "UNKNOWN head")
                      ", written " (fmt newest)))
        (when (and (.exists db) (> (.lastModified db) newest))
          (println (str "WARNING: .slopp/store.db changed at " (fmt (.lastModified db))
                        ", after that materialization — this jar may be STALE."
                        " Re-run the `build` tool if you expect recent store"
                        " changes in it."))))))
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
    ;; Declare what this jar BUNDLES, from the basis that is producing it.
    ;; `java -jar` gives the runtime no basis, so `add-libs` believes the JVM
    ;; is bare: it "adds" coords the uberjar already carries (and loses to the
    ;; parent classloader), and resolves every transitive graph from nothing.
    ;; slopp.boot/ensure-bundled-libs! seeds this back in at startup. Generated
    ;; rather than hand-listed so it cannot drift from what actually shipped;
    ;; :paths are dropped because they name THIS machine's ~/.m2, which is not
    ;; where the code is once it is inside the jar.
    (let [f (io/file class-dir "META-INF" "slopp" "bundled-libs.edn")]
      (io/make-parents f)
      (spit f (pr-str (into (sorted-map)
                            (map (fn [[lib coord]] [lib (dissoc coord :paths)]))
                            (:libs basis)))))
    ;; THE FRAMEWORK slopp vendors into the stores it serves
    ;; (D-framework-injection part 2). Two facts, both generated so neither can
    ;; drift from what shipped:
    ;;
    ;;   framework-version.edn — which slopp-web this jar's slopp/web/** IS,
    ;;     authored once in the tracked manifest. NOT a maven version any more;
    ;;     slopp-web is never published, so this is a STAMP saying what a built
    ;;     tree carries.
    ;;   framework-files.edn   — the file list, because a jar cannot glob its
    ;;     own resources and the vendoring has to enumerate them at runtime.
    ;;     Derived from the tree being jarred rather than hand-listed: a
    ;;     hand-list is a claim that goes stale the first time a namespace is
    ;;     added to slopp.web.
    (when-let [v (get mf "X-Slopp-Web-Version")]
      (let [f (io/file class-dir "META-INF" "slopp" "framework-version.edn")]
        (io/make-parents f)
        (spit f v)))
    (let [root  (io/file (str src))
          web   (io/file root "slopp" "web")
          files (cond-> (vec (sort (for [f (file-seq web)
                                         :when (and (.isFile f)
                                                    (.endsWith (.getName f) ".clj"))]
                                     (str "slopp/web/"
                                          (subs (.getPath f)
                                                (inc (count (.getPath web))))))))
                  (.exists (io/file root "slopp" "web.clj"))
                  (conj "slopp/web.clj"))
          f     (io/file class-dir "META-INF" "slopp" "framework-files.edn")]
      (io/make-parents f)
      (spit f (pr-str (vec (sort files)))))
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
                     :uber-file (str jar-file ".building")
                     :basis     basis
                     :main      (symbol main)}
              extra (assoc :manifest extra)))
    ;; ATOMIC swap: writing the final path directly TRUNCATES the inode a
    ;; running server has open — its lazy classloads then read a shifted zip
    ;; and every server-side op fails (the jar-swap corruption). A rename
    ;; replaces the PATH while the old inode survives for whoever holds it:
    ;; the running server keeps serving its jar; the next launch gets this one.
    (java.nio.file.Files/move
     (.toPath (io/file (str jar-file ".building")))
     (.toPath (io/file jar-file))
     (into-array java.nio.file.CopyOption
                 [java.nio.file.StandardCopyOption/ATOMIC_MOVE
                  java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
    (println "built" jar-file "Main-Class:" main)))
