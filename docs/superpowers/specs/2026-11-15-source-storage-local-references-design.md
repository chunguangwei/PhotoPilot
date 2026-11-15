# 来源管理与本机照片引用

## 用户目标

修复断开NAS后删除配置无响应，在NAS新建及编辑流程中显示本地同步选择，并让本机照片和目录通过持久引用建立相框，减少存储和等待。用户此前已委托产品逻辑，本轮继续按此授权执行。双端原生、英语与简体中文，版本拟1.4.0(61)。

## 已核实的问题

Android移除来源受到AssetOrigin的NO_ACTION和Collection层级关系约束，部分错误被吞。iOS断开来源缺少删除配置动作，原断开异步错误被忽略，另有旧viewContext版本冲突。NAS新建/编辑没有持久化存储选择入口。系统照片原流程导出/暂存并进入原图导入，Files选择先复制到staging，原URI或安全书签没有持久化。

## 删除配置

断开卡片提供删除配置确认。默认保留已下载的App私有照片，将必要的Origin归入本地文件来源，保留隐私/删除/策略状态。FrameSelection、人工排除、排序、AI归属及封面映射到实际保留的Asset。远端独有引用清理，空相框提供可操作状态。两端可采用解除本地文件夹关系或物化选片的方式保留当前已确认展示内容。

删除前停止并等待相关索引/导入/相框同步任务。数据库事务负责外键与层级顺序、选择映射、epoch和版本；成功后清理凭据。失败在UI给出错误及重试入口。共享其他来源的照片受到保护。NAS操作保持只读。

## NAS照片存储

新建与编辑都提供“按需读取，节省空间”和“同步所选目录到本地，支持离线”。新来源默认按需，编辑初始选中当前配置。用户确认切换更新catalogOnly和epoch，取消旧任务，按已确认来源目录范围索引或导入。来源级本地同步会占用所选目录的空间，界面解释其影响。逐相框NAS同步沿用当前frame/version限定规则。

## 本机引用的数据与读取

使用既有SourceAsset、Collection、FrameSelection和SettingsKV，维持17表。引用来源设置referenceOnly=true，runtime使用既有remote:<SourceAssetID>内部身份，持久选择仍用raw sourceAsset FK。

系统相册多对多成员以版本化reference.membership.<collectionID>元数据保存sourceID、epoch及SourceAsset IDs。目录复用source_folder和精确规范化相对路径。索引只查询标识、名称、时间、尺寸等可用元数据，原图读取和hash发生在确有需要的读取流程。

Android照片复用旧MediaStore来源和numeric remoteKey，保留历史AssetOrigin身份；文件引用使用独立于私有文件导入的来源。SAF文件/目录保留read grant，保存URI，直接ContentResolver/ImageDecoder采样读取。

iOS使用PHAsset.localIdentifier与公开PhotoKit目标尺寸图片读取。引用Photos来源可独立于旧副本来源，但读取、人工排除及AI必须审查相同PH标识的历史Origins。进入引用模式停止旧本机批量导入恢复。Files使用asCopy=false选择、minimalBookmark、security scope和FileCoordinator直接读取。目录持久化root书签。

新建引用入口保存来源和选片关联，经过二次确认创建相框。缩略图、封面、播放、手工排序、AI与人工排除共同使用来源身份和authority。需要URL的AI可用限额临时派生图。解码缓存32MiB、临时照片128MiB、派生向量16MiB的预算继续适用。本机引用按实际可读状态播放，混合NAS local-mode也应支持本机原图直接读取。local-only引用界面说明本机原照片，逐框同步开关用于NAS。

## 授权、版本与旧数据

读取前后核对授权、当前source/asset revision、隐私及删除状态，拒绝迟到结果。修改、移动、删除、权限撤销、进程重启、有限照片授权均需验证。无法持久授权时提供重新选择或用户明确选择的副本路径。旧已导入Asset沿用私有原图，自动清理原副本需要独立确认和可恢复来源证据。

标准SAF供应商可能不提供可靠mtime/size。此类引用可读取，每次刷新引入generation并跳过长期解码/AI特征缓存。有效元数据tuple与权限可用于常规版本验证。供应商在tuple相同且无通知时替换内容，是metadata-only方案的可观测性边界。

本机纯引用的隐藏/移除动作必须有持久策略记录，刷新和新身份不得绕过已有可识别的隐私及人工排除。引用移除只修改App关联与缓存。

## 验证与交付

先保留NAS删除与模式切换的确定RED/GREEN。新增本机metadata零正文/零原图/零ImportJob、直接读取、目录边界、重启授权、改版/撤销/迟到结果、AI与混合相框回归。通过实际系统文件/目录选择入口和原生触控测试。英语/简体中文、窄屏、横屏、大字、暗色需真实截图核对。

运行共享检查、双端完整构建/分析/单测及原生集成。最终单一fresh综合review后处理确认项并验证修复delta。产出签名APK与unsigned iOS归档，检查版本/资源/凭据排除。既有仓库提交推送及Release已获授权，AppStore更新材料同步。Store签名刷新仍需原账号和明确授权，物理旧机/最低OS/实体NAS验证按实际结果记录。
