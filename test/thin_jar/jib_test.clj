(ns thin-jar.jib-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [thin-jar.jib :as jib])
  (:import
   (com.fasterxml.jackson.databind ObjectMapper)
   (com.google.cloud.tools.jib.api Containerizer)
   (java.io ByteArrayInputStream PushbackReader)
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)
   (java.util.zip GZIPInputStream)
   (org.apache.commons.compress.archivers.tar TarArchiveInputStream)))

(defn- with-specs [test]
  (let [dir (.toFile (Files/createTempDirectory "thin-jar-jib-" (make-array FileAttribute 0)))
        base {:target-image "example.invalid/project/image:latest"
              :entrypoint ["java" "-jar" "/app.jar"]}]
    (try
      (doseq [name ["app.jar" "other.jar" "main.py" "test_skip.py" "notes.txt" "other.rb"]]
        (spit (io/file dir name) name))
      (test {:deps (assoc base :layers [{:type :deps :lib-dir (str dir) :container-path "/lib"}])
             :app (assoc base :layers [{:type :app :jar-path (str (io/file dir "app.jar")) :container-path "/app.jar"}])
             :files (assoc base :layers [{:type :files :local-dir (str dir) :container-path "/scripts"}])}
            dir)
      (finally
        (doseq [file (reverse (file-seq dir))]
          (io/delete-file file))))))

(defn- config-error [f spec]
  (try
    (f spec)
    nil
    (catch clojure.lang.ExceptionInfo e e)))

(defn- modern-destination [{:keys [target-image to-tar] :as spec}]
  (-> spec
      (dissoc :target-image :to-tar)
      (assoc :to (if to-tar
                   {:tar to-tar :image-name target-image}
                   {:registry target-image}))))

(defn- container-paths [spec]
  (set (map :container-path (get-in (jib/plan spec) [:layers 0 :entries]))))

