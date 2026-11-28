# Ok Map — Product and Engineering Roadmap

Status: living plan for `feature/offline-navigation`.
Scope: Android app, offline-first, current Georgia data package. This document records verified repository facts separately from proposals. It is not a claim that planned features already exist.

## Product direction

**Ok Map: dependable navigation when the network is unavailable.**

The product should prioritize trustworthy routing, durable user data, understandable controls, and predictable offline behavior over feature count. Preserve the no-INTERNET-permission guarantee unless a separately reviewed product decision changes it. Do not merge this branch or modify `main` as part of this roadmap.

## Verified baseline

- Android application using MapLibre Native, Kotlin, SQLite, OSM-derived vector tiles, a local places index, a binary road graph, optional relief/contour data, and cached satellite imagery.
- The current default dataset and several validation assumptions are Georgia-specific. A manual workflow can accept another OSM extract, but this is not yet an in-app regional package manager.
- Car routing exists, including one-way roads, static via-node/via-way restrictions, and a supported subset of conditional via-node restrictions. README explicitly documents unsupported restriction cases and vehicle dimensions/weight, tolls, ferries, traffic, and other gaps.
- GPS track recording runs in a foreground service. User waypoints and tracks are stored in SQLite. GPX import/export is streamed and import is transactional.
- The database schema is version 3. Track points now persist a segment identifier; GPX import keeps multiple `trkseg` elements under one app track, and export writes segment boundaries. Map geometry uses separate lines and elevation-profile distance resets at segment boundaries. These changes are implemented, but a full parser-level GPX import/export round-trip test is still outstanding.
- The current UI is concentrated in `MainActivity.kt` and `activity_main.xml`; the main activity owns many unrelated workflows.
- Current Android instrumentation checks database rollback/streaming, v1-to-v3 migration (including legacy track points), separate segment geometry, the no-INTERNET manifest invariant, packaged offline assets, and basic layout geometry.
- CI run #236 completed successfully on commit `c490ae78939408ad884fef3ec659119cc9d9d129`: Kotlin router unit tests, Android emulator smoke tests, APK assembly, and artifact upload all passed. The `ok-map-apk` artifact was uploaded (448,468,917 bytes). This is emulator/CI evidence, not physical-device validation.
- The app has 3D building extrusion, not 3D terrain. README documents that distinction.

## Non-negotiable product principles

1. Offline core: map rendering, local search, routing on installed data, GPS, saved places, track recording, and GPX must not require network access.
2. Honest coverage: show region/data coverage, source date, and missing capabilities; never imply a road or restriction dataset is complete when it is not.
3. Safe routing: unsupported or ambiguous restrictions must not be silently presented as verified safe routing.
4. User data integrity: imports, migrations, exports, and interrupted writes must preserve user data or fail without partial changes.
5. Map-first UX: keep the map dominant; advanced tools must be discoverable without permanently covering the map.
6. Measured change: do not remove or combine features until their behavior, entry points, and user impact have been audited.
7. Evidence before release: passing unit tests alone is insufficient; require emulator, artifact, and physical-device checks.

## Current progress snapshot (2026-11-28)

- P0.1 CI: passed on the current recorded head in run #236; repeat on any later commit before release.
- P0.2 GPX segments: schema/import/export/rendering/profile implementation is present. Parser-level multi-segment round-trip, malformed-file rollback, empty-segment and multi-track cases still need direct tests.
- P0.3 persistence/lifecycle: basic rollback and v1 migration coverage exists; v2-to-v3 migration and process/service lifecycle failure cases need explicit regression coverage.
- Physical-device validation has not been performed as part of this repository workflow.
- The open PR still contains pre-existing unrelated repository noise (macOS `.DS_Store` and a Python `__pycache__` bytecode file) that should be reviewed and removed from the PR diff before merge.

## Prioritized delivery plan

### P0 — Release integrity and data correctness

