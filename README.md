# thin-jar-build

Reusable thin JAR build and Jib deployment functions for Clojure projects.

## Why Thin JARs?

Traditional uberjars include all dependencies (~100MB+). With Docker layer caching, changing one line of code requires pushing the entire JAR again.

Thin JARs split the build:
- **lib/** directory: Precompiled dependency JARs (~25-30 MB, rarely changes)
- **app.jar**: Application code only (~2-5 MB, changes frequently)

After the first deploy, subsequent deploys only push the small app layer.

## Installation

Add to your `deps.edn`:

```clojure
:aliases
{:build {:deps {thin-jar-build/thin-jar-build {:local/root "../thin-jar-build"}}
         :ns-default build}}
```

## Usage

### build.clj

```clojure
(ns build
  (:require [thin-jar.build :as tb]))

(defn thin [_]
  (tb/thin {:main-class 'myapp.server
            :jar-file "target/myapp.jar"}))
```

Options:
- `:main-class` (required) - Symbol for main namespace
- `:jar-file` (required) - Output JAR path
- `:lib-dir` - Lib directory (default: "target/lib")
- `:src-dirs` - Source directories (default: ["src" "resources"])
- `:class-dir` - Class output (default: "target/classes")
- `:aliases` - deps.edn aliases to include

### jib.clj

```clojure
(ns jib
  (:require [thin-jar.jib :as jib]))

(defn deploy [_]
  (jib/deploy! {:target-image "us-west1-docker.pkg.dev/proj/repo/myapp:latest"
                :entrypoint ["java" "-cp" "/app/myapp.jar:/app/lib/*" "myapp.server"]
                :layers [{:type :deps
                          :lib-dir "target/lib"
                          :container-path "/app/lib"}
                         {:type :app
                          :jar-path "target/myapp.jar"
                          :container-path "/app/myapp.jar"}]}))
```

## Layer Types

### :deps - JAR dependencies

```clojure
{:type :deps
 :lib-dir "target/lib"           ; Local directory with JARs
 :container-path "/app/lib"}     ; Container destination
```

### :app - Application JAR

```clojure
{:type :app
 :jar-path "target/myapp.jar"    ; Local JAR path
 :container-path "/app/myapp.jar"} ; Container destination
```

### :files - Additional files

For Python scripts, configs, etc:

```clojure
{:type :files
 :local-dir "scripts"            ; Local directory
 :container-path "/scripts"      ; Container destination
 :extensions #{".py" ".txt"}     ; File extensions to include
 :blacklist-prefixes ["test_"]}  ; Prefixes to exclude
```

## Options

### deploy!

- `:target-image` (required) - Full image name with tag
- `:entrypoint` (required) - Vector of entrypoint strings
- `:layers` (required) - Vector of layer specs
- `:base-image` - Base image (default: "gcr.io/distroless/java21")
- `:verbose` - Print Jib logs (default: true)

## Examples

### Simple thin JAR (server2)

```clojure
;; build.clj
(defn thin [_]
  (tb/thin {:main-class 'server2.server
            :jar-file "target/server2.jar"}))

;; jib.clj
(defn deploy [_]
  (jib/deploy! {:target-image "us-west1-docker.pkg.dev/proj/reddit-server2:latest"
                :entrypoint ["java" "-cp" "/app/server2.jar:/app/lib/*" "server2.server"]
                :layers [{:type :deps :lib-dir "target/lib" :container-path "/app/lib"}
                         {:type :app :jar-path "target/server2.jar" :container-path "/app/server2.jar"}]}))
```

### With Python scripts and custom base (classifier)

```clojure
(defn deploy-classifier [_]
  (jib/deploy! {:base-image "us.gcr.io/proj/reddit-classifier-base:latest"
                :target-image "us.gcr.io/proj/reddit-classifier:latest"
                :entrypoint ["java" "-cp" "/lib/*:/app.jar" "clojure.main" "-m" "myapp.main"]
                :layers [{:type :deps :lib-dir "target/lib" :container-path "/lib"}
                         {:type :app :jar-path "target/app.jar" :container-path "/app.jar"}
                         {:type :files
                          :local-dir "classify"
                          :container-path "/classify"
                          :extensions #{".py" ".txt"}
                          :blacklist-prefixes ["test_" "experiment_"]}]}))
```
