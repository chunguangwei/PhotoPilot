# 来源管理和本机引用实施计划

> 使用executing-plans实施，按独立平台及Android数据服务分工，确认缺陷先做RED/GREEN。

**目标** 修复NAS配置删除和存储选项，接通本机照片/文件/目录零原图复制的相框引用。

**架构** SourceAsset引用、持久授权、versioned SettingsKV成员元数据，既有媒体服务按来源分派读取。保留17表与原生栈。

**规格** docs/superpowers/specs/2026-11-15-source-storage-local-references-design.md

## 文件与责任

- 主Agent维护根文档、contracts/strings、生成词条、版本证据及发布。
- iOS Agent维护ios服务、媒体/数据/UI/生命周期与测试。
- Android集成Agent维护现有android文件、入口/读取/授权/AI/UI及整套验证。
- Android数据Agent独占新data/references目录和LocalReference测试，与集成Agent直接对齐API。

## 来源删除与NAS存储

- [x] 查明外键/层级和隐藏/吞错入口，记录确定RED。
- [x] 删除配置重归已保留副本、选择/规则/AI/封面，保护共享来源，错误可见。
- [x] 新建/编辑NAS存储选择，目录scope、epoch、旧job取消和模式路由。
- [x] 最终全套与双语实际触控/截图验证。

## Android本机引用

- [x] 对齐LocalReferenceRepository API与独占目录。
- [x] Metadata索引、持久SAF授权、MediaStore、目录路径/成员和直接采样解码。
- [x] MainActivity系统照片/文件/目录→二次选片→相框/封面/播放/AI/排序/排除/生命周期。
- [x] 实际SAF权限跨进程、撤销/移动/变更/未知版本、原图0复制和混合local-mode。
- [x] 完整JVM/native/UI/构建/lint/签名包。

## iOS本机引用

- [x] 本机文件默认引用的确定RED/GREEN、公开书签与直接解码。
- [x] 系统相册成员及PhotoKit直读、旧Source别名、取消旧导入与启动恢复。
- [x] 授权变化、修改/删除/移动、重启、AI/封面/手工排除/混合相框。
- [x] 完整原生/实际UI、build/analyze和unsigned archive。

## 综合交付

- [x] 唯一fresh综合review，逐项核实/修复并验定点delta。
- [x] root独立make check与平台验证、正常入口及payload签名/版本/资源核对。
- [x] 更新16、隐私/README/Store材料与真实截图证据。
- [ ] 合并现有main、push、tag/Release及匿名完整下载验证。
- [x] 报告实际未验证旧机/最低OS/实体NAS与Store签名依赖。

## 当前验证进度

- iOS确定RED `.build/ios-source-red-behavior.log`，删除后来源/选片未清，GREEN `.build/ios-source-green2.log`6/6。文件旧入口RED `.build/ios-reference-red.log`出现ImportJob和原图副本，新引用GREEN `.build/ios-reference-green.log`11/11。引用策略扩展 `.build/ios-reference-policies.log`15/15。最终全套待缓存修复后重跑。
- Android NAS删除RED `/private/tmp/android-nas-red-test.log`为真实Room外键约束异常，GREEN `/private/tmp/android-nas-native-retry.log`3/3，含触控存储切换和删除。直接引用/真实SAF选择器跨进程验证与最终全套待整合。
- 唯一fresh综合review初始4Important，root逐项读当前代码成立。Android修SAF grant清理、全局引用隐藏、Source设置优先目录scope，iOS修未知供应商版本派生图/向量缓存。修复后仅由同一reviewer验delta。
- root实际目检来源删除按钮与NAS正常字号截图，文字及动作可见。AX截图为scroll-to-bottom造成选项部分越出viewport，iOS更新为顶部对齐并纵向header，最终截图待复核。
- root共享校验首轮因新增词条触发历史设计来源digest过期停止，生成词条并更新来源摘要后重跑。未将首轮标为通过。

- root共享检查最终通过 `/private/tmp/photopilot-local-reference-check.log`，57工具测试+6AI来源测试，612词条/16复数/8产物与17表一致，4857历史设计资产摘要完整性通过。双端最终build/analysis及native仍待冻结。