(defn- documented-keys [cell]
  (set (map (comp keyword second) (re-seq #"`:(.*?)`" cell))))

(deftest contract-matches-readme
  (let [{:keys [top base to layers]} @#'jib/spec-contract
        contracts (merge {"top" top "base" base "to" to}
                         (update-keys layers name))
        rows (re-seq #"(?m)^\| (top|base|to|deps|app|files) \|([^|]+)\|([^|]+)\|([^|]*)\|$"
                     (slurp "README.md"))
        documented (into {}
                         (for [[_ row allowed required source] rows]
                           [row {:allowed (documented-keys allowed)
                                 :required (documented-keys required)
                                 :source (documented-keys source)}]))
        actual (into {}
                     (for [[row {:keys [allowed required alternatives source-key]}] contracts]
                       [row {:allowed allowed
                             :required (into required cat (vals alternatives))
                             :source (set (when source-key [source-key]))}]))]
    (is (= (count contracts) (count rows)))
    (is (= actual documented))))

(deftest top-level-keys-affect-plan
  (with-specs
    (fn [{:keys [app files]} _]
      (let [modern (modern-destination app)
            cases
            {:base [modern (assoc modern :base {:image "example.invalid/base" :reason "Custom runtime"})]
             :base-image [app (assoc app :base-image "example.invalid/base")]
             :target-image [app (assoc app :target-image "example.invalid/other")]
             :to [modern (assoc modern :to {:registry "example.invalid/other"})]
             :to-tar [app (assoc app :to-tar "image.tar")]
             :entrypoint [app (assoc app :entrypoint ["/other"])]
             :layers [app files]
             :verbose [app (assoc app :verbose false)]
             :env [app (assoc app :env {"K" "V"})]
             :labels [app (assoc app :labels {"org.example.service" "worker"})]}]
        (is (= (get-in @#'jib/spec-contract [:top :allowed]) (set (keys cases))))
        (doseq [[k [before after]] cases]
          (testing (str k)
            (is (not= (jib/plan before) (jib/plan after)))))))))

(deftest layer-keys-affect-plan
  (with-specs
    (fn [{:keys [deps app files] :as specs} dir]
      (let [alternate (io/file dir "alternate")
            _ (.mkdir alternate)
            _ (spit (io/file alternate "different.py") "alternate")
            cases {:deps {:type app :lib-dir (update-in deps [:layers 0] assoc :lib-dir (str alternate))
                          :container-path (update-in deps [:layers 0] assoc :container-path "/other")}
                   :app {:type deps :jar-path (update-in app [:layers 0] assoc :jar-path (str (io/file dir "other.jar")))
                         :container-path (update-in app [:layers 0] assoc :container-path "/other.jar")}
                   :files {:type deps :local-dir (update-in files [:layers 0] assoc :local-dir (str alternate))
                           :container-path (update-in files [:layers 0] assoc :container-path "/other")
                           :extensions (update-in files [:layers 0] assoc :extensions #{".rb"})
                           :blacklist-prefixes (update-in files [:layers 0] assoc :blacklist-prefixes ["test_"])
                           :names (update-in files [:layers 0] assoc :names #{"main.py"})}}]
        (doseq [[type changes] cases]
          (is (= (get-in @#'jib/spec-contract [:layers type :allowed]) (set (keys changes))))
          (doseq [[k updated] changes]
            (testing (str type " " k)
              (is (not= (:layers (jib/plan (specs type))) (:layers (jib/plan updated)))))))
        (is (= #{"/scripts/main.py" "/scripts/test_skip.py" "/scripts/notes.txt"} (container-paths files)))
        (is (= #{"/scripts/main.py"}
               (container-paths (update-in files [:layers 0] assoc :extensions #{".py"}
                                           :names #{"main.py" "test_skip.py" "other.rb"}
                                           :blacklist-prefixes ["test_"]))))))))

(deftest unknown-keys-never-reach-jib
  (with-specs
    (fn [{:keys [files] :as specs} _]
      (let [{:keys [top base to layers]} @#'jib/spec-contract
            modern (assoc (modern-destination files) :base {:image "scratch" :reason "Fixture"})
            scopes (concat [[files [] top] [modern [:base] base] [modern [:to] to]]
                           (for [[type contract] layers]
                             [(specs type) [:layers 0] contract]))
            calls (atom [])
            unexpected (fn [& _] (swap! calls conj :jib) (throw (ex-info "Unexpected Jib call" {})))]
        (with-redefs [jib/containerize! unexpected
                      jib/base-builder unexpected
                      jib/make-containerizer unexpected]
          (is (= {:registry (:target-image files)} (:destination (jib/plan files))))
          (doseq [[spec path {:keys [allowed]}] scopes
                  typo (into #{:to-tarr :enviroment :container_path}
                             (map #(keyword (str (name %) "x")) allowed))
                  f [jib/plan jib/deploy!]]
            (testing (str path " " typo " " f)
              (let [data (ex-data (config-error f (assoc-in spec (conj path typo) true)))]
                (is (= :unknown-key (:reason data)))
                (is (= path (:path data)))
                (is (= [typo] (:offending-keys data) (:unknown-keys data)))
                (is (= allowed (set (:allowed-keys data))))))))
        (is (= [] @calls))))))

(deftest invalid-configuration
  (with-specs
    (fn [{:keys [deps app files]} dir]
      (let [absent (str (io/file dir "absent"))
            modern (modern-destination app)]
        (doseq [[spec reason path offending]
                [[[] :expected-map [] []]
                 [(assoc app :layers [[]]) :expected-map [:layers 0] []]
                 [(assoc modern :to "tar") :expected-map [:to] [:to]]
                 [(assoc app :base false) :expected-map [:base] [:base]]
                 [(dissoc app :entrypoint) :missing-required-key [:entrypoint] [:entrypoint]]
                 [(update-in files [:layers 0] dissoc :local-dir) :missing-required-key [:layers 0 :local-dir] [:local-dir]]
                 [(assoc-in files [:layers 0 :type] :unsupported) :unknown-layer-type [:layers 0 :type] [:type]]
                 [(assoc-in deps [:layers 0 :lib-dir] absent) :source-not-found [:layers 0 :lib-dir] [:lib-dir]]
                 [(assoc-in app [:layers 0 :jar-path] absent) :source-not-found [:layers 0 :jar-path] [:jar-path]]
                 [(assoc-in files [:layers 0 :local-dir] absent) :source-not-found [:layers 0 :local-dir] [:local-dir]]
                 [(assoc app :to-tar nil) :invalid-to-tar [:to-tar] [:to-tar]]
                 [(assoc app :env {"KEY" 1}) :invalid-env [:env] [:env]]
                 [(assoc app :labels {:key "value"}) :invalid-labels [:labels] [:labels]]
                 [(assoc app :layers []) :invalid-layers [:layers] [:layers]]
                 [(assoc app :base {:image "scratch"}) :missing-required-key [:base :reason] [:reason]]
                 [(assoc app :base {:image "scratch" :reason " \t "}) :invalid-base [:base :reason] [:reason]]
                 [(assoc modern :to {:registry ""}) :invalid-destination [:to :registry] [:registry]]
                 [(assoc modern :to {:tar "out.tar"}) :missing-required-key [:to :image-name] [:image-name]]
                 [(assoc modern :to {:image-name "example.invalid/img"}) :missing-required-key [:to :tar] [:tar]]
                 [(-> app (dissoc :target-image) (assoc :to-tar "out.tar")) :missing-required-key [:target-image] [:target-image]]]]
          (testing (str reason " " path)
            (let [data (ex-data (config-error jib/plan spec))]
              (is (= reason (:reason data)))
              (is (= path (:path data)))
              (is (= offending (:offending-keys data)))
              (is (vector? (:allowed-keys data))))))))))

(deftest destination-and-label-conflicts
  (with-specs
    (fn [{:keys [app]} _]
      (let [modern (modern-destination app)
            base-label "org.opencontainers.image.base.name"
            version-label "io.github.realgenekim.thin-jar.version"]
        (doseq [[spec path keys]
                [[(assoc modern :target-image (:target-image app)) [] #{:to :target-image}]
                 [(assoc modern :to-tar "out.tar") [] #{:to :to-tar}]
                 [(assoc modern :target-image (:target-image app) :to-tar "out.tar") [] #{:to :target-image :to-tar}]
                 [(update modern :to assoc :tar "out.tar" :image-name "example.invalid/name") [:to] #{:registry :tar :image-name}]
                 [(update modern :to assoc :image-name "example.invalid/name") [:to] #{:registry :image-name}]
                 [(assoc app :base-image "scratch" :base {:image "scratch" :reason "Fixture"}) [] #{:base :base-image}]
                 [(assoc app :labels {base-label "bad"}) [:labels] #{base-label}]
                 [(assoc app :labels {version-label "bad"}) [:labels] #{version-label}]
                 [(assoc app :labels {base-label "bad" version-label "bad"}) [:labels] #{base-label version-label}]]]
          (let [data (ex-data (config-error jib/plan spec))]
            (is (= :conflicting-keys (:reason data)))
            (is (= path (:path data)))
            (is (= keys (set (:offending-keys data))))))
        (doseq [[spec path] [[(dissoc app :target-image) []]
                             [(assoc modern :to {}) [:to]]]]
          (let [data (ex-data (config-error jib/plan spec))]
            (is (= :no-destination (:reason data)))
            (is (= path (:path data)))))))))

(deftest environment-key-is-rejected-with-guidance
  (with-specs
    (fn [{:keys [app]} _]
      (let [error (config-error jib/plan (assoc app :environment {"K" "V"}))]
        (is (= :unknown-key (:reason (ex-data error))))
        (is (re-find #":env\b.*whole environment map.*JVM flags" (ex-message error)))))))

(deftest legacy-and-modern-specs
  (with-specs
    (fn [{:keys [app files]} dir]
      (let [fork-tar (assoc app :to-tar "out.tar")
            fork-env (assoc app :env {"FOO" "bar"})
            fork-names (update-in files [:layers 0] assoc :names #{"main.py"})
            expected {:destination {:registry (:target-image app)}
                      :base {:image "gcr.io/distroless/java21" :legacy true}
                      :entrypoint ["java" "-jar" "/app.jar"]
                      :env {}
                      :labels {"org.opencontainers.image.base.name" "gcr.io/distroless/java21"
                               "io.github.realgenekim.thin-jar.version" "0.2.0"}
                      :layers [{:type :app :name "application"
                                :entries [{:source (str (io/file dir "app.jar")) :container-path "/app.jar"}]}]
                      :verbose true}]
        (is (= "0.2.0" jib/version))
        (is (= expected (jib/plan app)))
        (is (= (assoc expected :destination {:tar "out.tar" :image-name (:target-image app)})
               (jib/plan fork-tar)))
        (is (= (assoc expected :env {"FOO" "bar"}) (jib/plan fork-env)))
        (is (= #{"/scripts/main.py"} (container-paths fork-names)))
        (doseq [spec [app fork-tar fork-env fork-names
                      (assoc fork-names :to-tar "out.tar" :env {"FOO" "bar"} :verbose false)]]
          (is (= spec (jib/validate-spec! spec)))
          (is (= (jib/plan spec) (jib/plan (modern-destination spec)))))
        (let [legacy (jib/plan (assoc app :base-image "example.invalid/base"))
              declared (jib/plan (assoc (modern-destination app) :base
                                        {:image "example.invalid/base" :reason "Custom runtime"}))]
          (is (= {:image "example.invalid/base" :legacy true} (:base legacy)))
          (is (= {:image "example.invalid/base" :reason "Custom runtime"} (:base declared)))
          (is (= (update legacy :base dissoc :legacy) (update declared :base dissoc :reason))))))))

(deftest upstream-comment-examples-plan
  (with-specs
    (fn [_ dir]
      (let [examples (with-open [reader (PushbackReader. (io/reader "src/thin_jar/jib.clj"))]
                       (->> (repeatedly #(read {:eof ::eof} reader))
                            (take-while #(not= ::eof %))
                            (filter #(and (seq? %) (= 'comment (first %))))
                            first
                            rest
                            (filter #(and (seq? %) (= 'deploy! (first %))))
                            (mapv second)))
            source-paths #{"target/lib" "target/server2.jar" "target/reddit-scraper.jar" "classify"}
            file io/file]
        (doseq [p ["target/lib" "classify"]]
          (.mkdirs (file dir p)))
        (doseq [p ["target/server2.jar" "target/reddit-scraper.jar"]]
          (spit (file dir p) "fixture"))
        (is (= 2 (count examples)))
        (with-redefs [io/file (fn [p] (if (source-paths p) (file dir p) (file p)))]
          (doseq [spec examples]
            (is (= {:registry (:target-image spec)} (:destination (jib/plan spec))))))))))

(defn- archive-entries [input]
  (with-open [stream (TarArchiveInputStream. input)]
    (loop [entries {}]
      (if-let [entry (.getNextEntry stream)]
        (recur (assoc entries (.getName entry) (.readAllBytes stream)))
        entries))))

(deftest scratch-tar-round-trip
  (with-specs
    (fn [{:keys [app]} dir]
      (let [tar (io/file dir "image.tar")
            legacy-tar (io/file dir "legacy.tar")
            target (:target-image app)
            spec (assoc (modern-destination app)
                        :to {:tar (str tar) :image-name target}
                        :base {:image "scratch" :reason "Registry-free image"}
                        :env {"APP_ENV" "test"}
                        :labels {"org.example.service" "worker"}
                        :verbose false)
            modern-containerizer @#'jib/make-containerizer]
        (with-redefs [jib/make-containerizer
                      (fn [image path logger]
                        (doto ^Containerizer (modern-containerizer image path logger)
                          (.setOfflineMode true)
                          (.setBaseImageLayersCache (.toPath (io/file dir "base-cache")))
                          (.setApplicationLayersCache (.toPath (io/file dir "app-cache")))))]
          (let [receipt (jib/deploy! spec)
                entries (archive-entries (io/input-stream tar))
                json (ObjectMapper.)
                manifest (.readTree json ^bytes (get entries "manifest.json"))
                image (.get manifest 0)
                config (.readTree json ^bytes (get entries (.asText (.get image "Config"))))
                layer-names (map #(.asText %) (.get image "Layers"))
                layer-files (into {} (mapcat #(archive-entries
                                                (GZIPInputStream. (ByteArrayInputStream. (get entries %))))
                                             layer-names))
                legacy (jib/deploy! (-> spec (dissoc :to)
                                        (assoc :target-image target :to-tar (str legacy-tar))))]
            (is (.isFile tar))
            (is (= target (.asText (.at manifest "/0/RepoTags/0"))))
            (is (= "app.jar" (some-> (get layer-files "app.jar") (String. "UTF-8"))))
            (is (= "APP_ENV=test" (.asText (.at config "/config/Env/0"))))
            (is (= {:destination (:to spec)
                    :pushed? false
                    :image-digest (:image-digest receipt)
                    :image-id (:image-id receipt)
                    :base {:image "scratch"}
                    :labels {"org.opencontainers.image.base.name" "scratch"
                             "io.github.realgenekim.thin-jar.version" "0.2.0"
                             "org.example.service" "worker"}
                    :library-version "0.2.0"}
                   receipt))
            (doseq [[k v] (:labels receipt)]
              (is (= v (.asText (.get (.at config "/config/Labels") k)))))
            (is (re-matches #"sha256:[a-f0-9]{64}" (:image-digest receipt)))
            (is (re-matches #"sha256:[a-f0-9]{64}" (:image-id receipt)))
            (is (.isFile legacy-tar))
            (is (= {:destination :tar :target-image target :tar-path (str legacy-tar)
                    :digest (:image-digest receipt) :image-id (:image-id receipt)}
                   (select-keys legacy [:destination :target-image :tar-path :digest :image-id])))
            (is (= {:tar (str legacy-tar) :image-name target} (:resolved-destination legacy)))))))))
