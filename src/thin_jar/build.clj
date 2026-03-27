(ns thin-jar.build
  "Thin JAR build functions for better Docker layer caching.

   Creates separate lib/ directory with precompiled deps and thin app JAR.
   See: https://robjohnson.dev/posts/thin-clj-jars/"
  (:require [clojure.tools.build.api :as b]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.util.jar JarFile]))

(defn- has-class-files?
  "Check if JAR contains precompiled .class files (not just source)."
  [jar-path]
  (try
    (with-open [jar (JarFile. (io/file jar-path))]
      (boolean
       (some #(and (.endsWith (.getName %) ".class")
                   ;; Exclude module-info.class which doesn't count
                   (not (.endsWith (.getName %) "module-info.class")))
             (enumeration-seq (.entries jar)))))
    (catch Exception _
      false)))

(defn- jar-file?
  "Check if path points to a JAR file."
  [path]
  (and (string? path)
       (.endsWith ^String path ".jar")
       (.exists (io/file path))))

(defn- get-lib-jars
  "Get all JAR files from basis that contain precompiled classes."
  [basis]
  (->> (:classpath-roots basis)
       (filter jar-file?)
       (filter has-class-files?)))

(defn- copy-lib-jars!
  "Copy precompiled JARs to lib/ directory."
  [basis lib-dir]
  (let [jars (get-lib-jars basis)]
    (println (str "Copying " (count jars) " precompiled JARs to " lib-dir "/"))
    (.mkdirs (io/file lib-dir))
    (doseq [jar-path jars]
      (let [jar-name (.getName (io/file jar-path))
            dest (io/file lib-dir jar-name)]
        (io/copy (io/file jar-path) dest)))
    jars))

(defn- build-classpath-string
  "Build Class-Path manifest entry for lib/ JARs."
  [lib-jar-paths]
  (->> lib-jar-paths
       (map #(.getName (io/file %)))
       (map #(str "lib/" %))
       (str/join " ")))

(defn thin
  "Build thin JAR with separate lib/ directory for precompiled deps.

   Required keys:
   - :main-class  - Symbol for main namespace (e.g. 'server2.server)
   - :jar-file    - Path to output JAR (e.g. \"target/server2.jar\")

   Optional keys:
   - :lib-dir     - Path to lib directory (default: \"target/lib\")
   - :src-dirs    - Source directories (default: [\"src\" \"resources\"])
   - :class-dir   - Class output directory (default: \"target/classes\")
   - :basis       - tools.build basis (default: creates from deps.edn)
   - :aliases     - Aliases to include in basis (default: none)

   Returns map with :lib-jars (count of JARs) and :jar-file."
  [{:keys [main-class jar-file lib-dir src-dirs class-dir basis aliases]
    :or {lib-dir "target/lib"
         src-dirs ["src" "resources"]
         class-dir "target/classes"}}]
  ;; Validate required keys
  (when-not main-class
    (throw (ex-info "Required: :main-class" {:provided-keys (keys {:main-class main-class :jar-file jar-file})})))
  (when-not jar-file
    (throw (ex-info "Required: :jar-file" {:provided-keys (keys {:main-class main-class :jar-file jar-file})})))

  ;; Clean target
  (b/delete {:path "target"})

  ;; Create or use provided basis
  (let [basis (or basis
                  (b/create-basis (cond-> {:project "deps.edn"}
                                    aliases (assoc :aliases aliases))))
        lib-jars (copy-lib-jars! basis lib-dir)
        classpath-str (build-classpath-string lib-jars)]

    ;; Copy source + resources
    (b/copy-dir {:src-dirs src-dirs
                 :target-dir class-dir})

    ;; Compile app code
    (println "Compiling Clojure sources...")
    (b/compile-clj {:basis basis
                    :ns-compile [main-class]
                    :class-dir class-dir})

    ;; Build thin JAR with Class-Path manifest
    (println "Building thin JAR...")
    (b/jar {:class-dir class-dir
            :jar-file jar-file
            :manifest {"Main-Class" (str main-class)
                       "Class-Path" classpath-str}})

    (println)
    (println "Thin JAR build complete:")
    (println (str "  App JAR: " jar-file))
    (println (str "  Lib dir: " lib-dir "/ (" (count lib-jars) " JARs)"))
    (println)
    (println "To run locally:")
    (println (str "  java -jar " jar-file))

    {:lib-jars (count lib-jars)
     :jar-file jar-file}))
