(ns thin-jar.jib-test
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [thin-jar.jib :as jib])
  (:import
   (com.fasterxml.jackson.databind ObjectMapper)
   (com.google.cloud.tools.jib.api Containerizer)
   (java.io ByteArrayInputStream)
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)
   (java.util ArrayList)
   (java.util.function Consumer)
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
                 [(assoc app :to-tar nil) :invalid-value [:to-tar] [:to-tar]]
                 [(assoc app :env {"KEY" 1}) :invalid-value [:env] [:env]]
                 [(assoc app :labels {:key "value"}) :invalid-value [:labels] [:labels]]
                 [(assoc app :layers []) :invalid-layers [:layers] [:layers]]
                 [(assoc app :base {:image "scratch"}) :missing-required-key [:base :reason] [:reason]]
                 [(assoc app :base {:image "scratch" :reason " \t "}) :invalid-base [:base :reason] [:reason]]
                 [(assoc modern :to {:registry ""}) :invalid-value [:to :registry] [:registry]]
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
            base-label "org.opencontainers.image.base.name"]
        (doseq [[spec path keys]
                [[(assoc modern :target-image (:target-image app)) [] #{:to :target-image}]
                 [(assoc modern :to-tar "out.tar") [] #{:to :to-tar}]
                 [(assoc modern :target-image (:target-image app) :to-tar "out.tar") [] #{:to :target-image :to-tar}]
                 [(update modern :to assoc :tar "out.tar" :image-name "example.invalid/name") [:to] #{:registry :tar :image-name}]
                 [(update modern :to assoc :image-name "example.invalid/name") [:to] #{:registry :image-name}]
                 [(assoc app :base-image "scratch" :base {:image "scratch" :reason "Fixture"}) [] #{:base :base-image}]
                 [(assoc app :labels {base-label "bad"}) [:labels] #{base-label}]]]
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
                      :labels {"org.opencontainers.image.base.name" "gcr.io/distroless/java21"}
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

(deftest source-kinds-are-validated
  (with-specs
    (fn [{:keys [app deps files]} dir]
      (let [file (str (io/file dir "app.jar"))]
        (doseq [[spec key source expected] [[deps :lib-dir file :directory]
                                            [files :local-dir file :directory]
                                            [app :jar-path (str dir) :regular-file]]]
          (let [data (ex-data (config-error jib/plan (assoc-in spec [:layers 0 key] source)))]
            (is (= :thin-jar.jib/invalid-config (:type data)))
            (is (= :wrong-source-kind (:reason data)))
            (is (= [:layers 0 key] (:path data)))
            (is (= expected (:expected-kind data)))))))))

(deftest file-filters-are-validated
  (with-specs
    (fn [{:keys [files]} dir]
      (.mkdir (io/file dir "folder.py"))
      (doseq [[key values] {:names [["main.py"] '("main.py") "main.py" #{:main.py} #{" "}]
                            :extensions [nil #{} #{:py} [".py"] ".py"]
                            :blacklist-prefixes ["main" [:main]]}
              value values]
        (testing (pr-str [key value])
          (let [path [:layers 0 key]
                data (ex-data (config-error jib/plan (assoc-in files path value)))]
            (is (= [:invalid-value path] ((juxt :reason :path) data))))))
      (let [path [:layers 0 :names]
            data (ex-data (config-error jib/plan
                                        (assoc-in files path #{"main.py" "mian.py" "folder.py"})))]
        (is (= [:source-not-found path ["folder.py" "mian.py"]]
               ((juxt :reason :path :missing-names) data))))
      (is (= (jib/plan files) (jib/plan (assoc-in files [:layers 0 :names] nil))))
      (is (= #{"/scripts/main.py"}
             (container-paths (-> files
                                  (assoc-in [:layers 0 :names] #{"main.py"})
                                  (assoc-in [:layers 0 :extensions] #{"py"}))))))))

(deftest spec-values-are-validated
  (with-specs
    (fn [{:keys [app deps files]} dir]
      (let [modern (modern-destination app)]
        (.mkdir (io/file dir "empty"))
        (doseq [[spec path values]
                [[app [:entrypoint] ["java" [] [:java 1] nil]]
                 [app [:target-image] [nil :oops " " "NOT A REF"]]
                 [modern [:to :registry] ["https://example.invalid/image" "NOT A REF"]]
                 [(assoc modern :to {:tar "out.tar" :image-name "image:local"})
                  [:to :image-name] ["NOT A REF"]]
                 [(assoc modern :to {:tar "out.tar" :image-name "image:local"})
                  [:to :tar] [nil " " 1]]
                 [(assoc app :base {:image "scratch" :reason "Fixture"})
                  [:base :image] [nil "NOT A REF"]]
                 [app [:base-image] [nil "NOT A REF"]]
                 [app [:verbose] ["false" nil 1]]
                 [app [:to-tar] [nil " " 1]]
                 [app [:env] [{"KEY" 1} [:key "value"]]]
                 [app [:labels] [nil {:key "value"}]]
                 [app [:layers 0 :container-path] ["rel/app.jar" "" 5]]
                 [(assoc-in deps [:layers 0 :lib-dir] (str (io/file dir "empty")))
                  [:layers 0 :container-path] [nil "relative"]]
                 [files [:layers 0 :container-path] ["" "relative"]]]
                value values]
          (testing (pr-str [path value])
            (let [updated (assoc-in spec path value)]
              (doseq [validate [jib/plan jib/validate-spec!]]
                (let [data (ex-data (config-error validate updated))]
                  (is (= [:thin-jar.jib/invalid-config :invalid-value path]
                         ((juxt :type :reason :path) data))))))))
        (is (= (jib/plan app) (jib/plan (assoc app :env nil))))
        (is (= (jib/plan app) (jib/plan (update app :entrypoint #(ArrayList. %)))))
        (is (= {:image "scratch" :legacy true}
               (:base (jib/plan (assoc app :base-image "scratch")))))
        (doseq [spec [deps files]]
          (let [data (ex-data (config-error jib/plan (update-in spec [:layers 0] dissoc :container-path)))]
            (is (= [:missing-required-key [:layers 0 :container-path]]
                   ((juxt :reason :path) data)))))))))

(deftest layer-collections-are-compatible
  (with-specs
    (fn [{:keys [app deps files]} _]
      (let [layers (mapv (comp first :layers) [files deps app])
            expected (:layers (jib/plan (assoc app :layers layers)))]
        (is (= (jib/plan app) (jib/plan (update app :layers set))))
        (doseq [ordered [(apply list layers) (ArrayList. layers)]]
          (is (= expected (:layers (jib/plan (assoc app :layers ordered))))))
        (is (= :invalid-layers
               (:reason (ex-data (config-error jib/plan (assoc app :layers (first layers)))))))))))

(deftest upstream-comment-examples-plan
  (with-specs
    (fn [_ dir]
      (let [examples (edn/read-string (slurp (io/resource "thin_jar/f5bd8dd_examples.edn")))
            source-keys [:lib-dir :jar-path :local-dir]
            path #(str (io/file dir %))
            entry (fn [source destination] {:source (path source) :container-path destination})
            rewrite-sources (fn [spec]
                              (update spec :layers
                                      (fn [layers]
                                        (mapv (fn [layer]
                                                (reduce (fn [result key]
                                                          (if (contains? result key)
                                                            (update result key path)
                                                            result))
                                                        layer source-keys))
                                              layers))))
            defaults {:env {} :verbose true}
            expected
            [(assoc defaults
                    :destination {:registry "us-west1-docker.pkg.dev/proj/repo/server2:latest"}
                    :base {:image "gcr.io/distroless/java21" :legacy true}
                    :labels {"org.opencontainers.image.base.name" "gcr.io/distroless/java21"}
                    :entrypoint ["java" "-cp" "/app/server2.jar:/app/lib/*" "server2.server"]
                    :layers [{:type :deps :name "dependencies"
                              :entries [(entry "target/lib/dep.jar" "/app/lib/dep.jar")]}
                             {:type :app :name "application"
                              :entries [(entry "target/server2.jar" "/app/server2.jar")]}])
             (assoc defaults
                    :destination {:registry "us.gcr.io/proj/reddit-classifier:latest"}
                    :base {:image "us.gcr.io/proj/reddit-classifier-base:latest" :legacy true}
                    :labels {"org.opencontainers.image.base.name" "us.gcr.io/proj/reddit-classifier-base:latest"}
                    :entrypoint ["java" "-cp" "/lib/*:/reddit-scraper.jar" "clojure.main" "-m" "genek.cli.main"]
                    :layers [{:type :deps :name "dependencies"
                              :entries [(entry "target/lib/dep.jar" "/lib/dep.jar")]}
                             {:type :app :name "application"
                              :entries [(entry "target/reddit-scraper.jar" "/reddit-scraper.jar")]}
                             {:type :files :name (str "files:" (path "classify"))
                              :entries [(entry "classify/main.py" "/classify/main.py")]}])]]
        (doseq [p ["target/lib" "classify"]]
          (.mkdirs (io/file dir p)))
        (doseq [p ["target/lib/dep.jar" "target/server2.jar" "target/reddit-scraper.jar"
                   "classify/main.py" "classify/test_skip.py" "classify/other.rb"]]
          (spit (io/file dir p) "fixture"))
        (is (= expected (mapv (comp jib/plan rewrite-sources) examples)))))))

(deftest only-the-base-label-is-automatic
  (with-specs
    (fn [{:keys [app]} _]
      (let [labels (:labels (jib/plan (assoc app :labels {"io.github.realgenekim.thin-jar.version" "mine"})))]
        (is (= #{"org.opencontainers.image.base.name" "io.github.realgenekim.thin-jar.version"} (set (keys labels))))
        (is (= "mine" (get labels "io.github.realgenekim.thin-jar.version")))))))

(deftest library-labels-are-isolated
  (let [library-labels (ns-resolve 'thin-jar.jib 'library-labels)]
    (is (some-> library-labels meta :private))
    (when library-labels
      (with-specs
        (fn [{:keys [app]} _]
          (with-redefs-fn {library-labels (constantly {})}
            #(is (= {"example.label" "value"}
                    (:labels (jib/plan (assoc app :labels {"example.label" "value"})))))))))))

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

(deftest deployment-stdout-matches-upstream-and-fork
  (with-specs
    (fn [{:keys [app]} dir]
      (let [target (:target-image app)
            tar-path (str (io/file dir "out.tar"))
            spec (assoc app :base {:image "scratch" :reason "Offline fixture"})
            layer (first (:layers spec))
            make-containerizer @#'jib/make-containerizer
            log-events (atom [])]
        (with-redefs [jib/make-containerizer
                      (fn [image tar logger]
                        (doto ^Containerizer (make-containerizer image (or tar tar-path) logger)
                          (.setOfflineMode true)
                          (.setBaseImageLayersCache (.toPath (io/file dir "base-cache")))
                          (.setApplicationLayersCache (.toPath (io/file dir "app-cache")))))
                      jib/make-logger (fn [_]
                                        (reify Consumer
                                          (accept [_ event] (swap! log-events conj event))))]
          (doseq [verbose [false true]
                  tar? [false true]]
            (let [options (cond-> (assoc spec :verbose verbose)
                            tar? (assoc :to-tar tar-path :env {"APP_ENV" "test"}))
                  expected (concat
                             [(if tar?
                                "Building container image tarball with Jib (no push)..."
                                "Building and pushing container image with Jib...")
                              "  Base image: scratch"
                              (str "  Target image: " target)]
                             (when tar? [(str "  Tarball: " tar-path) "  Env: {\"APP_ENV\" \"test\"}"])
                             ["  Layers:"
                              (str "    - :app: " (dissoc layer :type))
                              :elapsed
                              (if tar?
                                (str "Built image tarball " tar-path " (not pushed)")
                                "Container image built and pushed successfully!")
                              (str "  Image: " target)])
                  actual (str/split-lines (with-out-str (jib/deploy! options)))]
              (is (= expected (map #(if (re-matches #"\"Elapsed time: [0-9.]+ msecs\"" %) :elapsed %) actual))))))
        (is (empty? @log-events))))))

(deftest release-notes-cover-compatibility
  (let [changelog (slurp "CHANGELOG.md")]
    (is (re-find #"(?s)### Behaviour changes.*`deploy!`.*receipt.*nil" changelog))
    (is (re-find #"(?s)### Behaviour changes.*unknown keys.*wrong-kind.*invalid values.*:names.*:container-path.*image root" changelog))
    (is (re-find #"(?s)### Known, unchanged.*credential.*target image's registry.*f5bd8dd.*fork" changelog))))
