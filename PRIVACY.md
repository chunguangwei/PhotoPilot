# PhotoPilot · 光屿 Privacy Policy / 隐私政策

Effective date / 生效日期：2026-10-20
Contact / 联系方式：chunguangwee@gmail.com

---

## English

### 1. Our core commitment

PhotoPilot turns a spare iPad, iPhone or Android device into a quiet digital photo frame. **Your photos are never uploaded to our servers.** We operate no photo backend and we do not create a PhotoPilot account for you. Importing, organising, thumbnail generation, blurred-fill rendering and slideshow playback all happen on your device.

### 2. What the app processes

1. **Photos and derived images** — Photos you explicitly choose to import are stored in the app's private storage, together with display copies, thumbnails, blurred backgrounds and prefetch caches. They stay on your device and are excluded from system cloud backup by default.
2. **Photo metadata** — Capture time, orientation, dimensions, source or import batch, and a local content fingerprint, used for ordering, de-duplication and rendering. Stored on device only.
3. **Source credentials** — NAS usernames/passwords are kept in platform secure storage (iOS Keychain / Android encrypted storage protected by the system keystore).
4. **NAS connection details** — The protocol (WebDAV or SMB), address, port and folders you enter yourself. The app does not scan your local network on its own.
5. **City or approximate location for weather** — Weather is off by default. When enabled, you choose a city manually by default; you may separately grant "while using the app" location access. To fetch weather the app sends only the city or an approximate location to the weather provider (Open-Meteo, with wttr.in as a fallback) and **never any photo or photo-related information**. The provider sees your device IP as the connection source address. We do not track your location in the background.
6. **Diagnostics** — The app keeps a bounded local log (job id, error code, timing, resource counts) to help troubleshoot. It contains no tokens, NAS passwords, full addresses, photo filenames or photos. Logs do not leave your device by default; if you export them explicitly they are redacted first and you decide who receives them.
7. **Settings** — Language, appearance, photo duration, display mode, rest hours and similar preferences, stored on device.

### 3. What we do not do

- We do not upload your photos, folder structures or filenames.
- We include no third-party advertising SDKs, no ad attribution and no cross-app or cross-site tracking.
- We build no face index and send no photo to any AI service for recognition or training.
- We do not sell, rent or trade your personal information.
- We do not use remote configuration to silently widen photo access or auto-display new photos.

### 4. Third parties

- **Apple system photo library / iCloud Photos** — Accessed through system authorisation for photos you can already access; handled under Apple's terms and your system settings.
- **Weather providers** — Open-Meteo (CC-BY 4.0) is the primary provider; wttr.in is used as a fallback when the primary is unreachable. Only the chosen city or approximate location is sent.
- **Your NAS** — Owned and configured by you; we do not host it.

### 5. Retention and deletion

Photo copies and settings remain until you delete them. Several independent deletion paths are provided: remove from a frame (the photo is kept), delete from the on-device library, disconnect a source (with an explicit choice about keeping imported copies), and "Erase all data" in Settings. Erasing covers photo copies, derived images, caches, job queues, settings and source credentials. **We never delete the originals in your system album or on your NAS.** The "mirror source deletions" option is off by default and requires a second confirmation; even when enabled, only the on-device copy is removed — writing deletions back to the source is not supported.

Because PhotoPilot has no photo backend or user account, all deletion happens on your device.

### 6. Children

The app is not designed for children and does not knowingly collect children's information.

### 7. Security

The app uses platform secure storage for credentials and encrypted, verified network transport by default (HTTPS). A per-host private-network plaintext option exists for WebDAV and is off by default; enabling it shows an explicit risk confirmation. SMB signing is not encryption, so not every possible link can be described as encrypted. No method is perfectly secure; device passcodes and system updates matter too.

### 8. Changes

Updates will be surfaced in the app and on the store listing. Material changes to how data is handled will be explained before they take effect.

### 9. Contact

For privacy questions, contact chunguangwee@gmail.com.

---

## 简体中文

### 一、核心承诺

