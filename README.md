# PillGuard

Android alarm app for fixed-time medication where meals must be kept away from doses.

Rules built in (all editable in Settings, behind the carer PIN):

- Doses at fixed times of day (defaults 07:00, 10:30, 14:30, 18:30, 22:30).
- No eating until 30 min after a dose was actually taken (QR scanned).
- Eating must finish 90 min before the next dose.
- "I ate something" pushes the *next* dose only, to at least 90 min after the meal.
- The alarm screen shows the dose time and two buttons — "Get pill" and "Scan". "Get pill" mutes the alarm for 3 minutes but leaves the screen up, counting down on the button itself, so Scan is still in front of him when he comes back with the container; the sound returns if the dose is still unconfirmed. It never stops ringing on its own, and repeats every 3 minutes until the QR code on the container is scanned, or the carer PIN is used (logged as an override).
- A red warning triangle in the top-right puts the dose off by an hour, at most twice per dose, with a loud ntfy ping to the carer each time. It sits top-right because the system accessibility button lives bottom-right. Confirming is a full screen with two large buttons; the "yes" is held for 3 seconds, and Back is inert for that same hold so a panicked jab at it cannot bounce him back to a ringing alarm.
- Opening the scanner or the delay screen silences the alarm for 60 seconds — room to deal with it in an appointment or a cinema without the noise. If the dose is still unconfirmed when the minute is up, the alarm resumes at whatever level the ramp has reached.
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

## Telling the alarms apart

Each alarm type owns a colour and a glyph, so the type is clear before any text is read: pills are a
blue screen with a capsule, meals a green screen with a knife and fork. The glyph matters as much as
the colour — dark blue against dark green is the pair red-green colour deficiency hits hardest. The
heads-up notifications carry the same colours.

## Contrast

Every text/background pair on the alarm, meal, main and widget surfaces is contrast-checked; the
lowest ratio anywhere is 5.45:1, against a WCAG AA minimum of 4.5:1 for body text and 3:1 for large
text. Alarm button colours are set in explicit styles using `backgroundTint` (the app-namespace
attribute MaterialButton actually reads — `android:backgroundTint` is silently ignored) so the theme
cannot substitute its own colours for them.

## Volume

Pill alarms and meal reminders have separate volume settings — ceiling (as a percentage of the
phone's alarm volume), starting level, and ramp length. Pills default to 100% / 25% / 5 min; meals
to a gentler 70% / 30% / 20 min. Each climbs from its start to its ceiling over its ramp: the pill
alarm measured from when the dose came due (so pressing "GET PILL" and walking away does not reset
it), the meal bugle from the meal time, so later repeats are louder.

Both are capped by the phone's own alarm-stream volume. That slider is not the ringtone or
notification one: press a volume key, expand the panel, and set "Alarm" — or just press the volume
keys while a PillGuard alarm is actually sounding, which adjusts the alarm stream directly.

## Meal reminders

A bugle call (synthesised, on the alarm stream) at each meal time — defaults breakfast 07:30, lunch 11:00, dinner 19:00 — repeating every 15/15/30 minutes until "Eating now" is tapped on the notification (which also logs the meal and shifts the next pill if needed) or "Not today". A reminder only sounds while eating is actually allowed, and is abandoned once the next meal time or the next pill arrives. Sound, times and repeat intervals are in Settings.
