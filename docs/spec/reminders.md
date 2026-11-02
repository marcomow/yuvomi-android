# Reminders on the phone

Upstream 2.84.0 added `GET /api/v1/reminders/upcoming` "for a client that schedules them on
the device". The web app shows reminders only while it is open (`public/reminders.js`, polling
`/reminders/pending`). The phone shows them as notifications, also while the app is closed.
ADR 0021.

- **REM-R1** The schedule is rebuilt from the server in four cases: about every 6 hours with a
  connection (WorkManager), when the app goes to the background, on sign-in and sign-out, and
  after a task is completed from the widget.
- **REM-R2** A refresh asks for the next 14 days (`from` = now, `to` = now + 14 days, the
  endpoint's limit) and schedules exactly the rows that come back, each once, at `fires_at`.
  Anything scheduled before and no longer in the answer is cancelled: removed, moved, its task
  done or archived, its module switched off. A network failure keeps the current schedule. A
  server without the endpoint (older than 2.84.0, a 404 without a reason) leaves nothing
  scheduled.
- **REM-R3** When a reminder is due, its notification shows. The title is the origin's label as
  in upstream's `REMINDER_ORIGINS` ("Tasks", "Calendar", ...; "Reminder" for an unknown
  origin). The body follows `reminderBody`: the move-returned sentence, the fasting and cycle
  sentences, the inherited prevention row with the person's name, else the entity's title.
  **Dismiss** dismisses the reminder on the server (`PATCH /reminders/{id}/dismiss`), as the web
  app's toast does.
- **REM-R4** A tap opens the item: a task, an event (`calendar?open=`), or a document. Otherwise
  it opens the origin's page: the cycle, fasting and prevention areas of Health, or the module's
  start for subscriptions (Budget), inventory, pantry, schedule, waste and shopping. Only routes
  `AppLinks.allowed` accepts are followed.
- **REM-R5** Alarms are exact when Android allows exact alarms, otherwise within a few minutes,
  also in Doze. A reboot or an app update sets the saved alarms again.
- **REM-R6** Settings, Notifications, **Reminders on this phone** has the switch, on by default.
  It is app-only, next to upstream's push channels. Switching it off or signing out cancels
  everything scheduled. The section says when the server is too old.
- **REM-R7** On Android 13 and newer, the app asks once for the notification permission when
  the shell first opens. While it is denied, the section says so and links to Android's setting.
