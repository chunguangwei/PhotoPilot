# Remote frame update preparation — 1.3.0 (60)

Status — implementation and native verification are in progress. This packet contains draft update text and a release checklist. No Store upload or submission has occurred.

## English update text

Play photos from your WebDAV or SMB server on demand. New remote frames keep a bounded shared photo cache to save device storage. Turn on local sync in frame settings when you want offline access. Keep your selections and classification rules when changing storage mode. Local AI classification can read selected remote photos on demand, with optional model downloads.

## 简体中文更新说明

新增远端照片按需播放。连接 WebDAV 或 SMB 后，在线选择照片并创建相框，通过限额共享缓存节省设备空间。需要离线播放时，可在相框设置中打开本地同步。切换存储方式时保留选片和分类规则。本地 AI 分类可按需读取选定的远端照片，模型仍按需下载。

## 繁體中文更新說明

新增遠端照片按需播放。連接 WebDAV 或 SMB 後，線上選取照片並建立相框，透過限額共用快取節省裝置空間。需要離線播放時，可在相框設定中開啟本機同步。切換儲存方式時保留選片和分類規則。本機 AI 分類可按需讀取選定的遠端照片，模型仍按需下載。

## Draft review workflow

1. Existing local-library import and playback remain available through the original entry points. Ordinary playback requires no AI model.
2. Configure a read-only WebDAV or SMB source using separately supplied review credentials. The review server should contain only synthetic or lawfully usable non-private photos. Metadata-only browsing indexes directory entries before a photo is selected for display.
3. Create a remote frame, choose its photos or folder scope, and confirm the selection. New remote frames default to on-demand storage. Connect through a network allowed by the app's existing network policy.
4. Start playback. The shared raw-photo cache has a 128MiB limit, including temporary downloads. A 64MiB free-space reserve applies before writes. The app fetches photos for display and prefetches the next photo.
5. In frame settings, enable local sync for offline access to the selected scope. Turn it off to resume on-demand access. Release local copies only after reviewing the displayed reclaimable/shared amounts and confirming the action. Copies needed by other local frames remain protected.
6. For AI frames, download a model separately and classify the explicitly selected remote scope. Classification executes on the device. Remote photos require source access unless an eligible local copy or current cached photo is available.
7. Disconnect the network to inspect cached-photo playback and retry feedback. Reconnect and retry. Revoking the source must cancel work and remove its displayed/cached content from use.

The review server receives directory and photo-read requests, authentication data and the connection IP. PhotoPilot operates no photo backend. Requests are read-only. Cached originals are temporary app-private data and may be evicted by the app or operating system. Local sync deliberately consumes storage for offline use. AI model files and explicitly retained local originals have separate storage lifecycles from the raw-photo cache.

## Exact release verification still pending

- Normal-entry selection, frame editing, playback, AI classification and source revocation on both platforms.
- Bounded streaming with unknown/incorrect lengths, independent cancellation, full-disk behavior, cache eviction and recovery.
- Local-sync scope isolation, upgrade preservation, dedup rehydration and safe confirmed cleanup.
- English/Simplified Chinese UI evidence on iPhone, iPad and Android, including large text and landscape.
- Final versioned payload, signatures, hashes, third-party notices and regression results.
- Updated published privacy policy matching the completed implementation and final App Privacy review.
- Private review-server reachability and credentials, supplied outside this public repository.

## Store signing dependency

The preceding 1.2.0 export found a mismatch between the original publishing team's Store profile and installed Distribution certificate. Automatic approval rejected an attempt to refresh signing assets with `-allowProvisioningUpdates`, because it could modify the Apple team's signing assets. Store export requires explicit owner authorization for that operation and a usable original publishing account. Development-signed or unsigned archives do not constitute an App Store IPA. This dependency does not prevent GitHub packaging or preparation of update materials.
