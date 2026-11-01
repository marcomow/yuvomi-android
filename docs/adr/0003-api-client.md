# 0003. API client

**Status:** Accepted, 2026-11-01. Retrofit dropped by [0012](0012-fewer-libraries.md); the operation list is the `Operation` enum.

## Context

Upstream publishes an OpenAPI 3.1 document generated from `server/openapi/`, covering 687
operations at v2.83.0. Every request body is declared as an untyped object (`jsonBody(null)`), and responses
mix a `{ data: ... }` envelope with bare objects (`/auth/login`, `/auth/me`,
`/tasks/meta/options`). The document is served admin-only at runtime.

## Decision

- Write the client **by hand**, for the operations the specs list and nothing more.
- Models are Kotlin data classes with `kotlinx.serialization`, `ignoreUnknownKeys = true`,
  `explicitNulls = false`, and defaults on every property that upstream may omit.
- One `ApiResult<T>` type carries success, a typed `ApiError(status, reason, message)`, or a
  network failure. Upstream's `reason` field is kept verbatim.
- Envelope handling is explicit per endpoint (`Envelope<T>` vs bare `T`), as documented in each
  spec, rather than guessed.
- Interceptors add `X-CSRF-Token` on state-changing requests and an `Idempotency-Key` (random
  UUID per user action, reused on retry) on every POST outside `/auth` (ADR 0007).
- The OpenAPI document is snapshotted into `upstream/openapi.json` and used as a **diff signal**
  (ADR 0008), never as a code generator input.
- `upstream/operations-used.txt` lists every `METHOD /path` the app calls. A unit test checks the
  Retrofit interfaces against it, and the sync job checks it against the newest OpenAPI snapshot.

## Consequences

- A few dozen small, readable, fully typed calls instead of 709 generated ones with `Any` bodies.
- Response shapes are verified by contract tests against a real server (ADR 0008), which catches
  drift a spec diff cannot.
- Adding an operation means adding it by hand, to the list and to a contract test.

## Alternatives considered

- **OpenAPI Generator (Kotlin/Retrofit):** large output, untyped inputs, noisy diffs on every
  upstream release.
