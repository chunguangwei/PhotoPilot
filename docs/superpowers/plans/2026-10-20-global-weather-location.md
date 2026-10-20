# Global bilingual weather location implementation plan

> **For agentic workers:** Use superpowers:subagent-driven-development or superpowers:executing-plans task by task. Native iOS and Android work is independent and dispatched under dispatching-parallel-agents; integration/release belongs to the primary agent.

**Goal:** Implement approved global bilingual weather location design and publish 1.1.50 with both paired iOS devices updated.

**Architecture:** Preserve raw place identity/names/coordinates; add optional displayZh/displayEn structured objects to stored places, containing locality, subLocality, subAdminArea, adminArea, country. Compact text uses locality plus subLocality only, falling back through subAdminArea/adminArea/country; detailed context uses nonduplicate region components. No street/house fields. Same-ID automatic location updates enrich optional display fields without replacing original place data or downgrading known detail.

**Tech Stack:** UIKit/Swift, native Android Views/Kotlin. Existing providers and persistence only.

**Spec:** design/weather-location-v1.3.md, approved by user 2026-10-20.

## Global Constraints

- iOS 15+ / Android API29+, no new SDK, no code comments.
- Chinese broad-to-specific “城市 · 片区”, English specific-to-broad “District, City”; absent translation falls back to existing local name.
- Stable provider IDs bind translations; never match only by name or nearest coordinate; no new weather request solely for display enrichment.
- Existing selection, coordinates, snapshot TTL and follow-location flag remain intact; location async results cannot overwrite later manual selection.
- User authorizes GitHub release and paired iPhone/iPad covering install, not App Store submission.

## Review Focus

- Rural/city-state/missing locality must show useful existing name without inventing levels (Task1).
- Arabic and accent text must survive normalization and number concatenation (Task1/2).
- Old stored location must enrich once and never lose detail after failure (Task1).
- English-only search and unrelated namesakes must remain correct (Task1).
- Maximum text size and long names must keep temperature visible on narrow devices (Task2).

### Task1: Independent native implementations

Files: WeatherLogic/WeatherRepository, native weather providers/geocoders, picker and settings/player display callers, native weather tests. iOS/Android agents own their platform only; no root16 edits or commits. Optional display objects have nullable locality/subLocality/subAdminArea/adminArea/country, absence decodes safely.

- [x] Add regression tests first for native formatter international inputs, bilingual candidate merging, old persistence and enrichment; run RED before implementing.
- [x] Implement structured optional metadata, formatter, stable-ID bilingual merging, locale-aware search and unified reverse-name paths.
- [x] Preserve raw fields on automatic enrichment and guard delayed location writes against changed selection/follow mode.
- [x] Run native tests and report actual results.

### Task2: Layout and integration

Files: native Player views, test screenshots, contracts/vectors/weather-place-display.json, 04 engineering spec.

- [x] Shared compact/context vectors pin Chinese/English ordering, duplicates, blanks, absent levels, RTL and accents; both native tests consume vectors.
- [x] Split place and conditions into independent text spans/rows, max2 lines place, retain full accessibility text; verify with native layout tests/screenshots on narrow/large text and iPad landscape.
- [x] Integrate both branches in shared workspace, review actual diff and run make check, iOS build/analyze/full XCTest and Android build/lint/full tests.

### Task3: Cleanup and release

- [x] Audited tracked references and removed 96 obsolete Xcode cache/intermediate directories (2,984,221,922 logical bytes); current build caches, products, signing, history and evidence retained. Exact allowlist/result in .build/cleanup-result-v1.1.50.json.
- [x] Set both native versions 1.1.50/build50, regenerate Xcode project, create signed Android Release and iOS Release archive.
- [x] Sign iOS local archive with same certificate/profile/application+keychain entitlements; cover install both paired devices, query versions and running processes.
- [ ] Update 16 actual outcomes and remaining limits; commit, tag v1.1.50, push main+tag and GitHub Release APK+SHA256SUMS; verify remote asset digest/updater metadata.

Actual verification: iOS150/150, Android374/374; Debug/Release/build/analyze/lint passed (Android182warnings0errors); paired iPhone/iPad49→50 install/launch/process and DB aggregate preservation verified. Native layout fixture evidence has no hardware safe insets, worldwide service coverage remains unverified.
