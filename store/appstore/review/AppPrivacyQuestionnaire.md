# App Privacy 填写稿 / entry worksheet — 1.1.58 (2026-10-21)

2026-10-23已在App Store Connect保存三类天气数据及各自App Functionality/linked=yes/tracking=no草稿，并生成预览；用户于2026-10-23随后明确确认，已点击隐私标签“发布”并核验后台发布时间。三语言隐私政策URL已填，待人工检查。下文保留分类依据与条件。适用 iOS/iPadOS 1.1.58(58)；Android GitHub 更新器不属于此 iOS 问卷。下列“拟填”是基于代码和供应商公开说明的申报判断，不是供应商日志审计或 Apple 批准。最终提交人须按真实后台逐项核对；候选58包内PrivacyInfo.xcprivacy已同步三个天气类别及关联/用途/追踪标记；它仍不能代替实际ASC标签填写。

## 1. 首屏与三个天气类别

| 中文字段 / English field | 拟填 / prepared answer | 依据与限制 |
|---|---|---|
| 隐私政策网址 / Privacy Policy URL | `https://github.com/chunguangwei/PhotoPilot/blob/main/PRIVACY.md` | 双语公开政策；正式填写前以匿名访问核验最终 URL 与提交包一致 |
| 隐私选择网址 / Privacy Choices URL | 可留空 / Leave blank | 可选项；没有独立隐私管理网站，不伪造表单 |
| 你或第三方合作伙伴是否收集此 App 的数据？ / Do you or your third-party partners collect data from this app? | 是 / Yes | Open-Meteo 排障日志可保存90天；不是只为实时返回而短暂处理 |
| 数据类型 / Data Types | 粗略位置 / Coarse Location；搜索历史记录 / Search History；其他诊断数据 / Other Diagnostic Data | 城市/一位小数天气坐标；城市查询；IP等技术排障信息。Search History 是基于 URL 查询可能进入日志的保守映射，供应商未公布独立城市查询日志字段表 |

对上述**每一个**类别分别填写以下子问卷：

| 中文字段 / English field | 拟填 / prepared answer |
|---|---|
| 第三方广告 / Third-Party Advertising | 不选 / No |
| 开发者广告或营销 / Developer’s Advertising or Marketing | 不选 / No |
| 分析 / Analytics | 不选 / No；当前证据是天气服务及技术排障，不是用户行为分析 |
| 产品个性化 / Product Personalization | 不选 / No；定位驱动天气属于功能必需输入，不据此声明推荐或广告画像 |
| App 功能 / App Functionality | 勾选 / Yes |
| 其他目的 / Other Purposes | 不选 / No |
| 与用户身份关联？ / Linked to the user? | 保守拟填 是 / Yes |
| 用于追踪？ / Used for tracking? | 否 / No |

“关联=是”并不表示 PhotoPilot 有账号。Apple 要求考虑账户、设备或其他身份线索，以及收集前的去标识措施；Open-Meteo 声称技术 IP 不关联用户身份，但没有证明存储前不可逆匿名化，也没有公布 IP 与请求坐标/搜索字段是否同条存储。故现稿采用 linked=yes；若希望改为 no，应先取得供应商关于收集前去标识、跨表重关联和后续用途的确切证据，再重新判断。不能把供应商“non-personal”措辞等同 Apple 标签定义。三类别的 no tracking 依据为当前无广告、无跨公司广告归因/数据经纪用途；这与 linked 是两个独立问题。

## 2. 完整数据类型核对表

“不选”仅表示当前没有发现应在 iOS 标签中申报的离机留存流，不是 App 不使用该类系统权限。下表覆盖 Apple 当前类别；后台新增类别须重新核对。

| 分组 / Group | 类型 / Data types | 当前填写 |
|---|---|---|
| 联系信息 / Contact Info | 姓名 Name、邮件 Email Address、电话 Phone Number、地址 Physical Address、其他联系信息 Other User Contact Info | 不选；主动支持邮件另见§3，不能把支持收件地址误当用户邮件收集 |
| 健康与健身 / Health & Fitness | Health、Fitness | 不选 |
| 财务 / Financial Info | Payment Info、Credit Info、Other Financial Info | 不选；免费、无购买流程 |
| 位置 / Location | Precise Location | 不选；App传给天气服务的是0.1°坐标；CLGeocoder 接收原始位置属于 Apple 系统服务，不能声称它只接收粗略位置 |
| 位置 / Location | Coarse Location | 勾选，按§1 |
| 敏感信息 / Sensitive Info | Sensitive Info | 不选 |
| 通讯录 / Contacts | Contacts | 不选 |
| 用户内容 / User Content | Emails or Text Messages、Photos or Videos、Audio Data、Gameplay Content、Other User Content | 不选；照片、导入音乐、相框名本地处理；没有 PhotoPilot 上传端点 |
| 用户内容 / User Content | Customer Support | 当前不自动勾选；主动支持边界及条件见§3 |
| 浏览 / Browsing History | Browsing History | 不选；不监测用户外部浏览历史 |
| 搜索 / Search History | Search History | 勾选，按§1；指天气城市查询，不是本地照片搜索 |
| 标识符 / Identifiers | User ID、Device ID | 不选；没有 PhotoPilot 账号/设备标识上传。IP按用途映射诊断，不自动归 Device ID；NAS认证另见§3 |
| 购买 / Purchases | Purchase History | 不选 |
| 使用数据 / Usage Data | Product Interaction、Advertising Data、Other Usage Data | 不选；没有自定义启动/操作统计上传；Apple自身统计不等于集成了分析SDK |
| 诊断 / Diagnostics | Crash Data、Performance Data | 不选；无自动开发者诊断上传；主动导出边界见§3 |
| 诊断 / Diagnostics | Other Diagnostic Data | 勾选，按§1 |
| 环境 / Surroundings | Environment Scanning | 不选 |
| 身体 / Body | Hands、Head | 不选 |
| 其他 / Other Data | Other Data Types | 不选；没有证据时不作为兜底重复勾选 |

