# PhotoPilot 1.4.20（81）App Store 更新资料

## 简体中文版本说明

- 优化 NAS 相框播放。首张照片就绪后即可播放，随后提前读取接下来的照片。遇到短暂等待时会保留当前画面并自动重试。
- 相框封面单独保存缩略图，清理临时缓存后仍可快速打开。
- 点按首页封面即可预览照片和相框信息，需要修改时可从预览进入编辑。

## English (U.S.) What's New

- NAS frames now start as soon as the first photo is ready and load upcoming photos ahead of playback. During a brief delay, the current photo stays on screen while the app retries.
- Frame cover thumbnails are saved separately so they can still load after the temporary cache is cleared.
- Tap a cover on the Frames tab to preview the photo and frame details. Choose Edit to make changes.

## App Review Notes

PhotoPilot is a digital photo frame. Local photos can be used without signing in. To verify the changes, create a frame using device photos, return to Frames, and tap its cover. The preview displays the cover, name, photo count, and playback mode. Tap Edit to open the frame editor.

NAS playback is optional. With a configured SMB or WebDAV photo source, create a frame containing several photos and leave offline sync off. Start playback. The first photo appears when ready and the app reads upcoming photos into a bounded temporary cache. Frame cover previews use a separate thumbnail store. These changes do not require an AI model.

## 提交准备

- 上传与送审前，确认上一版在 App Store Connect 的实时状态。
- 若继续提供 NAS 审核测试账号，先从外网核对测试端点和账号，再复用后台已有的访问说明。
- 本资料用于准备更新，实际上传和送审状态见交接记录。
