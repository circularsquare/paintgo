# First steps

Bare-bones checklist for priority #1 in [SPEC.md](SPEC.md) — a runnable skeleton that shows the user's location on a map. Nothing else yet.

- [x] Create Android Studio project: Kotlin, Jetpack Compose, empty activity, min SDK 29
- [x] `git init`, initial commit
- [x] Add dependencies: MapLibre Android SDK, Room (+ KSP), Play Services Location
- [x] Manifest: `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `POST_NOTIFICATIONS`; declare the foreground service with `android:foregroundServiceType="location"`
- [x] Compose screen with MapLibre view centered on current location (one-shot fix for now, no recording)
- [x] Permission request flow: fine location (background deferred until the foreground service needs it)
- [x] Room setup: `Owner` / `Session` / `LocationPoint` entities, DAO, database; seed one `Owner` row with `is_self = true` on first launch
- [x] Foreground service scaffold: persistent notification, starts/stops cleanly (no location logic yet)
- [ ] Background location permission flow (do when wiring service)

Once all boxes are checked, move on to priority #2 (start/stop recording UI, points persist to DB).
