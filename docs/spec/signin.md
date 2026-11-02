# Spec: Sign-in and session

Status: ready for implementation · Upstream checked: 2.83.0 · Decisions: ADR 0004, 0006, 0007,
0008, 0011

## Purpose

Connect the app to a household's Yuvomi server, sign a member in with their own password (and
second factor when they have one), keep them signed in for as long as the server allows, and sign
them out cleanly.

## Scope

**In:** server address, password sign-in, TOTP or recovery code, session persistence, CSRF
handling, household context (timezone, members, permissions), version gate, sign-out, language
choice, "open in browser" for the web app.

**Out:** single sign-on, API tokens, first-time server setup, invites, password reset, account
settings, 2FA enrolment. All of these are reached in the browser.

## Upstream operations

| Method | Path | Envelope | Auth | Reads |
|---|---|---|---|---|
| GET | `/version` | bare | none, or session | `app_name`, `setup_required`, `password_reset_enabled`; with a session also `version`, `max_upload_bytes` |
| POST | `/auth/login` | bare | none | either `twoFactorRequired`, `recoveryAvailable`, or the sign-in payload below |
| POST | `/auth/2fa/verify` | bare | pending session | the sign-in payload, plus `twoFactorMethod`, `recoveryRemaining` |
| GET | `/auth/me` | bare | session | the sign-in payload without `changelog_seen` |
| POST | `/auth/logout` | bare | session + CSRF | `ok` |
| GET | `/preferences` | `data` | session | `timezone_effective`, `disabled_modules`, `dashboard_widgets`, `dashboard_today_glance` |
| GET | `/people/{id}/avatar?v=<key>` | image | session | the picture bytes |

**Sign-in payload** (from `loginPayload()` in upstream `server/auth.js`):

```json
{
  "user": { "id": 1, "username": "alex", "display_name": "Alex Johnson",
            "avatar_color": "#2563EB", "avatar_data": "/api/v1/people/1/avatar?v=…" ,
            "role": "admin", "family_role": "dad", "access_scope": "family" },
  "permissions": { "admin": true,
                   "modules": { "tasks": "write", "budget": "none" },
                   "widgets": { "budget": "allow" },
                   "capabilities": { "tasks_reorder_family": "allow" } },
  "householdSize": 5,
  "csrfToken": "64 hex chars"
}
```

`avatar_data` is either a relative URL (session readers, since upstream migration v251), a
`data:` URL, or `null`. Permissions are sparse: a module or widget missing from the map means full
access, matching upstream's fail-open `public/permissions.js`.

**Request bodies:** `POST /auth/login` `{ "username", "password" }`;
`POST /auth/2fa/verify` `{ "code" }` (a 6-digit TOTP or a recovery code).

**CSRF:** the auth routes above are mounted before upstream's CSRF middleware and need no token.
Every other `/api/v1` response to a session carries the current token in the `X-CSRF-Token`
response header; every state-changing request must send it back in the `X-CSRF-Token` request
header.

## Requirements

### Server address

- **SIGNIN-R1** The first screen asks for the server address. Input is trimmed; a missing scheme
  becomes `https://`; a trailing `/` and a trailing `/api/v1` are removed.
- **SIGNIN-R2** `http://` is accepted only for private-network hosts (`localhost`, `*.local`,
  `10/8`, `172.16/12`, `192.168/16`, `100.64/10`, IPv6 ULA) and shows a warning that the password
  travels unencrypted. Any other `http://` address is refused with a message. The network security
  config permits cleartext only in this case.
- **SIGNIN-R3** The app probes `GET /version` without credentials.
  - A JSON answer with `app_name` means it is a Yuvomi server; the next screen shows `app_name`.
  - `setup_required: true` shows "This server is not set up yet" with **Open in browser** to
    `<server>/setup`, and stops.
  - Anything else (no JSON, 404, TLS error, timeout after 15 s) shows a specific message for the
    failure: not reachable, certificate problem, not a Yuvomi server.
- **SIGNIN-R4** The last successful address is remembered and prefilled.

