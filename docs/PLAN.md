# Implementation plan

The order in which the specs get built. Each milestone is one or more small PRs, and a milestone
is done when its acceptance criteria pass in CI.

All five milestones were built in one pass (2026-11-01). Status per milestone:

| Milestone | Delivers | Proves |
|---|---|---|
| **M0 Scaffold** (done) | Gradle project (Kotlin DSL, version catalog), app module, theme from `assets/tokens.json`, i18n loader for `assets/i18n/`, API core (OkHttp, cookie jar, CSRF and idempotency interceptors, envelope and error decoding), navigation shell with Overview, Tasks and "More in the web app", CI: build, unit tests, lint, and the contract-test job against `ghcr.io/ulsklyc/yuvomi:<pin>` seeded with `seed-demo.js`. Module icons converted from `nav-icons.js` by the sync. | SIGNIN-A7, SIGNIN-A12, SIGNIN-A13, a contract smoke test of `GET /version` |
| **M1 Sign-in** (done) | [signin.md](spec/signin.md) in full. | SIGNIN-A1 to A13 |
| **M2 Overview** (done) | [overview.md](spec/overview.md) in full. Ticking off from the overview uses the tasks repository from M3's first PR, so M3.1 lands first or together. | OVERVIEW-A1 to A11 |
| **M3 Tasks tier 1** (done) | [tasks.md](spec/tasks.md) R1 to R27. | TASKS-A1 to A10, A17, A18 |
| **M4 Tasks tier 2** (done) | [tasks.md](spec/tasks.md) R28 to R40. | TASKS-A11 to A16 |

Not done yet, in order of value:

- Try Shopping, Meals, Recipes, Notes and Pantry by hand on a phone (ADR 0015); the MVP has been.
- Drag and drop where the web app has it (shopping and pantry order, meals between days);
  up and down buttons stand in for now.
- Convert upstream's module glyphs (`public/nav-icons.js`) to vector drawables in the sync.
- Apply overview tile options (OVERVIEW-R15) as query parameters.
- Upgrade to AGP 9 and compile SDK 37 to take the newest AndroidX and OkHttp releases (ADR 0012).
- Add the release signing key to the repository secrets (docs/RELEASING.md); until then every
  automatic release needs an uninstall first.
- Repository setting for the weekly sync: *Settings → Actions → General → Allow GitHub Actions
  to create and approve pull requests*.
