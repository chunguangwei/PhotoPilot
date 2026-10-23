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
| 地区与 EU | [TerritoryPlan.md](TerritoryPlan.md)：按用户最新决定：全部实际可选非大陆、非EU地区，港澳台包含；见TerritoryPlan当前保存实证 |
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

### 协议与App ID进展（2026-10-23续）

Developer账户实际显示新版协议签发2026-10-14、接受2026-10-23，ASC协议阻塞提示已消失。已在同一发布团队注册显式`PhotoPilot / dev.photopilot.scaffold`并在Identifiers列表核验成功；保持BundleID、未新增可选Capabilities。尝试创建iOS商店记录（主语言en-US、SKU`photopilot-ios`）时，Apple返回“PhotoPilot名称已被使用”，未创建成功。英文名称候选`PhotoPilot - Photo Frame`已请用户确认，未擅改原文案或重新提交创建。DSA事实、审核联系人/版权、Distribution与最终加密/许可交付项目仍未闭环；没有上传或提交审核。

### 商店记录已创建（2026-10-23续）

用户确认英文商店名`PhotoPilot - Photo Frame`及个人业余项目性质。ASC应用记录已创建并在重载列表核验：App ID `6826092247`，BundleID不变，主语言en-US、SKU`photopilot-ios`，状态Prepare for Submission。已保存版本`1.1.58`及英文描述/推广文本/关键词/支持URL/审核说明、用户提供的私有审核联系人；无需登录，手动发布。重进版本页确认标题/侧栏为1.1.58、保存按钮禁用。审核联系人仅填ASC私有字段与本机0600记录，电话不入Git。版权人另待确认，未从联系人擅自推断；未填NAS私有凭证，未附审核zip。

截图上传尝试被Kimi扩展“允许访问文件网址”未开启拦截，系统选择器替代未完成；媒体管理大屏iPhone当前仍0/10，不能写成已上传。用户可选择允许扩展该权限，或由用户在系统选择器选图，不自动扩大扩展权限。个人业余事实已确认，DSA按PhotoPilot单App核对，未修改账号级声明以免影响其他App。签名身份复核仍未发现本地Distribution；没有正式签名打包/Validate/构建上传/提交审核，其他本地化和截图/问卷仍需ASC实际填写。

### 最新地区/平台决定（2026-10-23）

用户明确改为非大陆、非EU，只发布iPhone/iPad、无需账号登录。已实际保存147地区供应，28排除项逐一表格核对，未来新地区自动加入关闭。此前含EU/法国的计划为历史，当前不要求法国首发材料；SMB加密问卷不因此免除。版权邮箱提议已按Apple字段规则建议改用年份+真实版权人，尚待用户确认。复查发现前轮审核资料部分未持久保存，已重填联系人/备注/无需登录并再次保存；离页重载复核仍待完成，不能沿用此前“全部已保存”断言。

### 审核字段保存复核（2026-10-23续）

复现：WebBridge fill/click对审核资料React字段只改变表面DOM，未触发保存状态；前轮联系人/无需登录保存断言撤回。改用授权页面内的可信键盘输入后Save变为可用；实际浏览器请求`POST appStoreReviewDetails`返回201。整页重新导航后只读核对：loginRequired=false，四项联系人非空、备注1822字符、版本1.1.58。联系方式内容未输出到公开证据。当前已确认无需登录持久保存。

Mac与Vision Pro默认勾选已取消并保存；免费初始定价确认各地区0.00、确认保存。地区仍147，不将定价表175行误当可用175地区。版权建议2026+真实版权人姓名，邮箱继续用于支持联系，待用户确认姓名填写。

### 人工检查前资料补齐（2026-10-23）

用户要求先补信息再人工检查，不提交。已补三语言App名称/副标题、简中/繁中版本描述/推广/关键词/支持网址；英文保留。分类摄影与录像/生活，内容版权选择有必要第三方权利，Apple标准EULA保留。年龄问卷原已存在，本轮逐页读取确认所有否/无，非Kids、无提高覆盖，计算4+；未把现有答案冒称本轮新建。版权按用户“可以”确认填2026 魏春光。

三语言隐私政策URL已保存，三个天气类别的用途/关联/追踪各子表保存，预览显示位置/搜索/诊断与身份关联；没有点击隐私标签发布。PRIVACY.md范围/生效日已对齐1.1.58/Oct23，数据处理承诺未新增。无需隐私选择网站，不编造可选URL。

本轮用原私有记录只读复验：WebDAV列目录、SMB3.1.1强加密、六张图片双协议哈希均通过（`.build/appstore-submit/nas-readonly-oct23.json`）；不执行写入/删除。私有审核备注补两协议真实连接参数、临时凭证、六图步骤与样图公开ZIP链接；2755 UTF-8字节，未公开这些凭证。ZIP未实际附加，截图仍未上传；只提供链接不声称附件已附。审核联系信息保持，App登录仍不需要。

[OwnerReview.md](OwnerReview.md)汇总人工检查入口、隐私重点和真实未完项。Distribution/Validate/构建上传/加密随包问卷、截图权限、账号级DSA提示及最终分发证据仍需下步；不创建生产密钥、不动其他App、不点击添加审核或提交。

### 必填截图与附件已上传（2026-10-23续）

原扩展上传缺本地文件权限，改用系统文件选择器成功，未扩大权限。后台当前将iPhone中等屏列必填，故另建隔离iPhone18Pro模拟器，以当前58 Release构建补拍1206×2622五张；build-for-testing和截图UI测试1/1通过，逐图目检，见ScreenshotEvidence58。该新模拟器默认自带6张Apple图，另导入6张自有SeedMedia，公开首页/播放器显示自有图；含图库的横屏编辑捕获排除，不把图库资产12当成仅6。截图后该专用模拟器已shutdown，个人真机/其他模拟器未改。

英文/简中/繁中三套后台均实际关联iPhone中屏5/大屏5/iPad13英寸6；本轮重新导航逐语言核对计数（48处展示、16个独立PNG），MediaUploadEvidence记录结果。ReviewPhotos.zip通过系统选择器上传，整页重载后精确文件名仍存在、input files无待上传项；私有NAS备注和无需登录保持，未将凭证写入公开证据。交接页面切到英文版App预览和截屏，而不是隐私页或标题宣传图页。

按真实team准备本地ExportOptions（destination=export、关闭自动改version/build），实际exportArchive失败：无对应App Store provisioning profile，提示需allowProvisioningUpdates。本机有效Distribution身份为空。已具体请求允许该发布账号创建/下载PhotoPilot签名描述文件及缺少时生成Distribution证书；等待明确答复，不撤销已有证书、不自动生成生产密钥。未经此步骤不能宣称IPA/Validate/上传完成。可选视频/标题创意素材有意留空，不阻塞首次提交。隐私标签仍草稿，无添加审核/提交。
