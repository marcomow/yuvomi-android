# 0021. Reminders as phone notifications

**Status:** Accepted, 2026-11-02.

## Context

Upstream 2.84.0 added `GET /reminders/upcoming` so a native client can schedule reminders on
the device. Before that, a reminder reached a phone only while the web app was open, or
through a push channel the household set up (Gotify, ntfy, ...). The owner asked for
reminders on the phone. They decided against 2.84.0's device sign-in, which would make an admin
an ordinary member in the app.

## Decision

- **Pull and schedule, no push service.** A refresh reads the next 14 days and sets one
  `AlarmManager` alarm per reminder. When an alarm fires, it posts the notification with the
  title, body and target fixed at scheduling, so nothing needs the network at that moment.
- **Rebuild, don't patch.** Every refresh replaces the whole schedule with the server's answer.
  A completed task, a moved event or a disabled module therefore drops out at the next refresh.
  Refreshes run every 6 hours (WorkManager), when the app goes to the background, on sign-in
  and sign-out, and after a widget completes a task.
- **No special permissions beyond Android's notification prompt.** Exact alarms are used when
  Android allows them, otherwise inexact ones that still wake in Doze. The app does not ask
  for `SCHEDULE_EXACT_ALARM`.
- **Same session, same rules.** The refresh uses the app's session, and the server applies its
  own visibility rules (own reminders, nothing private of others, nothing from a disabled
  module). Sign-out cancels everything.

## Consequences

A reminder created on the web reaches the phone at the next refresh, within about 6 hours or
as soon as the app is opened and closed. Reminders created in the app reach the schedule when
the app goes to the background. A server older than 2.84.0 simply has no phone reminders, and
the settings section says so.
