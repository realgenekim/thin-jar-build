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
- Kept the floating `gcr.io/distroless/java21` default: this release makes no
  breaking change. A later release may record the default base digest as
  release data and report the live digests across consumers. Announce the
  tradeoff before changing the default: pinning a digest stops automatic
  pickup of JDK patches.

### Behaviour changes

- `deploy!` returns a receipt. At `f5bd8dd` it returned nil.
- Newly refused, before any build or push, with a typed `ex-info` `:reason`:
  unknown keys; missing or wrong-kind sources; malformed values such as a string
  or empty entrypoint (an empty one threw at `f5bd8dd`) or an unparseable image
  reference; `:names` given
  as a map or containing a non-string; conflicting or missing destinations.
- Vector, list and Java-array `:names` allowlists now match filenames by value
  (the fork's `contains?` did not match their elements). This is additive.
- Still accepted as at `f5bd8dd`: a `java.io.File` directory `:container-path`,
  an `ImageReference` `:base-image`, and `:extensions nil` (an empty selection,
  reported below).
- Empty directory layers are recorded in `:empty-layers` in the plan and both
  receipt shapes. Jib drops empty layers. Deployment prints one warning per
  empty layer before building, regardless of `:verbose`.

  ```text
  WARNING thin-jar: layer <i> (<type> <source>) selects no files; filters: <filters>
  ```
- An omitted, nil, or empty directory-layer `:container-path` means the image root `/`.

Every other spec that planned and built at `f5bd8dd` or in the fork is meant to
build the same image content. The tests and four review rounds exercised the
forms named here; specs they did not exercise are not separately proven.

### Known, unchanged

- Base-image credentials are looked up for the target image's registry, as at
  `f5bd8dd` and in the fork.
