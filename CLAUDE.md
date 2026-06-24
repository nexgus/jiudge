# Hiking Map App - Project Memory

Android-only offline hiking map for Taiwan. Solo-developer personal project.
For the full specification, read `docs/spec.md`.

## Communication

- User communicates primarily in **Traditional Chinese (Taiwan)**. Respond in Traditional Chinese when chatting.
- Code, comments, commits, branch names, and PR titles are in **English**.
- Variable names follow English conventions; never romanize Chinese.

## What This Project Is

A custom outdoor hiking map app that addresses two pain points of existing Taiwan hiking apps: **UI/operation experience** and **route planning/tracking quality**.

This is a personal project. There is **no backend**, no user accounts, no telemetry, no analytics. Everything runs on-device. Distribution is **APK side-load**. Play Store is **not** a near-term target: route storage relies on `MANAGE_EXTERNAL_STORAGE` (All files access), which Google Play restricts to file-manager-class apps - listing on Play would require re-architecting route storage first (see the storage rationale below). **Android only** - iOS is not in scope.

Map data is **not produced by this project**. The app consumes RudyMap (MOI.OSM Taiwan TOPO) data directly: the mapsforge `.map` basemap, its bundled render theme, and `.hgt` DEM. These are downloaded in-app from RudyMap's public mirrors for personal use only - never re-hosted or redistributed.

## Architecture

These are deliberate, considered choices - treat them as the default and don't churn them
casually. They are **not** immutable: finding a real problem or a clearly better approach is a
reason to change, not something to suppress. When you do, raise it, discuss the trade-off, then
change it - and update this table and the rationale notes below so the record stays honest.

| Layer | Choice |
|---|---|
| App framework | Native Android (Kotlin), Jetpack Compose UI, Android-only |
| Map rendering | `org.mapsforge:mapsforge-map-android` (standard Canvas renderer; in-process JVM) |
| Offline map format | mapsforge `.map` from RudyMap (consumed as-is, not produced here) |
| Map style | RudyMap's bundled render theme, used as-is (no custom cartography) |
| Routing engine | BRouter (`org.btools:brouter-core`, Java), called in-process - no platform channel needed |
| Routing data | BRouter `.rd5` segments (5x5-deg tiles) + `.brf` profile, OSM-derived - separate from the rendering `.map`, which carries no routing topology |
| DEM / hillshade | RudyMap `.hgt` DEM (`hgtmix`); mapsforge native hillshading + on-device elevation queries |
| Local storage | One JSON/GPX file per planned route/track. **User routes live in a fixed public folder** `Documents/Jiudge/` (`plans/`, `tracks/`) written via `java.io.File`, gated by the `MANAGE_EXTERNAL_STORAGE` (All files access) permission, so they survive uninstall and a reinstalled app re-reads them after the user re-grants. Downloadable `.map`/`.rd5`/DEM live under `getExternalFilesDir` (`core/storage/AppPaths`). Room (SQLite) only if/when querying many records demands it |
| GPS background | Android Foreground Service + Wake Lock + persistent notification |
| Backend | None. Map data fetched in-app directly from RudyMap public mirrors (static HTTPS) |

If a proposed library or approach contradicts this table, raise it before proceeding - not
because the table is sacred, but so the change is a decision on the record rather than a silent
drift.

**Why native, not Flutter (decided 2026-06-22):** the original Flutter plan relied on
`mapsforge_flutter` (pure-Dart port). It cannot render the directional hillshade RudyMap's
theme requires (the theme `<hillshading>` painter is stubbed out in 4.0.0; any DEM relief is
DIY, low-res, and expensive), and its label de-clutter ignores the theme's `priority` weights.
Since hillshade is a hard requirement and both core engines (mapsforge rendering, BRouter
routing) are JVM libraries, native Android runs both in-process at full fidelity - matching the
RudyMap reference app - with no platform-channel marshalling.

