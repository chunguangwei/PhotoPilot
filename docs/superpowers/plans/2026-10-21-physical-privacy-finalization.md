# Physical regression and privacy finalization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Verify the connected devices safely and align the next release with verifiable privacy/licence facts.

**Architecture:** Use an independent XCTest runner against the installed iOS app, without reset arguments, and adb UI automation against the Android release. Restrict new imports to synthetic review images. Remove the unverified automatic weather fallback and retain the primary provider/cache behavior; prepare factual bilingual policy and licence documents.

**Tech Stack:** UIKit/Swift, Kotlin native Views, XCTest, adb, XcodeGen, Gradle.

**Spec:** User authorisation2026-10-21; AGENTS.md; 10-合规与上架材料.md §13; store/appstore/review/SubmissionMaterials.md.

## Global Constraints

- 双端原生 Swift/UIKit、Kotlin 原生 Views；iPadOS 15+ / Android 10 API 29+。
- 中英双语，系统授权界面语言由系统控制。
- 导入不等于展示；未经确认的新照片不能自动公开。
- Preserve existing device libraries; never use -uitest.reset, uninstall or clear-data on personal apps.
- Keep credentials outside Git; no store upload/submission or invented external legal conclusions.

## Review Focus

- A disconnected/locked device is NOT_RUN, never a passing test.
- Existing frames/sources must survive regression.
- Source import must require explicit display selection.
- Weather failure must use valid cache or hide the row, with no unverified provider request.
- Third-party licence permissions must survive project non-commercial terms.

### Task1: Safe physical regression

**Files:** private .build/physical-regression-2026-10-21/ runner and evidence; final16 summary.
- [ ] Build/sign standalone iOS runner using existing wildcard development profile; inspect installed UI without reset.
- [ ] Run both NAS protocols through recursive6-image import, explicitselection and playback on available devices; inspect language/settings/rotation/large text where safe.
- [ ] Verify library integrity/count deltas; record skipped cases and blockers explicitly.

### Task2: Privacy and licence preparation

**Files:** PRIVACY.md; store/appstore/review privacy/terms/rights/SMB records; AppEnvironment.swift/.kt.
- [ ] Remove active wttr forecast fallback; use existing provider/cache/failure regressions and verify no production construction of WttrProvider.
- [ ] Adopt verified supplier facts and standard EULA plus independent third-party rights; retain specific unresolved factual inputs.
- [ ] Update bundled notices/policy references if necessary; run relevant platform tests/build/static checks and make check.

### Task3: Delivery

**Files:** versions/build artifacts, review packet and16.
- [ ] Build final signed packages, install in place and verify versions and data preservation where devices permit.
- [ ] Update review evidence, commit/push authorized repository and publish any necessary update; no App Store submission.
