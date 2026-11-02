# 0016. Calendar, Schedule, Documents, Health, Budget and Settings native

**Status:** Accepted, 2026-11-02. Extends 0015.

## Context

After 0015 the owner asked to port every remaining section. They were built in parallel the
same way as in 0015, one package each, in two waves. Seven are finished for this release;
Rewards, Birthdays, Contacts, Inventory, Housekeeping, Waste and the cross-module Tags view
follow in their own pull request.

## Decision

- **Native now:** Calendar, Schedule, Documents, Health, Budget (with its Subscriptions and
  Split-expenses tabs, built as `feature/subscriptions` and `feature/splitexpenses`, routes
  under `budget/`), and Settings. Each has a spec in `docs/spec/` that names what stays in
  the browser.
- **Pending modules.** `Features.pending` holds modules whose packages exist but are not
  finished. Their routes are registered, so a link from a finished module lands on a
  placeholder that offers the web app, but they count as web modules everywhere else:
  "More" lists them with the browser icon.
- **Navigation.** With eleven native modules beyond Overview and Tasks, the rail on wide
  screens holds the same entries as the bottom bar (Overview, Tasks, Shopping, Meals, More)
  instead of every module. This replaces the rail rule in 0015.
- **Shared calls.** When two modules call the same upstream operation, the one under the
  module's own server path owns it and the other calls that module's enum entry (Calendar's
  holidays, Schedule's entries, Documents' previews and downloads, Budget's meta). Calls that
  belong to no single module move to core `Operation`: `GET /family/members`,
  `GET /auth/users`, `GET /calendar/sync-targets`.
- **Files to other apps.** Documents hands a cached copy to "Open with" and Share through a
  `FileProvider` (authority `${applicationId}.files`, cache path `documents-open/`).
- **Strings.** The upstream namespaces these modules use are shipped (`upstream/i18n-namespaces.txt`),
  including `settings` (about 100 KB per locale), which 0015 had left out.

## Consequences

- The APK grows with the extra namespaces and screens.
- The rail no longer gives one-tap access to every module on tablets; "More" does.
- Contract tests now cover every operation of these modules against the pinned server in CI.