## 3. 特殊边界与必须保留的条件

- **天气不是可省略披露的可选功能**：首次同意后仍可能随播放刷新，不满足 Apple 对偶发、自愿、每次主动提交等全部例外条件。关闭天气不请求天气服务，不会改变已启用用户的披露义务。
- **Apple系统服务**：原始当前位置传入 CLGeocoder 双语反向解析；城市搜索还可回退系统地理编码。公开政策披露此流。Apple说明开发者不负责申报 Apple 自行收集的数据；若未来 PhotoPilot/其他供应商留存原始精确坐标，则重新增加 Precise Location。
- **NAS**：用户指定 NAS 接收读取请求、连接IP和认证；没有开发者集中收集服务器。只读不是完全不发送数据，认证确实离机。不能把任意用户自有 NAS 日志冒充开发者统一日志，也不能承诺 NAS 不留存。审核 NAS 仅手动配置后访问合成目录，不自动用于普通用户；若该服务变成面向用户的开发者托管来源，应单独查其认证/访问日志、留存期及 User ID/Diagnostics 等标签，不能沿用本稿。
- **本地导出诊断**：SettingsViewController 使用系统分享界面，没有预设上传收件人；生成文件不等于开发者收集。用户自行在外部邮件中发给支持时，开发者可能持有邮件地址和支持内容。不要宣称仅凭“自愿”就自动满足 Apple 全部 optional-disclosure 条件：当前 App 本身没有带显著用户名的支持提交表单。最终运营人须确认实际支持接收方式；若把系统分享→开发者支持收件箱作为 App 的收集流程，则添加 Email Address、Customer Support，必要时 Other Diagnostic Data，App Functionality、linked=yes、tracking=no；仅在有证据满足全部例外时才省略。这是实际支持运营需确认的唯一标签分支，不要求捏造不存在的 App 内表单。
- **App Analytics**：只使用 Apple 提供的聚合下载/隐私阈值统计，不增加自定义遥测；如将来取得可识别原始数据、集成SDK或加入安装ping，重填。诊断导出没有照片名、NAS地址或凭证字段。

## 4. 可追溯证据

代码位置按本次工作树核验；哈希基线见[加密问卷](ExportComplianceQuestionnaire.md#1-二进制与源码事实)。

| 事实 | 当前源码证据 |
|---|---|
| active forecast仅Open-Meteo；搜索含系统回退 | `ios/PhotoPilot/App/AppEnvironment.swift:44–46` |
| ephemeral session、天气一位小数经纬度 | `ios/PhotoPilot/Data/Net/WeatherProvider.swift:35–45,68–74,81–90` |
| 城市名/语言进入Open-Meteo查询 | 同文件`:121–150` |
| 原始位置进入系统地名解析 | `ios/PhotoPilot/Data/WeatherRepository/WeatherAutoLocator.swift:50–57,77–92` |
| 城市系统回退及本地SHA256 ID | `ios/PhotoPilot/Data/Net/WeatherProvider.swift:303–322`；本地SHA不是上传设备ID |
| 诊断字段和本地临时文件 | `ios/PhotoPilot/Data/Diagnostics/DiagnosticsExporter.swift:5–33` |
| 诊断系统分享，无固定收件人 | `ios/PhotoPilot/UI/Settings/SettingsViewController.swift:588–592` |
| 当前公开政策 | `PRIVACY.md:19–29,40–49`；既有[天气证据](WeatherPrivacyEvidence.md) |

官方依据（2026-10-21联网核验）：[Apple App Privacy Details](https://developer.apple.com/app-store/app-privacy-details/)：离机且超出实时请求所需时间的可访问留存、第三方、IP按用途分类、关联/追踪及本地/Apple服务边界；[Open-Meteo Free API privacy](https://open-meteo.com/en/terms)：技术IP及可能含坐标的90天排障日志。上述分类与linked保守选择是项目推断，不是Apple对PhotoPilot的个案判定。