**Why BRouter, not GraphHopper (decided 2026-06-22):** the `.map` basemap carries no routing
topology (mapsforge is rendering-only, by the format authors' own design), so route planning
needs a separate OSM-derived dataset regardless of engine - deriving a graph from the `.map`
is lossy and topologically unreliable (tile-clipped geometry, quantized coords, lost shared-node
connectivity, can't tell a junction from an overpass). The reference stack (OruxMaps/RudyMap)
likewise routes off a separate engine, not the render tiles. BRouter fits this app better than
GraphHopper: its `.rd5` segment data is tiny per region (all of Taiwan is ~1-2 of its 5x5-degree
tiles vs a full-`pbf` graph build), its core (`brouter-core`) runs in-process, and its `.brf`
profile system is purpose-built for foot/hike trail weighting. Same offline, no-backend,
JVM-in-process properties as the original GraphHopper plan.

**Why MANAGE_EXTERNAL_STORAGE for routes, not SAF (decided 2026-06-24):** routes must survive
uninstall, be readable again after reinstall, and live in a fixed predictable folder with no
per-use picker. SAF (`OPEN_DOCUMENT_TREE`) cannot auto-create a fixed folder - it is
consent-gated, and Android 11+ blocks granting standard shared dirs (Documents/Download/root)
wholesale, forcing the user through a confusing "create new folder" pick on every fresh install.
MediaStore does not help either: it is media-only by Google's own guidance, and a reinstalled app
loses access to its own non-media (`.json`) files because that needs the now-gutted
`READ_EXTERNAL_STORAGE`. `MANAGE_EXTERNAL_STORAGE` is the only API delivering fixed-path +
no-picker + reinstall-recoverable at once. Its cost is Play Store eligibility (see Distribution),
which we accept because this is a personal, side-loaded app. Before going to Settings, the app
shows a rationale dialog explaining the permission. If Play Store ever becomes a goal, route
storage must move back to SAF (per-install folder pick) or app-specific storage (no
uninstall survival).

## Hard Constraints

- **Fully offline.** Every feature except "Check for updates" and "Open in Google Maps" must work with no network. If an implementation needs connectivity at use-time, flag it before writing code.
- **Background GPS must survive screen-off and Doze.** Always Foreground Service. Never `startService`-only for location.
- **Android only.** No iOS. The whole app is native Kotlin/JVM; there are no platform channels and no cross-platform abstraction layer. Do not add abstraction whose only justification is "so iOS can plug in later".
- **Consume RudyMap data as-is.** Do not build a custom tile/contour/hillshade pipeline (no Planetiler, no GDAL pre-bake, no PMTiles). Do not author custom map cartography; use RudyMap's bundled theme. If a feature needs map content RudyMap's `.map` does not contain, flag it before writing code. (Routing is the known exception: the `.map` has no routing topology, so route planning uses BRouter `.rd5` segments downloaded prebuilt from brouter.de - OSM-derived, not a custom cartography pipeline.)
- **No backend.** Do not propose features requiring server state, accounts, or sync. The only network use is downloading RudyMap data in-app from their public mirrors; the app hosts and redistributes nothing.

## Performance Budgets

- Cold start: < 3 s
- First map frame after navigation: < 1 s
- 8 h continuous track recording: < 30% battery drain on a modern mid-range Android
- Installed app size: < 100 MB (RudyMap data is downloaded in-app on first run, not bundled; the basemap alone is ~298 MB zipped)

## v1 Scope Discipline

In scope for v1: see `docs/spec.md` §1.1.

**Explicitly out of scope for v1** (do not implement, even if a refactor "naturally enables" it):

- Multi-user, accounts, cloud sync
- Multi-day route planning with overnight stops and per-day itinerary
- Off-route deviation warnings during recording
- Satellite/aerial imagery overlay
- User-annotated POIs (water sources, campsites, etc.)
- Forestry Bureau communication-point overlay

If a request naturally invites one of these, mention it as v2 candidate and **stop**; do not implement without explicit go-ahead.

## Development Status

Work here is **feature-driven, not strict-phase-linear** - features land when they are useful,
not in a fixed order. `docs/spec.md` still describes a Phase 0-3 plan for reference, but the
project deliberately did not follow it in sequence, so do **not** block or gate work on "that
belongs to a later phase". The old "never jump phases" rule is retired.

**Built so far:**
- Offline map rendering with hillshade (`feature/map`)
- In-app download of map / DEM / BRouter data (`feature/mapdata`, `core/mapdata`)
- Route planning via BRouter - waypoints, save/load (`feature/planning`, `core/routing`, `data/route`)
- Map-symbol identify ("?") (`feature/identify`)
- Main menu / about (`feature/about`)
- Current-location marker + recenter button - foreground GPS + compass facing cone, OruxMaps-style
  (`feature/map/CurrentLocationLayer`, `core/location`). Foreground-only (subscribed while the map is
  visible, released on pause); this is *not* the background track recording below

**Not yet built (known remaining work):**
- Background track recording (foreground-service GPS) + GPX import/export - this was the original
  "Phase 1 core" and is still outstanding
- Elevation profile during planning
- Update-check mechanism

Still applies: build what is asked, do not silently expand scope (see Working With Me), and keep
the v1 out-of-scope list below off-limits without an explicit go-ahead.

## Development Conventions

### Code style
- Kotlin: ktlint default rules.
- No `// TODO` without a tracking reference (GitHub issue number or spec section like `// TODO(spec §4.2)`).

### Directory layout (native Android)
```
app/src/main/kotlin/io/github/nexgus/jiudge/
  feature/        # feature modules: map (+ current-location overlay), planning, identify, mapdata, about (planned: recording, gpx, settings)
  core/           # shared infra: mapdata, routing, storage, location (foreground GPS + compass; planned: background recording service, networking)
  data/           # repositories, models, data sources (currently: route)
  ui/             # Compose components, theming, design tokens (planned)
docs/             # spec, design notes, architecture decisions
```

### Testing
- Unit tests (JUnit) for pure Kotlin logic; target > 70% coverage in `core/` and `data/`
- Instrumented/UI tests for non-trivial Compose screens
- Integration tests reserved for critical user flows (recording, planning, GPX round-trip)

### Git
- Branches: `feat/<short-desc>`, `fix/<short-desc>`, `chore/<short-desc>`
- Commits in Conventional Commits format
- Always run `./gradlew ktlintFormat lint` before committing
- Do not commit build artifacts, generated files, or large binary assets

## Working With Me

- **Ask before adding dependencies.** Every new Maven/Gradle dependency is a long-term maintenance commitment for a solo dev. Justify it.
- **Verify before claiming done.** A feature is done when it runs on a real Android device, not when it compiles.
- **Surface open questions.** Don't silently pick an answer to ambiguity. See §"Open Questions" below.
- **No silent scope expansion.** Stick to the smallest change that fulfills the request.

## Open Questions (Not Yet Decided)

See `docs/spec.md` §9 for context. Don't assume answers:

1. Whether to include Forestry Bureau comm-point/shelter overlay in v1
2. Whether auto-routed paths support manual node-drag adjustment afterward
3. ~~Whether RudyMap's `.map` carries `name:en` tags~~ - **resolved**: it carries both zh + en, so zh/en label switching is feasible
4. Live cumulative ascent shown during waypoint dragging

## Key External References

- RudyMap (MOI.OSM Taiwan TOPO) downloads: https://rudymap.tw/ (mirrors: https://moi.kcwu.csie.org/ , https://map.happyman.idv.tw/rudy/)
  - Basemap: `MOI_OSM_Taiwan_TOPO_Rudy.map.zip` (~298 MB; Lite ~168 MB) under each mirror's `/v1/` path
  - DEM: `hgtmix.zip` (~46 MB, Taiwan 30 m + islands 90 m) or `hgt90.zip` (~8 MB) - `.hgt` format
  - Theme: `MOI_OSM_Taiwan_TOPO_Rudy_hs_style.zip` (light + dark variants)
  - Static HTTP GET, no manifest/SHA256; version date (`vYYYY.MM.DD`) is on the page, not in filenames - detect new releases via HTTP `Last-Modified`/`ETag`. Released weekly (Thursdays).
- mapsforge format, themes & Android library (`mapsforge-map-android`, latest 0.25.0 on Maven Central): https://github.com/mapsforge/mapsforge
- BRouter (offline routing engine, embedded in-process via `org.btools:brouter-core`): https://brouter.de/ , source https://github.com/abrensch/brouter
  - Routing data: `.rd5` segment files on 5x5-degree tiles (Taiwan = `E120_N20`, `E120_N25`), from https://brouter.de/brouter/segments4/ ; OSM-derived, separate from the rendering `.map`. Profiles (`.brf`) tune foot/hike weighting; in-process usage follows `brouter-routing-app`'s `BRouterWorker`.
- OSM Taiwan extract (only needed if self-building BRouter segments instead of using prebuilt `.rd5`): https://download.geofabrik.de/asia/taiwan.html