# Links to your own server

Android only. ADR 0022.

The app does not register as the default handler for the server's web links. That would need
the server's host in the APK at build time, and the APK and this repository are public. Since
Android 12, web links for a host the app does not declare open in the browser. What the app
does instead needs no host anywhere in its code:

- **LINKS-R1** A link inside the app (Markdown in notes and task text, a shopping item's link)
  to the signed-in server opens its native screen. Every other link opens in the browser as
  before. A link is the server's when its scheme, host and port equal the signed-in server's
  and its path lies under the server's base path.
- **LINKS-R2** **Share → Open in Yuvomi** from any app opens the first address in the shared
  text the same way. When it is not the server's, or the app has no screen for it, a message
  says so.
- **LINKS-R3** Web paths map to these screens (`?open=` and `?list=` as the web app reads
  them): `/tasks`, `/tasks?open=<id>`, `/calendar?open=<id>`, `/notes?open=<id>`,
  `/recipes?open=<id>`, `/documents?open=<id>`, `/shopping?list=<id>`, `/health/<area>`,
  `/settings...` (the Settings start), `/` (Overview), and every other module path to its start.
  Pages without a native screen (`/login`, `/join`, the API) stay in the browser. Every route
  goes through `AppLinks.allowed`.
