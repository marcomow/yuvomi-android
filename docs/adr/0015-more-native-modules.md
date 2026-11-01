# 0015. Five more native modules, each in its own package

**Status:** Accepted, 2026-11-01. Widens the scope of 0010.

## Context

The MVP (0010) works on a phone. The owner asked for Shopping, Meals, Notes, Recipes and
Pantry next, at full parity with the web app, in one pull request, built in parallel.

Until now every feature edited the same few files: the navigation shell, one `Operation` enum,
one `operations-used.txt`, and one file of app strings. Five parallel branches would have
collided in every one of them.

## Decision

- **Scope.** Shopping, Meals, Recipes, Notes and Pantry become native at full parity with the
  web app's pages at the pinned version: everything those pages let a member do. What the web
  app sends to Settings stays in the browser (0006). Each module has a spec in `docs/spec/`.
- **One package per module.** `feature/<module>/` holds the module's screens, view models,
  models, API wrapper, its `<Module>Operation` enum and its `<Module>Routes`. The module
  implements `Feature` and is listed once in `Features.all`.
- **Navigation.** The bottom bar holds Overview, Tasks, Shopping, Meals and More. "More" lists
  Recipes, Notes and Pantry first, then the modules that open in the web app. The rail on wide
  screens shows every native module. Modules link to each other only through the other
  module's `*Routes` helpers and `ShellContext.navigate`.
- **Operations.** `ApiOperation` is the common interface. Core and Tasks keep `Operation`
  (`upstream/operations-used.txt`); each module has its enum and
  `upstream/operations-used.d/<module>.txt`. Calls that several kitchen modules make (lists
  of shopping lists, categories, pantry, recipes and meals, creating a recipe or a meal, the
  undo of a transfer, the kitchen summary) are core operations wrapped by `KitchenApi`. Every
  other call belongs to the one module that makes it, even under another module's path.
  `ContractFilesTest` checks every enum against its list and the OpenAPI snapshot, and that no
  operation is declared twice.
- **Strings.** Upstream namespaces for the five modules are added to
  `upstream/i18n-namespaces.txt` (not `settings`, which is large and mostly unrelated). App-only
  strings of a module live in `assets/i18n-android/<module>/<locale>.json`.
- **Contract tests** per module extend `ContractTestBase`.
- **Overview.** The Shopping and Meals tiles and the today sheet open the native screens; a
  generic tile of a native module opens the module.

## Consequences

- Parallel work on modules touches disjoint files; the shared files change once, here.
- The APK carries more upstream strings (about 2.8 MB of locale JSON before compression).
- The app now depends on more of upstream's API (see the operation lists). The weekly sync
  report covers all of them.

## Alternatives considered

- **A Gradle module per feature.** Cleaner boundaries, but slower builds and more setup than a
  one-person proof of concept needs (0001).
- **Kitchen tabs like the web app**, one screen with Shopping, Meals, Recipes and Pantry as
  tabs. Closer to the web app, but buries Shopping, the module used most on a phone, one tap
  deeper.
