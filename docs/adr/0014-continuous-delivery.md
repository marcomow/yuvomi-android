# 0014. Continuous delivery of the APK

**Status:** Accepted, 2026-11-01. Supersedes the trigger and version-code parts of 0013.

## Context

0013 published a release when someone pushed a tag or ran the workflow with a version. The owner
works from a phone and wants every change on `main` to reach the phone without a manual step.

## Decision

- **Every push to `main` that changes the app** publishes the next patch release. Patch numbers
  follow the highest existing `v*` tag, so the first one is `v0.1.0`. "Changes the app" means a
  path under `app/` or `gradle/`, the Gradle build files, `upstream/VERSION` or the release
  workflow. Docs-only merges publish nothing.
- **Minor and major versions** stay manual. *Run workflow* with a version such as `0.2.0`, or a
  pushed tag, publishes that version; the next automatic release continues from it (`0.2.1`).
- The workflow runs the unit tests itself before building. The contract tests stay in the
  Android workflow, which runs on the same push.
- Releases run one at a time, in push order. The workflow refuses a version that is not higher
  than the latest release, because Android refuses such an update. A re-run on a commit that is
  already released does nothing.
- **Version code** = `major * 1000000 + minor * 1000 + patch`, with minor and patch at most 999
  and major at most 2000. 0013 allowed only 99 patches per minor, which automatic patch releases
  would use up quickly. No release had been made under 0013's scheme.
- Signing and the pre-release flag are unchanged from 0013.

## Consequences

- The phone can follow `main` with no step beyond installing the newest APK.
- Every app change becomes a public pre-release, including ones nobody tried on a device.
  The release notes list the merged pull requests (GitHub's generated notes).
- Without the release key in the secrets, every release needs an uninstall first, which defeats
  the point. The key setup in docs/RELEASING.md is a prerequisite for this to be useful.

## Alternatives considered

- **Release only on a version change in a file.** Still a manual step per release.
- **Nightly release.** Fewer releases, but a change can wait a day to reach the phone.
- **Debug APK from the Android workflow as the release.** Rejected in 0013.
