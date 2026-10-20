# Release 归档复核 · 2026-10-20

构建来自 v1.1.46 基线与本轮未提交修复；未改版本号。归档为本地未签名检查产物，不能用于 App Store 提交，也没有执行 Apple Validate 或上传。

| 检查 | 实际结果 |
|---|---|
| 配置/平台 | Release，iphoneos27.1，arm64，最低 iOS 15.0 |
| App 身份 | dev.photopilot.scaffold，1.1.46 (46)，iPhone + iPad |
| 隐私清单 | 入包且与源文件逐字节一致；追踪 false；收集数组为空不等于隐私标签已经定稿 |
| Required-reason API | UserDefaults CA92.1；FileTimestamp C617.1；DiskSpace E174.1；本轮补 SystemBootTime 35F9.1 |
| 使用理由证据 | LaunchViewController 使用 systemUptime 计算应用内启动展示时长，不向外发送系统启动时间 |
| 许可文本 | ThirdPartyNotices.txt 入包且与源文件一致；SHA-256 944b9199742089e2c60d031329d5f973bb389b6f8cea550ffcb7aa57cc1b7ff4 |
| 非系统动态框架 | AMSMB2.framework；otool -L 确认 App 通过 @rpath 加载 |
| 测试与源文件 | 无 .xctest、无 .swift 源文件入包 |
| 音乐/演示素材 | 14 首音乐、8 张演示照片均与仓库源文件逐字节一致；详情 AssetEvidence.json |
| 字体文件 | 未打包 .ttf/.otf 文件；不代表所有商店物料字体使用均已完成权利审核 |
| 加密声明 | 移除未经定稿的 ITSAppUsesNonExemptEncryption=false 预设；须在 ASC 填写真实问卷 |
| 签名环境 | 当前钥匙串未发现 Apple Distribution 身份；不能据此断言账号未开通或云端没有证书 |

本地证据：`.build/appstore-audit/PhotoPilot-unsigned.xcarchive`、`ReleaseAudit.json`；构建日志 `.build/appstore-release-archive.log`。

## 加密问卷的实际输入

当前 HTTPS/Keychain/CryptoKit 使用系统能力；AMSMB2 内的 libsmb2 还包含 NTLM、哈希与 AES-CCM 等实现。Apple 平台 AES 封装调用 CommonCrypto，但归档框架仍导出 AES reference 实现符号。不能仅凭“HTTPS 使用系统加密”概括整个 App，也不能仅凭发现符号直接认定全部均非豁免。须结合实际用途、分发地区及问卷完成分类。

