# App Store 提交准备单 · 1.1.58 (58)

2026-10-23：本轮仅准备；没有登录 App Store Connect、注册标识、创建生产证书、上传或提交审核。Android 由用户自行更新，GitHub 仍发 v1.1.58，安装包未变。

## 可直接使用的材料

| App Store Connect 项目 | 材料与填法 |
|---|---|
| App 信息与三个本地化 | [ListingMetadata.md](ListingMetadata.md)：英文主语言，英文/简中/繁中名称、副标题、关键词、宣传文本和描述。原生界面仅英文/简中；繁中仅商店文案 |
| 分类、定价、购买 | Photo & Video；免费；无广告、无内购、无订阅；不选择 Kids；年龄问卷依据 ListingMetadata，最终评级由 Apple 计算 |
| 截图 | `../iphone-6.9/` 按 01–05 顺序；`../ipad-13/` 按 01–06 顺序。英文界面可用于各商店本地化，不声称是中文截图；不上传 raw 目录或测试失败截图 |
| 图标 | Xcode Asset Catalog 的 AppIcon 随构建交付；不要用设计预览图替代实际 App 图标 |
| 支持与隐私 | 支持 https://github.com/chunguangwei/PhotoPilot ；隐私 https://github.com/chunguangwei/PhotoPilot/blob/main/PRIVACY.md ；邮箱 chunguangwee@gmail.com |
| 审核说明 | [README.md](README.md) 的英文步骤及 [ListingMetadata.md](ListingMetadata.md) 的行为解释；不需要 PhotoPilot 登录 |
| 审核附件 | [ReviewPhotos.zip](ReviewPhotos.zip)：六张自有合成样图；[RightsRegister.md](RightsRegister.md)：素材来源；按 ASC 实际附件入口提供，不写“已附”直到完成 |
| NAS 私有审核说明 | [NASEnvironment.md](NASEnvironment.md)：SMB 和 WebDAV 地址、端口、路径、六图预期。账号密码从本机私有记录复制到审核私有备注，不能放公开文案；提交当天再实连，并保持服务至审核结束 |
| 隐私问卷 | [AppPrivacyQuestionnaire.md](AppPrivacyQuestionnaire.md)：照片本地处理与天气第三方数据流分别说明；不因无账号就填“无收集” |
| 加密问卷 | [ExportComplianceQuestionnaire.md](ExportComplianceQuestionnaire.md)：SMB 含非系统密码实现，不直接套用仅 HTTPS/系统加密豁免 |
| 地区与 EU | [TerritoryPlan.md](TerritoryPlan.md)：全部实际可选非大陆地区，港澳台包含；EU DSA/法国材料未齐不能默填，也不擅自剔除地区 |
| EULA 与源码交付 | Apple Standard EULA，加 [UserTerms.md](UserTerms.md) 的第三方许可补充；[SMBDistribution.md](SMBDistribution.md) 记录 LGPL 源码/替换库路径，最终签名分发实证仍待完成 |
| 审核后发布 | 建议选择手动发布，待审核通过后由用户确认上线；本轮没有保存该选项 |

## 图片核对

iPhone 五张均为 1320×2868；iPad 五张竖屏为 2064×2752，一张横屏为 2752×2064。已逐图查看首页、播放、播放设置、音乐选择和来源页，无个人照片或凭证。截图原始采集版本/素材更新历史见 [ScreenshotEvidence.json](ScreenshotEvidence.json)，不能把历史截图改标为 58 新采集。当前 58 的新增修复主要是通用设置页 AX/窄屏布局及大字号导航，截图显示默认字号；最终 Distribution 冒烟时再核对默认画面，若发生差异必须刷新对应图。

