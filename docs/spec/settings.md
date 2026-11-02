# Spec: Settings

Status: implemented · Upstream checked: 2.84.0 · Decisions: ADR 0003, 0005, 0006, 0007, 0011, 0015

## Purpose

The household's settings as native screens: the same tree of domains, sheets and sections as
the web app (`public/settings/registry.js`), with every section a member uses on a phone
native, and the rest one tap away in the browser.

## Scope

| Area | Native | Requirement IDs |
|---|---|---|
| Tree | domains, sheets, sections, visibility, search, one route per sheet | R1–R6 |
| Account | profile, picture, colour, contact details, password, two-factor, recovery codes, other sessions, SSO unlink, sign out | R10–R17 |
| Appearance and region | display language (device), data language, region, currency, time zone, date and time format | R20–R25 |
| Weather | my own weather place with place search | R26, R27 |
| Modules | household switches (incl. kitchen children, installed modules), my navigation order, visibility and phone bar | R30–R33 |
| Module options | tasks, calendar, health switches, rewards, countdowns, budget, housekeeping, schedule templates | R40–R48 |
| Family | members (add, edit, delete, order), invitations, household second factor | R50–R57 |
| Permissions | role profiles and member exceptions for modules, widgets, capabilities | R60–R63 |
| Admin | notification channels, SMTP, backup (status, run now, download, WebDAV), system, API tokens | R70–R78 |

**Left in the browser** (a card names the section and opens `<sheet path>?section=<id>`, ADR 0006):
`personal-device` and `device-display` (PWA install, wall mode and screensaver are browser-local),
web push in `personal-notifications` (ADR 0005), `admin-displays` (pairing), `admin-immich`,
`admin-weather` (provider and keys), `modules-install` (ZIP/GitHub upload), the calendar sheet's
subscriptions, feeds, birthday feed and household calendar options, `sync-calendar` (OAuth),
`personal-schedule`, `feed-*`, `sync-reminders`, `modules-kitchen`, `sync-contacts`,
`documents-storage`, `documents-dms`, `modules-health`, the visibility defaults of
`personal-health`, the health caregivers in the member editor, single sign-on linking (OAuth),
and restoring a backup (needs a raw upload). The theme is not a setting: the app follows the
device's light or dark mode, as the web app's "System".

## Upstream references

`public/settings/registry.js`, `module-order.js`, `region-presets.js`, `currency.js`,
`weather-location.js`; `public/settings/pages/personal-account.js`, `personal-appearance.js`,
`personal-weather.js`, `personal-tasks.js`, `personal-calendar.js`, `personal-health.js`,
`modules-active.js`, `modules-navigation.js`, `modules-options.js`, `modules-rewards.js`,
`modules-countdowns.js`, `admin-family.js`, `admin-permissions.js`, `notifications.js`,
`admin-email.js`, `admin-backup.js`, `admin-system.js`, `admin-api.js`; `public/utils/friendly-error.js`.
Server: `server/auth.js`, `server/routes/preferences.js`, `family.js`, `modules.js`,
`permissions.js`, `notifications.js`, `email.js`, `backup.js`, `weather.js`, `tasks.js`.

## Upstream operations

All 55 are in `SettingsOperation` and `upstream/operations-used.d/settings.txt`. Besides those,
Settings uses core `GET /auth/me`, `GET /preferences`, `GET /version`.
Envelopes: `data` except `/auth/me/profile`, `/auth/users` writes (`{ user }`), `/auth/oidc/*`,
`/auth/2fa/overview` (`{ data, required }`), `/auth/api-tokens` (`{ data, subjects }`, create
adds a top-level `token`), `/auth/logout-others` (`{ ok, ended }`), `DELETE /auth/users/{id}`
(`{ ok, outcome }`).

Not under `/settings` but only called by settings sheets (other builders may also declare them):
`/tasks/sync-targets`, `/tasks/points/affected`, `/tasks/points/rebase`, `/calendar/sync-targets`,
`/weather/geocode`, `/family/members/reorder`, `/auth/*`, `/preferences` (PUT),
`/notifications/*`, `/email/*`, `/backup/*`, `/permissions/*`, `/modules*`.

## Requirements

### Tree

- **SET-R1** Settings sits under "More". Its root lists the domains Account, Household
  (admins), Modules, each with its visible sheets (`filterSettingsDomains`,
  `settingsSheetsForDomain`): general sheets, module sheets in navigation order
  (`sortNavigationItems` with `module_order`), then "Add module". ✅
