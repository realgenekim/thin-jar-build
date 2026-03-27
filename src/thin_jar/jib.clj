(ns thin-jar.jib
  "Jib deployment functions with layer support for Docker images.

   Supports three layer types:
   - :deps  - JAR dependencies from lib/ directory
   - :app   - Thin application JAR
   - :files - Additional files (Python scripts, configs, etc.)"
  (:require [clojure.java.io :as io])
  (:import
   (com.google.cloud.tools.jib.api Jib
                                   RegistryImage
                                   Containerizer
                                   ImageReference)
   (com.google.cloud.tools.jib.api.buildplan AbsoluteUnixPath
                                             FileEntriesLayer)
   (com.google.cloud.tools.jib.frontend CredentialRetrieverFactory)
   (java.util.function Consumer)
   (java.nio.file Paths)
   (java.io File)
   (java.util ArrayList List)))

;; ============================================================
;; Internal helpers
;; ============================================================

(defn- get-path [filename]
  (Paths/get (.toURI (File. ^String filename))))

(defn- into-list
  [& args]
  (ArrayList. ^List args))

(defn- to-imgref [image-config]
  (ImageReference/parse image-config))

(defn make-logger
  "Create a Jib logger Consumer."
  [verbose]
  (reify Consumer
    (accept [this log-event]
      (when verbose
        (println (.getMessage log-event))))))

(def ^:private default-logger (make-logger true))

;; ============================================================
;; Layer builders
;; ============================================================

(defn- build-deps-layer
  "Build a Jib layer from a lib/ directory containing precompiled JARs."
  [{:keys [lib-dir container-path]}]
  (let [lib-dir-file (io/file lib-dir)
        builder (FileEntriesLayer/builder)]
    (.setName builder "dependencies")
    (doseq [jar-file (.listFiles lib-dir-file)]
      (when (.isFile jar-file)
        (.addEntry builder
                   (.toPath jar-file)
                   (AbsoluteUnixPath/get (str container-path "/" (.getName jar-file))))))
    (.build builder)))

(defn- build-app-layer
  "Build a Jib layer for the thin application JAR."
  [{:keys [jar-path container-path]}]
  (-> (FileEntriesLayer/builder)
      (.setName "application")
      (.addEntry (get-path jar-path)
                 (AbsoluteUnixPath/get container-path))
      (.build)))

