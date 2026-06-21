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
| App framework | Flutter 3.x (Dart), Android-only |
| Map rendering | `mapsforge_flutter` (pure-Dart mapsforge standard renderer; in-process, no platform channel) |
| Offline map format | mapsforge `.map` from RudyMap (consumed as-is, not produced here) |
| Map style | RudyMap's bundled render theme, used as-is (no custom cartography) |
| Routing engine | GraphHopper (Java), embedded into Android via platform channel |
| DEM / hillshade | RudyMap `.hgt` DEM (`hgtmix`), queried on-device for elevation + hillshade |
| Local storage | SQLite for tracks/routes/settings; filesystem for `.map`/graph/DEM |
| GPS background | Android Foreground Service + Wake Lock + persistent notification |
| Backend | None. Map data fetched in-app directly from RudyMap public mirrors (static HTTPS) |

If a proposed library or approach contradicts this table, stop and ask first.

## Hard Constraints

- **Fully offline.** Every feature except "Check for updates" and "Open in Google Maps" must work with no network. If an implementation needs connectivity at use-time, flag it before writing code.
- **Background GPS must survive screen-off and Doze.** Always Foreground Service. Never `startService`-only for location.
- **Android only.** No iOS. Native logic (GPS service, GraphHopper) sits behind platform channels for clean separation, not for cross-platform portability. Do not add abstraction whose only justification is "so iOS can plug in later".
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

- **Phase 0**: technical prototype (Flutter + `mapsforge_flutter` renders a RudyMap `.map`, GPS dot; validates the open items in spec §6 Phase 0)
- **Phase 1**: core usable (offline map + background track recording + basic GPX I/O)
- **Phase 2**: route planning (GraphHopper integration + waypoints + elevation profile)
- **Phase 3**: update mechanism + polish

Do not jump phases. If Phase 2 work would unblock something in Phase 1, raise it and let me decide; do not just do it.

## Development Conventions

### Code style
- Dart: follow `dart format` defaults. Lint with `flutter_lints` (strict).
- Kotlin: ktlint default rules.
- No `// TODO` without a tracking reference (GitHub issue number or spec section like `// TODO(spec §4.2)`).

### Directory layout (Flutter)
```
lib/
  features/       # feature modules: map, planning, recording, gpx, settings
  core/           # shared infra: gps, storage, platform channels, networking
  data/           # repositories, models, data sources
  ui/             # shared widgets, theming, design tokens
android/
  app/src/main/kotlin/.../   # native code for platform channels
docs/             # spec, design notes, architecture decisions
```

### Testing
- Unit tests for pure Dart logic; target > 70% coverage in `core/` and `data/`
- Widget tests for non-trivial widgets
- Integration tests reserved for critical user flows (recording, planning, GPX round-trip)

### Git
- Branches: `feat/<short-desc>`, `fix/<short-desc>`, `chore/<short-desc>`
- Commits in Conventional Commits format
- Always run `dart format . && flutter analyze` before committing
- Do not commit build artifacts, generated files, or large binary assets

## Working With Me

- **Ask before adding dependencies.** Every new pub.dev or Maven package is a long-term maintenance commitment for a solo dev. Justify it.
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
- mapsforge format & themes: https://github.com/mapsforge/mapsforge
- `mapsforge_flutter`: https://pub.dev/packages/mapsforge_flutter
- OSM Taiwan extract (for GraphHopper routing graph only): https://download.geofabrik.de/asia/taiwan.html
- GraphHopper: https://www.graphhopper.com/

Verify exact Flutter package names against pub.dev - the ecosystem moves fast and `mapsforge_flutter` is a single-maintainer port, so confirm its current status and feature coverage (continuous zoom, rotation, hillshade, CJK fonts) before committing.