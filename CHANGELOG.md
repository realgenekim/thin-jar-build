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
- Planning rejects unknown keys, missing or wrong-kind sources, invalid values,
  and a `:names` entry that matches no file in `:local-dir`.
- A directory layer requires an absolute `:container-path`. Upstream placed
  files from directory layers with an omitted path at the image root.

### Known, unchanged

- Base-image credentials are looked up for the target image's registry, as at
  `f5bd8dd` and in the fork.
