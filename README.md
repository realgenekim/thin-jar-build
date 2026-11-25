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

## Jib contract

`thin-jar.jib/plan` and `deploy!` accept these keys. Unknown keys fail before
credential lookup or image building. Errors report the reason, path, allowed
keys, and offending keys.

| Scope | Allowed keys | Required keys | Existing local source |
| --- | --- | --- | --- |
| top | `:entrypoint`, `:layers`, `:to`, `:target-image`, `:to-tar`, `:base`, `:base-image`, `:env`, `:labels`, `:verbose` | `:entrypoint`, `:layers`, and a destination | |
| to | `:registry`, `:tar`, `:image-name` | `:registry` alone, or `:tar` and `:image-name` | |
| base | `:image`, `:reason` | `:image`, `:reason` | |
| deps | `:type`, `:lib-dir`, `:container-path` | `:type`, `:lib-dir` | `:lib-dir` |
| app | `:type`, `:jar-path`, `:container-path` | `:type`, `:jar-path`, `:container-path` | `:jar-path` |
| files | `:type`, `:local-dir`, `:container-path`, `:extensions`, `:blacklist-prefixes`, `:names` | `:type`, `:local-dir` | `:local-dir` |

`:entrypoint` takes a collection or array of strings and may be empty. `:layers`
takes a non-empty collection or array in build order. Container paths are absolute
Unix paths. For directory layers, an omitted, nil, or empty `:container-path`
means the image root `/`.

A `:deps` layer includes every regular file directly in `:lib-dir`. A `:files`
layer selects regular files directly in `:local-dir` using a collection of
extensions (default `.py`, `.txt`, `.sh`, `.json`, `.yaml`, `.yml`). It then
applies the optional exact filename allowlist `:names` and the optional exclusion
list `:blacklist-prefixes`. The allowlist may include absent files. Credential
logging defaults to enabled. Set `:verbose` to false or nil to disable it.

Planning rejects unknown keys, missing or wrong-kind sources, invalid values,
and directory layers that would contain no files.

### Destination and base

```clojure
{:to {:registry "us-west1-docker.pkg.dev/proj/repo/myapp:latest"}}
{:to {:tar "target/myapp.tar" :image-name "myapp:local"}}
{:base {:image "gcr.io/distroless/java21" :reason "Java 21 runtime"}}
```

A tar destination writes a local archive. Jib can still pull a registry base
when building that archive. Use `:base {:image "scratch" :reason "..."}` to build
from an empty base. A declared base requires a non-blank reason. With no base
configured, the floating default is `gcr.io/distroless/java21`.

`:env` adds or overrides individual variables using a map of strings to strings.
`:labels` accepts a map of strings to strings. The library reserves and sets
`org.opencontainers.image.base.name` to the base reference. It sets no version
label, so an image digest changes only when the image changes; the library version
is in the receipt as `:library-version`.

### Plan and receipt

`(jib/plan spec)` checks local sources and returns data without reading credentials
or contacting a registry. It includes the normalized `:destination`, `:base`
(with `:reason` or `:legacy true`), `:entrypoint`, `:env`, `:labels`, `:verbose`,
and the ordered `:layers`. Each layer includes its type, name and entries with
absolute local `:source` paths and resolved `:container-path` values.

`(jib/deploy! spec)` executes that plan. With `:to` it returns this receipt shape:

```clojure
{:destination {:tar "target/myapp.tar" :image-name "myapp:local"}
 :pushed? false
 :image-digest "sha256:..."
 :image-id "sha256:..."
 :base {:image "gcr.io/distroless/java21"}
 :labels {"org.opencontainers.image.base.name" "gcr.io/distroless/java21"}
 :library-version "0.2.0"}
```

Legacy destination calls keep the fork's keyword `:destination` (`:tar` or
`:registry`), `:target-image`, `:tar-path`, and `:digest`. They expose the
destination map as `:resolved-destination` and also return the other receipt
fields shown above. `jib/version` returns the library version string.

### Migration

| Old key or combination | Current form | Still accepted? |
| --- | --- | --- |
| `:target-image` | `:to {:registry ref}` | Yes |
| `:target-image` and `:to-tar` | `:to {:tar path :image-name ref}` | Yes |
| `:base-image` | `:base {:image ref :reason why}` | Yes |
| `:env` | `:env` | Yes |
| `:names` in a files layer | `:names` | Yes |
| `:environment` (another fork's key) | Use `:env` and apply any required JVM flag policy in the caller | No |

Supply one destination form and at most one base form. Run the offline tests
with `clojure -X:test`.

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
