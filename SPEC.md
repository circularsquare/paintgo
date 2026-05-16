# PaintGo — Android App

Android-only. Free, shipping to the Play Store so friends can install it. Eventually social — share progress/maps with friends — but v1 is single-user local.

Everything in this spec is questionable / changeable. If you feel like we should switch something in stack or something, just let me know and we can discuss.

## Concept
Fog-of-war style map that reveals wherever you walk. Inspired by Fog of World ($30 on Play Store); this is a free alternative. Track GPS continuously, clear "fog" around visited locations, show stats.

## Stack
- **Language:** Kotlin
- **UI:** Jetpack Compose
- **Maps:** MapLibre GL Native for Android
- **Tiles:** Protomaps (self-hosted or hosted) with a custom cute/muted style
- **Location:** FusedLocationProviderClient in a foreground service
- **DB:** Room
- **Min SDK:** target Android 14+ (API 34), so handle the foreground service type + background location permission flow

## Architecture

### Tracking (Strava pattern, v1)
- Manual start/stop via a big button in the UI
- Foreground service with persistent notification while recording
- `FusedLocationProviderClient` with `PRIORITY_BALANCED_POWER_ACCURACY`, ~5–10s interval
- Declare foreground service type `location` in manifest
- Runtime permission flow: `ACCESS_FINE_LOCATION` first, then `ACCESS_BACKGROUND_LOCATION` with rationale
- Future: auto-start via Activity Recognition API, but not v1

### Data model (Room)
- `Owner`: id, display_name, is_self (bool). Pre-seed one row for the user ("self"). Imported friends become additional rows.
- `Session`: id, owner_id (FK), start_time, end_time, name (optional), imported_at (nullable — null for own sessions)
- `LocationPoint`: id, session_id, lat, lng, timestamp, accuracy

That's it. No pre-baked tile/fog tables — fog is derived from points at render time.

The `Owner` concept is there from v1 even though the import flow ships later (see Social below). Adding it now costs nothing; adding it later means a migration.

### Fog rendering (circle-based, smooth)
- Fog = inverse of the union of circles (radius ~50m) around every GPS point
- Render as a MapLibre fill layer from a GeoJSON source
- Low opacity, feathered edges, soft pastel color — aiming for cute, not harsh
- Geometric ops (buffer + union of circles) computed on-device; for a personal tool in NYC, point count stays manageable for years
- If perf becomes an issue later: periodically bake points into a simplified polygon and store that

### Stats (computed on demand from points table)
- Total km walked (sum of distances between consecutive points per session)
- % of administrative region explored (visited area ∩ boundary polygon / boundary area)
- Number of sessions, longest session, etc.
- v1 region source: NYC borough/neighborhood polygons from NYC Open Data (since that's where I am). Generalize to other cities later — design the region-loading code so the data source is swappable (e.g. bundle a few GeoJSON files, or fetch from OSM/Overpass on demand).

### Export
- JSON dump of all sessions + points
- Optionally: GeoJSON of the explored-area polygon, PNG render of fog for sharing

### Social: async file-based sharing (post-v1)
No backend, no accounts, no live sync. The sharing primitive is the export file — a friend sends you their data (Android share sheet, iMessage, email, whatever), you import it, and it becomes another `Owner` in your local DB. From there it flows through the same fog-rendering and stats code as your own data.

What you can do with imported data:
- View their fog on the map, overlaid with yours (different color / opacity toggle)
- Compare stats side by side (km walked, % of region explored, session counts)
- Toggle owners on/off in a layer panel (self, friend A, friend B, …)
- Delete a friend's data cleanly (single cascade delete on `Owner` removes their sessions and points)

Design notes:
- **Export format is a public API.** Once friends exchange files across app versions, the format has to stay backward-compatible. Include a `schema_version` field in the export from day one. Keep it simple — JSON (optionally gzipped) with sessions + points + an `exported_by` display name.
- **Dedup on re-import:** use (friend-id, original-session-id) as the unique key so re-importing a newer export from the same friend updates rather than duplicates.
- **Privacy:** imported data is someone's raw GPS history. Surface this clearly in the import UI and make deletion obvious.
- **Size:** a year of 5–10s points is large. JSON+gzip is probably fine for v1; revisit if files get unwieldy.

## Play Store requirements
Shipping free to the Play Store (not personal sideload) adds compliance work:
- Play Console developer account ($25 one-time)
- **Privacy policy** hosted at a public URL — required because we collect location
- **Data Safety** form in Play Console declaring location collection, retention, sharing
- **Background location justification**: Google reviews apps requesting `ACCESS_BACKGROUND_LOCATION`. We need a clear in-app explanation + a demo video for the review. Fitness-tracker-style use case is accepted but must be justified.
- App signing, versioning, release tracks (internal → closed → production)
- Icon, feature graphic, screenshots, store listing copy

None of this blocks v1 dev; tackle at the end before the first internal-track upload.

## Gotchas I already know about
- Android 14+ foreground service type declaration in manifest
- `ACCESS_BACKGROUND_LOCATION` needs its own permission request flow separate from fine location, with a rationale screen
- MapLibre on Android wants the style URL set before adding sources/layers
- Protomaps style JSON is customizable — want to heavily tweak for cute aesthetic
- Play Store review is stricter than sideload — don't leave debug-only behavior (logging raw locations, hardcoded test data) in release builds

## Priorities
1. Skeleton project: manifest with permissions, foreground service, Room setup, MapLibre screen showing my location
2. Start/stop recording UI, points persist to DB
3. Fog rendering from points
4. Stats screen
5. Export
6. Styling pass (cute palette, custom Protomaps style)
7. Play Store prep: privacy policy, Data Safety form, store listing, internal-track release to friends
8. Social: import a friend's exported file, render their fog alongside yours, compare stats

Ask me before making architectural decisions I haven't specified. Use the Kotlin ecosystem defaults (Coroutines, Flow, Hilt if DI is needed). Don't over-engineer, but keep the data model and code free of hard assumptions that would block a later social/multi-device feature.
