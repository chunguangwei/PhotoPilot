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
- [x] Updated actual results; commit 0f3c74c/tag v1.1.50 pushed, GitHub Latest published with APK+SHA256SUMS; remote digest/size/HTTP200 verified. Authenticated updater metadata verified; local anonymous API rate-limited403, not claimed passing.

Actual verification: iOS150/150, Android374/374; Debug/Release/build/analyze/lint passed (Android182warnings0errors); paired iPhone/iPad49→50 install/launch/process and DB aggregate preservation verified. Native layout fixture evidence has no hardware safe insets, worldwide service coverage remains unverified.

## User revision v1.1.51: inline place and temperature

The user approved single-row place+temperature on all platforms, with wide/narrow adaptation. This supersedes Task2's vertical-row design; geographic data and service rules stay unchanged.

- [x] Observe native layout regressions RED: same row, wide complete centered group, narrow/max font full temperature, long/RTL names ellipsized with complete accessibility.
- [x] Use independent same-row labels: reserve temperature prefix width, let place and condition description tailtruncate when needed, no infinite font shrink or change to stored text.
- [x] Run iOS full tests/build/analyze/Release and Android full tests/build/lint/signed Release, then sign/install both paired devices and verify stored library counts.
- [x] Updated16/design; commit ccb18bc and tag v1.1.51 pushed, Latest published with APK+SHA256SUMS. Authenticated+anonymous latest both51/HTTP200, server digest/size and public download HTTP200 verified.

v1.1.51 actual: iOS153/153, Android379/379; all required build/analyze/lint/Release checks passed; paired devices50→51 install/launch/process and matching DB aggregates verified. 320AX and short-landscape native screenshots inspected. Android native runtime UI unavailable.


## 2026-10-21 续轮：无天气静默隐藏发布与商店准备

用户授权：发布此前已实现的隐藏调整，覆盖升级两台现有真机、更新既有 GitHub，再准备 App Store 剩余工作；不执行商店上传/提交。

1. 双端升至 1.1.52(52)，重生成工程，验证检查、iOS build/analyze/测试与 Release 归档、Android build/lint/test 与同证书签名 APK。
2. 沿用旧开发签名及钥匙串权限，两台真机安装前后只读核验数据库汇总，覆盖升级、版本/启动/进程确认，不卸载或重置。
3. 提交、tag、既有 GitHub Release/Latest，校验资产 SHA-256 与匿名更新入口。
4. 更新最终版本商店截图及审核操作材料，补分发许可源码交付/重构建操作证据、天气隐私实际数据流和 ASC 表单准备；外部服务/团队/许可判断不能伪报已完成。
5. 主线程回填16、复核遗留；定价、SMB范围、审核NAS信息缺口已异步向用户询问，等待期间推进独立工作。

证据与执行进度归档 `.build/releases/v1.1.52/`、`.build/device-install-v1.1.52/` 和 `.build/appstore-audit/`；既有主线已获发布授权，延续原工作区执行，避免生成额外闲置分支或重复方案文件。

### 同轮用户追加：鸿蒙 4.3.0 语言失效与全量检查

- Android旧系统语言/外观兼容、英文界面语言选项误标；iOS同步验证切换与冷启持久化。
- 已确证并修复中的设置问题：Android重复打开旧实体、顺序队列与暂停常亮；iOS统一时钟/日期/位置/音乐设置应用及设置页天气迟到回写。日期保留系统地区（01 §10.1），不把地区格式误判为语言失效。
- 网络/存储：双端Wi-Fi偏好接线、可恢复暂停/重试、NAS失败状态、防止清缓存删除任务/分享暂存；未设置Wi-Fi值按PRD默认开启、已有false不改。
- 新增兼容recreate必须同时核验旧runner退出/新runner恢复的生命周期边界，不能出现两个runner处理同一任务。
- 本轮初次1.1.52装机已做但未公开发布；最终代码变化后必须重归档/重装/复核，再GitHub发布。此前截图/测试失败保留，不写成最终通过。

未接入统计服务的“分享使用统计”开关从双端现行设置页收起，避免保存后没有实际作用的控件；保留既有数据键及词条，不引入统计SDK或外发。
