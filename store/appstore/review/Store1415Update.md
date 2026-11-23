# Store update copy — iOS 1.4.15 (76) / Android 1.4.23 (84)

2026-11-23。本轮修复 NAS（WebDAV/HTTPS）因证书状态导致的浏览与播放失败，并优化相框播放的图像适配与转场。以下为商店字段话术草稿：应用界面仅支持 English 与简体中文；繁體中文仅供商店文案展示，不得宣称繁体界面支持。不是 App Store Connect 保存/上传/提交。

## What's New / 更新说明

**English**:

NAS frames stay connected when your server's certificate is renewed or expires: a certificate you already trusted no longer blocks browsing, and an untrusted one asks for confirmation with its fingerprint. Frame playback is calmer too — photos no longer jump in size when two aspect ratios meet, and two new cinematic transitions, Depth and Diagonal, join the existing set.

**简体中文**:

当 NAS 服务器证书续期或过期时，相框依然可用：你已信任过的证书不再阻断浏览，未信任的证书会显示指纹并请求确认。相框播放也更平顺——两张照片比例不同时不再突然放大跳动，并新增「景深推进」「对角轻扫」两种更精致的转场效果。

**繁體中文**:

當 NAS 伺服器憑證續期或過期時，相框依然可用：你已信任過的憑證不再阻斷瀏覽，未信任的憑證會顯示指紋並請求確認。相框播放也更平順——兩張照片比例不同時不再突然放大跳動，並新增「景深推進」「對角輕掃」兩種更精緻的轉場效果。

## Promotional text（宣传文本，≤170 字符）

**English**（158 字符）：

Keep NAS frames working after certificate changes, with smoother photo fitting and two new cinematic transitions. Photos stay on your device: no ads, no account.

**简体中文**（69 字符）：

NAS 证书续期后相框照常播放；画面适配更平顺，新增两种电影感转场。照片只在本机处理，无广告、不需要账号。

**繁體中文**（68 字符）：

NAS 憑證續期後相框照常播放；畫面適配更平順，新增兩種電影感轉場。照片僅在本機處理，無廣告、不需要帳號。

## 本版变更

- 修复 NAS（WebDAV/HTTPS）在证书续期、过期或重新签发后「列表仍可见但照片无法浏览/播放」的问题：已信任指纹的证书即使过期也继续可用；未信任或指纹变化的证书改为显示指纹确认，而不是静默失败。SMB 走同一套证书策略，一并生效。
- 修复相框播放切换瞬间当前照片突然被放大/裁切的问题：切换期间不再改动正在展示的那张照片的画面适配，新照片的适配与模糊补边在过渡结束后淡入。
- 新增两种转场：「景深推进」（前图轻微后退淡出、后图由 106% 推近淡入）与「对角轻扫」（斜向滑入伴轻淡出）；「随机」池同步扩充，播放设置页可单独选择并预览。
- 减少动态效果时仍然只做短淡入或直接切换，不引入持续缩放。

## 备注

- 本轮不改变导入、选片、数据库、天气与 AI 业务语义；未接入 Google/Live Photo。
- Android 发版资产为签名 APK 与 SHA256SUMS，iOS 由用户从 Xcode 归档上传（本会话不执行上传/提交）。
