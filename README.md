# PillGuard

Android alarm app for fixed-time medication where meals must be kept away from doses.

Rules built in (all editable in Settings, behind the carer PIN):

- Doses at fixed times of day (defaults 07:00, 10:30, 14:30, 18:30, 22:30).
- No eating until 30 min after a dose was actually taken (QR scanned).
- Eating must finish 90 min before the next dose.
- "I ate something" pushes the *next* dose only, to at least 90 min after the meal.
- The alarm says "go and get your pills" with two buttons: "I'm going to get the pill" (quiet for 2 min, then rings again). It never stops ringing on its own and "I'm taking it now — scan". It repeats every 2 minutes until the QR code on the container is scanned, or the carer PIN is used (logged as an override).
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

## Volume

The alarm starts quiet and climbs to full over 5 minutes, measured from when the dose came due (so
pressing "I'm going to get the pill" and walking away does not reset it). Settings has the ceiling
(as a percentage of the phone's alarm volume), the starting level, and the ramp length, with a
sample button that plays both ends. Meal bugles get louder on the same curve as the repeats go on.

## Meal reminders

A bugle call (synthesised, on the alarm stream) at each meal time — defaults breakfast 07:30, lunch 11:00, dinner 19:00 — repeating every 15/15/30 minutes until "Eating now" is tapped on the notification (which also logs the meal and shifts the next pill if needed) or "Not today". A reminder only sounds while eating is actually allowed, and is abandoned once the next meal time or the next pill arrives. Sound, times and repeat intervals are in Settings.
