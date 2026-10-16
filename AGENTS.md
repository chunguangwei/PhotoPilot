# PhotoPilot · 光屿 — 后续 Agent 工作约束

## 当前授权边界

2026-10-14 新授权：对已实现双端 UI/交互检查并修复不符合设计的部分，重新设计品牌 Logo 与启动页。用户确认「取景框＋光点、沿用暖白/墨色/香槟金」及「每次冷启动展示6秒，首帧可跳过；热恢复/旋转不重播」。跳过不取消必要初始化，失败立即显示；本轮可更新原生品牌资源与版本化本地设计增量，不含生产发布/提交/M0验收。现行细则见 01 §0.3、02 §7.2、16 最新记录，旧600ms/800ms启动规则不再生效。

2026-10-14 收尾授权（已执行完毕）：review 代码与 UI 设计；iOS 打包并覆盖升级安装到当前接入的 iPhone（v1.0.0，截图实证）；**Git 提交并上传 GitHub 公开仓库 `github.com/chunguangwei/PhotoPilot`**（含 v1.0.0 Release 与签名 APK 资产）；双端打包；**Android 应用内 GitHub 更新器已实现**（检查更新→断点续传→前台保活→安装）；本机 release keystore 已生成（`android/photopilot-release.keystore`+`keystore.properties`，仅本机不入 Git，丢失即无法覆盖更新）；商业授权联系邮箱确认为 **chunguangwee@gmail.com**（双 e，全仓同步）。后续 Git 提交/推送/Release 发布在既有仓库范围内视为已授权；新增仓库、商店上架、Google/Live Photo 仍需单独授权。

用户已授权正式开发并已完成多轮切片：**M1**（本地导入→二次选片→播放，双端原生、数据层、夹具驱动测试）、**NAS 来源**（N1–N8：阶段守卫/WebDAV/TLS-TOFU/凭证安全存储/增量同步；SMB 库经用户 2026-10-08 批准已安装集成）、**天气**（M2-W1：Open-Meteo 客户端直连，供应商决议见 08 §8）、**RAW**（按系统解码运行时探测；**视频支持 2026-10-16 用户裁决移除**：mp4/mov 移出导入白名单、双端播放/浏览按 media_kind 过滤存量、Android READ_MEDIA_VIDEO 权限撤销，播放器视频渲染代码休眠保留待清理切片）、**休息屏**与多轮 review 清偿。R01–R19 已闭环并经用户确认，contracts/ 与设计包 v1.1 已交付，禁止重建或重开。**仍未授权**：Google（M0-22 书面结论未出）、Live Photo 实装；需账号、签名、真机或外部服务的 M0 实测；商店上架（App Store/应用市场）；大型 SDK 安装。`contracts/scaffold.json` 现为 stage=`m2_services_development`、networkEnabled=true、thirdPartyServicesEnabled=true。

## 先读

1. `16-开发进度与Agent交接.md`：真实进度、验证结果与停止点。
2. `14-开发准备度评审.md`：历史 R01–R19 证据（均已闭环并经用户确认）与当前工程/验收残留，不重开旧裁决。
3. `15-脚手架与开发启动说明.md`：目录、工具与命令。
4. `01-产品需求PRD.md`、`03-原生技术与接入方案.md`、`04-工程规格书.md`：相关模块原始规则；发现冲突先解决，不自行任选一份实现。

`REVIEW-01/02/03` 为此前未完成草稿，不能引用为复核通过记录。00–15 的现状需对照实际文件与 16 的真实证据；若交接文出现旧“契约未建/下一步实建”，须核查 `contracts/` 和 `tools/`，不能据此重建或覆盖。

本轮协作写入边界：开发 Agent 在其切片授权范围内编辑 ios/、android/、contracts/、tools/、tests/ 与根文档；16 由主 Agent 统一校验与回填，受限子 Agent 报告修改与残留，不越界回填 16。

## 核心约束

- 双端原生 Swift/UIKit、Kotlin 原生 Views；iPadOS 15+ / Android 10 API 29+。
- 中英双语，系统授权界面语言由系统控制。
- 导入不等于展示；未经确认的新照片不能自动公开。
- 停留与转场为独立参数；完整构图＋同图模糊补边为默认。
- 照片只在本机处理；不记录照片名/路径/凭证，不写回删除源端照片。
- Google 保留政策未选型、门控未过；SMB 库已装、天气客户端已实现，但 M0 门控未完成（SMB 真机链路与 LGPL 合规签字、Open-Meteo 法务签字均未做）；不得伪造「已通过门控/验收通过」状态。
- 不擅自提交 Git、发布应用、注册账号、配置生产密钥或安装大型 SDK。
- 本应用及源代码仅供非商业使用（根目录 LICENSE，商用须事先书面许可，联系 chunguangwee@gmail.com）；应用内"关于/开源许可"页须同步展示该声明，不得移除。
- 不添加代码注释，除非用户要求；工具自动生成文件保留工具输出。
- advisor/评审意见逐条先核实真伪（读原文行号与当前文件），成立的当轮修复并给验证证据，不成立的记录驳回理由；不得整份照抄，也不得整体忽略。

## 维护与校验

- 现有 XcodeGen 声明和 Gradle 配置为脚手架候选，不是生产版本冻结。
- 修改 iOS 配置后运行 `make ios-generate`，不要手工改生成的工程文件。
- 根目录运行 `make check`；iOS 修改运行 `make ios-build`、`make ios-analyze`；Android 修改运行 `make android-build`、`make android-lint`、`make android-test`，环境见 15。
- 没有测试源码时写明 NO-SOURCE，不宣称测试覆盖；编译通过不等于启动、真机验收或 M0 通过。
- 本地 SVG 源与 PNG 导出按 12 v1.1 版本化归档；不等同 Figma 组件实例、可点击原型或真实 App 截图。未取得签字、原生字体/最大字号/触控语义/材质/真机证据不得宣称设计验收通过；未执行 Git 提交。
- 完成任务后由主 Agent 同步更新 16 的实际结果、遗留项和下一步，不把计划写成已完成；受限子 Agent 报告修改与残留，不越界回填 16。
