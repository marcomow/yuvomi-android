# 0012. Fewer libraries than first planned

**Status:** Accepted, 2026-11-01. Supersedes the library choices in [0001](0001-native-stack.md)
(Room, Retrofit) and the Retrofit mention in [0003](0003-api-client.md).

## Context

ADR 0001 named Retrofit for HTTP and Room for the read cache. Writing the client showed that
neither earns its place:

- Every call already goes through one `YuvomiClient.call(Operation, ...)` that knows upstream's
  conventions (CSRF retry, session expiry, error reasons, idempotency keys). Retrofit interfaces
  would sit on top of it without adding anything.
- The read cache (ADR 0007) stores "the last good answer of a screen" and is never queried.
  Plain JSON files do that; Room would bring an annotation processor (KSP) for no query.

Separately, the newest AndroidX and OkHttp releases (late 2026) require compile SDK 37, which
requires Android Gradle Plugin 9.

## Decision

- **HTTP:** OkHttp only. `Operation` is the single list of upstream operations; typed wrappers
  (`AuthApi`, `HouseholdApi`, `TasksApi`) decode with `kotlinx.serialization`.
  `ContractFilesTest` checks `Operation` against `upstream/operations-used.txt` and the OpenAPI
  snapshot, replacing the planned Retrofit check.
- **Cache:** JSON files under the app's files folder, one directory per server and user
  (`ResponseCache`).
- **Images:** avatars are fetched with the session cookie through the same client and decoded
  with `BitmapFactory`; no image library.
- **Versions:** AGP 8.13 with compile SDK 36, and the newest library versions that compile
  against it (pinned in `gradle/libs.versions.toml`). Moving to AGP 9 and SDK 37 is a separate
  upgrade.

## Consequences

- The runtime dependencies are Compose, Navigation, Lifecycle, DataStore, Browser (Custom Tabs),
  kotlinx.serialization, coroutines and OkHttp. No annotation processing.
- Lint reports newer versions as warnings until the AGP 9 upgrade.