- root最终共享检查 `/private/tmp/photopilot-local-reference-check-final.log`通过58工具+6AI来源测试，新增用户授予文件元数据的3B52.1回归。iOS独立DerivedData build/analyze `/private/tmp/photopilot-local-reference-ios-root.log`均SUCCESS。
- Android root冻结点全构建/lint/440JVM及原签名Release通过，lint0error205warnings。首个候选APK154138358B/SHA38df1e67814b4f8cef9b4ef8e2651dfb48e340cba0cfce509be4cffec4074025，原证书/4ABI/catalog/notices/无权重均核，但随后真实SAF入口发现RemoteFrameView初始reload与onResume刷新并发，可能移除被触摸checkbox。该UI修复后必须重建APK和checksum，当前候选禁止发布。
- Android grant清理与源范围/隐藏三项review源码delta通过。单文件撤权补normalized和rawSecurityException隔离，其他文件继续ACTIVE，OS已撤权的release幂等并继续清理。最终native/系统Files和目录跨进程/UI仍待结果。
- iOS未知版本派生lease/向量/检查点缓存修复及共享128MiB预算、幂等释放、隐藏失败Retry均通过同一reviewer源码delta。新PrivacyInfo含3B52.1。大字号NAS选项与滚动详情图片root已实际目检通过，17张iPhone QA截图见.build/ios-v1.4-screenshots-iphone。实际PhotoKit流程已到合成相册→选片→保存→播放，重启步骤待最终log。模型fixture测试仅隔离QA工程，生产archive使用独立.build/ios-archive14。

- iOS actualPhotoKit UI1/1通过，root独立readonly实读DB为Asset/Origin/ImportJob0、SourceAsset3、Frame1、Selection1、originals0、quick_check ok。真实TCC撤权重启后停止显示原照片，保留frame/selection。隔离模型QA6/6无skip，含真实SystemFiles引用的Lite多语分类预览/确认及0Asset，生产full274执行268pass6skip0fail，6环境case另由模型QA与合成相册setup覆盖。旧unsigned archive693362c4...已核版本/资源/manifest/权重排除，但新增reverse alias EXCLUDE修复和本机引用中性card/NAS-only sync文案后必须重新归档并核hash。
- Android真实入口发现并修RemoteFrameView并发load job/token和旧foreground任务重置新editor，待最终UI/native结果。root另确认Source配置删除后的AI保留成员在自动续扫会因scope移除被撤下，正在修保留成员转手工选片/无scope暂停，最终包必须包含该修复。单一reviewer继续仅验这些确认delta。

- root已补标准make ios-build/ios-analyze双SUCCESS `/private/tmp/photopilot-local-reference-ios-final-make.log`，当时iOS全套276执行270pass6skip0fail，模型QA6/6，iPhone/iPad各真实PhotoKitUI1/1。签名资产只读复核实际有7个有效identity，但原团队当前Store profile仍不匹配，未刷新任何签名资产。
- root真实查看Android系统选图后缓存截图，定位到旧NAS后台自动同步失败的全局Alert挡住新Files编辑器。平台修为自动错误仅KV布尔可见状态，网络失败保持原authority/epoch及cacheoffline，手动modal绑定发起view/overlay/epoch。reviewer确认后续事务缺口并交owner收尾，不把猜坐标和fixture等待失败写为产品授权失败。
- root核Source配置删除的多版本映射，Android遍历覆盖及iOSSet.first会选旧版，iOS还把历史版本重加目录。已确定iOS v1/v2真实RED3项。版本证据来自provenance KV（双端AssetOrigin均无importedRevision字段，root先前字段建议已核实撤回），旧数据可复用source_folder当前CollectionAsset唯一成员，原提交逻辑会移除该SourceAsset旧版本成员。保留所有字节，选片/cover/AI/folder只重映射有证据的当前copy。此修复使3feb...archive及前述APK再次成为候选，最终须重包。
- iOS外部Files真实UI补验证采用专用sim/极小本地fixtureapp，共享容器已在系统Files显示，DocumentManager首次点选报告Cannot find source，正排除fixture注册因素。生产Info未开启共享，无账号/大SDK/个人设备操作。最终按实际结果记录此项。

## 最终候选冻结

双端生产代码冻结，唯一reviewer Ready to merge，开放项0。Android最终31native/440JVM通过，真实SAF6段含PID与撤权，Root布局后最终APK154171126B/SHA b2574f03a6280119e61a3bbe3a162fd2e8b37a71ba88ad1a3313bb92c761d1b6。NAS整体滚动真实RED→GREEN，实际横屏形状viewport与中文竖屏详情按真实尺寸记录，附加横屏picker/sensor组合未验证。

iOS最终278执行272pass6skip0fail，模型专项6、Photos真实iPhone/iPad各1。Root标准build/analyze及独立DD通过，unsigned archive最终binary SHA de3136f79b4d5f61365a3e98ea0eacd9ec1e53e2ab6c7030252c3eb4be4249fe。实际外部Files provider系统source=null，完整UI链路保留未测，native读取链路通过。Store签名只读复核仍缺匹配profile，生产签名刷新授权仍待用户。

- [ ] 合并现有main、push、tag/Release及匿名完整下载验证，另记发布实证。
- [ ] 外部Files真实provider UI、硬件/最低OS/实体NAS/长稳与正式Store签名上传，按上述具体依赖后续处理。
