# PhotoPilot

一个把旧 iPad / Android 平板变成数字相框的应用：导入本机、文件或 NAS（WebDAV/SMB）里的照片与视频，按相框精选内容，配上时钟、日期与天气，常亮播放。

Turn a spare iPad or Android tablet into a digital photo frame: import from the device, files, or a NAS (WebDAV/SMB), curate frames, and play them with clock, date and weather overlays.

## 特性 / Features

- 本地导入、二次选片、手动排序与自动播放（中英双语）
- NAS 来源：WebDAV（HTTPS/HTTP 私网）与 SMB，凭证安全存储（Keychain / Keystore）、TLS TOFU 证书信任、增量同步与墓碑删除保护
- 天气叠字（Open-Meteo，CC-BY 4.0 署名）、时钟与休息时段、节日专属播放
- 视频（mp4/mov，默认静音）与 RAW（系统解码）播放
- 背景音乐：14 首内置循环曲（8 首公有领域节日旋律自有合成编曲 + 6 首氛围原创）或本地音频文件，随播放/暂停/休息联动
- 照片只在本机处理；不上传、不记录照片名与路径
- Android 内置 GitHub 更新检查（断点续传 + 前台保活下载）

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

第三方组件保留各自许可：SMBJ / Room / Kotlin（Apache-2.0）、slf4j-api / mbassador（MIT）、Bouncy Castle（Bouncy Castle License）、SQLite（Public Domain）、AMSMB2（MIT）、libsmb2（LGPL-2.1，动态链接）；天气数据由 Open-Meteo 提供（CC-BY 4.0）。
