# Spec: Contacts

Status: implemented · Upstream checked: 2.84.0 · Decisions: ADR 0003, 0006, 0007, 0011, 0015

Code: `app/src/main/java/io/github/marcomow/yuvomi/feature/contacts/` (`ContactsRules`, `VCard` and
`BirthField` are the pure ports; `ContactsViewModel` the list, `ContactDetailViewModel` the read
view, `ContactEditorViewModel` the form, `ContactCategoriesViewModel` the manager; `ContactsBus`
carries changes and pending deletes between them). Routes: `contacts` (list, `ContactsRoutes.START`),
`contacts/{id}` (read view, `ContactsRoutes.contact(id)`), `contacts-editor?id={id}&category={category}`
(form), `contacts-categories` (manager).

## Purpose

The household's important contacts as a native screen: grouped by category, searchable, with
call, e-mail and maps straight from the row and the read view, adding, editing and deleting,
categories with their tones, the way into Birthdays, and vCard import and export.

## Scope

Everything `public/pages/contacts.js` lets a member do. Nothing of the page stays in the browser.
Not part of the page and therefore not here: CardDAV sync (`/contacts/cardav/*`, Settings owns
it), the Birthdays import list (`/birthdays/import/candidates`, Birthdays owns it). The web
page's keyboard shortcuts and its list-and-detail split view have no phone counterpart.

There are no favourites in upstream's contacts (no field, route or UI at 2.83.0), so none here.

## Upstream references

- `public/pages/contacts.js` (`filterContacts`, `filterCategoryKeys`, `drawList`, `renderContactItem`,
  `runContactMenuAction`, `renderContactDetail`, `openContactDetail`, `contactCardEl`,
  `buildContactForm`, `emailsLockedFor`, `deleteContact`, `deleteSelected`, `toggleSelectAll`,
  `openImportSelectionModal`, `importParsedContacts`, `showImportResult`, `nameVariants`,
  `resolveVCardCategory`, `birthdayRowAction`, `birthdaySuggestionRow`, `linkContactToBirthday`,
  `addContactToBirthdays`, `openContactCategoryManager`, `contactsEmptyStateHtml`).
- `public/utils/contact-name.js`, `vcard.js`, `birth-date.js`, `phone.js` (`countryFromRegion`),
  `friendly-error.js`, `ux.js` (`scheduleUndoableDelete`); `public/components/category-manager.js`.
- `server/routes/contacts.js`, `server/routes/birthdays.js` (`PUT /:id`, `POST /import`).

## Upstream operations

| Method | Path | Envelope | Used for |
|---|---|---|---|
| GET | `/contacts` | `data` | the list (also the Tags view) |
| POST | `/contacts` | `data`, 201 | the form's "Add", each imported card |
| GET | `/contacts/meta` | `data: { categories, categoryColors }` | the tones a category may take |
| GET | `/contacts/categories` | `data` | categories for list, form and manager |
| POST | `/contacts/categories` | `data`, 201; 409 `category_exists` | add a category |
| PATCH | `/contacts/categories/reorder` | `data` (all) | move up or down |
| PUT | `/contacts/categories/{key}` | `data`; 409 `category_exists`; 404 | rename (`{ name }`) or recolour (`{ color }` only) |
| DELETE | `/contacts/categories/{key}` | 204; 409 `category_in_use`, `category_last` | delete |
| GET | `/contacts/{id}` | `data` with `phones`, `emails`, `addresses`, birthday link and suggestion | read view, form prefill |
| PUT | `/contacts/{id}` | `data`; 404 `contacts_contact_not_found`; 403 `contact_email_protected`; 409 `email_in_use` | the form's "Save" |
| DELETE | `/contacts/{id}` | 204; 403 `family_member_contact` | delete |
| GET | `/contacts/{id}/vcard` | `text/vcard` file | export |
| PUT | `/birthdays/{id}` | `data` | "Link" of the birthday suggestion (`{ contact_id }`) |
| POST | `/birthdays/import` | `data`, 201 | "Add to birthdays" (`{ contact_ids: [id] }`) |

