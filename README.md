# PhotoPilot

一个把旧 iPad / Android 平板变成数字相框的应用：引用本机相册与文件照片，或按需播放 NAS（WebDAV/SMB）远端照片，按相框精选内容，配上时钟、日期与天气，常亮播放。

Turn a spare iPad or Android tablet into a digital photo frame: reference device albums or files, or play NAS (WebDAV/SMB) photos on demand, curate frames, and play them with clock, date and weather overlays.

## 特性 / Features

- 本机照片/目录直接引用、二次选片、手动排序与自动播放（中英双语）
- NAS 来源：WebDAV（HTTPS/HTTP 私网）与 SMB，凭证安全存储（Keychain / Keystore）、TLS TOFU 证书信任、增量同步与墓碑删除保护
- 天气叠字（Open-Meteo，CC-BY 4.0 署名）、时钟与休息时段、节日专属播放
- RAW 照片按系统解码能力支持
- 背景音乐：14 首内置循环曲（5 首历史旋律自有合成编曲 + 9 首程序原创）或本地音频文件，随播放/暂停/休息联动
- 照片只在本机处理；不上传、不记录照片名与路径
- Android 内置 GitHub 更新检查（断点续传 + 前台保活下载）

## NAS 浏览与选片修复 / NAS browsing and selection（1.4.1）

Android 的 SMB、WebDAV 来源可直接选择“浏览照片”，按需模式通过“刷新照片列表”更新目录。图库和相框选片显示 NAS 索引中的照片数量，无需先下载原图。确认选片后首页显示相框并支持在线播放，临时缓存有容量上限；离线同步由用户主动开启。

Android SMB and WebDAV sources offer **Browse photos** and an on-demand **Refresh photo list** action. Folder counts and frame selection use the NAS photo index. Confirmed frames appear on Home and play online with bounded temporary caching. Enable local sync explicitly when offline copies are needed. Legacy frames without an explicit storage preference also play NAS photos on demand and do not start offline downloads.

未明确设置同步模式的旧相框也按需播放 NAS 照片，不会自动启动离线下载；已有本机照片及副本保留。

首次选片确认后直接播放，默认相框名称可随后修改。来源管理保持独立，从图库或来源选片时可选择已有相框或明确新建。

## 本机引用与来源管理 / Local references and source management（1.4.0）

本机相册、所选文件与目录默认保存照片关联和持久访问授权，建立索引时无需复制原图。预览、播放与 AI 按需读取，仍需确认选片后才展示。原照片由系统相册或文件供应商管理，移动、删除或撤销授权后可重新选择来源。云端相册或文件供应商可能由系统按需下载内容。

新建及编辑 NAS 来源都可选择按需读取或同步所选目录到本地，新来源默认按需读取。来源级同步和逐相框同步分别按各自确认范围占用空间。断开后的来源卡片可删除配置，保留已下载私有副本及有确定版本依据的相框关联。历史多版本缺少关联依据时，文件继续保留，相关相框可重新选片。历史导入副本继续可用，沿用原有资料库管理流程。

Device albums, selected files and directories use persistent references by default. Indexing stores metadata and frame relationships without copying originals. Previews, playback and AI read on demand. Confirm your selection before displaying photos. Reselect the source when an original moves, disappears or loses permission. System photo libraries and file providers may fetch cloud content as needed.

NAS setup and edit offer on-demand access or local sync of the selected folders. New sources default to on-demand access. Source-level and frame-level sync use their confirmed scopes. Disconnected cards allow configuration removal while retaining downloaded private photos and deterministically mapped frame relationships. Ambiguous historical versions retain their files and require photo reselection. Existing imported copies remain usable and retain their existing library lifecycle.

## 远端按需相框 / On-demand remote frames（1.3.0）

在相框页进入在线选片，连接 WebDAV 或 SMB，选择照片或目录后确认保存。新远端相框默认按需播放，目录浏览先索引元数据。播放、封面、预览与 AI 共用 128MiB 原始照片临时缓存，包含未完成下载，自动淘汰，播放只预取下一张。缓存还受系统清理影响，远端照片没有可用副本时需要联网访问来源。

相框中开启本地同步可保留所选原图供离线播放，切换模式保留选片与 AI 规则。释放本地副本先查看可释放与共享占用，再确认清理。其他本地相框需要的副本受到保护。旧数据缺少存储模式字段时按本地模式解析，来源级导入会产生额外存储占用。模型、元数据、明确同步的原图及其他导入内容另占空间。普通播放无需 AI 模型。

Open online photo selection from Frames, connect WebDAV or SMB, choose photos or a folder scope, and confirm. New remote frames default to on-demand playback. Metadata browsing fetches directory entries first. Playback, covers, previews and AI share a 128MiB temporary raw-photo cache including partial downloads. The cache evicts automatically and playback prefetches only the next photo. The operating system may also purge it. Photos require source access when a usable local or cached copy is unavailable.

Enable local sync per frame for offline access. Storage-mode changes preserve selections and AI rules. Review reclaimable/shared storage before confirming local-copy release. Copies required by other local frames remain protected. Frames without a stored mode use local playback. Source-level imports also occupy device storage. Models, metadata, synced originals and other imports use additional storage. Ordinary playback needs no AI model.

