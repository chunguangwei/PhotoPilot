# Photo Flow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task.

**Goal:** 完整优化选片到播放及来源管理的双端交互。

**Architecture:** 沿用现有来源、相框和播放数据层，修正入口上下文、草稿传递、确认与导航。首次自动命名相框在确认后持久化。

**Tech Stack:** Swift/UIKit and Kotlin Views.

**Spec:** docs/superpowers/specs/2026-11-15-photo-flow-design.md

## Global Constraints

- iOS 15+、Android API 29+，中英文，17表不增加。
- 不新增代码注释、SDK、源端写入、原图复制、生产签名或商店提交。
- 连接来源不自动展示照片，保持显式展示确认。
- 主Agent独占16；已有数据/排除/本地引用语义保持兼容。

## Review Focus

- 首次及后续来源入口不能混淆。
- 多文件选择和跨来源编辑保留范围及草稿。
- 取消、重试和快速重复保存不产生多余相框或覆盖数据。
- 导航push/present与返回/保存匹配。
- 中英文及窄屏大字操作可达。

### Task 1: iOS完整流程

**Files:** ios/PhotoPilot/UI/Sources/、UI/Frames/、UI/Imports/、主容器及相关Data层与ios测试，必要共享文案由主Agent协调。

- [ ] 追踪所有来源、首次、图库、已有相框和播放空态入口，按Spec实现并修复相关实际问题。
- [ ] 添加针对具体回归的测试，执行iOS构建、分析、原生及关键UI验证。
- [ ] 写明精确修改、验证与限制，提交仅本任务文件，交任务审查。

### Task 2: Android完整流程

**Files:** android/app/src/main/java/dev/photopilot/scaffold/MainActivity.kt、ui/remote/、ui/frames/及相关测试。

- [ ] 同Task1产品语义，检查所有入口和草稿交接，保留引用/按需语义。
- [ ] 完成Android构建、lint、JVM及针对关键路径的原生/UI验证。
- [ ] 写明精确修改、验证与限制，提交仅本任务文件，交任务审查。

### Task 3: 集成及交付

- [ ] 主Agent核对共享词条、产品规则与双端行为一致。
- [ ] 完成make check及最终范围审查，回填16真实结果与未验证边界。
- [ ] 提交并推送既有仓库分支，交付可核查结果，版本发布按实际打包状态报告。
