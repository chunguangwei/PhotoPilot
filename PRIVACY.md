# PhotoPilot · 光屿 Privacy Policy / 隐私政策

Effective date / 生效日期：2026-10-23
Contact / 联系方式：chunguangwee@gmail.com
Scope / 适用：PhotoPilot 1.1.58（iOS/iPadOS 与 Android）。Versions 1.1.55–1.1.58 use Open-Meteo only; earlier 1.1.53/1.1.54 builds used wttr.in weather fallback. / 1.1.55–1.1.58 仅使用 Open-Meteo；旧版 1.1.53/1.1.54 包含 wttr.in 天气备用请求。

---

## English

### 1. Our core commitment

PhotoPilot turns a spare iPad, iPhone or Android device into a quiet digital photo frame. **Your photos are never uploaded to our servers.** We operate no photo backend and we do not create a PhotoPilot account for you. Importing, organising, thumbnail generation, blurred-fill rendering and slideshow playback all happen on your device.

The current release is free, with no advertising, subscriptions or in-app purchases. Any future paid offering is not enabled in this version.

### 2. What the app processes

1. **Photos and derived images** — Photos you explicitly choose to import are stored in the app's private storage, together with display copies, thumbnails, blurred backgrounds and prefetch caches. They stay on your device and are excluded from system cloud backup by default.
2. **Photo metadata** — Capture time, orientation, dimensions, source or import batch, and a local content fingerprint, used for ordering, de-duplication and rendering. Stored on device only.
3. **Source credentials** — NAS usernames/passwords are kept in platform secure storage (iOS Keychain / Android encrypted storage protected by the system keystore).
4. **NAS connection details** — The protocol (WebDAV or SMB), address, port and folders you enter yourself. The app does not scan your local network on its own.
5. **City or approximate location for weather** — Weather is off by default. When enabled, you choose a city manually by default; you may separately grant "while using the app" location access. To fetch weather the app sends only the city or an approximate location to the weather provider (Open-Meteo) and **never any photo or photo-related information**. The provider sees your device IP as the connection source address. Choosing current location also enables location refresh when playback starts while weather and location following remain enabled. Choosing a city manually turns location following off. We do not track your location in the background.
6. **Diagnostics** — When you choose to export diagnostics, the app generates a report containing the app version/build, OS version, database schema version, generation time and, when applicable, an error code and error type. The report is built from these fields; it does not export photos, photo filenames, NAS addresses, passwords or system logs. You choose the recipient through the system sharing interface. The app also writes operational messages to the system logging facility; it does not automatically upload those logs.
7. **Settings** — Language, appearance, photo duration, display mode, rest hours and similar preferences, stored on device.

