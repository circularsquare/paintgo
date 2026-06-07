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
- [x] Background location permission flow (do when wiring service)

# Priority #2: recording → DB

- [x] Record FAB in the map UI (already wired to start/stop service)
- [x] On service start: open `Session` row (ownerId = self, startTime = now)
- [x] Subscribe to `FusedLocationProviderClient` at 5s, balanced power; filter accuracy > 100m
- [x] On each fix: insert `LocationPoint` rows into Room
- [x] On service stop: set `endTime` on the session, unsubscribe
- [x] Manual verify on emulator: DB rows land in `LocationPoint` tied to a `Session` (confirmed via Database Inspector).
- [x] 5m grid dedup via unique `(sessionId, cellX, cellY)` index + INSERT OR IGNORE — keeps stationary points from exploding row count.
- [x] Banded grid: 10° latitude bands; cellY's lng step is per-band cos(midLat) so cells are ~square in meters across the world. Unique index becomes `(sessionId, band, cellX, cellY)`. v2→v3 migration recomputes cells from lat/lng with INSERT OR IGNORE — non-destructive.

# Priority #3: fog rendering

- [x] Add JTS for geometric ops
- [x] `computeFog(points, viewport)` — local equirectangular projection, buffer + union of 50m circles, subtract from viewport, simplify
- [x] Fog source/layer wired into MapScreen below user-location layer
- [x] Recompute on point flow updates or camera idle

# Priority #4: stats screen

- [x] Suspend DAO queries: all sessions, all points for self
- [x] `stats/Stats.kt` — haversine sum per session, totals
- [x] `StatsScreen.kt` — list view of distance / sessions / longest / time / point count
- [x] `MainActivity` switches between Map and Stats via TopAppBar; back arrow + system back wired up
- [x] Strip sessions / longest / duration / points from `WalkStats` + screen
- [x] Area covered: bucket points into 10m cells on-demand in `computeStats` (no schema change)
- [x] Region % coverage — swappable `RegionSource` (bundled NE countries + US states + NYC boroughs v1). Persistent WKB cache per source so cold-launch only parses GeoJSON once per APK version.
- [x] Progress bars + adaptive-decimal percent formatter in stats screen.
- [ ] Streetwise coverage — separate task. OSM way ingest, segment into ~25m chunks, cell→segment index, `% streets walked` per region.

# Perf

- [x] Fog: bbox-filter points at DB layer via `cellX/cellY` range query, expanded by `maxFogRadius + MAX_RUN_SEGMENT_M`. Drops JTS workload from O(all points) to O(viewport points).
- [x] Fog: signature dedup (cell range + point count) — skip JTS work when an insert lands off-screen.
- [x] Add a non-unique `(band, cellX, cellY)` index on `LocationPoint` — moves the bbox query from table scan to indexed range. Shipped (non-destructive) in `MIGRATION_4_5` — the "costs a destructive migration" worry was wrong; `CREATE INDEX` on an existing table is additive.
- [x] Replace the full-list `allForSelf()` Flow trigger in MapScreen with `selfPointCount(): Flow<Long>`. Fog effect keys off the count; no per-insert list re-emission. `LocationPointDao.allForSelf()` removed (unused).
- [x] Spatial chunking system — per-chunk cache (~2km tiles, `Chunk.kt`), invalidated only on writes to that chunk (`markPointsDirty`), recomputed incrementally. Powers area, region %, and fog.
  - [x] **Phase 1 — stats.** `ChunkCoverage` / `ChunkRegionCoverage` / `SessionStat` tables + `MIGRATION_4_5` (additive, no point loss). `StatsEngine.refresh` recomputes only dirty chunks/sessions then SUMs the cache; subsumes the old `computeStats` + fast-pass/full-scan/`VisitedRegions` dance. One-time backfill from existing points on first refresh.
  - [x] **Phase 2 — fog tiles.** `ChunkFog` table (cleared-area union per chunk as WKB, persisted) with its own dirty flag + `MIGRATION_5_6` (additive; seeds dirty tiles from existing chunks). `FogTiles.clearedTilesForViewport` recomputes only dirty *visible* tiles (lazy); `Fog.assembleViewportFog` builds the fill from cached tiles instead of one full-history JTS union. Render-path skip via `dirtyCountInViewport` + sig (recorded only after a completed push, so a fix-cancelled assembly can't freeze the fog half-drawn). `computeChunkClearedUnion` baked at fixed precision; zoom-scaled simplify at assembly. One-time `ensureChunkBackfill` (prefs-flag-gated, run from both stats + map paths) seeds the cache from all history.
  - [ ] **Known limitation (Phase 2):** fog tile recompute uses a 50m halo (just the coverage radius) so a tile only processes its own points. A long transit segment (subway/train hop, up to `MAX_RUN_SEGMENT_M`) that crosses a tile with *both* endpoints outside it won't paint in that pass-through tile. Re-add transit painting via a cheaper mechanism than a `MAX_RUN_SEGMENT_M` halo (e.g. index long segments separately, or split hops into interpolated points at ingest). Ties into the "train integration" idea in SPEC.