尺寸已对照 [Apple 截图规范](https://developer.apple.com/help/app-store-connect/reference/app-information/screenshot-specifications/)（2026-10-23）核实。无需额外制作无真实界面依据的营销拼图或预览视频。

## 打包已准备与下一步执行

当前候选归档：项目根目录下 `.build/appstore-audit/PhotoPilot-v1.1.58-final-unsigned.xcarchive`。这是 unsigned Release，不能直接称为可提交 IPA。版本 1.1.58、build58，Bundle ID `dev.photopilot.scaffold`，iPhone/iPad，最低 iOS15；工具 Xcode27.1。证据见 [PreparationEvidence.json](PreparationEvidence.json) 和 [ReleaseAudit.md](ReleaseAudit.md)。

只读核查本机有效签名身份没有 Apple Distribution；现有 project.yml 的 DEVELOPMENT_TEAM 与开发身份不代表用户已选定商店发布主体。用户已于2026-10-23确认发布 Apple ID 与 Xcode 登录账号一致，具体登录邮箱仅存本机私有记录，不作为公开支持邮箱。下一步在该已确认账号核对 membership、协议、ASC 权限、Bundle ID 所有权及 app record。不要沿用别人的开发身份，也不要为方便修改 Bundle ID。若 ASC 已用过 build58，则 iOS 的 build number 必须另定，Android 保持 58，不重复上传同一 build。

确认发布团队后，通过 Xcode 的 Signing & Capabilities 选择该团队并使用适用的 App Store 分发签名；如需修改工程声明，应修改 ios/project.yml 后 `make ios-generate`。重新执行 `make check`、`make ios-build`、`make ios-analyze` 和最终相关测试，再以 PhotoPilot scheme / Release / Any iOS Device 归档。命令式归档等价入口（TEAM_ID 必须由真实账号确定，不能照抄占位值运行）：

```sh
xcodebuild -project ios/PhotoPilot.xcodeproj -scheme PhotoPilot \
  -configuration Release -destination 'generic/platform=iOS' \
  -archivePath .build/appstore-submit/PhotoPilot-1.1.58-58.xcarchive \
  DEVELOPMENT_TEAM=TEAM_ID CODE_SIGN_STYLE=Automatic \
  CODE_SIGN_IDENTITY='Apple Distribution' archive
```

本轮没有运行此签名命令。需要证书/描述文件时在下一步账号操作中处理，不将 `-allowProvisioningUpdates` 隐式加入准备脚本。Xcode Organizer 核对版本、Bundle ID、团队、签名、隐私清单、第三方 notices、图标和 dSYM；执行 Validate App 并留存报告，再用 App Store Connect 分发流程。若需本地导出，当前 Xcode 的 ExportOptions 使用 `method=app-store-connect`、`destination=export`、真实 `teamID` 与正确签名配置；`destination=upload` 会上传，本轮不使用。

最终签名产物必须再次检查：无 UITest/私有审核凭证/个人图库/fixture；隐私清单和 notices 与源一致；AMSMB2 源码及可替换库交付对应这个包。留存 archive、dSYM、IPA 与 SHA256；加密问卷补最终签名包证据。分发候选需完成 fresh install/升级、跳过启动、照片有限/拒绝、离线本地播放、两种 NAS、旋转/键盘/大字号冒烟；现有开发包回归结果不能替代正式签名候选。

Apple 会处理上传构建后才在 ASC 可选；上传不等于提交审核。[Apple 上传流程](https://developer.apple.com/help/app-store-connect/manage-builds/upload-builds/)。

## 下次需要用户/账号提供的真实信息

1. 发布 Apple ID 已由用户确认，与 Xcode 登录账号一致，无需再次询问。仍需核对账号下具体团队、个人或组织、真实主体所在地，以及当前 Bundle ID 是否归该团队。
2. 商店版权人及年份、审核联系人姓名与可接通电话。支持邮箱已确认，不再重复询问。
3. EU DSA：此 App 是否在职业/经营活动中提供；若为 trader，提供真实可公开且可验证的地址/电话等。免费不能替代判断。
4. 维持法国首发时所需的加密材料及最终问卷路径，按已准备证据完成，不假定豁免。
5. Distribution 身份/描述文件、最终 Validate 与处理结果、尚未闭环的 LGPL 最终签名分发实证。真实 VoiceOver 全流程和最低 OS 硬件仍未完整验证，不勾选未经证实的无障碍能力声明；稳定性由用户测试。

以上为明确剩余项，不是“零拒审风险”承诺。完成账号资料与正式候选检查后，下一步才填写保存、上传及提交审核。

## 正式发布现场核对（2026-10-23）

用户要求开始正式发布准备后，已通过Xcode账号设置、开发者门户和ASC当前页面只读核对：发布Apple ID与用户确认一致，Xcode显示Admin；开发者门户团队与现有真机开发候选描述文件TeamIdentifier一致。具体账号/团队记录存本机私有文件，不复制其他App的审核联系人。开发者会员显示个人注册及2027年续订日期。

实际阻塞：ASC提示新版Developer Program协议需账户持有人接受，同时提示EU DSA身份待提供。账户持有人需自行阅读并接受法律协议；Agent未代签。ASC当前应用列表未见PhotoPilot；新建App表单只查看后取消，未创建。门户的App IDs列表不含当前`dev.photopilot.scaffold`，因此尚不能把既有真机通配符开发描述文件当作正式商店App ID。

操作顺序：完成协议/DSA事实确认 → 为PhotoPilot处理显式BundleID（优先检查保留现有ID的可注册性，不擅改标识）与ASC应用记录 → 分发签名及Release归档/Validate → 完成文案、截图、隐私/年龄/加密/地区和私有NAS审核资料 → 上传处理后检查候选并提交审核。版权与审核姓名电话需要用户提供或明确确认，不能推断自账号姓名。当前未创建正式标识/证书/应用记录、未上传或提交。