**P0.1 Finish and stabilize CI**
- Confirm a successful workflow on the exact reviewed head and artifact upload (run #236 passed for commit `c490ae7`).
- Repeat the workflow after subsequent changes; fix failures at their source; do not skip, weaken, or mark required tests as optional to obtain green CI.
- Add bounded timeouts and useful diagnostics to long-running emulator/build steps.
- Keep optional data visibly optional; mandatory map, search, graph, app compilation, and core tests must fail the build when invalid.

Acceptance:
- A fresh workflow completes successfully from the current branch head (run #236 passed on `c490ae7`; rerun after any later change).
- Unit tests, Android instrumentation tests, map/graph/contour validation, APK assembly, and artifact upload all succeed.
- The APK artifact is present; download/install and package/version inspection remain to be done.
- The run SHA matches the reviewed branch head.

**P0.2 Preserve GPX track and segment structure**
- Add a segment field to stored track points and migrate database v2 to v3 without data loss.
- Import all `trkseg` elements belonging to one `trk` into one app track.
- Preserve segment boundaries during streaming export.
- Keep batch writes bounded and the whole import atomic.

Acceptance:
- A GPX containing one track with multiple segments imports as exactly one track with the original segment count and point order (implementation present; direct parser test outstanding).
- Export → import round-trip preserves track names, segment boundaries, coordinates, elevation, and timestamps within format precision (not yet verified by an automated round-trip test).
- Large GPX imports remain batched; malformed input rolls back all imported records (needs parser-level regression coverage).
- v1 and v2 databases migrate without losing existing waypoints or track points (v1 is covered; add an explicit v2-to-v3 migration test).

**P0.3 Expand persistence and lifecycle regression coverage**
- Test migration paths, rollback, interrupted imports, empty segments, multiple tracks, large point batches, and recording service shutdown.
- Verify user data survives app process recreation and app upgrade.

Acceptance:
- Each migration has an instrumentation test from its immediately previous schema.
- Failure injection proves imports are atomic.
- Tests verify no orphan track points and no accidental splitting/merging of tracks.

### P1 — Navigation trust and GPS quality

- Build a routing capability matrix from OSM tags and document each supported/unsupported restriction.
- Add tests for via-way restrictions, overlapping restrictions, conditional windows, one-way access, private/destination access, barriers, roundabouts, and disconnected graphs.
- Add vehicle profile dimensions/weight only when the source data and algorithm can enforce them reliably.
- Improve GPS map matching with accuracy-aware thresholds, heading/speed confidence, U-turn/reverse travel, and hysteresis against GPS jumps.
- Add clear route warnings for off-network connectors, unsupported restrictions, stale data, and low GPS accuracy.
- Test arrival detection, rerouting, tunnel/no-fix behavior, and background navigation on physical devices.

Acceptance:
- A published route test corpus has expected legal/illegal paths and regression results.
- No known unsupported restriction is silently described as enforced.
- GPS noise does not cause repeated false reroutes or false arrival in recorded replay tests.
- Background guidance and recording survive screen-off and process/lifecycle transitions supported by Android.

### P2 — Map-first UX and accessibility

- Audit every visible control, menu item, dialog, tab, and setting for duplicate entry points, overlap, unclear labels, and low-frequency actions.
- Keep the map as the primary surface; group secondary tools into coherent destinations.
- Separate map content/layers from visual appearance (day/evening/night and palette).
- Consolidate saved waypoints, folders, tracks, and GPX actions into one understandable library without removing existing capabilities.
- Review touch targets, small screens, landscape, tablets, edge-to-edge insets, keyboard behavior, TalkBack, contrast, and font scaling.
- Move hard-coded user-facing strings into resources and verify translations.

Acceptance:
- Every control has one clear purpose and one predictable place.
- No unintended overlap at supported screen sizes and font scales.
- Core actions (search, select destination, route preview/start/cancel, save place, record/stop track) are reachable without navigating nested settings.
- Accessibility and layout smoke tests cover representative screen sizes and font scales.

### P3 — Regional offline data management

- Replace the current build-time-only region workflow with a defined region package format.
- Each package should declare bounds, source and source date, schema/graph versions, hashes, required/optional assets, and storage estimate.
- Support import/export of packages from local files first; consider network downloads only as a separate opt-in feature and only after a privacy/security review.
- Install, validate, activate, update, and remove packages atomically; retain the previous valid package if an update fails.
- Avoid loading a route graph or search index for a region whose assets are missing or incompatible.

Acceptance:
- A user can install and remove a region from local storage without losing saved places/tracks.
- Interrupted package installation leaves the previous package usable.
- Data provenance, age, coverage, and storage use are visible.
- Package compatibility is checked before activation.

### P4 — Additional routing modes

- Add walking, cycling, and off-road profiles as separate graph-costing and access-rule implementations, not as labels over the car router.
- Model surface, track type, steps, access restrictions, bicycle permissions, and elevation cost where data supports them.
- Include route quality/coverage warnings and profile-specific test corpora.

Acceptance:
- Each mode has independent routing rules and regression tests.
- No mode is exposed as supported until route legality and representative real-world routes have been checked.
- Routes explain major trade-offs such as distance, ascent, surface, and restricted access.

### P5 — Terrain, trip planning, and safety

- Evaluate a true terrain rendering pipeline independently from 3D building extrusion.
- Add multi-stop trip planning, route saving/reversal, and reusable route collections.
- Add configurable trip dashboard metrics (speed, elevation, heading, distance, elapsed time, GPS accuracy).
- Add safety tools such as return-to-start, off-route/time alerts, emergency coordinate sharing/export, and user-controlled check-in reminders. Avoid implying emergency dispatch or guaranteed rescue.
- Consider weather or live traffic only as optional online add-ons; never make them dependencies of offline core navigation.

Acceptance:
- Terrain rendering has measured memory, battery, and frame-time budgets on supported devices.
- Multi-stop routes handle reordering, deleted destinations, and offline gaps predictably.
- Safety features state their limitations and work without requiring a server where possible.

## Removal and consolidation policy

No feature is scheduled for deletion based on appearance or assumption alone. First inventory its entry points, dependencies, user-data impact, and usage. Likely consolidation candidates (subject to that audit):
- Separate map type and appearance controls into distinct but adjacent sections.
- Consolidate waypoint/folder/track/GPX management into a single personal library.
- Consolidate navigation preferences into named travel profiles.
- Keep diagnostics and advanced tools out of the primary map surface, but retain them in an accessible tools/help area.
- Retain compass and Qibla as optional tools unless evidence shows they create confusion or maintenance risk.

## Release gates

A release candidate is not complete until all are true:
- CI is green on the exact release commit and required artifacts are uploaded.
- Offline permission invariant is tested in the packaged manifest.
- Data integrity, migrations, GPX round-trip, routing, and layout regression tests pass.
- APK installs and launches on a physical Android device.
- GPS fix, voice guidance, background navigation/recording, screen-off behavior, battery impact, and recovery after force-stop/reboot are checked on physical hardware.
- Map coverage, data age, attribution, and unsupported navigation cases are documented.
- No unresolved P0/P1 defect remains.

## Working method

For every task: inspect the current implementation and related call sites first; make the smallest coherent change; add a regression test; run the narrow test; run the full relevant suite; inspect the diff; verify CI on the resulting commit. Do not begin the next feature while a blocking regression remains.
