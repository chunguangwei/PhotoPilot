# Store listing fields — 1.1.58 preparation

This is a preparation worksheet, not an App Store Connect save, upload or submission. The native interface supports English and Simplified Chinese. Traditional Chinese below is store copy only; do not claim Traditional Chinese UI support. The requested availability is outside China mainland and the EU, subject to each territory's final account/compliance requirements.

**上架区域决策（2026-10-20 用户裁决）**：仅上架**非中国大陆地区**（App Store Connect地区选择排除China mainland；其他地区仍按当地要求完成资料，不将排除大陆当作全球合规豁免）。本地化语言三套：**English（主语言 en-US）、繁體中文（台灣/香港）、简体中文（新加坡等）**。

| 字段 | 简体中文 | 繁體中文 | English |
|---|---|---|---|
| 名称 | 光屿 PhotoPilot | 光嶼 PhotoPilot | PhotoPilot - Photo Frame |
| 副标题（30 字符内） | 安静的家庭电子相框 | 安靜的家庭電子相框 | A quiet home for your photos |
| 关键词（100 UTF-8 字节内，逗号分隔） | 电子相框,照片播放,幻灯片,平板相框,NAS照片,离线相册,家庭照片,数字相框 | 電子相框,照片播放,幻燈片,平板相框,NAS照片,離線相簿,家庭照片,數位相框 | digital photo frame,slideshow,photo frame,NAS photos,offline album,family photos |
| 支持 URL | https://github.com/chunguangwei/PhotoPilot | 同左 | 同左 |
| 隐私政策 URL | https://github.com/chunguangwei/PhotoPilot/blob/main/PRIVACY.md | 同左 | 同左 |

**宣传文本（≤170字符，最终按App Store Connect可编辑状态操作）**：
- 简体中文：节日规则自动切换专属相框，可选内置背景音乐或自己的音频文件，照片只在本机处理，无广告、不需要账号。
- 繁體中文：節日規則自動切換專屬相框，可選內建背景音樂或自己的音訊檔案，照片只在本機處理，無廣告、不需要帳號。
- English: Holiday rules switch frames with music for birthdays, weddings and festivals. Your photos stay on your device. No ads, no account.

**中文描述**：把闲置的 iPad 或 iPhone 变成一台安静的家庭电子相框。导入本机、文件或 NAS（WebDAV/SMB）中的照片，按相框精选内容，配上时钟、日期与天气，常亮播放。导入不等于展示：先导入，再确认每个相框具体展示哪些照片；完整展示加模糊补边，横竖照片都不裁掉主体；停留时间与转场时长独立设置；可按节日规则自动切换专属相框，配上一首背景音乐，可选内置曲目或自己的音频文件。照片只在本机处理：不上传、无广告、不需要账号。

**繁體中文描述**：把閒置的 iPad 或 iPhone 變成一台安靜的家庭電子相框。匯入本機、檔案或 NAS（WebDAV/SMB）中的照片，按相框精選內容，配上時鐘、日期與天氣，常亮播放。匯入不等於展示：先匯入，再確認每個相框具體展示哪些照片；完整展示加模糊補邊，橫豎照片都不裁掉主體；停留時間與轉場時長獨立設定；可按節日規則自動切換專屬相框，配上一首背景音樂，可選內建曲目或自己的音訊檔案。照片只在本機處理：不上傳、無廣告、不需要帳號。

**English description**: Turn a spare iPad or iPhone into a quiet digital photo frame. Import photos from the device, files, or your NAS (WebDAV/SMB), curate frames, and play them with clock, date and weather overlays. Importing is not displaying: import first, then choose exactly what each frame displays; full-image display with blurred fill keeps every composition intact; photo duration and transition length are independent settings; holiday rules switch frames automatically, and optional background music lets you choose a bundled track or your own audio file. Your imported photos are processed on your device, with no photo uploads, ads or PhotoPilot account.

**审核备注（App Review Notes 草案，最终构建实测后提交）**：

No PhotoPilot account or sign-in is required; no app login credentials are needed. On first launch, tap “Take a look first” for an offline sample slideshow without photo or location permission. This preview does not add sample photos to the library and does not expose the full frame editor or music settings. Tap “Exit sample”, then “Choose photos”. Import permitted JPEG/PNG images through Files or “Photos on this device”, select the photos to display, create a frame, save and start playback. Imported photos are not displayed until the user confirms the frame selection. Playback settings include background music and independent photo/transition durations. Files import is available when photo-library permission is declined.

