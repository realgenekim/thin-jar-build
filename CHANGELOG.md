# Changelog

## v0.2.0

- Closed the Jib configuration contract at the top level, in layer specs, and
  in the new destination and base maps. Invalid inputs fail before deployment.
- Added explicit registry and tar destinations, declared base images with a
  reason, image labels, and the local-only `plan` function.
- Kept the legacy destination and base keys, plus a downstream
  fork's tar, environment, filename filtering, and legacy receipt fields.
- Added deployment receipts with Jib image digests, image IDs, and push status,
  plus a public library version (in the receipt) and one provenance label,
  `org.opencontainers.image.base.name`. No version label: it would change every
  image digest on each library release.
- Kept the floating `gcr.io/distroless/java21` default so consumers continue to
  pick up JDK patches. A later release may record the default base digest as release
  data and report the live digests across consumers. Announce the tradeoff before
  changing the default: pinning a digest stops automatic pickup of JDK patches.

### Behaviour changes

- `deploy!` returns a receipt. At `f5bd8dd` it returned nil.
- Validation refuses, with a typed `ex-info` `:reason` before any build or push:
  unknown keys or an unknown layer type; a missing required key; a non-map spec,
  layer, `:to` or `:base`; `:layers` that is a map or an empty collection; a
  conflicting or missing destination; an app JAR whose path is missing or does not point to a
  regular file; a string, empty, or non-string entrypoint; an image reference
  Jib cannot parse; a relative container path; a directory source that
  `clojure.java.io/file` cannot convert (a string, `File`, `URI` or file `URL`
  are accepted); and malformed values: `:names` that is not nil, a map, a set,
  a transient map or set, a collection or an array, `:extensions` or
  `:blacklist-prefixes` that is not a collection or array of strings,
  non-string `:env` or `:labels`, the reserved base label, a blank `:base`
  `:reason`. A malformed filter value or a relative container path is refused
  even where `f5bd8dd` built an image only because the directory was empty or
  absent, so no file reached it.
- Vector, list and Java-array `:names` allowlists now match filenames by value
  (the fork's `contains?` did not match their elements). This is additive.
  Maps match by key and sets keep their existing membership rules.
- Still accepted as at `f5bd8dd`: a `:container-path` given as any object whose
  text form is an absolute path (`java.io.File`, `java.nio.file.Path`), a
  `java.io.File` `:jar-path`,
  an `ImageReference` `:base-image`, and `:extensions nil` (an empty selection,
  reported below).
- A directory layer that contributes zero files reports its cause as `:missing`
  (source path does not exist), `:not-a-directory` (source is a file), `:empty`
  (directory has no files), or `:filtered` (filters excluded every file).
  The plan and both receipt shapes record it in `:empty-layers`. Jib drops empty
  layers. Deployment prints one warning per empty layer before building,
  regardless of `:verbose`.

  ```text
  WARNING thin-jar: layer <i> (<type> <source>) selects no files (<cause>); filters: <filters>
  ```
- An omitted, nil, or empty directory-layer `:container-path` means the image root `/`.

Every other spec that planned and built at `f5bd8dd` or in the fork is meant to
build the same image content. Compatibility evidence covers the forms exercised
by the tests and reviews.

### Known, unchanged

- Base-image credentials are looked up for the target image's registry, as at
  `f5bd8dd` and in the fork.