The last two are Birthdays' routes (permission `calendar`); they sit in `ContactsOperation` until
the integrator moves them. `GET /preferences` (core) gives the household region for phone numbers.

## Requirements

### List

- **CONT-R1** The list loads contacts, categories, the category tones and the region together, and
  sorts by category position, then by last name (or the display name) in the UI locale (`render`,
  `byName`, `contactSortKey`).
- **CONT-R2** Contacts are grouped under their category's label, in the categories' order; a
  category's tone colours its header (`drawList`, `catTintStyle`). Seeded categories are labelled
  through `label_key`, own ones by name, an unknown key as it is (`catLabel`).
- **CONT-R3** Search matches name, phone or e-mail; a chip row offers "All" and every category in
  use (the active one kept), and none when at most one category is in use (`filterContacts`,
  `filterCategoryKeys`). An empty search names its term and category with one "Reset search".
- **CONT-R4** A row shows the person: a household member in their colour and picture, everyone
  else as initials (`contactAvatar`), the name, and phone · e-mail. It has a call button when there
  is a phone, and a menu with e-mail, maps, export and (not for members, only with write) delete
  (`renderContactItem`).
- **CONT-R5** The empty list says "No contacts yet"; with write it adds the description, the hint
  and "Add contact" (`contactsEmptyStateHtml`).

### Read view and actions

- **CONT-R6** Call opens the dialler (`ACTION_DIAL`, E.164 where the platform can format it, else
  the dialable characters), e-mail opens `ACTION_SENDTO mailto:`, maps opens a `geo:` search or,
  without a maps app, the web app's OpenStreetMap search; a website opens in the browser.
- **CONT-R7** The read view loads the full contact and shows a card (avatar, organisation · job
  title, call / e-mail / maps for the first of each), then phones and e-mails with their labels
  ("other" hidden), addresses (structured ones formatted by `formatAddress`), birthday, website,
  nickname, category and notes (`renderContactDetail`, `contactCardEl`). Edit, export and (not for
  members) delete are in its bar; read-only members get export only.
- **CONT-R8** The birthday row shows the contact's own date, else the linked birthday's; a date
  without a year is written as day and month (`birthDateText`).
- **CONT-R9** A linked birthday offers "Show in birthdays" when Birthdays is visible; a contact with
  a birthday, no link and no suggestion offers "Add to birthdays" when the member may write
  `calendar`, which imports it and reloads the contact (`birthdayRowAction`, `addContactToBirthdays`).
- **CONT-R10** An unlinked contact with a suggested birthday shows the quiet hint ("same name", or
  "Link to {{name}}?" for the same date) with "Link" (`birthdaySuggestionRow`, `linkContactToBirthday`).

### Writing

- **CONT-R11** Delete (row menu, read view, form) hides the contact at once and sends `DELETE` when
  the snackbar goes without "Undo"; an already deleted contact counts as deleted, another failure
  brings it back with its sentence (`deleteContact`, `deleteOnServer`).
- **CONT-R12** "Select" (tools menu) turns rows into checkboxes; members cannot be chosen; "Select
  all" toggles every visible non-member; "Delete" asks once, then deletes all with one undo
  (`enterSelectMode`, `toggleSelectAll`, `deleteSelected`).
- **CONT-R13** Offline (cached list) shows the banner and no writing affordance; a write that
  still starts says `common.errorOfflineMutation`.
- **CONT-R14** "Import vCard" opens the system file picker, parses every card like `vcard.js`
  (unfolding, quoted-printable, structured `N`, `BDAY` with and without year, `CATEGORIES`
  resolved to a category), skips cards without a name with a note, preselects all but those whose
  name already exists in either order (`nameVariants`), and creates the chosen ones one by one. The
  result names the count, birthdays and failures, with "Retry" for failures or "Go to Birthdays"
  (`showImportResult`).
- **CONT-R15** Export saves the server's vCard through the system "save as" dialog.
- **CONT-R16** "Manage categories" adds, renames, recolours (from `categoryColors`, or none),
  moves up or down and deletes categories, with the manager's sentences for in use, last and
  duplicate (`category-manager.js` with `colors`). A colour change sends only `color`, so seeded
  names stay translated.
