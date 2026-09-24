# PillGuard

Android alarm app for fixed-time medication where meals must be kept away from doses.

Rules built in (all editable in Settings, behind the carer PIN):

- Doses at fixed times of day (defaults 07:00, 10:30, 14:30, 18:30, 22:30).
- No eating until 30 min after a dose was actually taken (QR scanned).
- Eating must finish 90 min before the next dose.
- "I ate something" pushes the *next* dose only, to at least 90 min after the meal.
- The alarm screen shows the dose time and two buttons — "Get pill" and "Scan". "Get pill" mutes the alarm for 3 minutes but leaves the screen up: the time is replaced by "Take pills now" in the same large type with a spinner above it, the button greys out and counts down, and the second button becomes "Done" for when he comes back with the container. The scanner says "scan the code … to confirm done", so both screens use the same word. The sound returns if the dose is still unconfirmed. It never stops ringing on its own, and repeats every 3 minutes until the QR code on the container is scanned, or the carer PIN is used (logged as an override).
- A red warning triangle in the top-right puts the dose off by an hour, at most twice per dose, with a loud ntfy ping to the carer each time. It sits top-right because the system accessibility button lives bottom-right. The test alarm shows the triangle too, so the whole flow can be rehearsed — the confirm screen says it is only a test and nothing is shifted or sent. Confirming is a full screen with two large buttons; the "yes" is held for 3 seconds, and Back is inert for that same hold so a panicked jab at it cannot bounce him back to a ringing alarm.
- Opening the scanner or the delay screen silences the alarm for 60 seconds — room to deal with it in an appointment or a cinema without the noise. If the dose is still unconfirmed when the minute is up, the alarm resumes at whatever level the ramp has reached.
- The last dose of the day is a different set of pills, so it is treated as its own habit: a low bell
  instead of the alarm tone, a violet screen instead of the blue one, and the words "Night pills"
  above the time (the tint on its own is no use if violet and blue look alike). Any dose can be
  marked this way — Settings → dose time → "Different pills". The night container can have its own
  printed QR code, and until one is set up it accepts the daytime code; once it has one, only that
  code clears a night dose, and scanning the wrong container says which one to fetch.
- "I'm going out" — a red button on the main screen, and one on the widget — silences everything for
  a couple of hours (Settings sets how long). It first shows what it is about to silence: which
  pills move, which meal reminders are dropped, and anything already overdue. The length is stated
  on the buttons themselves ("Going out for 2 hours", "Longer — 3 hours", "Cancel" in red) rather
  than in a heading and a line of times, and the whole screen fits without scrolling. The carer gets
  an ntfy note naming the new times, at both ends of the outing. Doses inside the window are moved
  to the end of it rather than skipped, spaced an hour apart if two fall together;
  a dose that was already overdue is left where it is, because it is still owed. The widget says
  "Out — alarms off" for the duration, and one tap on it (or on the notification) ends the outing
  early and puts any moved dose back at its own time.
- A manual scan from the main screen only counts as a dose if one is due or the next is within 60 minutes; otherwise it is just logged.
- History shows a day-by-day diary and can share it as text or CSV.
- An untaken dose is logged as missed once the following dose becomes due.

## Install

Download the APK from the latest GitHub release on the phone, open it and allow installing from this source. On first launch the app lists what it still needs (notifications, exact alarms, full-screen alarms, battery optimisation off, carer PIN) with a Fix button for each.

Then: Settings → set carer PIN → QR code → Print, and stick a code on the bottom of the container. Use Settings → Test alarm with the phone locked to check sound and lock-screen behaviour.

## Notes

- Alarms play on the alarm audio stream, so silent mode and Do Not Disturb don't mute them.
- The phone's own alarm volume controls loudness.
- Everything is stored on the phone; nothing is sent anywhere.

## Screen design

The alarm screen carries only what is needed: the time, two buttons, and a small carer-override
link. Icons and a title were tried and removed — at a glance they read as more buttons. Button
labels are sentence case; all-caps is harder to read, not easier, because the word shape is lost.
Where a screen can say the same thing in a heading or on a button, it says it on the button: the
going-out screen lost its question and its end times that way and now fits without scrolling.

