# Hiking Map App - Project Memory

Android-only offline hiking map for Taiwan. Solo-developer personal project.
For the full specification, read `docs/spec.md`.

## Communication

- User communicates primarily in **Traditional Chinese (Taiwan)**. Respond in Traditional Chinese when chatting.
- Code, comments, commits, branch names, and PR titles are in **English**.
- Variable names follow English conventions; never romanize Chinese.

## What This Project Is

A custom outdoor hiking map app that addresses two pain points of existing Taiwan hiking apps: **UI/operation experience** and **route planning/tracking quality**.

This is a personal project. There is **no backend**, no user accounts, no telemetry, no analytics. Everything runs on-device. Distribution is APK side-load and (later) Play Store. **Android only** - iOS is not in scope.

Map data is **not produced by this project**. The app consumes RudyMap (MOI.OSM Taiwan TOPO) data directly: the mapsforge `.map` basemap, its bundled render theme, and `.hgt` DEM. These are downloaded in-app from RudyMap's public mirrors for personal use only - never re-hosted or redistributed.

## Architecture - Locked

Do not reconsider these choices without explicit discussion.

| Layer | Choice |
|---|---|
| App framework | Native Android (Kotlin), Jetpack Compose UI, Android-only |
| Map rendering | `org.mapsforge:mapsforge-map-android` (standard Canvas renderer; in-process JVM) |
| Offline map format | mapsforge `.map` from RudyMap (consumed as-is, not produced here) |
| Map style | RudyMap's bundled render theme, used as-is (no custom cartography) |
| Routing engine | GraphHopper (Java), called in-process - no platform channel needed |
| DEM / hillshade | RudyMap `.hgt` DEM (`hgtmix`); mapsforge native hillshading + on-device elevation queries |
| Local storage | SQLite (Room) for tracks/routes/settings; filesystem for `.map`/graph/DEM |
| GPS background | Android Foreground Service + Wake Lock + persistent notification |
| Backend | None. Map data fetched in-app directly from RudyMap public mirrors (static HTTPS) |

If a proposed library or approach contradicts this table, stop and ask first.

**Why native, not Flutter (decided 2026-06-22):** the original Flutter plan relied on
`mapsforge_flutter` (pure-Dart port). It cannot render the directional hillshade RudyMap's
theme requires (the theme `<hillshading>` painter is stubbed out in 4.0.0; any DEM relief is
DIY, low-res, and expensive), and its label de-clutter ignores the theme's `priority` weights.
Since hillshade is a hard requirement and both core engines (mapsforge rendering, GraphHopper
routing) are JVM libraries, native Android runs both in-process at full fidelity - matching the
RudyMap reference app - with no platform-channel marshalling.

## Hard Constraints

- **Fully offline.** Every feature except "Check for updates" and "Open in Google Maps" must work with no network. If an implementation needs connectivity at use-time, flag it before writing code.
- **Background GPS must survive screen-off and Doze.** Always Foreground Service. Never `startService`-only for location.
- **Android only.** No iOS. The whole app is native Kotlin/JVM; there are no platform channels and no cross-platform abstraction layer. Do not add abstraction whose only justification is "so iOS can plug in later".
- **Consume RudyMap data as-is.** Do not build a custom tile/contour/hillshade pipeline (no Planetiler, no GDAL pre-bake, no PMTiles). Do not author custom map cartography; use RudyMap's bundled theme. If a feature needs map content RudyMap's `.map` does not contain, flag it before writing code.
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

## Phase Discipline

The spec breaks work into four phases:

- **Phase 0**: technical prototype (native Android + `mapsforge-map-android` renders a RudyMap `.map` with hillshade, GPS dot; validates the open items in spec §6 Phase 0)
- **Phase 1**: core usable (offline map + background track recording + basic GPX I/O)
- **Phase 2**: route planning (GraphHopper integration + waypoints + elevation profile)
- **Phase 3**: update mechanism + polish

Do not jump phases. If Phase 2 work would unblock something in Phase 1, raise it and let me decide; do not just do it.

## Development Conventions

### Code style
- Kotlin: ktlint default rules.
- No `// TODO` without a tracking reference (GitHub issue number or spec section like `// TODO(spec §4.2)`).

### Directory layout (native Android)
```
app/src/main/kotlin/io/github/nexgus/jiudge/
  feature/        # feature modules: map, planning, recording, gpx, settings
  core/           # shared infra: gps, storage, mapdata, networking
  data/           # repositories, models, data sources
  ui/             # Compose components, theming, design tokens
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
3. Whether RudyMap's `.map` carries `name:en` tags (decides if zh/en label switching is feasible) - resolve in Phase 0
4. Live cumulative ascent shown during waypoint dragging

## Key External References

- RudyMap (MOI.OSM Taiwan TOPO) downloads: https://rudymap.tw/ (mirrors: https://moi.kcwu.csie.org/ , https://map.happyman.idv.tw/rudy/)
  - Basemap: `MOI_OSM_Taiwan_TOPO_Rudy.map.zip` (~298 MB; Lite ~168 MB) under each mirror's `/v1/` path
  - DEM: `hgtmix.zip` (~46 MB, Taiwan 30 m + islands 90 m) or `hgt90.zip` (~8 MB) - `.hgt` format
  - Theme: `MOI_OSM_Taiwan_TOPO_Rudy_hs_style.zip` (light + dark variants)
  - Static HTTP GET, no manifest/SHA256; version date (`vYYYY.MM.DD`) is on the page, not in filenames - detect new releases via HTTP `Last-Modified`/`ETag`. Released weekly (Thursdays).
- mapsforge format, themes & Android library (`mapsforge-map-android`, latest 0.25.0 on Maven Central): https://github.com/mapsforge/mapsforge
- OSM Taiwan extract (for GraphHopper routing graph only): https://download.geofabrik.de/asia/taiwan.html
- GraphHopper: https://www.graphhopper.com/