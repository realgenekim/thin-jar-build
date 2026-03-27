(ns thin-jar.build
  "Thin JAR build for Docker layer caching.

   Proven pattern from reddit-scraper (runs for years) and video-publisher
   (Cloud Run Jobs). Lets compiler find git deps via basis classpath —
   no source copying into class-dir.

   Usage in build.clj:
     (require '[thin-jar.build :as tb])
     (def config {:jar-file \"target/my-app.jar\"
                  :ns-compile ['my.app.cli]})
     (defn thin-build [_] (tb/thin-build config))

   CLI:
     clojure -T:build thin-build    # Full build (clean + deps + jar)
     clojure -T:build clean         # Clean target/
     clojure -T:build copy-deps     # Copy JARs to target/lib/
     clojure -T:build thin-jar      # Build thin app JAR only

   See: https://robjohnson.dev/posts/thin-clj-jars/"
  (:require [clojure.tools.build.api :as b]
            [clojure.java.io :as io]))

(defn- jar-file?
  "Check if path points to a JAR file."
  [path]
  (and (string? path)
       (.endsWith ^String path ".jar")
       (.exists (io/file path))))

(defn- get-lib-jars
  "Get all JAR files from basis classpath.
  Includes source-only JARs (e.g. core.specs.alpha) — Clojure needs them at runtime."
  [basis]
  (->> (:classpath-roots basis)
       (filter jar-file?)))

(defn- resolve-basis
  "Create or reuse a tools.build basis."
  [{:keys [basis aliases]}]
  (or basis
      (b/create-basis (cond-> {:project "deps.edn"}
                        aliases (assoc :aliases aliases)))))

(defn clean
  "Clean build artifacts. Deletes target/ directory.

   Optional keys:
   - :target-dir - Directory to clean (default: \"target\")"
  [{:keys [target-dir] :or {target-dir "target"}}]
  (println "Cleaning" target-dir "...")
  (b/delete {:path target-dir}))

(defn copy-deps
  "Copy all JAR dependencies to lib/ directory.

   Optional keys:
   - :lib-dir - Output directory (default: \"target/lib\")
   - :basis   - tools.build basis (default: from deps.edn)
   - :aliases - Aliases to include in basis

   Returns count of JARs copied."
  [{:keys [lib-dir] :as opts
    :or {lib-dir "target/lib"}}]
  (let [basis (resolve-basis opts)
        jars (get-lib-jars basis)]
    (b/delete {:path lib-dir})
    (.mkdirs (io/file lib-dir))
    (println (str "Copying " (count jars) " dependencies to " lib-dir "/"))
    (doseq [jar-path jars]
      (let [jar-name (.getName (io/file jar-path))
            dest (io/file lib-dir jar-name)]
        (io/copy (io/file jar-path) dest)))
    (count jars)))

(defn thin-jar
  "Build thin app JAR (code only, no dependencies).

   AOT compiles the entry namespace — transitively compiles all requires
   including git deps (gcp-secrets, logging, etc.) via basis classpath.
   Does NOT copy git dep sources into class-dir.

   Required keys:
   - :jar-file    - Output JAR path (e.g. \"target/my-app.jar\")
   - :ns-compile  - Vector of namespaces to AOT compile (e.g. ['my.app.cli])

   Optional keys:
   - :src-dirs  - Source directories (default: [\"src\" \"resources\"])
   - :class-dir - Class output directory (default: \"target/classes\")
   - :basis     - tools.build basis (default: from deps.edn)
   - :aliases   - Aliases to include in basis"
  [{:keys [jar-file ns-compile src-dirs class-dir] :as opts
    :or {src-dirs ["src" "resources"]
         class-dir "target/classes"}}]
  (when-not jar-file
    (throw (ex-info "Required: :jar-file" {})))
  (when-not ns-compile
    (throw (ex-info "Required: :ns-compile" {})))
  (let [basis (resolve-basis opts)]
    (println "Building thin JAR:" jar-file)
    (b/delete {:path class-dir})
    ;; Copy project source + resources only (NOT git dep sources)
    (b/copy-dir {:src-dirs src-dirs :target-dir class-dir})
    ;; AOT compile — finds git deps on classpath via basis naturally
    (b/compile-clj {:basis basis
                    :src-dirs (vec (filter #(.isDirectory (io/file %)) src-dirs))
                    :class-dir class-dir
                    :ns-compile (vec ns-compile)})
    ;; Main-Class: clojure.main — invoke with: java -cp lib/*:app.jar clojure.main -m ns
    (b/jar {:class-dir class-dir
            :jar-file jar-file
            :manifest {"Main-Class" "clojure.main"}})))

(defn thin-build
  "Full thin build: clean + copy-deps + thin-jar.

   Required keys:
   - :jar-file    - Output JAR path
   - :ns-compile  - Vector of namespaces to AOT compile

   Optional keys:
   - :lib-dir   - Lib directory (default: \"target/lib\")
   - :src-dirs  - Source directories (default: [\"src\" \"resources\"])
   - :class-dir - Class output directory (default: \"target/classes\")
   - :basis     - tools.build basis
   - :aliases   - Aliases to include in basis

   Returns map with :lib-count and :jar-file."
  [{:keys [jar-file lib-dir] :as opts
    :or {lib-dir "target/lib"}}]
  (clean opts)
  (let [lib-count (copy-deps (assoc opts :lib-dir lib-dir))]
    (thin-jar opts)
    (println)
    (println "Thin build complete!")
    (println (str "  JAR: " jar-file))
    (println (str "  Deps: " lib-dir "/ (" lib-count " JARs)"))
    {:lib-count lib-count :jar-file jar-file}))
