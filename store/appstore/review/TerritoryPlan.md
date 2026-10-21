# 非中国大陆首发地区 / Territory worksheet — 2026-10-21

用户方向是先发布非中国大陆。此稿采用**所有当前ASC可选的非中国大陆商店**为拟定范围，不擅自缩为美国；尚未写入ASC或发布。系统当前实际可选列表和账号合规状态只能在授权进入ASC后验证。

## 拟定可照填选择

| 字段 / Field | 拟定值 / Prepared value | 当前条件 |
|---|---|---|
| 分发方式 / Distribution | Public on the App Store / 公开App Store | 不是企业/私有Custom App |
| 价格 / Price | Free / 免费 | 无广告、IAP、订阅；不宣称永远免费 |
| 可用地区 / Availability | 选择当前全部地区，再明确取消 China mainland / 中国大陆 | 后台保存前逐项核对下列三个独立商店和特殊条件；不能自动勾选未来新地区而覆盖既定排除项 |
| 中国大陆 / China mainland | 排除 / Unselected | 本轮不准备大陆备案/版号，不把大陆规则套用于港澳台 |
| 香港 / Hong Kong | 纳入 / Selected | 独立商店 |
| 澳门 / Macao | 纳入 / Selected | 独立商店 |
| 台湾 / Taiwan | 纳入 / Selected | 独立商店；简体中文/英文UI不伪称繁体中文UI |
| 美国 / United States | 纳入 / Selected | 仍受真实出口分类与Apple出口问卷约束 |
| 法国 / France | 计划纳入，但文件条件待完成 / Intended, documentation pending | 额外标准SMB密码学触发法国声明分支，见[加密问卷](ExportComplianceQuestionnaire.md)；不能填写无文件已批准 |
| EU其余26国 / Other 26 EU territories | 计划纳入，DSA身份待确认 / Intended, DSA pending | DSA不能因个人开发者/免费自动填non-trader |
| 英国、瑞士、挪威、冰岛、列支敦士登 / UK, Switzerland, Norway, Iceland, Liechtenstein | 纳入 / Selected | 不误归EU27；其他账号适用合规要求以真实提示核对 |
| 其他所有ASC可选非大陆地区 / All other selectable non-mainland territories | 纳入 / Selected | 包括日本、韩国、新加坡、加拿大、澳新及其余地区；不凭本稿生成一份会过期的固定商店数量 |
| 上线方式 / Release option | Manually release this version / 手动发布 | 审核通过后仍保留共同上线检查，不在本轮执行 |

计划范围与可实际启动的地区分开记录：若法国或EU合规未就绪，可准备“其余非大陆先发、法国/EU暂缓”的差异清单，但**需要用户明确选择后**才改变地区；不能默默排除法国/EU，更不能把全局方向替换为US-only。

## EU DSA 逐项稿

| 字段 / Field | 当前可填写内容 / Answer boundary |
|---|---|
| Trader status / 交易商身份 | 待账户所有人事实确认；暂不勾选任何虚构结论 |
| 判断事实 / Self-assessment facts | 已知当前免费/无广告/无IAP；未知是否以职业/商业身份开发、商业推广、VAT注册、公司业务关系和商业化意图。存在“将来视使用量可能收费”的规划，不能描述成已确定永不商业化的纯爱好项目 |
| 若非交易商 / If non-trader | 由主体确认本App实际在职业/经营范围之外，选择相应non-trader声明；Apple会向EU消费者展示相关消费者保护权利不适用的提示 |
| 若交易商且个人账号 / If trader, individual | 填真实公开地址或P.O.Box、电话、邮箱；验证邮件/电话，补姓名/地址和替代地址关联证明；不能用支持邮箱代替必填地址/电话 |
| 若交易商且组织账号 / If trader, organization | 核对D-U-N-S关联地址；补公开电话/邮箱及验证；信息须符合真实组织 |
| 所有交易商 / All traders | 按ASC要求补付款账户及EU合规声明，不能因下载免费推定无此要求 |
| App-specific status / 单App身份 | 核对账号级与PhotoPilot级身份设置一致，账号其他App的事实不能由本项目推断 |

Apple要求即便不在EU分发也申报身份；EU27可用性须以实际合规状态为准。EU27是：Austria, Belgium, Bulgaria, Croatia, Cyprus, Czechia, Denmark, Estonia, Finland, France, Germany, Greece, Hungary, Ireland, Italy, Latvia, Lithuania, Luxembourg, Malta, Netherlands, Poland, Portugal, Romania, Slovakia, Slovenia, Spain, Sweden。

## 其他地区与真实账号信息

- 韩国的Apple特定身份信息规则依“主体是否位于韩国、个人/组织及协议状态”等事实触发，不是任意外国免费App一律需要韩国BRN。先确认真实主体所在地，再对照账号提示；不能伪造登记号。
- 其他国家监管许可、制裁/出口限制或Apple不可选地区不由一份静态稿绕过；仅选Apple实际可提供的地区。发现具体阻塞时记录地区、原文、缺失材料，再决定是否暂缓该地区。
- 最小待补：真实Developer/ASC账号类型与主体所在地；本App的DSA经营/职业事实及最终身份；若trader则可公开且可验证的地址/电话/邮箱及证明；法国首发文档路径；明确是否批准在文件未齐时仅暂缓相关地区。这些不是密码或2FA验证码，不应写入公开稿。

来源（2026-10-21联网核验）：[Apple availability](https://developer.apple.com/help/app-store-connect/manage-your-apps-availability/manage-availability-for-your-app-on-the-app-store/)、[Apple EU DSA](https://developer.apple.com/help/app-store-connect/manage-compliance-information/manage-european-union-digital-services-act-trader-requirements/)、[Apple Korea](https://developer.apple.com/help/app-store-connect/manage-compliance-information/manage-korea-compliance-information/)、[Apple encryption document matrix](https://developer.apple.com/help/app-store-connect/reference/app-information/export-compliance-documentation-for-encryption/)。此稿没有宣称全球法务审查或商店审核必过。
