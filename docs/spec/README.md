# Feature specs

One spec per feature. A spec is the contract between the decision records and the code: it names
the exact upstream operations and fields used, the behaviour, and acceptance criteria that become
tests. Code that does something a spec does not say is a bug in the code or a missing line in the
spec; fix whichever is wrong.

| Spec | Status | Upstream version checked |
|---|---|---|
| [Sign-in and session](signin.md) | Implemented | 2.84.0 |
| [Overview](overview.md) | Implemented | 2.84.0 |
| [Tasks](tasks.md) | Implemented (tiers 1 and 2) | 2.84.0 |
| [Shopping](shopping.md) | Implemented | 2.84.0 |
| [Meals](meals.md) | Implemented | 2.84.0 |
| [Recipes](recipes.md) | Implemented | 2.84.0 |
| [Notes](notes.md) | Implemented | 2.84.0 |
| [Pantry](pantry.md) | Implemented | 2.84.0 |
| [Calendar](calendar.md) | Implemented | 2.84.0 |
| [Schedule](schedule.md) | Implemented | 2.84.0 |
| [Documents](documents.md) | Implemented | 2.84.0 |
| [Health](health.md) | Implemented | 2.84.0 |
| [Budget](budget.md) | Implemented | 2.84.0 |
| [Subscriptions](subscriptions.md) | Implemented | 2.84.0 |
| [Split expenses](splitexpenses.md) | Implemented | 2.84.0 |
| [Settings](settings.md) | Implemented | 2.84.0 |
| [Rewards](rewards.md) | Implemented | 2.84.0 |
| [Birthdays](birthdays.md) | Implemented | 2.84.0 |
| [Contacts](contacts.md) | Implemented | 2.84.0 |
| [Inventory](inventory.md) | Implemented | 2.84.0 |
| [Housekeeping](housekeeping.md) | Implemented | 2.84.0 |
| [Waste](waste.md) | Implemented | 2.84.0 |
| [Tags](tags.md) | Implemented | 2.84.0 |

Where each acceptance criterion is tested: see the `@Test` names and KDoc in `app/src/test/`,
which carry the criterion IDs (`SIGNIN-A6`, `TASKS-A17`, ...). Criteria tagged **ui** are covered
by the Robolectric tests in `app/src/test/java/.../android/` where a dedicated test exists, and
otherwise by the end-to-end `AppSmokeTest`.

## Conventions

- **Requirement IDs** (`SIGNIN-R3`, `TASKS-R12`) are stable. A removed requirement keeps its ID,
  struck through, so references in tests and commits stay meaningful.
- **Acceptance criteria** (`SIGNIN-A3`) are written as Given / When / Then and name the test level
  that proves them:
  - **unit**: JVM test, no Android, fakes for the API.
  - **ui**: Compose UI test with a fake repository.
  - **contract**: the real API client against upstream's Docker image at the pinned version,
    seeded with `scripts/seed-demo.js` (see [UPSTREAM-SYNC.md](../UPSTREAM-SYNC.md)).
- **Paths** are written without the `/api/v1` prefix inside tables; every call goes to
  `<server>/api/v1/...`.
- **Envelope** says whether the JSON answer is wrapped as `{ "data": ... }` or bare.
- **Strings** are referenced by upstream i18n key (`tasks.title`). Strings that exist only in this
  app use the `android.` prefix and are listed in the spec that introduces them.
- Field lists name what the app **reads**. Every other field is ignored, and every listed field is
  treated as optional unless the spec says otherwise (ADR 0003).