8. **Music** — Built-in music plays locally. Audio files you explicitly select for background music are copied into app-private storage for local playback; they are not sent to a music or AI service. Your system document picker may access a cloud-storage provider according to the file source you choose.
9. **Android update checks** — When you choose to check for updates, the app contacts the public PhotoPilot GitHub Releases API. If you choose to download an update, it contacts GitHub and its download infrastructure. These services see connection IP, requested URLs and network headers, including a PhotoPilot Android user-agent; the app does not send your photo library, NAS credentials or a PhotoPilot account identifier. See [GitHub’s privacy statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement).
10. **Support messages** — If you email us or share a diagnostic report with us, we receive the address, message and attachments you choose to send, to respond and investigate. Avoid sending private photos or credentials. You may request deletion through the contact address; information may be retained where needed to handle the request or meet legal obligations. This is separate from automatic app telemetry.
11. **App Store statistics (iOS/iPadOS)** — Apple may provide the developer with aggregate download and usage reports through App Store Connect. Some usage metrics depend on users choosing to share diagnostics and usage information with app developers and on privacy thresholds. PhotoPilot contains no separate analytics SDK and does not send its own usage events to our server. These reports do not provide us with your photos or NAS credentials. See [Apple’s Analytics privacy explanation](https://developer.apple.com/help/app-store-connect-analytics/engagement/app-usage).

### 3. What we do not do

- We do not upload your photos, folder structures or filenames.
- We include no third-party advertising SDKs, no ad attribution and no cross-app or cross-site tracking.
- We build no face index and send no photo to any AI service for recognition or training.
- We do not sell, rent or trade your personal information.
- We do not use remote configuration to silently widen photo access or auto-display new photos.

### 4. Third parties

- **Apple system photo library / iCloud Photos** — Accessed through system authorisation for photos you can already access; handled under Apple's terms and your system settings.
- **Weather service** — Open-Meteo receives city-search text or forecast coordinates rounded to 0.1°, language/request parameters and connection IP. Its weather data is attributed under CC-BY 4.0. [Open-Meteo’s privacy notice](https://open-meteo.com/en/terms) describes technical IP processing and troubleshooting logs that may contain coordinates, with deletion after 90 days. PhotoPilot does not control these provider logs. There is no automatic wttr.in fallback in 1.1.58. Turning weather off stops weather requests; manual city selection avoids requesting the device’s current location but still sends city-search and forecast requests.
- **Apple geocoding services (iOS)** — The app uses Apple’s network-based geocoding service to resolve location names and, if the primary city search fails, to search for a city. Reverse geocoding receives the location returned by the system before the app rounds coordinates for weather requests; that original location can be more precise than 0.1°. City search receives your search text. See [Apple Location Services & Privacy](https://www.apple.com/legal/privacy/data/en/location-services/).
- **Android system geocoding services** — Where available, the device’s geocoding provider receives the original location returned by the system to resolve a place name, or your search text for fallback city search. The provider depends on the device/system configuration; only the coordinates sent to the weather forecast providers are rounded by the app.
- **Your NAS** — Your normal NAS source is configured by you; we do not host your private library. A separate, temporary, read-only review server may provide synthetic sample images to App Review or testers who explicitly configure it. It does not accept photo uploads and is not automatically contacted by ordinary app installations. Connecting to that server exposes connection information to its operator for providing and securing that test service.

### 5. Retention and deletion

Photo copies, imported music and settings remain until you delete them. Several independent deletion paths are provided: remove from a frame (the photo is kept), delete from the on-device library, disconnect a source (with an explicit choice about keeping imported copies), and "Erase all data" in Settings. Erasing covers photo copies, imported music, derived images, caches, job queues, settings and source credentials. **We never delete the originals in your system album or on your NAS.** The "mirror source deletions" option is off by default and requires a second confirmation; even when enabled, only the on-device copy is removed — writing deletions back to the source is not supported.

Photo copies and settings are deleted on your device. Third-party service logs are governed by the relevant provider’s retention and deletion practices.

### 6. Children

The app is not designed for children and does not knowingly collect children's information.

### 7. Security

The app uses platform secure storage for credentials and encrypted, verified network transport by default (HTTPS). A per-host private-network plaintext exception exists for NAS connections and is off by default; enabling it shows an explicit risk confirmation. SMB signing is not encryption, so not every possible link can be described as encrypted. No method is perfectly secure; device passcodes and system updates matter too.

### 8. Changes

The current policy is published with the app’s source repository and linked from the app. Its effective date and version scope identify changes. Material changes to data handling will be explained before the changed processing begins, with permissions requested where required.

### 9. Contact

For privacy questions, contact chunguangwee@gmail.com.

---

## 简体中文

### 一、核心承诺

光屿（PhotoPilot）把闲置的平板或手机变成一台安静的家庭电子相框。**你的照片不会上传到我们的服务器。** 我们没有照片后端，也没有为你创建 PhotoPilot 账号。照片的导入、整理、缩略图生成、模糊补边与播放全部在你的设备上完成。

当前版本免费，无广告、订阅或应用内购买。未来可能提供的收费方案尚未在本版本启用。

### 二、应用处理哪些信息

1. **照片与衍生图**：你明确选择导入的照片会保存在应用的私有存储中，并生成展示副本、缩略图、模糊背景与预取缓存。这些内容仅存在于你的设备上，默认不进入系统云备份。
2. **照片元数据**：拍摄时间、方向、尺寸、来源或导入批次、内容指纹，用于排序、去重与渲染适配。仅存本机。
3. **来源凭证**：NAS 账号密码保存在系统提供的安全存储中（iOS 钥匙串 / Android 由系统密钥保护的加密存储）。
4. **NAS 连接信息**：你手动填写的协议（WebDAV 或 SMB）、地址、端口与目录。应用不会主动扫描你的局域网。
5. **天气所需的城市或粗略位置**：天气功能默认关闭。启用后，默认由你手动选择城市；你也可以单独授权「仅使用期间」的定位。为了取得天气，应用只会向天气服务方（Open-Meteo）发送城市或粗略位置，**不会附带任何照片或照片信息**。服务方会看到你的设备 IP（连接源地址）。选择当前位置还会启用位置跟随：天气与位置跟随保持开启时，开始播放会刷新位置。手动选择城市会关闭位置跟随。我们不进行后台持续定位。
6. **诊断信息**：你选择导出诊断时，应用生成包含应用版本与构建号、系统版本、数据库结构版本、生成时间，以及适用时的错误码和错误类型的报告。报告按这些字段生成，不导出照片、照片文件名、NAS 地址、密码或系统日志。你通过系统分享界面选择接收方。应用也会向系统日志设施写入运行消息，不会自动上传这些日志。
7. **设置**：语言、外观、播放时长、画面模式、休息时段等偏好，保存在本机。

8. **音乐**：内置音乐在本机播放；你明确选择的背景音乐文件会复制到应用私有存储，不发送给音乐或 AI 服务。系统文件选择器可能按你选择的文件来源访问云存储服务。
9. **Android 更新检查**：你选择检查更新时，应用连接公开的 PhotoPilot GitHub Releases API；你选择下载时，连接 GitHub 及其下载基础设施。服务方会看到连接 IP、请求网址和网络请求头（含 PhotoPilot Android 用户代理）；应用不会发送照片库、NAS 凭证或 PhotoPilot 账号标识。参见 [GitHub 隐私声明](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement)。
10. **支持消息**：你向我们发邮件或分享诊断时，我们会收到你主动发送的地址、内容和附件，用于回复与排查。请勿发送私人照片或凭证。可通过联系邮箱请求删除；为处理请求或履行法定义务而必要的信息可能继续保留。这与应用自动遥测是不同的处理。
11. **App Store 统计（iOS/iPadOS）**：Apple 可以通过 App Store Connect 向开发者提供汇总下载与使用报告。部分使用指标取决于用户是否同意向开发者分享诊断与使用信息，以及隐私门槛。PhotoPilot 没有单独的统计 SDK，不会向我们的服务器发送自建使用事件。这些报告不会向我们提供你的照片或 NAS 凭证。参见 [Apple 统计隐私说明](https://developer.apple.com/help/app-store-connect-analytics/engagement/app-usage)。

### 三、我们不做的事

- 不上传你的照片、相册目录结构或文件名。
- 不接入第三方广告 SDK，不做广告归因，不做跨应用或跨网站追踪。
- 不建立人脸索引，不把你的照片交给任何 AI 服务做识别或训练。
- 不出售、不出租、不以数据交易为目的共享你的个人信息。
- 不在未向你说明的情况下，通过远程配置打开更多照片目录或自动展示新照片。

### 四、第三方

- **Apple 系统图库 / iCloud 照片**：通过系统授权访问你可访问的照片；相关处理遵循 Apple 的条款与你的系统设置。
- **天气服务**：Open-Meteo 接收城市搜索文字或取整至 0.1° 的天气坐标、语言等请求参数及连接 IP；天气数据按 CC-BY 4.0 标示来源。[其隐私说明](https://open-meteo.com/en/terms)描述技术性 IP 处理，以及可能含坐标、90 天后删除的排障日志。PhotoPilot 不控制服务方日志。1.1.58 不再自动请求 wttr.in。关闭天气会停止天气请求；手选城市避免请求设备当前位置，但仍会发送城市搜索与天气请求。
- **Apple 地理编码服务（iOS）**：应用通过 Apple 的联网地理编码服务解析位置名称，并在主城市搜索失败时搜索城市。逆地理编码接收系统返回的原始位置，发生在天气请求坐标取整之前，因此该位置可能比 0.1° 更精确；城市搜索接收你输入的搜索文字。参见 [Apple 定位服务与隐私](https://www.apple.com/legal/privacy/data/en/location-services/)。
- **Android 系统地理编码服务**：设备提供此服务时，地理编码服务方接收系统返回的原始位置以解析地名，或接收备用城市搜索的输入文字。服务方取决于设备与系统配置；应用仅对发往天气预报服务的坐标取整。
- **你的 NAS**：日常使用的 NAS 来源由你自行配置，我们不托管你的私人图库。另有临时、只读审核服务器，可向主动配置该来源的 App Review 审核员或测试者提供合成样图；它不接受照片上传，普通安装不会自动连接。连接该测试服务器会向其运营者暴露提供和保护测试服务所需的连接信息。

### 五、保留与删除

照片副本、导入的音乐与设置会一直保留，直到你删除它们。我们提供多条独立的删除路径：从相框移除（不删照片）、从本机照片库删除、断开来源（可选择是否保留已导入副本）、以及设置中的「清除所有数据」。清除会覆盖照片副本、导入音乐、衍生图、缓存、任务队列、设置与来源凭证。**你的系统相册与 NAS 上的原始文件不会被我们删除。**「本地镜像删除」选项默认关闭且需要二次确认；即使主动开启，被删除的**只是本机副本**，本应用不支持写回删除源端。

PhotoPilot 没有照片后端或用户账号，因此本机照片与设置由你在设备上删除。第三方服务日志的保留与删除遵循相应服务方的规则。

### 六、儿童

本应用不面向儿童设计，不定向收集儿童信息。

### 七、安全

应用采用系统安全存储保存凭证，网络默认加密并校验来源（HTTPS）。WebDAV 提供逐主机确认的私网明文选项，默认关闭，启用前会显式风险提示；SMB 签名不等于加密，不能承诺全部链路加密。没有任何方法绝对安全；设备锁屏与系统更新同样重要。

### 八、变更

现行政策随应用源码仓库发布，并在应用内提供链接；生效日期与版本范围用于标识变更。涉及信息处理方式的实质变更，会在新处理开始前说明，并按要求请求权限。

### 九、联系我们

隐私相关问题请联系 chunguangwee@gmail.com。
