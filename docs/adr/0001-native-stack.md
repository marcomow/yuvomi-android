# 0001. Native stack

**Status:** Accepted, 2026-11-01. Library list amended by [0012](0012-fewer-libraries.md): no Room, no Retrofit.

## Context

The client must feel native on Android. WebViews and wrappers (TWA, Capacitor) are excluded. The
tech stack and the effort should stay minimal. Upstream is vanilla JavaScript with no framework,
so no UI code can be shared whatever we pick; upstream's own iOS client is SwiftUI.

## Decision

- **Kotlin** and **Jetpack Compose** with **Material 3**, using only first-party Google and
  JetBrains libraries for the core: Navigation Compose, Lifecycle/ViewModel, Room, DataStore,
  `kotlinx.serialization`, `kotlinx.coroutines`.
- **OkHttp + Retrofit** for HTTP (the de-facto standard, Square-maintained).
- **One Gradle module** (`app`), package-by-feature.
- **Manual dependency injection** through a small `AppContainer`. Hilt is the next step when the
  container grows past what one file reads comfortably.
- **Min SDK 26** (Android 8.0), target and compile SDK at the current stable level.
- Gradle Kotlin DSL with a version catalog (`gradle/libs.versions.toml`).

## Consequences

- One language and one toolchain end to end.
- Compose gives predictive back, edge-to-edge, dynamic type, TalkBack semantics and
  RTL mirroring (upstream ships Arabic and Persian) with little extra work.
- No code sharing with iOS. That is accepted: upstream's iOS client is SwiftUI and lives
  elsewhere.

## Alternatives considered

- **Kotlin Multiplatform / Compose Multiplatform:** adds an iOS target nobody asked for.
- **Flutter:** the exact second ecosystem upstream declined in #255; own widget set rather than
  the platform's.
- **React Native:** no shared code with a framework-free PWA, so its main argument is gone.
