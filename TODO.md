# First steps

Bare-bones checklist for priority #1 in [SPEC.md](SPEC.md) — a runnable skeleton that shows the user's location on a map. Nothing else yet.

- [ ] Create Android Studio project: Kotlin, Jetpack Compose, empty activity, min SDK 34
- [ ] `git init`, initial commit
- [ ] Add dependencies: MapLibre Android SDK, Room (+ KSP), Play Services Location
- [ ] Manifest: `ACCESS_FINE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`; declare the foreground service with `android:foregroundServiceType="location"`
- [ ] Permission request flow: fine location first, then background with a rationale screen
- [ ] Room setup: `Owner` / `Session` / `LocationPoint` entities, DAO, database; seed one `Owner` row with `is_self = true` on first launch
- [ ] Foreground service scaffold: persistent notification, starts/stops cleanly (no location logic yet)
- [ ] Compose screen with MapLibre view centered on current location (one-shot fix for now, no recording)

Once all boxes are checked, move on to priority #2 (start/stop recording UI, points persist to DB).
