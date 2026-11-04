# 0023. `#archived`, a reserved tag for archiving

**Status:** Accepted, 2026-11-04. Replaces the phone-only tag archive and the "Archive" note
category of the previous release.

## Context

The owner wanted tags, notes and tasks to be archivable, and the archive to be seen by everyone
in the household, not only on one phone. Upstream has an archive for tasks and documents, but
none for tags, notes or shopping items.

## Decision

The tag `archived` is reserved. Archiving adds it, and restoring removes it:
- as the real tag `archived` on tasks;
- as the hashtag `#archived` at the end of the text of notes and shopping items.

Every client and the web app see it as an ordinary tag. The app recognises it everywhere
(`Archived`). A tag counts as archived when everything carrying it is completed or archived.
The page of `#archived` is the household's archive.

## Consequences

- **Trade-offs the owner accepted:**
  - `archived` can no longer be used as an ordinary tag.
  - The marker is visible text in notes and items.
  - Archiving a note edits it, so it needs the right to change the note.
  - The web app does not hide archived notes or tasks; it shows them with the tag.
- **Native archives stay as they are:** upstream's own task archive keeps working and counts as
  archived, but the app's Restore does not undo it.
- **Other modules:** items of modules the app cannot write (events, budget, ...) are not
  archived by **Archive tag**.