[Apple 加密材料要求](https://developer.apple.com/help/app-store-connect/reference/app-information/export-compliance-documentation-for-encryption/)区分仅系统加密与非系统标准算法；法国分发等条件须在真实问卷确认。分类完成后才能决定是否恢复 plist 的豁免取值。

## SMB 对应源码准备

已从无本地修改的锁定 checkout 制作本地源码快照，包含 libsmb2 子模块、构建文件及原始许可证，排除了 .git/.build/.swiftpm 与符号链接：

- AMSMB2 4.0.3：1726aaaf7adf63d7d1d2a0c5d1b0e635028215c0。
- libsmb2：aff9fa6ba9f41cfd3c15d184554601ec3f6d8d03。
- `.build/appstore-audit/AMSMB2-4.0.3-source.tar.gz`，218 文件，SHA-256 `0b4bd6046f2f06d79cd82a36f967f11b85b8e39aade627e1d6f5504ddd2e5077`。
- `.build/appstore-audit/SMBSourceManifest.json` 记录逐文件哈希。

这是供后续分发审核使用的源码准备，尚未建立最终用户下载渠道，也未完成替换/重链接步骤验证、最终签名分发兼容性判断或书面结论。源码 ZIP/链接和许可页都不能单独证明 LGPL 义务已履行。

## 仍需补齐

1. Apple Distribution 签名/描述文件、最终归档 Validate、ASC 构建处理结果。
2. 天气隐私标签及备用服务日志说明；线上政策与最终包一致。
3. SMB 最终源码交付、替换/重链接材料与分发结论；App 用户许可衔接。
4. 审核 NAS 可达环境和最小权限凭证；最终发布包的真机完整流程。
5. 账号表单、分发地区、加密分类、素材权利定稿及更新后的商店截图。

[Required-reason API 与 35F9.1](https://developer.apple.com/documentation/bundleresources/app-privacy-configuration/nsprivacyaccessedapitypes/nsprivacyaccessedapitype)。本记录证明本地检查结果，不代表审核批准。

## v1.1.47 分发收尾补记

2026-10-20 发布前按双端 1.1.47 (47) 再次生成本地未签名 iOS Release 归档：`.build/appstore-audit/PhotoPilot-v1.1.47-unsigned.xcarchive`。隐私清单与完整许可文本仍与当前源文件一致。包含新增大字号侧栏高度/滚动修复；全量 131 项 XCTest 通过。GitHub 的 Android 签名 APK 分发不等于 iOS Distribution 验证或 App Store 提交；上述未完成事项保持。

## v1.1.49 iOS 天气修复补记

2026-10-20 本地未签名 Release 归档 `.build/appstore-audit/PhotoPilot-v1.1.49-unsigned.xcarchive` 成功；全量 134 项 XCTest 与 build/analyze 通过。天气改缓存先显及共享在途请求；日期 full 原已含星期。沿用旧开发签名的本地副本已覆盖安装到 iPhone 13 与 iPad Air 4，版本/启动/进程均验证。此为开发签名安装，尚未完成 Distribution Validate、最终商店截图或上列发布门控；未提交 App Store。

## v1.1.50 全球双语天气地点补记

2026-10-20 双端版本 1.1.50(50)。iOS 本地未签名 Release 归档 `.build/appstore-audit/PhotoPilot-v1.1.50-unsigned.xcarchive`、build/analyze 及 150 项 XCTest 通过；开发签名副本沿用旧应用/钥匙串权限，iPhone 13/iPad Air 4 覆盖安装、启动与库汇总保留已验证。隐私清单与源文件一致，本轮未更换天气服务或额外存储精确坐标。全球双语地点与 AX 布局夹具不等于全球网络/真机完整 QA，商店截图仍须按最终 Distribution 构建核对；前述 App Store 门控未关闭，未上传商店。

## v1.1.51 同行排版补记

2026-10-20 双端版本 1.1.51(51)，仅变更播放/休息的地点与温度同行和宽窄屏布局，全球地点/坐标/天气服务逻辑未改。iOS 最终 Release archive、build/analyze 与153项 XCTest 通过，Android379项测试及签名构建通过；iPhone13/iPadAir4 开发签名覆盖安装与启动/库汇总核验完成。隐私清单仍与源文件一致；前述 Distribution/截图/全球网络真机QA等商店门控未关闭，未上传商店。

## v1.1.52 全量审计与商店准备补记

2026-10-21 最终本地未签名归档 `.build/appstore-audit/PhotoPilot-v1.1.52-submit-prep-unsigned.xcarchive` 成功。实读版本1.1.52(52)、dev.photopilot.scaffold、最低iOS15、SDK iphoneos27.1、设备族1/2；隐私清单四类理由与完整ThirdPartyNotices均与源文件逐字节一致。en/zh-Hans资源含正确语言选项与非计费网络说明。

最终iOS166项、Android387项测试通过，双端构建/静态检查与Android签名Release通过。Release模拟器iPhone与iPad各6项UI回归通过，11张商店截图已更新并目检；[截图证据](ScreenshotEvidence.json)记录文件哈希、像素与xcresult来源。横屏改用来源页真实2752×2064像素，移除含未核权OS图库样片的编辑页素材；不是合成App界面。模拟器素材不等于Distribution或真机完整验收。

最终归档的开发签名副本已覆盖安装iPhone13，进程与库112资产/1相框/1来源保留验证通过；iPadAir4在初期52安装后掉线，最终审计包仍待连接安装。Android/Harmony4.3未接入真机，NAS/iCloud实际切网未验收。

SMB锁定源码218文件经独立arm64重建通过、前后哈希不变；[源码交付与重建说明](SMBDistribution.md)提供公开交付地址与步骤；v1.1.52 Release已公开源码tar与逐文件manifest，公开下载与本地哈希一致，证据见16。此证据不关闭最终签名商店包替换/重链接、EULA及分发法律结论。正式团队/Distribution Validate、天气标签/供应商证据、审核NAS、定价地区与ASC表单仍未完成，未上传或提交App Store。
