# Local references and source management update — 1.4.0 (61)

Status — implementation, focused code review and local verification are complete. GitHub publication is being prepared. Store export, upload and submission are pending. Confirm the currently published Store version before entering cumulative release notes.

## English update text

Use photos from your device library, selected files and folders through persistent references to save storage and speed up selection. Preview, play and classify photos on demand. NAS setup and editing now offer on-demand access or local sync of selected folders. Remove disconnected NAS configurations while retaining downloaded private photos and their frame selections. This update also includes remote photo frames and on-device AI classification with English and Chinese descriptions, editable rules and optional automatic classification.

## 简体中文更新说明

本机相册、文件和目录支持直接引用原照片，减少副本占用并加快选片。预览、播放和 AI 分类按需读取。NAS 新建和编辑页增加按需读取及同步所选目录到本地的选项。断开后的 NAS 配置可删除，已下载私有照片及相框选片可保留。本次更新同时包含远端照片相框及中英文端侧 AI 分类，可编辑规则并选择是否持续自动分类。

## 繁體中文更新說明

本機相簿、檔案及目錄支援直接引用原照片，減少副本佔用並加快選片。預覽、播放及 AI 分類按需讀取。NAS 新增及編輯頁提供按需讀取與同步所選目錄到本機的選項。中斷連線後可刪除 NAS 設定，保留已下載的私有照片及相框選片。本次更新同時包含遠端照片相框及中英文端側 AI 分類，可編輯規則並選擇是否持續自動分類。

The app interface supports English and Simplified Chinese. Traditional Chinese text above is listing copy. The inherited remote-frame and model instructions remain in [RemoteFramesUpdate.md](RemoteFramesUpdate.md) and [AIFramesUpdate.md](AIFramesUpdate.md).

## Review workflow

1. Open Sources and choose device photos, files or a directory. Grant system access and confirm the photos to display. Local reference indexing saves metadata and relationships without creating an additional original-photo copy. Playback and previews read the original as needed.
2. Create a frame, set its cover, reorder its photos and play it. Local-only references use their original provider. Reopen the app to inspect persistence. A revoked permission or moved/deleted original can require reselection. System libraries and file providers may fetch cloud content under system settings.
3. Use the reference photo's hide action to remove it from display globally. Refresh the source and confirm that the hidden photo remains excluded. The app's hide/remove actions update local relationships and caches.
4. Configure a read-only NAS source using separately supplied private review credentials. At setup choose on-demand access or local sync of the selected folders. New sources default to on-demand access. Open Edit to inspect and change the stored selection and mode.
5. Disconnect a synthetic NAS source, then choose Remove source configuration. Confirm the operation. Downloaded private photos and current frame relationships are retained. Remote-only references are removed. Shared copies retain their other origins.
6. Verify AI classification with an optional downloaded model and English or Chinese categories. Review results before creating frames. Automatic classification remains optional and editable. Ordinary playback requires no model download.
7. Clear all app data to inspect removal of app-private copies, caches, models, tasks, saved sources and unused Android persistent reference permissions. Use only synthetic review data for this destructive check.

NAS access remains read-only. Source-level local sync and per-frame local sync use their separately confirmed scopes. Switching to on-demand preserves existing private copies for user-managed cleanup. Decoded images, temporary photo files and AI features use bounded caches. Metadata, model downloads and explicitly retained originals use additional space.

## Candidate verification

Android passed440 JVM tests and31 native tests with zero failures. Six actual system-file and tree workflow phases passed, including persistent grants, distinct-process restart, direct reads, preview/cover/save and grant revocation. The reference AI transport exercised a real resolver bitmap with a test embedder. Full Android model inference was not rerun in this slice. Native component checks cover16 language/font/theme/width combinations. Eight actual screenshots include English narrow portrait, Chinese dark large-text portrait and a1136×640 landscape-shaped NAS viewport. Sensor-driven rotation and the supplemental landscape system-picker combination remain unverified. Android lint reports0errors and205warnings.

The iOS native suite executed278 tests, with272 passed,6 skipped and0 failures. The skipped model/fixture cases have separate isolated model and synthetic-album setup evidence. The isolated model suite passed6 tests, including real Lite inference on a SystemFiles reference. Actual PhotoKit UI workflows passed on iPhone and iPad through album selection, frame save, playback and restart. Root independently read the dedicated iPhone database after that flow and confirmed3 SourceAssets,1 frame,1 selection,0 Assets,0 AssetOrigins,0 ImportJobs and0 app-private original files. The database quick check returnedOK.

The iOS external Files picker could display the isolated fixture's shared container, then the simulator's DocumentManager failed to resolve it. Standalone system Files reproduced the same source-null error after a standard scene-compatible fixture build and simulator restart. Real external-provider file/directory delivery, cross-restart external security scope and movement/removal remain unverified. Native delegate, bookmark, FileCoordinator and restart reader tests passed. This limit is recorded alongside the actual failed attempts and system control evidence.

Root independently verified both platforms' builds, analysis/lint and final package payloads. The single integrated review has0 open Critical/Important/Minor findings. Final metrics, hashes and limits are recorded in [LocalReferencesRelease140.json](LocalReferencesRelease140.json).

Eight opaque RGB iPhone/iPad XCUIScreen captures are included in [ScreenshotEvidence140.json](ScreenshotEvidence140.json). Four are normal Store candidates. Forty additional native viewport/action captures serve bilingual, narrow-screen, dark and accessibility-size QA and are retained locally with the full48-capture manifest. Final Store screenshot selection is pending.

The signed Android APK is154,171,126bytes, SHA256 `b2574f03a6280119e61a3bbe3a162fd2e8b37a71ba88ad1a3313bb92c761d1b6`. Its original signing certificate and4ABIs were verified. The unsigned iOS archive targets15.0 and iPhone/iPad, with executable SHA256 `de3136f79b4d5f61365a3e98ea0eacd9ec1e53e2ab6c7030252c3eb4be4249fe`. Neither payload contains model weights or test media. Retained artifacts are under `.build/releases/v1.4.0/`.

Physical older devices, minimum-OS runtime, real NAS providers and long-running playback remain outside the verified evidence. Unknown provider modification time/size is supported with refreshed generation and without long-lived image/feature caching. A provider replacing content while keeping the same observable metadata and sending no change signal is a metadata-only detection boundary.

Configuration removal preserves historical private files. Current-copy mapping uses version provenance, uniquely committed directory membership or an unambiguous single copy. Multiple historical copies without a determining association require explicit reselection.

## Store dependencies

The prepared iOS delivery is an unsigned archive. A fresh local-only check still found no matching Store profile for the installed original-team signing identities. The existing original publishing team's Store profile and installed Distribution certificate did not match during prior export. Automatic approval rejected refreshing signing assets with `-allowProvisioningUpdates` because that could change production signing assets. Export requires explicit owner authorization and usable original publishing credentials with a matching profile. The current task prepares code, packages and materials within that boundary.

Before upload, confirm the published Store version, use the final matching archive, validate privacy answers against the final binary and published policy, select the final screenshots and supply private read-only review-source credentials. Store upload and submission remain pending.

## Required-reason API check

The direct Files reference provider reads `contentModificationDateKey` and file size for user-selected files/directories. The final app manifest must retain the app-container reason `C617.1` and include `3B52.1` for these user-granted resources. Final archive verification must match the source manifest. Apple's current approved reasons distinguish app-container metadata access from metadata access through a document picker grant. [Apple required-reason API categories](https://developer.apple.com/documentation/bundleresources/app-privacy-configuration/nsprivacyaccessedapitypes/nsprivacyaccessedapitype)