- **SET-R2** A section is visible when not admin-only or the member is an admin; a sheet when
  one of its sections is, and for module sheets when the module's access is not `none`
  (`sheetModuleAccessible`; admins and the kitchen always pass). ✅
- **SET-R3** Search over sheets and sections, case and accent blind, label-start hits first
  (`searchSettings`, `orderSettingsHits`; the per-option index is not ported). ✅
- **SET-R4** Routes: `settings` and `settings/<leafId>`; `SettingsRoutes.leaf(id)` for other
  modules. A sheet the member may not see shows `settings.adminOnly`. ✅
- **SET-R5** A sheet lists its sections "for me" before "for the household", with the scope
  headings on module sheets that have both (`settingsSheetSections`). ✅
- **SET-R6** Preferences load cache-then-network; offline, the cached copy shows with the
  offline banner and every control is disabled (ADR 0007). Writes never queue. Every
  `PUT /preferences` answer replaces the sheet's copy and the shell's (`refreshPreferences`). ✅

### Account (`personal-account.js`)

- **SET-R10** Profile: name (required, ≤128), colour from the ten family tones (`USER_COLORS`),
  username read-only, phone, e-mail (`isEmailAddress`), birth date with "year unknown";
  `PATCH /auth/me/profile` with the web body; `email_in_use` at the field. ✅
- **SET-R11** Picture: picked from the photo picker, centred, 256 px JPEG data URL like
  `avatar-crop.js`; remove; "Use my Gravatar" only when `gravatarAvailable` and an address is
  saved, with its hint and reason sentences. ✅
- **SET-R12** Password (hidden for `sso_only`): `passwordProblems`; `wrong_password` at the
  current-password field. ✅
- **SET-R13** Two-factor: status; setup shows the secret as text in groups of four, plus an
  "open in authenticator" link for the `otpauth://` URI, then the code; recovery codes shown
  once with copy and share; new codes and turning off ask for a code; turning off is hidden
  while the household requires it. Reasons via `twoFactorErrorText`. ✅
- **SET-R14** The household requirement shows its hint. ✅
- **SET-R15** "Sign out on other devices" with confirmation and the ended count. ✅
- **SET-R16** SSO: unlink natively when `can_unlink`; linking opens the browser. ✅
- **SET-R17** Sign out with confirmation. ✅

### Appearance and region (`personal-appearance.js`)

- **SET-R20** Display language: this device's (`SettingsStore.language`, shared with the
  account sheet). ✅
- **SET-R21** Data language (admins): automatic (naming `language_auto`) or a locale. ✅
- **SET-R22** Region (admins): presets sorted by name plus custom; a preset writes currency,
  date and time format and region together (`REGION_PRESETS`, `resolveRegion`). ✅
