(ns thin-jar.jib
  "Plan and build layered container images with Jib."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   (com.google.cloud.tools.jib.api Containerizer ImageReference Jib JibContainer JibContainerBuilder LogEvent RegistryImage TarImage)
   (com.google.cloud.tools.jib.api.buildplan AbsoluteUnixPath FileEntriesLayer)
   (com.google.cloud.tools.jib.frontend CredentialRetrieverFactory)
   (java.io File)
   (java.util ArrayList List)
   (java.util.function Consumer)))

(def version "The thin-jar-build library version." "0.2.0")

(def ^:private default-base "gcr.io/distroless/java21")
(def ^:private base-label "org.opencontainers.image.base.name")
(def ^:private version-label "io.github.realgenekim.thin-jar.version")

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
                   :required #{:type :lib-dir :container-path}
                   :source-key :lib-dir}
            :app {:allowed #{:type :jar-path :container-path}
                  :required #{:type :jar-path :container-path}
                  :source-key :jar-path}
            :files {:allowed #{:type :local-dir :container-path :extensions :blacklist-prefixes :names}
                    :required #{:type :local-dir :container-path}
                    :source-key :local-dir}}})

(defn- invalid-config! [reason path allowed & [offending]]
  (let [offending (vec (sort-by pr-str offending))
        environment? (and (= reason :unknown-key) (empty? path)
                          (some #{:environment} offending))]
    (throw (ex-info (str "Invalid Jib configuration " (pr-str reason) " at " (pr-str path)
                         (when environment?
                           ". Use :env to add or override individual variables. The :environment key of another fork sets the whole environment map on the Jib builder and enforces JVM flags, so it is not accepted as an alias."))
                    {:type ::invalid-config
                     :reason reason
                     :path path
                     :offending-keys offending
                     :unknown-keys (if (= reason :unknown-key) offending [])
                     :allowed-keys (vec (sort allowed))}))))

(defn- validate-required! [spec path allowed required]
  (when-let [missing (first (remove spec (sort required)))]
    (invalid-config! :missing-required-key (conj path missing) allowed [missing])))

(defn- validate-keys! [spec path {:keys [allowed required]}]
  (when-not (map? spec)
    (invalid-config! :expected-map path allowed (take-last 1 path)))
  (when-let [unknown (seq (remove allowed (keys spec)))]
    (invalid-config! :unknown-key path allowed unknown))
  (validate-required! spec path allowed required))

(defn- source-exists? [source]
  (try
    (some-> (io/file source) .exists)
    (catch IllegalArgumentException _
      false)))

(defn- validate-layer! [index layer]
  (let [path [:layers index]
        type-path (conj path :type)]
    (when-not (map? layer)
      (invalid-config! :expected-map path #{:type}))
    (let [{:keys [type]} layer
          {:keys [allowed source-key] :as contract} (get-in spec-contract [:layers type])]
      (cond
        (nil? type) (invalid-config! :missing-required-key type-path #{:type} [:type])
        (nil? contract) (invalid-config! :unknown-layer-type type-path #{:type} [:type]))
      (validate-keys! layer path contract)
      (when-not (source-exists? (get layer source-key))
        (invalid-config! :source-not-found (conj path source-key) allowed [source-key])))))

(defn- nonblank-string? [x]
  (and (string? x) (not (str/blank? x))))

(defn- string-map? [x]
  (and (map? x)
       (every? (fn [[k v]] (and (string? k) (string? v))) x)))

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
        (when-not (nonblank-string? v)
          (invalid-config! :invalid-destination [:to k] allowed [k]))))
    (do
      (when-not (:target-image spec)
        (if (contains? spec :to-tar)
          (validate-required! spec [] top-allowed #{:target-image})
          (invalid-config! :no-destination [] top-allowed [:to :target-image])))
      (when (and (contains? spec :to-tar) (not (nonblank-string? (:to-tar spec))))
        (invalid-config! :invalid-to-tar [:to-tar] top-allowed [:to-tar])))))

(defn- validate-base! [spec allowed]
  (when (contains? spec :base)
    (when (contains? spec :base-image)
      (invalid-config! :conflicting-keys [] allowed [:base :base-image]))
    (let [base (:base spec)
          contract (:base spec-contract)]
      (validate-keys! base [:base] contract)
      (doseq [k [:image :reason]]
        (when-not (nonblank-string? (get base k))
          (invalid-config! :invalid-base [:base k] (:allowed contract) [k]))))))

(defn validate-spec!
  "Return spec if its configuration and layer sources are valid. Throw ex-info otherwise."
  [spec]
  (let [{:keys [allowed] :as contract} (:top spec-contract)]
    (validate-keys! spec [] contract)
    (validate-destination! spec allowed)
    (validate-base! spec allowed)
    (let [{:keys [layers env labels]} spec]
      (when-not (and (sequential? layers) (seq layers))
        (invalid-config! :invalid-layers [:layers] allowed [:layers]))
      (when (and (some? env) (not (string-map? env)))
        (invalid-config! :invalid-env [:env] allowed [:env]))
      (when (and (contains? spec :labels) (not (string-map? labels)))
        (invalid-config! :invalid-labels [:labels] allowed [:labels]))
      (when-let [reserved (seq (filter #(contains? labels %) [base-label version-label]))]
        (invalid-config! :conflicting-keys [:labels] allowed reserved))
      (doseq [[index layer] (map-indexed vector layers)]
        (validate-layer! index layer))))
  spec)

(defn- to-imgref [image-config]
  (ImageReference/parse image-config))

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

(defn- directory-entries [dir container-path include?]
  (for [^File file (.listFiles (io/file dir))
        :when (and (.isFile file) (include? (.getName file)))]
    (file-entry file (str container-path "/" (.getName file)))))

(defn- plan-layer [{:keys [type lib-dir jar-path local-dir container-path extensions blacklist-prefixes names]
                    :or {extensions #{".py" ".txt" ".sh" ".json" ".yaml" ".yml"}
                         blacklist-prefixes []}}]
  (let [[name entries]
        (case type
          :deps ["dependencies" (directory-entries lib-dir container-path (constantly true))]
          :app ["application" [(file-entry (io/file jar-path) container-path)]]
          :files [(str "files:" local-dir)
                  (directory-entries local-dir container-path
                                     (fn [filename]
                                       (and (some #(str/ends-with? filename %) extensions)
                                            (or (nil? names) (contains? names filename))
                                            (not-any? #(str/starts-with? filename %) blacklist-prefixes))))])]
    {:type type
     :name name
     :entries (vec entries)}))

(defn plan
  "Validate a spec and resolve its destination, base and layer entries using local files."
  [spec]
  (let [{:keys [to target-image to-tar base base-image entrypoint env labels layers verbose]
         :or {base-image default-base verbose true}} (validate-spec! spec)
        base (or base {:image base-image :legacy true})]
    {:destination (or to
                      (if to-tar
                        {:tar to-tar :image-name target-image}
                        {:registry target-image}))
     :base base
     :entrypoint (vec entrypoint)
     :env (or env {})
     :labels (assoc labels base-label (:image base) version-label version)
     :layers (mapv plan-layer layers)
     :verbose verbose}))

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
      (-> (RegistryImage/named ^String (:image base))
          (.addCredentialRetriever
            (-> (CredentialRetrieverFactory/forImage (to-imgref target-image) logger)
                (.dockerConfig)))))))

(defn- containerize! [^JibContainerBuilder builder ^Containerizer containerizer]
  (.containerize builder containerizer))

(defn- execute-plan! [{:keys [destination base entrypoint env labels layers verbose]}]
  (let [{:keys [registry tar image-name]} destination
        target-image (or registry image-name)
        logger (make-logger verbose)
        builder (base-builder base target-image logger)
        containerizer (make-containerizer target-image tar logger)]
    (.addEventHandler containerizer LogEvent logger)
    (doseq [layer layers]
      (.addFileEntriesLayer builder (build-layer layer)))
    (doseq [[k v] (sort env)]
      (.addEnvironmentVariable builder k v))
    (.setLabels builder labels)
    (.setEntrypoint builder (ArrayList. ^List entrypoint))
    (let [^JibContainer result (containerize! builder containerizer)]
      {:destination destination
       :pushed? (.isImagePushed result)
       :image-digest (str (.getDigest result))
       :image-id (str (.getImageId result))
       :base (select-keys base [:image])
       :labels labels
       :library-version version})))

(defn deploy!
  "Build the validated image and return a receipt. Legacy destination keys retain
   the fork's receipt fields and expose the normalized map as :resolved-destination."
  [spec]
  (let [{:keys [destination image-digest] :as receipt} (execute-plan! (plan spec))]
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
