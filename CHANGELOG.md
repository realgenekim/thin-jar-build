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
- Kept the floating `gcr.io/distroless/java21` default so consumers continue
  to pick up JDK patches.

### Behaviour changes

- `deploy!` returns a receipt. At `f5bd8dd` it returned nil.
- Newly refused configurations include unknown keys, missing or wrong-kind
  sources, malformed values such as a string or empty entrypoint or unparseable image
  reference, and conflicting or missing destinations.
- Vector and list `:names` allowlists now match filenames by value.
- Empty directory layers are recorded in `:empty-layers` in the plan and both
  receipt shapes. Jib drops empty layers. Deployment prints one warning per
  empty layer before building, regardless of `:verbose`.

  ```text
  WARNING thin-jar: layer <i> (<type> <source>) selects no files; filters: <filters>
  ```
- An omitted, nil, or empty directory-layer `:container-path` means the image root `/`.

Compatibility covers every spec the tests and reviews exercised outside the
refusal list above, and every spec outside that list.

### Known, unchanged

- Base-image credentials are looked up for the target image's registry, as at
  `f5bd8dd` and in the fork.
