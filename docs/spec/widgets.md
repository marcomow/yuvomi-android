# Home-screen widgets

Android only; upstream has no counterpart (the PWA cannot place widgets). ADR 0020.

- **WIDGETS-R1** Two widgets: **Tasks** and **Shopping**. Both are Glance app widgets that
  resize freely and refresh every 30 minutes. They also refresh when the app goes to the
  background, after a change made from a widget, and on sign-in and sign-out.
- **WIDGETS-R2** Tasks lists the member's open top-level tasks as the task list's default view
  shows them (`GET /tasks?status=open`, no future start dates). The order is overdue, then due
  today, then later, then undated. Within a group, tasks sort by due date and time, then by the
  list's order. Each row shows its due label ("Overdue" in the error colour, "Due today ·
  18:00", "Due tomorrow", else the date). At most 30 rows show, then "N more". Tapping a task
  opens it in the app, and the box completes it (`PATCH /tasks/{id}/status`). When the server
  refuses (locked, offline), the app opens on the task instead.
- **WIDGETS-R3** Shopping lists the open items of the list last opened in the app (else the
  first list), in the list's order, with the quantity as the detail. At most 40 rows show.
  Tapping opens the list, and the box checks the item off (`PATCH /shopping/items/{id}`).
- **WIDGETS-R4** The modules' permissions apply. With `none` the widget says the member has
  no access; with `read`, or on a read-only CalDAV list, there are no boxes.
- **WIDGETS-R5** A widget uses the app's own session and never signs in by itself. While
  signed out it says so and shows nothing of the household. Sign-out deletes the saved
  snapshots.
- **WIDGETS-R6** After each successful read the widget saves what it showed in the app's
  private files. Without a connection it shows that copy, marked "Not up to date", without
  boxes.
- **WIDGETS-R7** Texts come from upstream's strings (`nav.tasks`, `tasks.overdue`,
  `shopping.emptyList`, ...), plus `android.widgets.*`. The picker's description is an Android
  resource, the only text Android itself reads.
