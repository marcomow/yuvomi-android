# 0017. Every upstream module native, and a cross-module Tags view

**Status:** Accepted, 2026-11-02. Completes 0016.

## Context

0016 shipped seven modules and left Rewards, Birthdays, Contacts, Inventory, Housekeeping and
Waste in `Features.pending`. The owner also asked for a view that gathers everything sharing a
tag, not only tasks. Upstream 2.83.0 stores tags on tasks (editable) and shopping items
(mirrored from CalDAV, read-only) only.

## Decision

- **All modules native.** The six pending modules join `Features.all`; `Features.pending`
  stays as an empty list for future work in progress. "More" lists only native modules now,
  plus third-party extension modules, which still open in the browser.
- **Tags view** (`feature/tags`, docs/spec/tags.md), an app-only module with a ModuleCatalog
  entry of its own:
  - Real tags come from tasks and shopping items. In every other module, a `#hashtag` in an
    item's own text counts as a tag (`Hashtags`), compared with upstream's `tagKey` (NFC,
    lower case), so "Garten", "#garten" and a CalDAV "GARTEN" are one tag.
  - Each module contributes a `TagSource` from its own package (or from `feature/tags/sources`
    for modules built before this ADR); `TagSources.all` lists them. A source reads the same
    endpoints and permissions as its module and returns only what the member may see.
  - Renaming and deleting a tag go through upstream's task tag endpoints and therefore change
    task tags only; the dialogs say so. Hashtags in text are the member's own words and are
    never rewritten.
- **No upstream change is needed.** The web app shows hashtags as plain text and its search
  finds them.

## Consequences

- The Tags view loads every module's list on open; sources run in parallel and a failed one
  is named with a retry, and the last result is cached for offline.
- If upstream adds tags to more modules, their sources switch from hashtags to the real field.
