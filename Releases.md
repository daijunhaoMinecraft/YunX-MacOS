<div align="center">

<img src="desktop/src/main/resources/icon.png" width="96" alt="云析 YunX-Desktop" />

# 云析桌面版 · macOS 首个版本

基于官方 Windows 桌面版与 Compose Multiplatform 构建。

</div>

## 下载

| 芯片 | 文件 |
| --- | --- |
| Apple Silicon（M1 / M2 / M3 / M4） | `YunX-Desktop-1.3.3-macOS-arm64.dmg` |
| Intel Mac | `YunX-Desktop-1.3.3-macOS-x64.dmg` |

每个 DMG 均附带 `.sha256` 校验文件。请根据「 → 关于本机」中的芯片类型选择对应版本。

## 本次更新

- **双架构原生构建**：GitHub Actions 分别在 `macos-15-intel` 与 `macos-15` 上构建 Intel x64、
  Apple Silicon arm64 DMG，不依赖 Rosetta。
- **macOS 桌面适配**：菜单栏托盘、Finder 文件定位、原生文件选择器、剪贴板链接检测、系统代理读取。
- **安全存储**：数据库主密钥使用 macOS 钥匙串保护；认证备份仍可加密导出/导入。
- **浏览器登录**：修复 JCEF 初始化与退出竞态，内嵌 Chromium 登录在归档后重新打开仍可正常使用。
- **Cookie 导入**：支持从 Chrome / Edge / Brave / Vivaldi / Opera / Chromium / Firefox 导入登录态。
- **界面优化**：修复左上角大标题在 macOS 下呈浅灰半透明的问题，并避免折叠动画停留在中间状态。
- **完成通知**：下载完成后通过 macOS 通知提醒；详细下载进度仍显示在应用窗口内。

## 已知限制

- 当前构建未经过 Apple Developer ID 签名与公证。若首次启动被 Gatekeeper 拦截，请右键选择「打开」，
  或执行：

```bash
xattr -dr com.apple.quarantine /Applications/YunX-Desktop.app
```

- 推荐把应用安装到 `/Applications`。内嵌浏览器与部分系统能力需要在「应用程序」目录运行。
- ffplay 预览使用独立窗口；未安装 FFmpeg 时会回退到内置 JavaFX 播放器。
- 应用内部分说明文档继承自上游 Windows 版，macOS 专属路径与构建方式以 README / docs/BUILD.md 为准。

## 数据目录

```text
~/Library/Application Support/YunX
```

## 反馈

<https://github.com/daijunhaoMinecraft/YunX-MacOS/issues>

***

*基于 [tidain/YunX-Desktop](https://github.com/tidain/YunX-Desktop)，GNU AGPL-3.0 开源。*
