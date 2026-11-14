# Remote frame update preparation — 1.3.0 (60)

Status — native implementation, integrated code review and local verification are complete. GitHub publication is pending. This packet contains update text and review materials. Store upload and submission are pending.

## English update text

Play photos from your WebDAV or SMB server on demand. New remote frames keep a bounded shared photo cache to save device storage. Turn on local sync in frame settings when you want offline access. Keep your selections and classification rules when changing storage mode. Create AI frames with English or Chinese category descriptions, editable rules and name suggestions. Local AI classification can read selected remote photos on demand. Two models are optional downloads, and automatic classification is optional and editable.

## 简体中文更新说明

新增远端照片按需播放。连接 WebDAV 或 SMB 后，在线选择照片并创建相框，通过限额共享缓存节省设备空间。需要离线播放时，可在相框设置中打开本地同步。切换存储方式时保留选片和分类规则。同时支持本地 AI 创建相框，通过中英文分类描述、可编辑规则与名称建议整理照片。AI 可按需读取选定的远端照片，两种模型按需下载，持续自动分类可选并可后期修改。

## 繁體中文更新說明

新增遠端照片按需播放。連接 WebDAV 或 SMB 後，線上選取照片並建立相框，透過限額共用快取節省裝置空間。需要離線播放時，可在相框設定中開啟本機同步。切換儲存方式時保留選片和分類規則。同時支援本機 AI 建立相框，透過中英文分類描述、可編輯規則與名稱建議整理照片。AI 可按需讀取選定的遠端照片，兩種模型按需下載，持續自動分類為可選設定並可後續修改。

The packet includes the AI features previously released on GitHub in1.2.0. Store export of that candidate is pending. Verify the currently published Store version when entering update text. The detailed model-download and AI review steps remain in [AIFramesUpdate.md](AIFramesUpdate.md).

## Draft review workflow

1. Use the local-library entry points to import and play device photos or files. AI model downloads are optional.
2. Configure a read-only WebDAV or SMB source using separately supplied review credentials. The review server should contain only synthetic or lawfully usable non-private photos. Metadata-only browsing indexes directory entries before a photo is selected for display.
3. Create a remote frame, choose its photos or folder scope, and confirm the selection. New remote frames default to on-demand storage. Connect through a network allowed by the app's existing network policy.
4. Start playback. The shared raw-photo cache has a 128MiB limit, including temporary downloads. A 64MiB free-space reserve applies before writes. The app fetches photos for display and prefetches the next photo.
5. In frame settings, enable local sync for offline access to the selected scope. Turn it off to resume on-demand access. Release local copies only after reviewing the displayed reclaimable/shared amounts and confirming the action. Copies needed by other local frames remain protected.
6. For AI frames, download a model separately and classify the explicitly selected remote scope. Classification executes on the device. Remote photos require source access unless an eligible local copy or current cached photo is available.
7. Disconnect the network to inspect cached-photo playback and retry feedback. Reconnect and retry. Revoking the source must cancel work and remove its displayed/cached content from use.

The review server receives directory and photo-read requests, authentication data and the connection IP. Photo processing and classification execute on the device. NAS requests are read-only. Cached originals are temporary app-private data and may be evicted by the app or operating system. Local sync deliberately consumes storage for offline use. AI model files and explicitly retained local originals have separate storage lifecycles from the raw-photo cache.

## Verified candidate

Version1.3.0(60) passed435 Android JVM tests,22 Android native tests and260 iOS native tests. English/Chinese native UI checks include8 Android configurations,3 iPhone flows and3 iPad flows, with an additional incremental iOS flow. The code review has0 open Critical/Important/Minor findings. Root independently re-ran both platform build/analysis targets and shared checks. Android lint reports0errors and204warnings. iOS reports existing Sendable and AppIntents metadata warnings.

The signed AndroidAPK is153,988,162bytes, SHA256 `224743a5fde03991a010d0e8fe27c643186326814d837e8bfe64cb8e00e3a372`, with the original certificate and4ABIs. The iOS archive targets15.0 and iPhone/iPad. Its executable SHA256 is `11a23b91b508f592f0a3ef609ad9a943a50bd35f31d51fd7ba5ff301f75718c6`. Real remote-photo inference used Multilingual CLIP Lite with Chinese/English rules. Standard-model AVD limitations and prior1.2.0 model evidence are recorded in the existing delivery materials.

The versioned packet includes21 native iPhone/iPad captures with dimensions and hashes in [ScreenshotEvidence130.json](ScreenshotEvidence130.json). Accessibility-size captures serve QA. Choose the normal-size Store candidates for the listing, with at most10screenshots per device size and localization. iPad orientation and opaqueRGB encoding were normalized while retaining the captured screen content.

Detailed payload, test and review outcomes are recorded in [RemoteFramesRelease130.json](RemoteFramesRelease130.json). Validation used simulators, a dedicatedAVD, synthetic protocol sources and injected space limits. Physical old-device, minimum-OS runtime, realNAS and long-running playback validation remain pending.

## Store submission inputs

- Export the verified archive with the original publishing team's matching Store profile and certificate.
- Recheck the published privacy policy and final App Privacy answers against the completed data flows.
- Supply valid private read-only review-source credentials and confirm reachability throughout review.
- Confirm the currently published version in App Store Connect, choose the final screenshots and update text, then associate the exported build with the update.

## Store signing dependency

The preceding 1.2.0 export found a mismatch between the original publishing team's Store profile and installed Distribution certificate. Automatic approval rejected an attempt to refresh signing assets with `-allowProvisioningUpdates`, because it could modify the Apple team's signing assets. Store export requires explicit owner authorization for that operation and a usable original publishing account. The prepared iOS delivery is an unsigned archive, ready for a subsequent Store export with matching signing assets. This dependency does not prevent GitHub packaging or preparation of update materials.

## Privacy review for the new flow

Prepared assessment, pending confirmation against the final binary. Directory metadata, photo caches and AI features remain in app-private device storage. The configured NAS receives the requested paths, authentication and connection information. Photo processing and inference execute on the device. Preserve the existing weather, model-download, update-check and support disclosures. Final label answers depend on actual developer or partner access and retention, including any review-server operation. Apple's definition of collection includes data transmitted off-device and retained beyond servicing a request. Recheck the final answers when data practices change. [Apple App Privacy Details](https://developer.apple.com/app-store/app-privacy-details/)

The published policy must describe temporary remote-photo caching, optional offline sync, user-confirmed local-copy release and the separate storage used by AI models. The raw-photo cache quota does not limit the total app size. Private metadata, model packages, explicitly synced originals and other user imports also use storage.

## Screenshot slots

Checked2026-11-15. Apple currently requires an iPhone Dynamic Island medium-display screenshot and an iPad13-inch screenshot for this universal app. Native1206×2622 iPhone captures and2064×2752 or2752×2064 iPad captures fit the listed slots. Files must be opaque JPEG or PNG. Android captures are QA evidence and are kept separate from Store upload candidates. Final dimensions, hashes, theme and capture configuration belong in the versioned evidence manifest. [Apple screenshot specifications](https://developer.apple.com/help/app-store-connect/reference/app-information/screenshot-specifications)
