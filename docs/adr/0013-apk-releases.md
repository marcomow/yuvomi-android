# 0013. APK releases on GitHub

**Status:** Accepted, 2026-11-01. Amends the distribution bullet of 0009. Trigger and version code
superseded by [0014](0014-continuous-delivery.md).

## Context

0009 kept distribution to debug APKs in CI artifacts. Those need a GitHub sign-in to download,
expire after 14 days, and come as a zip. People trying the proof of concept need a stable link to
an APK they can open on the phone. The repository is public, so a release is public too.

## Decision

- Pushing a tag `v<major>.<minor>.<patch>`, or running the workflow by hand on `main` with a
  version (which creates that tag, and works from a phone), runs `.github/workflows/release.yml`. It runs the
  tests, builds the **release** variant (R8 on), and publishes a GitHub Release marked
  **pre-release** with `yuvomi-android-<version>.apk` and its SHA-256.
- The tag sets the version: `versionName` is the tag without `v`, `versionCode` is
  `major * 10000 + minor * 100 + patch`. Minor and patch stay at 99 or less.
- Signing uses an upload key held in repository secrets (`YUVOMI_KEYSTORE_BASE64`,
  `YUVOMI_KEYSTORE_PASSWORD`, `YUVOMI_KEY_ALIAS`, `YUVOMI_KEY_PASSWORD`). No key is ever
  committed.
- Without those secrets the workflow still publishes, signed with the runner's throwaway debug
  key, and says so in the release notes. That keeps the first release one tag away.
- Release notes repeat the unofficial-POC notice and print the signing certificate's SHA-256.
- No app store. The application ID and launcher name stay as in 0009.

## Consequences

- A stable public download link; sideloading still needs "install unknown apps".
- Android only installs an update signed with the same key. A debug-signed release cannot be
  updated by the next one, and moving to the real key means one uninstall. Losing the key means
  the same for every user.
- Release builds are minified, so they can fail where debug builds do not. Each release is
  tried on a device before it is announced (docs/RELEASING.md).

## Alternatives considered

- **Attach the debug APK from CI to a release.** No build step to add, but it is three times the
  size, debuggable, and signed with a different key on every run.
- **A key committed to the repository.** Updates would always install, but anyone could sign an
  APK that installs over ours.
- **Play Store or F-Droid.** Out of scope for a proof of concept, and needs the naming question in
  0009 settled first.