Weather is optional and off by default. Choose a city manually to test weather without location permission. Choosing current location enables location following when playback starts; manual city selection disables following. Forecast requests send coordinates rounded to 0.1 degrees to Open-Meteo. There is no active wttr.in fallback in this build. The system geocoder resolves original system-provided locations before rounding. The providers see the connection IP; Open-Meteo documents server logs retained for up to 90 days. When weather cannot be obtained, the app uses its cache validity rules and hides the playback and rest-screen weather row when no valid weather is available; downloaded photos can still play offline.

NAS import supports user-configured WebDAV and SMB servers and is optional for local photo playback. The separate review instructions must provide tested server access, credentials and steps for this feature. PhotoPilot does not upload photos or delete originals from the system library or NAS. Google Photos and video playback are not offered in this release.

**已准备/提交边界**：六张合成样图、审核步骤和只读公网NAS环境已准备，见store/appstore/review；账号凭证只填私有App Review信息。正式提交时确认实际附件/链接与最终Distribution包，未附的资料不写成已附。完整逐项隐私/加密/地区工作单与最终真机证据以该目录最新文件为准。


## Field checks and remaining factual inputs

- Primary language: English (U.S.); proposed primary category: Photo & Video. Free download; no ads, In-App Purchases, subscription or PhotoPilot account. No hypothetical future paid feature is advertised.
- Existing bundle ID: `dev.photopilot.scaffold`, version1.1.58/build58. Final owning team/app record and first-release store version must be confirmed in the actual account; no new identifier has been registered.
- Current screenshots: see ScreenshotEvidence.json and the iPhone/iPad directories. They are simulator Release captures, not final Distribution acceptance. Refresh if final submitted UI differs; do not use test screenshots containing credentials or personal photos.
- Support: https://github.com/chunguangwei/PhotoPilot ; privacy: https://github.com/chunguangwei/PhotoPilot/blob/main/PRIVACY.md ; public support email: chunguangwee@gmail.com . These do not replace required review-contact name/phone or verified publisher identity.
- Copyright owner/year, seller identity, review-contact name/phone and DSA factual status remain owner/account inputs. Do not infer legal identity from a development certificate or machine username.
- Terms: select Apple Standard EULA, retaining the prepared third-party/LGPL permission supplement. Do not paste the root source-code licence as an exclusive replacement for Apple's standard app EULA.

Keyword byte counts were checked locally: Simplified Chinese97 UTF-8 bytes, Traditional Chinese97, English80; the proposed subtitles are below30 characters. Apple currently allows100 keyword bytes; validate all final edits in the real form. [Apple platform-version fields](https://developer.apple.com/help/app-store-connect/reference/app-information/platform-version-information/).

## Age-rating factual worksheet

Suggested answers below follow the current private photo-frame feature set and must be compared with the actual form. Apple computes the resulting regional age ratings; no fixed rating is predeclared. [Apple definitions](https://developer.apple.com/help/app-store-connect/reference/app-information/age-ratings-values-and-definitions/).

| Field group | Prepared answer / basis |
|---|---|
| Parental controls / age assurance | No dedicated parental-control or age-assurance feature; no age-range gate implemented |
| Unrestricted web access | No: no embedded general-purpose browser or arbitrary website navigation; NAS image retrieval and external support/privacy links are not an in-app web browser |
| User-generated content / messaging / chat | No broad public content distribution, social feed, messaging or chat; users privately select/import their own photos |
| Advertising | No |
| Medical / wellness advice | None; photo display and weather are not health advice |
| Violence, sexual content/nudity, profanity, horror, substance-use content | None in the supplied product/review artwork and music; private user imports do not create an app-curated public catalogue |
| Gambling, simulated gambling, contests, loot boxes | None |
| Purchases/subscriptions | None |
| Kids category | Not proposed as a children's app; do not select Made for Kids without a separate product/compliance decision |

Account-specific additional questions or changed definitions must be reviewed against the exact final binary rather than answered automatically from this table.

**地区更新（2026-10-23）**：用户明确仅非大陆、非EU首发，覆盖上方旧“其他地区”计划；当前ASC147地区已保存。原生支持iPhone/iPad、无需PhotoPilot账号登录。
