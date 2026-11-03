# 0022. Links to the member's server without the server in the app

**Status:** Accepted, 2026-11-03.

## Context

The owner wanted links to their self-hosted server to open in the app. Android opens a web link
in an app only for hosts that app declares in its manifest at build time. Since Android 12, it
also requires verification through the site's `assetlinks.json` before an app becomes the
default. The repository and the released APK are public, and the owner ruled out putting their
host in either.

## Decision

No manifest host, and no build-time host injection. Instead, everything is matched at runtime
against the signed-in server (`ServerLinks`):

- Inside the app, `MainShell` provides a `UriHandler`. A link to the server navigates
  natively, and every other link goes to the browser.
- A share target, `ACTION_SEND` `text/plain` labelled "Open in Yuvomi", opens a shared link the
  same way.

## Consequences

Tapping a server link in another app (chat, email) still opens the browser. The member can
share it to Yuvomi instead. A self-built APK could declare its own host later (a
`manifestPlaceholders` value from `local.properties`); that is not done here.