## AI 分类相框 / AI frames（1.2.0 起）

在相框页选择“AI 创建相框”，选择本地相册或目录（1.3.0 起也可选远端目录），输入中英文类别（每行一个），按需设置包含/排除描述，先查看分类预览与名称建议，再确认创建。名称与选片均可修改。持续自动分类默认关闭。开启后仅处理所选目录后续新增的合格照片，也可在 AI 相框设置中关闭。人工添加和排除受到保护。

Choose **Create AI frames**, select local albums/folders or remote folders from1.3.0, and enter English or Chinese categories, one per line. Optional include/exclude descriptions narrow the matches. Review photos and editable name suggestions before saving. Automatic classification of newly discovered eligible photos within the selected scope is optional, initially off and editable later. Manual additions and exclusions remain under your control.

- **EmbeddingGemma 2 文本＋图像版 / standard** 约387.7 MB，使用分类所需的文本和图像部分。Android 标准模型需要64位进程。
- **多语 CLIP 轻量版 / Multilingual CLIP Lite** 约201.5 MB，包含文本编码器、图像编码器、投影和词表。完整下载比标准版小约48%，支持中英文零样本图像分类。
- 模型按需下载，国内源优先 ModelScope，国际源使用 Hugging Face，另提供 hf-mirror 回退。安装前校验固定版本及 SHA-256。备用镜像可能跳转海外 CDN，可用性取决于网络。下载完成后照片、提示词和分类都留在本机。
- Models are optional downloads from pinned sources and verified before installation. After download, inference is on-device. No photos or prompts are uploaded. Mirror availability varies by network.
- 分类得分表示相似度。模型可能误选或漏选，可调整阈值并确认边界结果。轻量模型每条描述最多126个内容分词，过长描述需缩短。名称建议结合用户描述与模型选出的图片主题。
- Scores represent similarity. Review uncertain matches and adjust strictness when needed. Lite accepts up to126 content tokens per description. Suggested names combine your description with image themes.
- 删除下载模型时，相框和照片仍可使用。在设置中清除全部数据会清理模型、分类缓存和任务。iOS 持续分类在应用可运行期间、导入后或回到前台处理。

下载来源及限制实证见 [模型来源记录](docs/superpowers/ai-model-source-evidence.md)，新增模型与 SDK 的许可见 [AI 第三方许可来源](docs/superpowers/ai-third-party-notices-evidence.md)。

## 构建 / Build

环境要求见 `15-脚手架与开发启动说明.md`。

```sh
make check            # 契约/词条/设计/文档全量校验
make ios-generate     # XcodeGen 生成 Xcode 工程
make ios-build        # iOS 编译（模拟器 SDK）
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
make android-build    # Android 编译
make android-test     # JVM 单元测试
```

## 仓库结构 / Repository layout

- `ios/`、`android/` — 双端原生工程（Swift/UIKit、Kotlin Views）
- `contracts/` — 机器可读契约（schema、词条真源、错误码、验收用例、跨端共享向量）
- `design/` — 设计源稿与导出资产（SVG/PNG，版本化归档）
- `tools/` — 契约生成与校验工具链；`tests/fixtures/` — 合成测试夹具
- `00–16` — 中文产品/工程文档（PRD、工程规格、进度交接等）

## 许可 / License

本应用及源代码仅供**非商业使用**。商用须事先书面许可，请联系 chunguangwee@gmail.com。
详见根目录 [LICENSE](LICENSE)。

This application and its source code are licensed for **non-commercial use only**.
Commercial use requires prior written permission — contact chunguangwee@gmail.com.
See [LICENSE](LICENSE).

产品使用条款与 LGPL 权限例外 / Product terms and LGPL permission exception:
[UserTerms.md](store/appstore/review/UserTerms.md)。App Store 渠道拟采用 Apple 标准 EULA。

第三方组件保留各自许可：SMBJ / Room / Kotlin（Apache-2.0）、slf4j-api / mbassador（MIT）、Bouncy Castle（Bouncy Castle License）、SQLite（Public Domain）、AMSMB2（MIT）、libsmb2（LGPL-2.1，动态链接）；天气数据由 Open-Meteo 提供（CC-BY 4.0）。

SMB 锁定源码、下载与独立重建记录见 [SMB 分发材料](store/appstore/review/SMBDistribution.md)。该记录不代表 App Store 分发许可已完成确认。

## 隐私政策 / Privacy Policy

见根目录 [PRIVACY.md](PRIVACY.md)（中英双语）。核心承诺：照片只在本机处理，不上传、无广告、不需要账号。

See [PRIVACY.md](PRIVACY.md) (bilingual). Photos never leave your device — no uploads, no ads, no account.


## 支持 / Support

使用中遇到问题，请发送邮件至 **chunguangwee@gmail.com**，说明设备型号、系统版本、PhotoPilot 版本和复现步骤。请勿附上私人照片、NAS 密码或服务器完整地址。

For help, email **chunguangwee@gmail.com** with your device model, OS version, PhotoPilot version and steps to reproduce the issue. Please do not include private photos, NAS passwords or full server addresses.