- **SET-R23** Currency (admins, `CURRENCY_CODES`); it never writes the region (#934). ✅
- **SET-R24** Time zone (admins): automatic (naming the effective zone), UTC, IANA zones;
  members read it. ✅
- **SET-R25** Date and time format: for members always, for admins with region "custom". ✅
- **SET-R26** My weather: place search (`/weather/geocode`, ≥2 letters, reasons), units,
  coordinates and city by hand, `weather_user` patch; "use the household's" resets it. ✅
- **SET-R27** Admins get the way to the household weather (browser). ✅

### Modules

- **SET-R30** Active modules (admins): built-ins by navigation group, the kitchen with its four
  children (the kitchen switch sets all four), installed modules via `PATCH /modules/{id}`; each
  switch writes the whole `disabled_modules`. ✅
- **SET-R31** Errors roll back the switch and say `errorSentence`. ✅
- **SET-R32** My navigation: three phone-bar slots (`resolveMobileNavOrder`), saved as
  `mobile_nav_order`. ✅
  Each slot also offers **Tags**, the app's own module. Upstream's `mobile_nav_order` accepts
  only its own modules and silently drops `tags`, so the slot showing Tags is kept on the phone
  (`SettingsStore.tagsBarSlot`). The module saved in that slot stays saved for the web app and
  moves to More in the app.
- **SET-R33** Sidebar order: up/down within a group writes the expanded `module_order`
  (`buildOrderPayload`); show/hide writes `hidden_modules` (kitchen children one by one). ✅

### Module options

- **SET-R40** Task defaults: reminder list (CalDAV lists, local, unavailable), visibility, lock. ✅
- **SET-R41** Calendar defaults: assign me, target calendar (Google, CalDAV), default reminders
  (at most five, debounced save). ✅
- **SET-R42** Health: my cycle switch (disabled when the household's is off), caregiver
  notifications; visibility defaults in the browser. ✅
- **SET-R43** Rewards: approval switch; default points 0–10000 and, when the old value was
  above zero and open tasks carry it, the offer to move them along (`offerRebase`). ✅
- **SET-R44** Countdowns: grace days 0–90. ✅
- **SET-R45–R48** Budget mode, cycle for the household, housekeeping payment tasks, subtasks
  expanded, schedule templates: switches as `modules-options.js`. ✅

### Family (`admin-family.js`)

- **SET-R50** Members: avatar, name ("me"), @username, family role or housekeeper, admin and
  former badges, phone and e-mail; former members last. ✅
- **SET-R51** Add and edit: username (3–64), name, colour, family role (child hint), contact
  details, birth date, SSO-only (when SSO is configured), password (required on create unless
  SSO-only; optional reset on edit), system admin. Create carries an idempotency key. ✅
- **SET-R52** Remove with the web's confirmation; the answer's `outcome` picks "deleted" or
  "deactivated". ✅
- **SET-R53** Order: move up and down among active household members (two or more), written as
  a whole (`PATCH /family/members/reorder`); a failure says `quickLinks.orderError`. ✅
- **SET-R54** Invitations: list with expiry, revoke with confirmation; create with name, role,
  starting permissions, username, e-mail, send by e-mail, admin; the one-time link
  (`<server>/join?token=…`) with copy. ✅
- **SET-R55** Household second factor: who has one; "required for everyone". ✅
- **SET-R56** Caregivers of a member (Health) stay in the browser. ⏭

### Permissions (`admin-permissions.js`)

- **SET-R60** By role or by member; admins show the "always full access" hint. ✅
- **SET-R61** Per module: none/read/write (members also "as the role", naming the inherited
  value); its widgets (locked while the module is none) and capabilities; general widgets. ✅
- **SET-R62** Every choice saves the subject's whole sparse set (`payloadOf`), queued; failure
  rolls back. ✅
- **SET-R63** Reset the subject with confirmation. ✅

### Admin

- **SET-R70** Notification channels (admins): list with provider, last error, enabled switch;
  add/edit per provider (`readChannelForm`: empty secrets keep the stored ones); test; delete.
  Members read the admin-only hint. ✅
- **SET-R71** SMTP: fields, environment-locked fields read-only, `emailConfigProblems`,
  reasons at their fields; test saves first and names the failure case. ✅
- **SET-R72** Backup: download through the system file picker (`GET /backup/database`), run
  now, scheduler status; restore in the browser. ✅
- **SET-R73** WebDAV: enabled, URL, user, password (kept when empty), path, keep; test, upload
  now, save. ✅
- **SET-R74** System: application name (save, reset), version, licence, setup state, app
  versions. ✅
- **SET-R75** API tokens: active and inactive lists, revoke, remove; create with name,
  subject, validity date, full or per-module scopes; the token shown once with copy. ✅
- **SET-R78** Errors map through `SettingsErrors` (`knownKey` of `friendly-error.js`); the
  server's English text is never shown, except a channel's or WebDAV's recorded `lastError`,
  which the web app shows too. ✅

## Acceptance criteria

| ID | Criterion | Test | Status |
|---|---|---|---|
| SET-A1–A12 | Registry visibility and order, search, module order and rows, account, region, option, family, permission, channel and admin rules | `SettingsLogicTest` | ✅ |
| SET-A13 | Admin sees Household; a member does not | `SettingsScreenTest` | ✅ |
| SET-A14 | A date format choice is one `PUT /preferences` with that key | `SettingsScreenTest` | ✅ |
| SET-A15 | Profile body as the web's; wrong password at its field | `SettingsScreenTest` | ✅ |
| SET-A16 | A module switch writes `disabled_modules` | `SettingsScreenTest` | ✅ |
| SET-A17 | A browser-only section names itself and opens the browser | `SettingsScreenTest` | ✅ |
| SET-A20–A40 | Every operation against the real server | `SettingsContractTest` | CI |
