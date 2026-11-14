# Remote Frames Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans within each assigned platform. Prove regression RED then GREEN and retain a single final review of the integrated branch.

**Goal** — Enable online remote-photo selection and frame playback with bounded local storage and optional per-frame offline synchronization.

**Architecture** — Existing SourceAsset metadata and source/path-derived Collection membership feed typed media references. One cache/lease service per platform handles protocol reads, playback, covers, thumbnails and AI. Settings extend existing JSON and keep the17-table schema.

**Tech Stack** — Swift/UIKit/CoreData iOS15, Kotlin/Views/Room API29, existing WebDAV/SMB and AI runtimes.

**Spec** — docs/superpowers/specs/2026-11-15-remote-frames-design.md

## Global Constraints

- User delegated product decisions and opted out of further design questions.
- Per-frame mediaStorageMode remote/local, omitted meanslocal. New remote frames explicitlyremote.
- Source.settings_json.catalogOnly=true gives metadata-only source startup/refresh behavior.
- Canonical SourceAsset selections persist through offline import for explicit-mode frames. Legacy rebinding preserved for old frames.
- Runtime remote:<SourceAssetID> distinguishes remote identities. Persisted FrameSelection ref_key remains rawID.
- Cache128MiB global includingpartials/reservations, free-space reserve64MiB, next-only prefetch, bounded decoded memory. No schema/largeSDK change.
- No new code comments. Root owns contracts/tools/docs/16/release. Platform agents own respective native tree. No personalNAS/physicalM0/Apple account mutations.

## Review Focus

1. Existing1.2.0 frames/settings/database survive upgrade and remote/local roundtrip.
2. Partial/cancelled/concurrent requests cannot exceed cache capacity or delete held/new files.
3. A source/privacy/epoch change rejects late downloads, image display andAI result commits.
4. Scoped offline sync never imports an unrelated NAS directory and stops on mode/selection changes.
5. Cleanup protects other local consumers and recovers after filesystem/database interruption.

## Task1 — Shared contract and independent design check

- [x] Root writes frame-storage contract and shared eligibility/path vectors.
- [x] Fresh read-only builder checked design. Seven concrete issues were resolved in the spec, including provenance and rehydration, frame-scoped rebinding and decode-scoped leases.
- [x] Root runs make check before platform integration and records baseline (57 tool tests and6 source checks passed).

## Task2 — iOS complete vertical slice

- [x] Add failing domain/protocol tests for metadata-only Source scan, typed refs, bounded cache and authority leases.
- [x] Implement service files under ios/PhotoPilot/Data/RemoteMedia and Domain/RemoteMedia as needed. Reuse adapters/clients and makeContext/transaction conventions.
- [x] Extend FramePlaybackSettings/FrameRepository/SelectionResolver plus FrameEdit/AssetGrid and remote source connection picker. Preserve unrelated JSON settings and legacy callers.
- [x] Route PlayerImageLoader/PlayerViewController, ThumbnailLoader/covers and AIFrameStore/Service through media lease resolution. Add revision-aware cost-limited image cache and change/revocation observers.
- [x] Add scoped offline import, canonical-ref preservation, confirmed shared-aware local-copy cleanup and crash recovery.
- [x] Re-run RED cases GREEN plus old full suite. Run make ios-generate for config edits, build/analyze/archive. Isolated simulator UI in English/Chinese and iPad large/landscape, offline and small-storage states.
- [x] Deliver test commands/results, screenshots and signedDevelopment/unsigned artifacts. Do not change Apple assets.

## Task3 — Android complete vertical slice

- [x] Add failing unit/Room/protocol tests mirroring Task2 and shared vectors.
- [x] Implement remote metadata membership without schema changes, typed PlaybackItem and cache leases under data/remote as appropriate.
- [x] Extend FramePlaybackSettings/FrameRepository/SelectionResolver, MainActivity/Nas connection and frame editor/remote picker. Route PlayerEngine/View, covers and AI through the service.
- [x] Scoped local import, stable source references, confirmed shared-aware cleanup/recovery and lifecycle invalidation.
- [x] Run native regressions, all unit tests, Debug/Release/lint and bilingual UI configurations. Preserve release keystore and fourABIs.
- [x] Deliver actual APK size/SHA and logs. Do not publish or edit root files.

## Task4 — Integrated review, package and publish

- [x] Root verifies each deliverable through normal entry points and shared cache/quota/refs tests.
- [x] Single fresh integrated review, triage confirmed issues and one fix pass with regressions.
- [x] Root final make check, platform suites, actual final payload/signatures/version and no credentials/fixture/model weights.
- [x] Prepare version1.3.0(60) bilingual update text/privacy/screenshot evidence. Update16 actual outcomes/limits.
- [ ] Commit/merge into existing main preserving user changes, publish existing GitHub release/tag/assets, anonymously verify complete downloads.
- [x] Retain Store preparation artifacts and report the actual outstanding signing/account approval dependency.