### Password and second factor

- **SIGNIN-R5** The sign-in screen has username and password fields, a show-password toggle, and
  a primary **Sign in** button. It names the server and shows "Unofficial Android client" in small
  text. Autofill hints are set so password managers work.
- **SIGNIN-R6** `POST /auth/login` outcomes:

  | Answer | Behaviour |
  |---|---|
  | 200 with `twoFactorRequired: true` | Go to the second-factor step. Keep the cookie: the pending state lives in the server session. |
  | 200 with `user` | Signed in (SIGNIN-R9). |
  | 401 | "Wrong username or password." Password field cleared, focus on it. |
  | 403 `reason: password_login_disabled` | "This household signs in with single sign-on, which this app does not support yet." with **Open in browser**. |
  | 403 `reason: account_cannot_sign_in` | "This account cannot sign in." |
  | 429 | "Too many attempts. Wait a minute and try again." Button disabled for 60 s. |
  | network failure | "Could not reach the server." with retry. |

- **SIGNIN-R7** The second-factor step has one field, as upstream's login page: it takes the
  6-digit code (numeric keyboard, one-time-code autofill), and also a recovery code when
  `recoveryAvailable` is true (then the keyboard is free text and the hint says so,
  `login.twoFactorHintRecovery`).
- **SIGNIN-R8** `POST /auth/2fa/verify` outcomes: 200 signs in. 401 with
  `"No pending sign-in."` returns to the password step with "The sign-in timed out. Enter your
  password again." Any other 401 says "Wrong code." When the answer has `recoveryRemaining` after a
  recovery code, the app shows how many remain.

### Session

- **SIGNIN-R9** After sign-in the app stores, per server: the session cookie (encrypted at rest,
  ADR 0004), the user, permissions, `householdSize`. The CSRF token is kept in memory only.
- **SIGNIN-R10** On every cold start with a stored session the app calls `GET /auth/me`, then
  `GET /preferences`, then `GET /version`. Until they answer, it shows the cached overview
  (ADR 0007).
  - `/auth/me` 401: the session ended on the server. Delete the cookie and cached data, go to
    sign-in with server and username prefilled, and say "You were signed out."
  - network failure: stay signed in, offline banner.
- **SIGNIN-R11** The CSRF token is refreshed from every response's `X-CSRF-Token` header and from
  `csrfToken` in `/auth/me`. A `403` with `reason: csrf_invalid` triggers one `GET /auth/me` and
  one retry of the request, as the PWA does; a second failure is shown as an error.
- **SIGNIN-R12** Any `401` on an authenticated call other than `/auth/login` and
  `/auth/2fa/verify` ends the session locally as in SIGNIN-R10.
- **SIGNIN-R13** Household context from `/preferences`: `timezone_effective` (IANA zone, used
  for every "today" computation), `disabled_modules` (modules switched off for the household,
  treated like `none` access). Overview fields are consumed by the overview spec.

### Version gate

- **SIGNIN-R14** With a session, `GET /version` returns `version`. Compare it with the supported
  range generated from `upstream/VERSION`:
  - below the minimum: blocking screen "This server runs Yuvomi X. This app needs Y or newer."
    with **Open in browser** and **Sign out**.
  - above the pin: once per server version, a dismissible notice "This server is newer than the
    version this app was tested with. Some things may not work."
  - unparsable: treated as above the pin.

### Sign-out, language, browser

- **SIGNIN-R15** **Sign out** (in the account sheet) calls `POST /auth/logout` with the CSRF
  header, then deletes cookie, cached rows and in-memory state for that server and user, whatever
  the server answered. Server address and username stay prefilled.
