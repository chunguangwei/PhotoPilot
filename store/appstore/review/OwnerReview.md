# PhotoPilot · 上架资料人工检查入口

2026-10-23；版本1.1.58(58)，App ID6826092247。用户要求先补齐信息、人工检查；不提交审核，不发布隐私标签、不上线。本页区分后台草稿与本地材料，不把已有文件等同已上传。

## 你可以直接检查的后台页面

- [版本资料](https://appstoreconnect.apple.com/apps/6826092247/distribution/ios/version/inflight)：英文、简中、繁中描述/推广文本/关键词/支持链接、版权、私有审核联系人及备注。原生语言为英文/简中；繁中仅商店文案。
- [App信息](https://appstoreconnect.apple.com/apps/6826092247/distribution/info)：英文名PhotoPilot - Photo Frame，简中光屿 PhotoPilot，繁中光嶼 PhotoPilot；主分类摄影与录像、次分类生活；英文主语言；内容版权及Apple标准许可协议。
- [隐私草稿](https://appstoreconnect.apple.com/apps/6826092247/distribution/privacy)：三个类别为粗略位置、搜索历史记录、其他诊断数据，均仅App功能、关联身份、不用于追踪。没有发布标签。
- [价格与地区](https://appstoreconnect.apple.com/apps/6826092247/distribution/pricing)：免费，147地区，排除大陆+EU27，未来商店自动加入关闭，Mac/VisionPro关闭，仅iPhone/iPad。版本设审核后手动发布。

## 隐私政策需要，并且已经准备

[中英文隐私政策](../../../PRIVACY.md)已核对至1.1.58，后台各语言指向公开同一双语政策。重点请核对：

1. 照片、音乐、相框和设置在设备处理；不上传照片，没有PhotoPilot账号、广告、内购或订阅。
2. 天气默认关闭。手选城市也会向Open-Meteo发送城市查询和天气坐标；天气坐标保留一位小数，连接IP可见；供应商说明90天排障日志。不是“无账号=零数据收集”。
3. Apple系统地理编码可接收原始系统位置；不能把所有定位都称为仅粗略位置。无后台持续定位。
4. NAS仅用户配置后连接，认证信息发给相应NAS；本机安全存储凭证。审查服务器仅测试合成样图，不是普通用户照片后端。
5. 删除仅影响本机副本，不删除系统图库/NAS原图；支持用户主动通过邮箱联系时可能保存其邮件及附件以处理请求。
6. iOS只用Apple提供的统计，无另加分析SDK；Android更新会连接GitHub，不能把Android行为填成iOS数据类别。

分类详细理由、保守关联判断和支持邮件条件见[隐私问卷](AppPrivacyQuestionnaire.md)。如果未来以诊断分享作为固定支持提交流程收集数据，需重新核对Email Address/Customer Support类别，不能机械沿用三个天气类别。

## 文案、许可与审核材料

[三语言文案原稿](ListingMetadata.md)、[用户条款](UserTerms.md)、[素材权利记录](RightsRegister.md)、[开源源码交付](SMBDistribution.md)、[加密问卷](ExportComplianceQuestionnaire.md)已备。版权栏为2026 魏春光；支持邮箱保持chunguangwee@gmail.com。Apple标准EULA不会替代第三方许可和LGPL补充。

年龄问卷已逐页检查：无家长控制/年龄保证、公开UGC/聊天/广告/网页浏览；所有成人、医疗、性、暴力、赌博内容均无；不选面向儿童、不提高分级，Apple当前计算4+并有区域差异。用户私人照片不构成产品公开内容目录。

## 尚未完成的交付步骤

- 商店截图已上传并在三语言分别关联：iPhone中等尺寸5张（最新58实拍）、iPhone大屏5张、iPad13英寸6张，共16个独立PNG/48处展示。通过系统文件选择器上传，未扩大扩展权限；逐语言重载核对通过，见[MediaUploadEvidence.json](MediaUploadEvidence.json)。当前后台必填iPhone中等尺寸，旧仅6.9图不够，本轮已补齐。
- 样图ZIP有公开下载链接；私有NAS说明由本机受限记录提供，连接步骤和账号密码已保存到私有审核字段，ZIP已实际上传为审核附件，重载后文件名仍可见；备注保留下载链接作备用。2026-10-23只读复验两协议六图哈希、SMB3.1.1加密通过；正式签名候选仍需最终冒烟。
- 加密类别有证据稿；本次不含法国，不能误填仅系统加密或虚构批准号。实际构建关联问卷留到Distribution候选阶段。
- 已实际尝试导出正式包：因缺少dev.photopilot.scaffold的App Store描述文件失败；本机无可用Distribution身份，创建/下载所需描述文件或分发证书的明确授权已询问、尚待答复。没有撤销证书或改动其他App。Distribution签名、归档Validate、匹配源码/库的最终分发验证、构建上传/Apple处理尚未完成。最低OS硬件/完整VoiceOver与长期稳定性不能填成已全验。
- 不修改影响该团队其他App的账号级DSA身份；PhotoPilot个人业余事实已确认，非EU范围已保存，账户提示仍需按实际入口核对。

人工检查完成后，再处理上述剩余步骤和审核提交；当前没有点击“添加以供审核”或“提交审核”。

可选App预览视频、标题/搜索结果创意素材本轮有意留空，不属于未完成的必填截图。页面已切到英文版“App预览和截屏”，可以直接查看实际上传图片。
