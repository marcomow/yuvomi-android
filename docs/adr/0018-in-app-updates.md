# 0018. In-app updates from the GitHub releases

**Status:** Accepted, 2026-11-02. Amends 0013 (installing) and the README's "talks only to your
own server".

## Context

Every change merged into `main` publishes a signed APK as a GitHub release (0014). Installing
one meant opening the release page in a browser, downloading the APK and opening it. The owner
asked to install updates from within the app.

## Decision

- **Where updates come from.** `GET https://api.github.com/repos/marcomow/yuvomi-android/releases`
  (unauthenticated, public). The newest non-draft release whose `yuvomi-android-<version>.apk`
  asset is newer than the installed `versionName` (semantic version compare) is offered.
  Prereleases count: every release of this POC is one.
- **Integrity.** The download is checked against the asset's `digest` (`sha256:...`) that
  GitHub publishes; a mismatch deletes the file. Android's package installer then accepts the
  APK only if it is signed with the installed app's key, so a foreign APK can never replace
  the app.
- **Installing.** The APK is saved under `cache/updates/` and handed to the system installer
  with `ACTION_VIEW` through the app's `FileProvider`. The app declares
  `REQUEST_INSTALL_PACKAGES`; until the member allows "install unknown apps" for it, the app
  says so and opens that setting. Old downloads are removed on the next check.
- **When.** "Check now" and "Install" sit in the account sheet. "Check automatically" (on by
  default) checks at most every 12 hours when the shell opens and announces each new version
  once, with an Install action.
- **Privacy.** This is the only traffic that does not go to the household's server. It uses
  its own HTTP client without the session's cookies and sends nothing about the member or the
  server (GitHub sees the device's IP address and a `yuvomi-android/<version>` user agent).
  Switching "Check automatically" off stops it; nothing is sent unless the member taps.
- **Debug builds** never check: they are signed with a different key and could not install a
  release over themselves (`BuildConfig.UPDATES_ENABLED`).

## Consequences

- The README no longer says the app talks only to your server.
- Google Play would forbid self-updating; this app is distributed through GitHub releases only.