- **SIGNIN-R16** The account sheet (opened from the user's avatar in the top bar) shows the
  member's name and avatar, the server, the app version and "Unofficial Android client", a
  **Language** row, **Open web app**, and **Sign out**.
- **SIGNIN-R17** Language: "Automatic" (device locale, mapped as in ADR 0011) or one of the 27
  upstream locales, listed by their own name. Stored per device. Changing it applies at once.
- **SIGNIN-R18** Avatars: a relative `avatar_data` is loaded from `<server><avatar_data>` with
  the session cookie and cached by URL (the `v=` key changes when the picture does); a `data:`
  URL is decoded; `null` shows initials on `avatar_color`.

## Acceptance criteria

| ID | Given / When / Then | Level |
|---|---|---|
| SIGNIN-A1 | Given input `home.example.org/` when normalised then it is `https://home.example.org`. Also for `https://x/api/v1/` → `https://x`. | unit |
| SIGNIN-A2 | Given `http://192.168.1.5:3000` then it is accepted with a warning; given `http://example.org` then it is refused. | unit |
| SIGNIN-A3 | Given a server answering `/version` with `setup_required: true` then the setup message and the browser action appear and no sign-in form is shown. | ui |
| SIGNIN-A4 | Given the seeded server when `alex` / `demo1234` signs in then `user.username` is `alex`, `permissions.admin` is true, and `GET /auth/me` with the stored cookie succeeds. | contract |
| SIGNIN-A5 | Given a wrong password then the answer is 401 and the app shows the wrong-credentials message with the password cleared. | contract + ui |
| SIGNIN-A6 | Given an account with TOTP when the password is right then `twoFactorRequired` is true, and a correct code then signs in using the same cookie jar. | contract |
| SIGNIN-A7 | Given a signed-in session when any state-changing call is made then it carries the `X-CSRF-Token` header equal to the last token the server sent. | unit |
| SIGNIN-A8 | Given a stored session the server no longer knows when the app starts then it lands on sign-in with server and username prefilled and no cached data remains. | ui |
| SIGNIN-A9 | Given server version 2.82.0 and minimum 2.83.0 then the blocking screen appears; given 2.90.0 and pin 2.83.0 then the notice appears once. | unit |
| SIGNIN-A10 | Given signed in when Sign out is tapped while offline then local state is still cleared and the sign-in screen appears. | ui |
| SIGNIN-A11 | Given `leo` (child) signs in then permissions show `budget` as `none`, and the modules list in the app does not offer Budget. | contract + ui |
| SIGNIN-A12 | Given the language is set to Polish then a plural string with count 5 uses the `_many` variant. | unit |
| SIGNIN-A13 | Given a 403 `csrf_invalid` on a write then the app refreshes via `/auth/me` and retries once; the user sees no error if the retry succeeds. | unit |

## Strings

Most messages reuse upstream's `login.*` keys (`login.invalidCredentials`,
`login.tooManyAttempts`, `login.twoFactorInvalid`, `login.twoFactorExpired`,
`login.accountCannotSignIn`, ...). The app-only strings live in
`app/src/main/assets/i18n-android/en.json`; the original list was:

`android.signin.unofficial` "Unofficial Android client" · `android.signin.serverLabel` "Server
URL" · `android.signin.notYuvomi` "This doesn't look like a Yuvomi server." ·
`android.signin.unreachable` "Could not reach the server." · `android.signin.certificate`
"The server's certificate could not be verified." · `android.signin.cleartextWarning` "This
connection is not encrypted. Use it only on your home network." · `android.signin.cleartextRefused`
"Unencrypted connections are only allowed to addresses on your home network." ·
`android.signin.setupRequired` "This server is not set up yet. Finish the setup in your browser." ·
`android.signin.ssoUnsupported` "This household signs in with single sign-on, which this app does
not support yet." · `android.signin.signedOut` "You were signed out." ·
`android.signin.timedOut` "The sign-in timed out. Enter your password again." ·
`android.version.tooOld` "This server runs Yuvomi {{server}}. This app needs {{min}} or newer." ·
`android.version.newer` "This server is newer than the version this app was tested with. Some
things may not work." · `android.browser.open` "Open in browser" · `android.browser.openWebApp`
"Open web app" · `android.account.language` "Language" · `android.account.automatic` "Automatic"

Everything else reuses upstream keys from the `login`, `common` and `offline` namespaces, matched
to the PWA's login page during implementation.
