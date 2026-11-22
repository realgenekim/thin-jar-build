# Changelog

## v0.2.0

- Closed the Jib configuration contract at the top level, in layer specs, and
  in the new destination and base maps. Invalid inputs fail before deployment.
- Added explicit registry and tar destinations, declared base images with a
  reason, image labels, and the local-only `plan` function.
- Kept the legacy destination and base keys, plus a downstream
  fork's tar, environment, filename filtering, and legacy receipt fields.
- Added deployment receipts with Jib image digests, image IDs, and push status,
  plus a public library version and two provenance labels.
- Kept the floating `gcr.io/distroless/java21` default: this release makes no
  breaking change. A later release may record
  the default base digest as release data and report the live
  digests across the fleet. Announce the tradeoff before changing the default:
  pinning a digest stops automatic pickup of JDK patches.
