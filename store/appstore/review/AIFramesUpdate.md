# AI Frames update preparation — 1.2.0

Status — local update packet prepared for the already-published PhotoPilot app. App Store signing export awaits Xcode account login.

## Proposed What’s New / 更新说明

Create photo frames with local AI. Describe categories in English or Chinese, choose albums or folders, and review matching photos and editable name suggestions. Download either supported model when you need it. Choose one-time classification or optional automatic classification of newly imported photos, and change this preference later. Photos and prompts stay on your device.

新增本地 AI 创建相框。输入中英文分类描述，选择相册或目录，查看匹配照片与可修改的名称建议。两种模型均按需下载。支持仅处理本次或可选的持续自动分类，后期可修改。照片与提示词均在本机处理。

## Proposed App Review notes

This update adds optional on-device photo classification to the existing photo-frame workflow. No sign-in or cloud AI account is required. Ordinary manual frames and playback do not require an AI model download.

1. Import non-private test photos through the existing Photos or Files flow. Use clearly identifiable, lawfully usable test photos to test semantic classification. The included abstract samples support slideshow testing.
2. Open Frames and choose Create AI frame. Select one of the two local image-text classification models. The standard model download is 387,710,976 bytes. The Lite package including both encoders, projection and vocabulary is 201,547,675 bytes. Peak memory requirements are higher and the app checks device resources before loading a model.
3. Select Auto, China, or Global under model sources. China prioritizes ModelScope, Global uses Hugging Face, and hf-mirror provides a fallback. Internet access is needed for the initial download. Installed models classify locally. Each downloaded file is checked against the same pinned SHA256. Availability varies by network.
4. Select imported album/folder scope and whether to include subfolders. Enter categories in English or Chinese. Include/exclude phrases and all/any inclusion rules narrow matching. Similarity scores require user review of the proposed matches.
5. Run classification, inspect matched and uncertain photos, edit the proposed names and save the corresponding frames. AI does not generate or alter the photos.
6. Automatic classification is an explicit optional setting, initially off. It can be changed later on an existing AI frame. It applies to later-imported eligible photos within the selected scope. Processing runs while the app is active and pauses when required by system resource conditions. Manual exclusions and additions remain under user control.
7. Pause/cancel and resume controls expose progress. Deleting a downloaded model does not delete existing photos or frames. Model download failure does not prevent ordinary photo playback.

Downloaded files contain model weights and tokenizer data. Inference and rule-execution code is included in the reviewed binary. Model scores and name suggestions may be imperfect and can be edited before saving.

## Checks required against the exact final binary

- Real standard and compact inference on iOS and Android; English/Chinese prompts and mixed-language text; image preprocessing/EXIF and tokenizer parity.
- Fresh install, no model, low space, interrupted download/retry, bad file hash, WiFi-to-cellular policy, model removal and offline inference.
- Editable auto classification, cancelled job remains cancelled, rule edit/deletion invalidates old work, manual include/exclude protection, no photo promotion outside selected scope.
- iPhone/iPad/Android narrow/wide/landscape/large text and keyboard; version upgrade preserves existing library.
- Signed final archive and APK, bundled third-party notices, exact versions and resources. Record actual test outcomes in the execution ledger.
- New App Store screenshots taken from the final native UI. Include the AI creation and preview screens.
- Privacy policy published before update submission; verify current App Privacy answers against actual model-download provider processing. Local prompts and embeddings remain on-device. Review provider IP and request logging when confirming the final privacy answers.

The user requested GitHub publication and App Store update preparation. Upload and submission can follow after the release candidate and materials are reviewed.

## Screenshot preparation

Use native English and Simplified Chinese captures of model selection and the AI frame form. iPhone17Pro captures at1206×2622 and iPad13-inch captures at2064×2752 or2048×2732 fit the documented device slots. Use opaque PNG or JPEG files. Capture the running UI with app-owned sample assets or lawfully usable photos. Checked2026-11-14 against [Apple screenshot specifications](https://developer.apple.com/help/app-store-connect/reference/app-information/screenshot-specifications).

## Description addition

Append this paragraph to the existing description when preparing version1.2.0.

Create frames with local AI by describing categories in English or Chinese. Select imported albums or folders, add include/exclude rules, and review photo matches and editable name suggestions before saving. Two models are available as optional downloads of approximately388MB and202MB. Automatic classification of later imports is optional and can be changed later. Photos and prompts are processed on-device. Model availability depends on device resources.

使用本地 AI 创建相框。输入中英文分类描述，选择已导入的相册或目录，设置包含与排除规则，预览匹配照片并修改名称建议后保存。两种模型按需下载，大小约388MB和202MB。后续导入照片的自动分类可选，并可随时修改设置。照片与提示词均在设备本机处理，模型运行受设备资源条件影响。

Traditional Chinese store update copy is provided for the existing store localization. The app interface supports English and Simplified Chinese.

新增本機 AI 建立相框，支援中英文分類描述、相簿或目錄篩選、包含與排除規則、照片預覽及可修改的名稱建議。兩種模型按需下載。持續自動分類為可選設定，之後可修改。照片與提示詞均在裝置本機處理。

## Package and signing status

The final1.2.0(59) candidate passed227 iOS unit tests,5 iPhone UI workflows and5 iPad UI workflows. Android passed415 unit tests and7 native regression cases. The execution ledger records the exact simulator/device scope. The archive targets iOS15.0 and later on iPhone and iPad. The standard model requires more than2GB physical RAM and sufficient available memory. Both models have resource preflight and memory-pressure handling.

A Development-signed archive is available for re-export with the original publishing team. App Store IPA export currently requires Xcode account login and a refreshed matching Store profile. The existing profile contains an older signing certificate. Automatic refresh returned `No Accounts`. Resume export after signing into the original publishing account in Xcode Settings → Accounts. The archive and preparation materials can be retained while this account dependency is resolved.

Physical-device AI performance, lowest-supported-OS execution and China mainland operator-network download access remain unverified. The Android standard-model emulator compatibility test used an explicit HWCAP workaround, as detailed in the execution ledger.

## Prepared screenshots

Eight opaque native screenshots cover English and Simplified Chinese model selection and configuration on iPhone and iPad13-inch. They are stored in [the versioned screenshot folder](../ai-frames-v1.2.0). Exact dimensions, hashes and capture provenance are recorded in [ScreenshotEvidence120.json](ScreenshotEvidence120.json).
