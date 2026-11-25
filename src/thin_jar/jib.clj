(ns thin-jar.jib
  "Plan and build layered container images with Jib."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   (com.google.cloud.tools.jib.api Containerizer ImageReference InvalidImageReferenceException Jib JibContainer JibContainerBuilder RegistryImage TarImage)
   (com.google.cloud.tools.jib.api.buildplan AbsoluteUnixPath FileEntriesLayer)
   (com.google.cloud.tools.jib.frontend CredentialRetrieverFactory)
   (java.io File)
   (java.util ArrayList Collection List Map Set)
   (java.util.function Consumer)))

(def version "The thin-jar-build library version." "0.2.0")

(def ^:private default-base "gcr.io/distroless/java21")
(def ^:private base-label "org.opencontainers.image.base.name")

(def ^:private spec-contract
  {:top {:allowed #{:base :base-image :target-image :entrypoint :layers :verbose :to :to-tar :env :labels}
         :required #{:entrypoint :layers}}
   :base {:allowed #{:image :reason}
          :required #{:image :reason}}
   :to {:allowed #{:registry :tar :image-name}
        :required #{}
        :alternatives {:registry #{:registry}
                       :tar #{:tar :image-name}}}
   :layers {:deps {:allowed #{:type :lib-dir :container-path}
                   :required #{:type :lib-dir}
                   :source-key :lib-dir}
            :app {:allowed #{:type :jar-path :container-path}
                  :required #{:type :jar-path :container-path}
                  :source-key :jar-path}
            :files {:allowed #{:type :local-dir :container-path :extensions :blacklist-prefixes :names}
                    :required #{:type :local-dir}
                    :source-key :local-dir}}})

(defn- invalid-config! [reason path allowed & [offending details]]
  (let [offending (vec (sort-by pr-str offending))
        environment? (and (= reason :unknown-key) (empty? path)
                          (some #{:environment} offending))]
    (throw (ex-info (str "Invalid Jib configuration " (pr-str reason) " at " (pr-str path)
                         (when environment?
                           ". Use :env to add or override individual variables. The :environment key of another fork sets the whole environment map on the Jib builder and enforces JVM flags, so it is not accepted as an alias."))
                    (merge {:type ::invalid-config
                            :reason reason
                            :path path
                            :offending-keys offending
                            :unknown-keys (if (= reason :unknown-key) offending [])
                            :allowed-keys (vec (sort allowed))}
                           details)))))

(defn- validate-required! [spec path allowed required]
  (when-let [missing (first (remove #(contains? spec %) (sort required)))]
    (invalid-config! :missing-required-key (conj path missing) allowed [missing])))

(defn- validate-keys! [spec path {:keys [allowed required]}]
  (when-not (map? spec)
    (invalid-config! :expected-map path allowed (take-last 1 path)))
  (when-let [unknown (seq (remove allowed (keys spec)))]
    (invalid-config! :unknown-key path allowed unknown))
  (validate-required! spec path allowed required))

(defn- nonblank-string? [x]
  (and (string? x) (not (str/blank? x))))

(defn- string-map? [x]
  (and (map? x)
       (every? (fn [[k v]] (and (string? k) (string? v))) x)))

(defn- invalid-value! [path allowed]
  (invalid-config! :invalid-value path allowed [(peek path)]))

(defn- source-file [source]
  (try
    (io/file source)
    (catch IllegalArgumentException _
      nil)))

(defn- validate-jar-source! [jar-path path allowed]
  (let [^File source (source-file jar-path)
        source-path (conj path :jar-path)]
    (when-not (and source (.exists source))
      (invalid-config! :source-not-found source-path allowed [:jar-path]))
    (when-not (.isFile source)
      (invalid-config! :wrong-source-kind source-path allowed [:jar-path]
                       {:expected-kind :regular-file}))))

(defn- non-map-collection? [value]
  (and (or (coll? value) (instance? Collection value))
       (not (map? value))))

(defn- collection-or-array? [value]
  (or (non-map-collection? value)
      (and (some? value) (.isArray (class value)))))

(defn- string-collection? [value]
  (and (collection-or-array? value) (every? string? value)))

(defn- validate-file-filters! [{:keys [names extensions blacklist-prefixes]}
                               path allowed]
  (when (or (string? names) (keyword? names))
    (invalid-value! (conj path :names) allowed))
  (when (and (some? extensions) (not (string-collection? extensions)))
    (invalid-value! (conj path :extensions) allowed))
  (when-not (and (seqable? blacklist-prefixes) (every? string? blacklist-prefixes))
    (invalid-value! (conj path :blacklist-prefixes) allowed)))

(defn- container-path-value
  "f5bd8dd and the fork used any container path through its text form (File, Path, ...)."
  [value]
  (cond
    (instance? File value) (.getPath ^File value)
    (or (nil? value) (string? value)) value
    :else (str value)))

(defn- validate-layer! [index layer]
  (let [path [:layers index]
        type-path (conj path :type)]
    (when-not (map? layer)
      (invalid-config! :expected-map path #{:type}))
    (when-not (some #{:type} (keys layer))
      (validate-keys! layer path {:allowed #{:type} :required #{:type}}))
    (let [{:keys [type]} layer
          {:keys [allowed] :as contract} (get-in spec-contract [:layers type])]
      (cond
        (nil? type) (invalid-config! :missing-required-key type-path #{:type} [:type])
        (nil? contract) (invalid-config! :unknown-layer-type type-path #{:type} [:type]))
      (validate-keys! layer path contract)
      (let [container-path (container-path-value (:container-path layer))]
        (when-not (or (and (not= :app type) (contains? #{nil ""} container-path))
                      (and (string? container-path) (str/starts-with? container-path "/")))
          (invalid-value! (conj path :container-path) allowed)))
      (when (= :app type)
        (validate-jar-source! (:jar-path layer) path allowed))
      (when (= :files type)
        (validate-file-filters! layer path allowed)))))

(defn- to-imgref [image-config]
  (ImageReference/parse image-config))

(defn- validate-image! [value path allowed]
  (when-not (nonblank-string? value)
    (invalid-value! path allowed))
  (try
    (to-imgref value)
    (catch InvalidImageReferenceException _
      (invalid-value! path allowed))))

(defn- validate-destination! [spec top-allowed]
  (if (contains? spec :to)
    (let [legacy-keys (filter #(contains? spec %) [:target-image :to-tar])
          {:keys [to]} spec
          {:keys [allowed alternatives] :as destination-contract} (:to spec-contract)]
      (when (seq legacy-keys)
        (invalid-config! :conflicting-keys [] top-allowed (cons :to legacy-keys)))
      (validate-keys! to [:to] destination-contract)
      (when (empty? to)
        (invalid-config! :no-destination [:to] allowed (keys alternatives)))
      (let [mode (if (contains? to :registry) :registry :tar)
            required (alternatives mode)]
        (when (some (complement required) (keys to))
          (invalid-config! :conflicting-keys [:to] allowed (keys to)))
        (validate-required! to [:to] allowed required))
      (doseq [[k v] to]
        (if (= :tar k)
          (when-not (nonblank-string? v)
            (invalid-value! [:to k] allowed))
          (validate-image! v [:to k] allowed))))
    (do
      (when-not (contains? spec :target-image)
        (if (contains? spec :to-tar)
          (validate-required! spec [] top-allowed #{:target-image})
          (invalid-config! :no-destination [] top-allowed [:to :target-image])))
      (validate-image! (:target-image spec) [:target-image] top-allowed)
      (when (and (contains? spec :to-tar) (not (nonblank-string? (:to-tar spec))))
        (invalid-value! [:to-tar] top-allowed)))))

(defn- validate-base! [spec allowed]
  (if (contains? spec :base)
    (do
      (when (contains? spec :base-image)
        (invalid-config! :conflicting-keys [] allowed [:base :base-image]))
      (let [base (:base spec)
            contract (:base spec-contract)]
        (validate-keys! base [:base] contract)
        (validate-image! (:image base) [:base :image] (:allowed contract))
        (when-not (nonblank-string? (:reason base))
          (invalid-config! :invalid-base [:base :reason] (:allowed contract) [:reason]))))
    (when (and (contains? spec :base-image) (not (instance? ImageReference (:base-image spec))))
      (validate-image! (:base-image spec) [:base-image] allowed))))

(defn validate-spec!
  "Return the original spec if its configuration and app source are valid. Throw ex-info otherwise."
  [spec]
  (let [{:keys [allowed] :as contract} (:top spec-contract)]
    (validate-keys! spec [] contract)
    (validate-destination! spec allowed)
    (validate-base! spec allowed)
    (let [{:keys [entrypoint layers env labels]} spec]
      (when-not (and (collection-or-array? entrypoint) (seq entrypoint) (every? string? entrypoint))
        (invalid-value! [:entrypoint] allowed))
      (when-not (and (collection-or-array? layers) (seq layers))
        (invalid-config! :invalid-layers [:layers] allowed [:layers]))
      (when (and (some? env) (not (string-map? env)))
        (invalid-value! [:env] allowed))
      (when (and (contains? spec :labels) (not (string-map? labels)))
        (invalid-value! [:labels] allowed))
      (when-let [reserved (seq (filter #(contains? labels %) [base-label]))]
        (invalid-config! :conflicting-keys [:labels] allowed reserved))
      (doseq [[index layer] (map-indexed vector layers)]
        (validate-layer! index layer)))
    spec))

(defn make-logger
  "Create a Jib logger Consumer."
  [verbose]
  (reify Consumer
    (accept [_ log-event]
      (when verbose
        (println (.getMessage log-event))))))

(defn- file-entry [^File file container-path]
  {:source (.getAbsolutePath file)
   :container-path (str (AbsoluteUnixPath/get container-path))})

(defn- plan-directory [index type name source container-path include? filters]
  (let [^File dir (source-file source)
        files (filter #(.isFile ^File %) (when dir (.listFiles dir)))
        entries (into []
                      (comp (filter #(include? (.getName ^File %)))
                            (map #(file-entry % (str container-path "/" (.getName ^File %)))))
                      files)]
    (cond-> {:type type :name name :entries entries}
      (empty? entries)
      (assoc :empty-layer {:index index
                           :type type
                           :source source
                           :cause (cond
                                    (not (and dir (.exists dir))) :missing
                                    (not (.isDirectory dir)) :not-a-directory
                                    (empty? files) :empty
                                    :else :filtered)
                           :filters filters}))))

(defn- as-filter-set [values]
  (if (instance? Set values) values (set values)))

(defn- names-membership [names]
  (cond
    (or (nil? names) (instance? Map names) (instance? Set names)) names
    (collection-or-array? names) (set names)
    :else #{}))

(defn- plan-layer [index {:keys [type lib-dir jar-path local-dir container-path extensions blacklist-prefixes names]
                          :or {extensions #{".py" ".txt" ".sh" ".json" ".yaml" ".yml"}
                               blacklist-prefixes []}}]
  (let [container-path (container-path-value container-path)]
    (case type
      :deps (plan-directory index type "dependencies" lib-dir container-path (constantly true) {})
      :app {:type type :name "application" :entries [(file-entry (io/file jar-path) container-path)]}
      :files (let [extensions (as-filter-set extensions)
                   names (names-membership names)]
               (plan-directory index type (str "files:" local-dir) local-dir container-path
                               (fn [filename]
                                 (and (some #(str/ends-with? filename %) extensions)
                                      (or (nil? names) (contains? names filename))
                                      (not-any? #(str/starts-with? filename %) blacklist-prefixes)))
                               {:extensions extensions
                                :names names
                                :blacklist-prefixes (vec blacklist-prefixes)})))))

(defn- library-labels [base]
  {base-label (str (:image base))})

(defn- plan-spec [spec]
  (let [{:keys [to target-image to-tar base base-image entrypoint env labels layers verbose]
         :or {base-image default-base verbose true}} (validate-spec! spec)
        base (or base {:image base-image :legacy true})
        planned-layers (mapv plan-layer (range) layers)]
    {:destination (or to
                      (if to-tar
                        {:tar to-tar :image-name target-image}
                        {:registry target-image}))
     :base base
     :entrypoint (vec entrypoint)
     :env (or env {})
     :labels (merge labels (library-labels base))
     :layers (mapv #(dissoc % :empty-layer) planned-layers)
     :empty-layers (into [] (keep :empty-layer) planned-layers)
     :verbose verbose}))

(defn plan
  "Validate a spec and resolve its destination, base and layer entries using local files."
  [spec]
  (update-in (plan-spec spec) [:base :image] str))

(defn- make-containerizer [target-image to-tar logger]
  (if to-tar
    (Containerizer/to
      (-> (TarImage/at (.toPath (.getAbsoluteFile (io/file to-tar))))
          (.named (to-imgref target-image))))
    (Containerizer/to
      (-> (RegistryImage/named (to-imgref target-image))
          (.addCredentialRetriever
            (-> (CredentialRetrieverFactory/forImage (to-imgref target-image) logger)
                (.dockerConfig)))))))

(defn- build-layer [{:keys [name entries]}]
  (let [builder (.setName (FileEntriesLayer/builder) name)]
    (doseq [{:keys [source container-path]} entries]
      (.addEntry builder (.toPath (io/file source)) (AbsoluteUnixPath/get container-path)))
    (.build builder)))

(defn- base-builder [base target-image logger]
  (if (and (= "scratch" (:image base)) (not (:legacy base)))
    (Jib/fromScratch)
    (Jib/from
      (-> (RegistryImage/named ^ImageReference (let [image (:image base)]
                                                 (if (instance? ImageReference image) image (to-imgref image))))
          (.addCredentialRetriever
            (-> (CredentialRetrieverFactory/forImage (to-imgref target-image) logger)
                (.dockerConfig)))))))

(defn- containerize! [^JibContainerBuilder builder ^Containerizer containerizer]
  (.containerize builder containerizer))

(defn- execute-plan! [{:keys [destination base entrypoint env labels layers empty-layers verbose]} layer-specs]
  (let [{:keys [registry tar image-name]} destination
        target-image (or registry image-name)
        logger (make-logger verbose)]
    (doseq [{:keys [index type source cause filters]} empty-layers]
      (println (str "WARNING thin-jar: layer " index " (" type " " source
                    ") selects no files (" cause "); filters: " filters)))
    (println (if tar
               "Building container image tarball with Jib (no push)..."
               "Building and pushing container image with Jib..."))
    (println (str "  Base image: " (:image base)))
    (println (str "  Target image: " target-image))
    (when tar (println (str "  Tarball: " tar)))
    (when (seq env) (println (str "  Env: " env)))
    (println "  Layers:")
    (doseq [{:keys [type] :as layer} layer-specs]
      (println (str "    - " type ": " (dissoc layer :type))))
    (let [builder (base-builder base target-image logger)
          containerizer (make-containerizer target-image tar logger)]
      (doseq [layer layers]
        (.addFileEntriesLayer builder (build-layer layer)))
      (doseq [[k v] (sort env)]
        (.addEnvironmentVariable builder k v))
      (.setLabels builder labels)
      (let [^JibContainer result (time
                                   (-> builder
                                       (.setEntrypoint (ArrayList. ^List entrypoint))
                                       (containerize! containerizer)))]
        (if tar
          (println (str "Built image tarball " tar " (not pushed)"))
          (println "Container image built and pushed successfully!"))
        (println (str "  Image: " target-image))
        {:destination destination
         :empty-layers empty-layers
         :pushed? (.isImagePushed result)
         :image-digest (str (.getDigest result))
         :image-id (str (.getImageId result))
         :base {:image (str (:image base))}
         :labels labels
         :library-version version}))))

(defn deploy!
  "Build the validated image and return a receipt. Legacy destination keys retain
   the fork's receipt fields and expose the normalized map as :resolved-destination."
  [spec]
  (let [{:keys [destination image-digest] :as receipt} (execute-plan! (plan-spec spec) (:layers spec))]
    (if (contains? spec :to)
      receipt
      (let [{:keys [registry tar image-name]} destination]
        (assoc receipt
               :destination (if tar :tar :registry)
               :resolved-destination destination
               :target-image (or registry image-name)
               :tar-path tar
               :digest image-digest)))))

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
