# 0020. Home-screen widgets with Glance

**Status:** Accepted, 2026-11-02.

## Context

The owner asked for home-screen widgets: today's tasks and the shopping list at a glance,
ticked off without opening the app. Upstream has nothing to port; this is the first feature
that is Android's own beyond the shell.

## Decision

- **Glance** (`androidx.glance:glance-appwidget`), the Compose-style widget toolkit, keeps the
  widget code close to the rest of the app. There are no RemoteViews by hand.
- **No separate sign-in.** A widget reads through the app's `SessionManager`, restoring the
  session if Android started the process only for the widget. The endpoints and the module
  permissions are the same as in the module, and so is the choice of shopping list, the one
  last opened in the app.
- **Fresh enough, never wrong about who is signed in.** The widgets refresh on Android's
  30-minute schedule, when the app goes to the background, and after a change made from a
  widget. Each successful read is saved in the app's private files and shown, marked as old,
  when there is no connection. Sign-out deletes the saved reads and blanks the widgets.
- **Ticking off from the widget** calls the same endpoints as the module. A refusal opens the
  app on the item instead of failing silently.

## Consequences

The widgets show household data on the home screen, as the app's notifications already do.
Whoever places a widget chooses that. Signing out removes it. A third widget is one more
`GlanceAppWidget` and `Widgets.load` call.