光屿（PhotoPilot）把闲置的平板或手机变成一台安静的家庭电子相框。**你的照片不会上传到我们的服务器。** 我们没有照片后端，也没有为你创建 PhotoPilot 账号。照片的导入、整理、缩略图生成、模糊补边与播放全部在你的设备上完成。

### 二、应用处理哪些信息

1. **照片与衍生图**：你明确选择导入的照片会保存在应用的私有存储中，并生成展示副本、缩略图、模糊背景与预取缓存。这些内容仅存在于你的设备上，默认不进入系统云备份。
2. **照片元数据**：拍摄时间、方向、尺寸、来源或导入批次、内容指纹，用于排序、去重与渲染适配。仅存本机。
3. **来源凭证**：NAS 账号密码保存在系统提供的安全存储中（iOS 钥匙串 / Android 由系统密钥保护的加密存储）。
4. **NAS 连接信息**：你手动填写的协议（WebDAV 或 SMB）、地址、端口与目录。应用不会主动扫描你的局域网。
5. **天气所需的城市或粗略位置**：天气功能默认关闭。启用后，默认由你手动选择城市；你也可以单独授权「仅使用期间」的定位。为了取得天气，应用只会向天气服务方（Open-Meteo，备用源 wttr.in）发送城市或粗略位置，**不会附带任何照片或照片信息**。服务方会看到你的设备 IP（连接源地址）。我们不进行后台持续定位。
6. **诊断信息**：应用在本机保留有限长度的运行日志（任务编号、错误码、耗时、资源计数），用于你遇到问题时排查。日志不包含令牌、NAS 密码、完整地址、照片文件名或照片。日志默认不离开设备；你显式导出时，我们会先做脱敏处理，发送给谁由你决定。
7. **设置**：语言、外观、播放时长、画面模式、休息时段等偏好，保存在本机。

### 三、我们不做的事

- 不上传你的照片、相册目录结构或文件名。
- 不接入第三方广告 SDK，不做广告归因，不做跨应用或跨网站追踪。
- 不建立人脸索引，不把你的照片交给任何 AI 服务做识别或训练。
- 不出售、不出租、不以数据交易为目的共享你的个人信息。
- 不在未向你说明的情况下，通过远程配置打开更多照片目录或自动展示新照片。

### 四、第三方

- **Apple 系统图库 / iCloud 照片**：通过系统授权访问你可访问的照片；相关处理遵循 Apple 的条款与你的系统设置。
- **天气服务方**：Open-Meteo（CC-BY 4.0）为主用服务；主服务不可达时使用 wttr.in 作为备用。仅发送你选择的城市或粗略位置。
- **你的 NAS**：由你自行拥有和配置，我们不代为托管。

### 五、保留与删除

照片副本与设置会一直保留，直到你删除它们。我们提供多条独立的删除路径：从相框移除（不删照片）、从本机照片库删除、断开来源（可选择是否保留已导入副本）、以及设置中的「清除所有数据」。清除会覆盖照片副本、衍生图、缓存、任务队列、设置与来源凭证。**你的系统相册与 NAS 上的原始文件不会被我们删除。**「本地镜像删除」选项默认关闭且需要二次确认；即使主动开启，被删除的**只是本机副本**，本应用不支持写回删除源端。

PhotoPilot 没有照片后端或用户账号，因此本机照片与设置由你在设备上删除。

### 六、儿童

本应用不面向儿童设计，不定向收集儿童信息。

### 七、安全

应用采用系统安全存储保存凭证，网络默认加密并校验来源（HTTPS）。WebDAV 提供逐主机确认的私网明文选项，默认关闭，启用前会显式风险提示；SMB 签名不等于加密，不能承诺全部链路加密。没有任何方法绝对安全；设备锁屏与系统更新同样重要。

### 八、变更

政策更新会在应用内与商店页面提示。涉及数据处理方式的实质变更，会在变更生效前说明。

### 九、联系我们

隐私相关问题请联系 chunguangwee@gmail.com。