- **CONT-R17** The form has first and last name (one required: `contacts.nameRequiredHint`),
  category (the active filter, else "misc", else the first), phone and e-mail rows with labels,
  and under "More details" address, birthday (date, or month and day with "Year unknown") and
  notes. It sends the web app's body: `name`, `category`, the first phone and e-mail, `phones` and
  `emails` with `other` for a blank label and the first as primary, `birthday` and
  `birthday_year_unknown`, and `firstName`/`lastName` unless an unstructured name was left
  untouched. An orphaned category is not sent. A member's e-mails are read-only and not sent unless
  the member or an admin edits (`emailsLockedFor`). Creating sends an `Idempotency-Key`.
- **CONT-R18** A save that finds the contact gone closes the form and reloads the list; a deleted
  category reloads the categories and keeps the form open; `email_in_use` says `common.emailInUse`.

### Errors, permissions, tags

- **CONT-R19** `contacts: read` keeps list and read view without FAB, tools, edit and delete.
- **CONT-R20** Errors use `friendly-error.js`'s sentences (`CASE_MESSAGES`, `REFUSAL_MESSAGES`);
  the limit sentences name the pinned server's limits, because the client keeps no `params`.
- **CONT-R21** `ContactsTagSource`: a `#hashtag` in a contact's name or notes is a tag; each tagged
  contact opens its read view.

## Acceptance criteria

| ID | Given / When / Then | Level | Status |
|---|---|---|---|
| CONT-A1 | Given the seeded server then list, categories (seeded `label_key`, own names) and the seven tones decode. | contract | written, runs in CI (`ContactsContractTest`) |
| CONT-A2 | Given a new contact then create, full read, update with the form's body and the vCard answer as expected; deleted, all four are a 404 (`contacts_contact_not_found` where the route says so). | contract | written, runs in CI |
| CONT-A3 | Given a deleted category, a too long name or an e-mail without `@` then the 400 carries its reason. | contract | written, runs in CI |
| CONT-A4 | Given a new category then it renames, recolours (a seeded one keeps `label_key`), reorders, refuses a duplicate and deletion while used, and deletes. | contract | written, runs in CI |
| CONT-A5 | Given a contact with a birthday then the import links one; a birthday with the same name is suggested and links. | contract | written, runs in CI |
| CONT-A6 | Given names then compose, split and sort key match `contact-name.js`; order, groups, search and chips match the page. | unit | passing (`ContactsLogicTest`) |
| CONT-A7 | Given vCards (structured N, QP soft breaks, Apple's omitted year, `--MMDD`, a bare year) then they parse like `vcard.js`; existing names are found in either order. | unit | passing |
| CONT-A8 | Given drafts then the form's body, the e-mail lock, the birthday field, the birthday actions and the error sentences match the page. | unit | passing |
| CONT-A9 | Given contacts with hashtags then the tag source lists them with their read-view route. | unit | passing |
| CONT-A10 | Given the list on screen then groups, chips and search work, the read view shows labelled numbers and notes, and "Add to birthdays" posts the import. | ui | passing (`ContactsScreenTest`) |
| CONT-A11 | Given `contacts: read` then there is no FAB, no tools menu and no delete. | ui | passing |
| CONT-A12 | Given a delete from the row menu when undo is used then no `DELETE` goes out; without undo one goes out. | ui | passing |
| CONT-A13 | Given a member's contact then select-all skips it and its menu has no delete. | ui | passing |
| CONT-A14 | Given the form then create sends the web app's payload with an `Idempotency-Key`. | ui | passing |

## Strings

Upstream keys from `contacts`, `category`, `birthdays`, `common`, `modal`, `datepicker`,
`emptyHint` and `nav`. App-only, in `app/src/main/assets/i18n-android/contacts/en.json`:
`android.contacts.exported`, `android.contacts.noApp`.

## Differences from the web app

- Phone numbers are shown and dialled with Android's `PhoneNumberUtils` instead of
  libphonenumber-js; the form's as-you-type preview and the "looks incomplete" warning are not shown.
- Dates of birth use the locale's medium date, not the household's date-format preference.