## Telling the alarms apart

Each alarm type owns a colour and a glyph, so the type is clear before any text is read: pills are a
blue screen with a capsule, meals a green screen with a knife and fork, night pills a violet screen
with the words "Night pills". The glyph or word matters as much as the colour — dark blue against
dark green is the pair red-green colour deficiency hits hardest, and violet against blue is a
difference of tint rather than hue. The heads-up notifications carry the same colours. Each also
sounds different: the phone's alarm tone for daytime pills, a low bell for night pills, a bugle for
meals. "Going out" gets its own slate screen, so it can never be mistaken for an alarm.

## Contrast

Every text/background pair on the alarm, night, meal, going-out, main and widget surfaces is
contrast-checked; on the violet night screen white is 10.6:1, the amber time 7.4:1 and the override
hint 7.2:1, on the slate going-out screen white is 11.5:1, the amber 8.0:1 and the red "Cancel"
10.7:1 on its white button, the red "I'm going out" button on the main screen carries white at
8.9:1, and the lowest ratio
anywhere is 5.45:1, against a WCAG AA minimum of 4.5:1 for body text and 3:1 for large text. Alarm
button colours are set in explicit styles using `backgroundTint` (the app-namespace attribute
MaterialButton actually reads — `android:backgroundTint` is silently ignored) so the theme cannot
substitute its own colours for them.

## Battery

An alarm app should cost almost nothing when it is not ringing. What it does between alarms:

- One AlarmManager alarm for the next dose, one for the next meal reminder, and one widget refresh
  armed at the next moment the widget's text actually changes (a window opening or closing, a dose
  falling due, midnight). Nothing polls. The widget's `updatePeriodMillis` is 0 — the system's own
  periodic widget update is off — and its clock is a Chronometer, which counts down in the launcher
  without waking the app.
- The wake lock is held only while a sound is actually playing. Quiet stretches — the three minutes
  after "Get pill", the minute while the scanner is open — end on an AlarmManager alarm instead, so
  the CPU can sleep through them.
- Each ringing bout vibrates for its first minute and then stops; the sound carries on. A vibration
  motor running for an hour is one of the most expensive things a phone can do.
- The alarm and meal screens hold the display awake for two minutes, then let it sleep normally. An
  unanswered alarm used to keep the screen lit at full brightness for as long as it rang.

Settings are parsed once and kept in memory rather than re-read from storage on every access.

## Volume

Pill alarms and meal reminders have separate volume settings — ceiling (as a percentage of the
phone's alarm volume), starting level, and ramp length. Pills default to 100% / 25% / 5 min; meals
to a gentler 70% / 30% / 20 min. Each climbs from its start to its ceiling over its ramp: the pill
alarm measured from when the dose came due (so pressing "Get pill" and walking away does not reset
it), the meal bugle from the meal time, so later repeats are louder.

Both are capped by the phone's own alarm-stream volume. That slider is not the ringtone or
notification one: press a volume key, expand the panel, and set "Alarm" — or just press the volume
keys while a PillGuard alarm is actually sounding, which adjusts the alarm stream directly.

## Meal reminders

A bugle call (synthesised, on the alarm stream) at each meal time — defaults breakfast 07:30, lunch 11:00, dinner 19:00 — repeating every 15/15/30 minutes until "Eating now" is tapped on the notification (which also logs the meal and shifts the next pill if needed) or "Not today". A reminder only sounds while eating is actually allowed, and is abandoned once the next meal time or the next pill arrives. Sound, times and repeat intervals are in Settings.

The reminder comes up as a full screen, the same way the pill alarm does: an alarm-clock alarm wakes
a short-lived foreground service, and that service puts the screen up. It has to go through a
service — Android silently drops an activity start made straight from a broadcast receiver, which
left the reminder as a heads-up notification whenever the phone was unlocked.