(defn- matches-extension?
  "Check if filename matches any of the extensions."
  [filename extensions]
  (some #(.endsWith filename %) extensions))

(defn- blacklisted-prefix?
  "Check if filename starts with any blacklisted prefix."
  [filename prefixes]
  (some #(.startsWith filename %) prefixes))

(defn- build-files-layer
  "Build a Jib layer for additional files (Python scripts, configs).

   Options:
   - :local-dir      - Local directory to include
   - :container-path - Container destination path
   - :extensions     - Set of file extensions to include (e.g. #{\".py\" \".txt\"})
   - :blacklist-prefixes - Prefixes to exclude (e.g. [\"test_\" \"experiment_\"])"
  [{:keys [local-dir container-path extensions blacklist-prefixes]
    :or {extensions #{".py" ".txt" ".sh" ".json" ".yaml" ".yml"}
         blacklist-prefixes []}}]
  (let [dir (io/file local-dir)
        builder (FileEntriesLayer/builder)]
    (.setName builder (str "files:" local-dir))
    (when (.exists dir)
      (doseq [f (.listFiles dir)]
        (when (and (.isFile f)
                   (matches-extension? (.getName f) extensions)
                   (not (blacklisted-prefix? (.getName f) blacklist-prefixes)))
          (.addEntry builder
                     (.toPath f)
                     (AbsoluteUnixPath/get (str container-path "/" (.getName f)))))))
    (.build builder)))

(defn- build-layer
  "Build a single layer based on its type."
  [{:keys [type] :as layer-spec}]
  (case type
    :deps (build-deps-layer layer-spec)
    :app (build-app-layer layer-spec)
    :files (build-files-layer layer-spec)
    (throw (ex-info (str "Unknown layer type: " type) {:layer-spec layer-spec}))))

;; ============================================================
;; Public API
;; ============================================================

(defn deploy!
  "Deploy container image with specified layers.

   Required keys:
   - :target-image - Full image name with tag (e.g. \"us-west1-docker.pkg.dev/proj/repo/img:latest\")
   - :entrypoint   - Vector of entrypoint strings (e.g. [\"java\" \"-jar\" \"/app.jar\"])
   - :layers       - Vector of layer specs (non-empty)

   Optional keys:
   - :base-image   - Base image (default: \"gcr.io/distroless/java21\")
   - :verbose      - Print Jib logs (default: true)

   Layer specs (each requires :type):
   - {:type :deps :lib-dir \"target/lib\" :container-path \"/app/lib\"}
   - {:type :app :jar-path \"target/app.jar\" :container-path \"/app/app.jar\"}
   - {:type :files :local-dir \"scripts\" :container-path \"/scripts\"
      :extensions #{\".py\"} :blacklist-prefixes [\"test_\"]}

   Returns the Jib build result."
  [{:keys [base-image target-image entrypoint layers verbose]
    :or {base-image "gcr.io/distroless/java21"
         verbose true}}]
  ;; Validate required keys
  (when-not target-image
    (throw (ex-info "Required: :target-image" {})))
  (when-not entrypoint
    (throw (ex-info "Required: :entrypoint" {})))
  (when (empty? layers)
    (throw (ex-info "Required: :layers (non-empty)" {})))

  (let [logger (make-logger verbose)]
    (println "Building and pushing container image with Jib...")
    (println (str "  Base image: " base-image))
    (println (str "  Target image: " target-image))
    (println "  Layers:")
    (doseq [{:keys [type] :as layer} layers]
      (println (str "    - " type ": " (dissoc layer :type))))

    (let [base-image-with-creds
          (-> (RegistryImage/named base-image)
              (.addCredentialRetriever
               (-> (CredentialRetrieverFactory/forImage
                    (to-imgref target-image)
                    logger)
                   (.dockerConfig))))

          containerizer
          (Containerizer/to
           (-> (RegistryImage/named (to-imgref target-image))
               (.addCredentialRetriever
                (-> (CredentialRetrieverFactory/forImage
                     (to-imgref target-image)
                     logger)
                    (.dockerConfig)))))

          ;; Build all layers
          built-layers (mapv build-layer layers)

          ;; Start with base image
          jib-builder (Jib/from base-image-with-creds)]

      ;; Add all layers
      (doseq [layer built-layers]
        (.addFileEntriesLayer jib-builder layer))

      ;; Set entrypoint and containerize
      (time
       (-> jib-builder
           (.setEntrypoint (apply into-list entrypoint))
           (.containerize containerizer)))

      (println "Container image built and pushed successfully!")
      (println (str "  Image: " target-image)))))

(comment
  ;; Example: server2 thin JAR deployment
  (deploy! {:target-image "us-west1-docker.pkg.dev/proj/repo/server2:latest"
            :entrypoint ["java" "-cp" "/app/server2.jar:/app/lib/*" "server2.server"]
            :layers [{:type :deps
                      :lib-dir "target/lib"
                      :container-path "/app/lib"}
                     {:type :app
                      :jar-path "target/server2.jar"
                      :container-path "/app/server2.jar"}]})

  ;; Example: classifier with Python scripts + custom base
  (deploy! {:base-image "us.gcr.io/proj/reddit-classifier-base:latest"
            :target-image "us.gcr.io/proj/reddit-classifier:latest"
            :entrypoint ["java" "-cp" "/lib/*:/reddit-scraper.jar" "clojure.main" "-m" "genek.cli.main"]
            :layers [{:type :deps
                      :lib-dir "target/lib"
                      :container-path "/lib"}
                     {:type :app
                      :jar-path "target/reddit-scraper.jar"
                      :container-path "/reddit-scraper.jar"}
                     {:type :files
                      :local-dir "classify"
                      :container-path "/classify"
                      :extensions #{".py" ".txt"}
                      :blacklist-prefixes ["test_" "experiment_"]}]}))
