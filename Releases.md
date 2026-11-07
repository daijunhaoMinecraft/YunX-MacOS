<div align="center">

<img src="desktop/src/main/resources/icon.png" width="96" alt="云析 YunX-Desktop" />

# 云析桌面版 · macOS 1.3.3 修复版

基于官方 Windows 桌面版与 Compose Multiplatform 构建。

</div>

## 下载

| 芯片 | 文件 |
| --- | --- |
| Apple Silicon（M1 / M2 / M3 / M4） | `YunX-Desktop-1.3.3-macOS-arm64.dmg` |
| Intel Mac | `YunX-Desktop-1.3.3-macOS-x64.dmg` |

每个 DMG 均附带 `.sha256` 校验文件。请根据「 → 关于本机」中的芯片类型选择对应版本。

## 本次修复

- **菜单栏托盘**：不再使用 AWT `PopupMenu` 的 AppKit 原生菜单跟踪循环，修复左键/右键打开托盘菜单后
  应用进入“应用程序没有响应”的问题。
- **Retina 图标**：菜单栏图标改为按 2x 尺寸生成并使用高质量缩放，修复顶部菜单栏图标模糊。
- **文件对话框**：macOS 改用 Swing 文件选择器，避开 AWT `FileDialog` 的 AppKit 原生模态循环，
  修复选择目录、导入备份或导出认证信息时应用无响应的问题。

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
