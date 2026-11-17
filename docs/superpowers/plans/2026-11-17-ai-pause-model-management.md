# AI 分类暂停保存与模型管理实施计划

> 执行时使用 superpowers:subagent-driven-development 或 superpowers:executing-plans，逐项验证后集成。

## 目标

依据用户明确需求，双端支持扫描中途暂停并保存相框、从未完成照片继续、设置页统一模型管理，以及可恢复的下载和分类任务。已有首页加载与播放菜单修复先以 1.4.6/67 交付，新增功能在后续版本集成。App Store 等待用户真机测试确认。

## 产品行为

- 设置页增加 AI 模型管理。模型条目显示大小、可用性、实际下载进度和暂停/继续/重试/删除操作。下载源与网络偏好持久化。
- AI 创建页保留模型选择及就绪状态。缺模型时主要操作跳到设置内对应模型，回程恢复来源、分类规则、名称、自动分类选项及已完成预览。
- 扫描进行中提供暂停并查看结果。当前照片处理到安全边界后落盘，预览明确显示已扫描及总数，允许保存部分结果。
- 暂停确认可建立暂无匹配的类别相框，并明确显示暂停和待继续，避免尚未扫描的类别丢失。每类别保持稳定 draftID 到 frameID 映射，重复确认和进程恢复幂等。
- 独立持久 userPaused 优先于 automatic。保存、修改配置、来源增量、回前台、重启均保留手动暂停，只有明确继续才解除。
- 继续扫描依据模型 revision、预处理版本、规则及内容指纹，跳过已成功处理的未变照片，重试失败或变化项。未扫描和失败照片保留原 AI 成员，成功扫描且拒绝才移除 AI-owned 成员，手动成员与排除保持优先。

## 执行与持久性

- 任务所有权归进程任务协调器。页面离开仅解绑观察者，明确暂停/取消才修改持久意图。模型删除、清空数据和迟到回调使用世代/身份保护。
- Android 使用 dataSync 前台任务与进度通知，持久任务描述和现有 Range/part 校验协作。超时及时保存停止，不等待不可取消的推理阻塞服务退出。按实际运行持有有界 WakeLock，用户活跃时保持屏幕唤醒，结束后释放。
- iOS 下载使用固定后台 URLSession 和每文件一个下载任务，delegate/AppDelegate 接回、getAllTasks 重接及 resumeData。保留已有 part 的迁移、Range/ETag/长度/SHA校验和备份排除。强制关闭且无可用 resumeData 时重试当前文件，保留已完成文件。
- iOS26+ 用户前台发起扫描采用 CPU BGContinuedProcessingTask。expiration 原因不明确时记为需明确继续的停止态。iOS15–25 使用有限后台时间及系统 BGProcessing 调度，持久断点供后续恢复。后台计算受系统许可约束。
- 统一屏幕唤醒 owner，避免 AI、播放器和导入页相互清除状态。继续遵守低电量、严重温度与低内存保护，系统中断状态与用户暂停区分。
- 复用现有设置/文件持久格式并兼容缺少新字段的旧数据，保持现有数据库表契约。照片仍只在设备处理，通知和日志使用通用进度。

## Android 切片

- [ ] 在 AIState、AIPreviewCheckpoint、AIPreview、AIFrameService 补用户暂停、部分结果和已处理指纹迁移的回归。
- [ ] 实现部分预览与事务性幂等保存，涵盖零匹配类别、旧成员保留、排除、进程恢复及继续扫描。
- [ ] 在 AIModels 及新增任务协调/服务实现持久下载与前台运行，按模型隔离资源租用，避免下载另一个模型阻塞已就绪模型推理。
- [ ] 在 SettingsView、AIFrameView、MainActivity 和 AppEnvironment 接入模型管理、焦点模型导航及草稿回程。保留 1.4.6 首页与菜单修复。
- [ ] 验证后台/重建/暂停/断点/删除竞争、标准与轻量模型、中文英文以及本机和 NAS。

## iOS 切片

- [ ] 在 AIFrameTypes、AIFrameStore、AIFrameService 补 userPaused、多类别独立 checkpoint、部分提交与 completedContentVersions 迁移。
- [ ] 建立持久草稿和类别映射，支持系统中断后恢复，保留未扫描/失败旧成员，防重复创建。
- [ ] 在 AIModelManager 及后台下载协调器实现文件级后台传输、恢复、暂停与删除回调失效保护。
- [ ] 在 AppDelegate、AppEnvironment、主容器和项目配置接入后台处理许可、系统过期处理与统一唤醒 owner。
- [ ] 新增模型管理页和设置入口，AI 表单跳转下载保留草稿，暂停后提供清楚的保存/继续动作。
- [ ] 运行现有模型、AI状态与Store测试，并补后台重接、多类别恢复、手动暂停优先、隐私排除和空类别测试。

## 分工与验证

Android Agent 负责 android 下的实现与测试，iOS Agent 负责 ios 下的实现与测试。共享词条、版本、设计摘要、16交接及发布由主 Agent 集成。既有冻结契约和生产密钥保持既定规则，新增功能采用现有库和系统框架。

- [ ] 根目录 make check，iOS generate/build/analyze，Android build/lint/test。
- [ ] 平台原生回归与中英手机小屏布局验证，下载网络中断、任务暂停和进程恢复必须有真实夹具证据。
- [ ] iPhone 真机在设备可用时验证本机/NAS暂停保存和继续；Android 当前以模拟器及用户截图验证，不能宣称华为真机通过。
- [ ] 独立代码审查，按实际证据回填16，打包和签名核对，更新既有GitHub仓库，AppStore保持用户确认门控。

## 平台依据

[Apple 后台传输](https://developer.apple.com/documentation/foundation/downloading-files-in-the-background)

[Apple 持续处理](https://developer.apple.com/documentation/BackgroundTasks/BGContinuedProcessingTask)

[Android 前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types)

[Android 前台服务超时](https://developer.android.com/develop/background-work/services/fgs/timeout)
