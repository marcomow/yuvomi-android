# Spec: Birthdays

Status: implemented · Upstream checked: 2.87.0 · Decisions: ADR 0003, 0006, 0007, 0011, 0015

Code: `app/src/main/java/io/github/marcomow/yuvomi/feature/birthdays/` (`BirthdaysRules` is the pure
port, with `BirthdaysFormat` for the words ICU makes; `BirthdaysViewModel` the list, delete,
linking, import and feed; `BirthdayEditorViewModel` the editor; `BirthdaysScreen`,
`BirthdaysSheets`, `BirthdayEditorScreen` the UI; `BirthdaysTagSource` the Tags view's source).
Routes: `birthdays?open={open}` (the list, also `BirthdaysRoutes.START`; `BirthdaysRoutes.birthday(id)`
opens one birthday's sheet, as upstream's `/birthdays?open=<id>`), `birthdays/edit?id={id}` (editor).

## Purpose

The household's birthdays as a native screen: who is next and how old they turn, adding and
editing birthdays (with or without a year, name day, picture, notes, reminder), linking a birthday
to its contact, importing birthdays from contacts, and the member's revocable ICS feed of all
birthdays.

## Scope

Everything `public/pages/birthdays.js` lets a member do, plus the birthday feed of
`public/settings/pages/personal-feeds.js` (part `birthdays`, settings leaf `feed-birthdays`), which
the native Settings leaves in the browser. Nothing stays in the browser. Not here: the nav badge
(the app has none), the dashboard tile (Overview), the desktop split view (the phone layout is the
list with a sheet), cropping a picture by hand (the app crops square from the centre).

The module's right is `calendar` (ModuleCatalog; `readOnly()` in birthdays.js). Import and the
contact link also need Contacts to be visible (`moduleAccess('contacts')`).

## Upstream references

- `public/pages/birthdays.js` (`sortByProximity`, `filteredBirthdays`, `birthdayListHtml`,
  `birthdayItemHtml`, `ageMeta`, `countdownChip`, `photoAvatar`, `storedReminderOffset`,
  `birthdayReminderOptions`, `unlistedReminderLabel`, `reminderToSave`, `reminderReadText`,
  `normalizeNameDaySelection`, `openBirthdayModal`, `fillContactLinkOptions`,
  `linkBirthdayToContact`, `contactSuggestionRow`, `openImportModal`, `importCandidateRowHtml`,
  `deleteBirthday`, `birthdayPaneSections`, `birthdayDetailHead`, `openBirthdaySheet`,
  `openBirthdayFromQueryNarrow`, `emptyStateHtml`, `importActionHtml`).
- `public/utils/date-format.js` (`dateText`, `daysFromTodayText`), `public/utils/birth-date.js`,
  `public/utils/friendly-error.js`, `public/settings/pages/personal-feeds.js`.
- `server/routes/birthdays.js`, `server/routes/birthday-feed.js`, `server/services/birthdays.js`
  (`hydrateBirthday`, `listBirthdayImportCandidates`, `importBirthdaysFromContacts`).

## Upstream operations

| Method | Path | Envelope | Used for |
|---|---|---|---|
| GET | `/birthdays` | `data`, hydrated (`next_birthday`, `next_age`, `days_until`, `next_name_day`, `name_day_days_until`, `contact_name`, `suggested_contact_id`) | the list |
| POST | `/birthdays` | `data`, 201; 400 validation; 409 `birthdays_contact_linked` | add |
| PUT | `/birthdays/{id}` | `data`; partial; 404 `birthdays_birthday_not_found`; 409 `birthdays_contact_linked`; 400 `birthdays_selected_contact_not_found` | edit, link |
| DELETE | `/birthdays/{id}` | 204; 404 `birthdays_birthday_not_found` | delete |
| GET | `/birthdays/import/candidates` | `data: { withBirthday, withoutBirthday }`; 403 without contacts | import list, link field |
| POST | `/birthdays/import` | `data: { imported, skipped }`, 201; at most 500 ids | import |
| GET | `/birthdays/feed` | `data: { token, url }` or `data: null` | feed state |
| POST | `/birthdays/feed/regenerate` | `data: { token, url }` | turn on, new address |
| DELETE | `/birthdays/feed` | `data: { token: null }` | turn off |

## Requirements

Status: all requirements below are built. Where the app knowingly differs from the web page, the
requirement says so ("Differs:").

### List

- **BDAY-R1** Top bar `birthdays.title` with a menu: "Import from contacts" (writable and Contacts
  visible) and "Birthday calendar export" (the feed, BDAY-R15). FAB `birthdays.addButton` for
  members with `calendar: write`. A search field (`birthdays.searchPlaceholder`) over name and
  notes.
- **BDAY-R2** On open the cached answer shows at once, then the list loads (ADR 0007); offline the
  cache shows with `OfflineBanner` and no writing. A load error without cache says
  `android.birthdays.loadError` with retry. Pull to refresh; back after 60 s reloads.
- **BDAY-R3** Order by `days_until`, then name (`sortByProximity`). Month heads ("November 2026")
  only when on average two birthdays share a month (`birthdayListHtml`).
- **BDAY-R4** A row (`birthdayItemHtml`): the avatar (`photoAvatar`: the entry's picture, else the
  member's picture or colour, else a neutral disc with initials), the name with a cake on the day,
  the countdown chip (`daysFromTodayText`: Today, Tomorrow, days to 30, weeks to 60, months to 364,
  then years; today and within a week in the module's tone, the next one highlighted), the date
  with the weekday within two weeks and "turns N" (no age without a year), the next name day, and
  the notes on one line.
- **BDAY-R5** Empty states: a search without hits says `common.searchNoResultsTitle` with "Reset
  search"; an empty list says `birthdays.emptyTitle`, with description and "Add birthday" only for
  writers (#1348).
- **BDAY-R6** Writers have a row menu with Edit and Delete. Differs: no swipe gesture; the row menu
  carries both actions, as on pointer devices.
- **BDAY-R7** Delete (`deleteBirthday`): the row goes at once, "Undo" in the snackbar brings it
  back, the `DELETE` goes out when the snackbar ends; a birthday already gone counts as deleted.
- **BDAY-R8** A tap opens the read sheet (`openBirthdaySheet`): picture, name, "Next birthday on
  …", turns N and the countdown, then birth date (with "Show contact" when linked and Contacts is
  visible), the quiet link hint, name day with its countdown, notes and reminder; writers get
  Delete and Edit. Read-only members get the same sheet without actions. `?open=<id>` opens it.
- **BDAY-R9** "Show contact" opens Contacts. Differs: it opens the Contacts list, not the contact
  (no `ContactsRoutes` helper for one contact yet).
- **BDAY-R10** The quiet hint (`contactSuggestionRow`): an unlinked birthday whose name matches one
  unlinked contact (`suggested_contact_id`) offers "Link", which sends `{ contact_id }` and says
  `birthdays.linkedToast`.

### Editor

- **BDAY-R11** Fields (`openBirthdayModal`): picture (pick, centre-cropped square, sent as a JPEG
  data URL; "Remove picture"), name (required), birth date (date picker up to today) or, with
  "Year unknown", month and day (sent with the placeholder year 2000), link to contact (BDAY-R12),
  name day (month, day, clear; both or neither), notes, reminder (presets, a stored value no preset
  knows named by its effect, or custom amount 1 to 999 and unit). Differs: the fields stand open,
  without the web page's "More details" fold.
- **BDAY-R12** The link field (`fillContactLinkOptions`): "None", every contact without a birthday
  and the linked one, by name; not for a member's birthday. The link goes out only when it
  changed; after `birthdays_contact_linked` or `birthdays_selected_contact_not_found` the choice
  reloads.
- **BDAY-R13** Saving (`#bd-save`): checks with the page's sentences (`common.nameRequired`,
  `common.required`, `birthdays.yearlessIncomplete`, `birthdays.nameDayIncomplete`,
  `birthdays.reminderAmountInvalid`); a new birthday writes the reminder it shows (one day before),
  an edited one only when it changed (#1363). Body `{ name, birth_date, year_unknown, name_day,
  notes, photo_data, contact_id?, reminder_* }`; create with an idempotency key. A birthday gone
  meanwhile closes the editor and leaves the list.

### Import and feed

- **BDAY-R14** Import (`openImportModal`): contacts with a birthday, each either already added,
  "Link to X?" with "Link" (`suggested_birthday_id`), or a checkbox; "Select all"; contacts without a
  birthday in a fold; "Add N" sends the ids in batches of 500 and says `birthdays.importSuccess`;
  nothing chosen says `birthdays.importNothingSelected`.
- **BDAY-R15** Feed (`personal-feeds.js`): a switch "Birthday calendar export" with its
  description. On: the address, the hint, "Copy address", "Subscribe in calendar app" (`webcal://`)
  and "Generate new link".
- **BDAY-R16** Turning off and a new link ask first (`settings.feedExport*Confirm` with detail).
- **BDAY-R17** Errors are the app's sentences (`errorSentence`): the `birthdays_*` cases,
  `contacts.errorContactGone`, refusals, else the general sentence.
- **BDAY-R18** Tags view (`BirthdaysTagSource`): every birthday whose name or notes carry a
  `#hashtag`; it opens the birthday's sheet.

## Acceptance criteria

| ID | Criterion | Test |
|---|---|---|
| BDAY-A1 | Order, search and month heads follow the page | `BirthdaysLogicTest` |
| BDAY-A2 | The countdown reads Today, Tomorrow, days, weeks, months, years | `BirthdaysLogicTest` |
| BDAY-A3 | The reminder follows the server and keeps values no preset knows | `BirthdaysLogicTest` |
| BDAY-A4 | Errors map to the app's sentences | `BirthdaysLogicTest` |
| BDAY-A5 | The list shows countdown, age and name day; the sheet links the same-named contact | `BirthdaysScreenTest` |
| BDAY-A6 | With `calendar: read` there is no add, no row menu, and the sheet only reads | `BirthdaysScreenTest` |
| BDAY-A7 | Delete goes out after the snackbar; undo keeps the birthday | `BirthdaysScreenTest` |
| BDAY-A8 | A new birthday sends the page's payload with an idempotency key | `BirthdaysScreenTest` |
| BDAY-A9 | An edit without reminder change leaves it out; a yearless date uses the year 2000 | `BirthdaysScreenTest` |
| BDAY-A10 | The import sends the chosen contacts | `BirthdaysScreenTest` |
| BDAY-A11 | The feed turns on with an address and off again | `BirthdaysScreenTest` |
| BDAY-A12 | Birthdays with hashtags become tagged items | `BirthdaysLogicTest` |
| BDAY-C1–C5 | Every operation against the real server | `BirthdaysContractTest` |
