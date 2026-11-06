# 构建与打包（macOS）

## 环境要求

- macOS 11 Big Sur 或更高版本
- JDK 17（推荐 Eclipse Temurin）
- Intel Mac 构建 `x64`，Apple Silicon 构建 `arm64`
- 能访问 Maven Central；项目同时配置了阿里云镜像作为回退

项目使用 Gradle Wrapper，无需预装 Gradle。构建时 `desktop/build.gradle.kts` 会读取
`os.name` / `os.arch`，自动选择：

- Compose Desktop 的当前平台运行时
- JavaFX 的 `mac` / `mac-aarch64` 原生分类器
- JCEF 的 `macosx-amd64` / `macosx-arm64` 原生包
- jpackage 的 DMG 目标格式

因此 Intel 和 Apple Silicon 包必须在对应架构的 macOS 上原生构建。

## 本地开发

```bash
git clone https://github.com/daijunhaoMinecraft/YunX-MacOS.git
cd YunX-MacOS

./gradlew :desktop:run
```

仅编译：

```bash
./gradlew :desktop:compileKotlin
```

运行测试：

```bash
./gradlew :desktop:test
```

如果工程路径或测试临时目录包含特殊字符，可使用：

```bash
./gradlew :desktop:testInProcess
```

## 生成应用与 DMG

生成未压缩的 `.app`：

```bash
./gradlew :desktop:createDistributable
```

输出：

```text
desktop/build/compose/binaries/main/app/YunX-Desktop.app
```

生成 DMG：

```bash
./gradlew :desktop:packageDmg
```

输出：

```text
desktop/build/compose/binaries/main/dmg/YunX-Desktop-<版本>.dmg
```

安装体验时建议移动到 `/Applications`：

```bash
ditto desktop/build/compose/binaries/main/app/YunX-Desktop.app /Applications/YunX-Desktop.app
open -a /Applications/YunX-Desktop.app
```

## 版本号

版本号唯一来源是仓库根目录的 `version.txt`：

```text
1.3.3
Beta=0
```

- 第一行必须是纯数字版本号，jpackage 不接受 `v` 前缀。
- 第二行 `Beta=1` 表示预览版，`Beta=0` 表示正式版。
- Git 标签使用 `v` 前缀，例如 `v1.3.3`。

应用内版本、DMG 文件名与 GitHub Release 均由这一来源派生。

## GitHub Actions 双架构发布

工作流：`.github/workflows/macos-release.yml`

触发方式：

```bash
git tag v1.3.3
git push origin v1.3.3
```

矩阵：

| Runner | `uname -m` | 产物 |
| --- | --- | --- |
| `macos-15-intel` | `x86_64` | `YunX-Desktop-<版本>-macOS-x64.dmg` |
| `macos-26` | `arm64` | `YunX-Desktop-<版本>-macOS-arm64.dmg` |

每个任务会先断言 runner 的真实架构，再执行：

```bash
./gradlew :desktop:packageDmg --console=plain
hdiutil verify <dmg>
shasum -a 256 <dmg>
```

构建完成后，`release` job 汇总两个架构的 DMG 与 `.sha256` 文件，创建或更新 GitHub Release。

也可以手动运行工作流验证两个构建任务，但非标签运行不会发布 Release。

## macOS 签名与公证

当前 CI 产物未使用 Apple Developer ID 签名，也未提交 Apple 公证。因此用户首次启动时可能看到
Gatekeeper 提示。临时处理：

```bash
xattr -dr com.apple.quarantine /Applications/YunX-Desktop.app
```

正式分发建议后续增加：

1. Developer ID Application 证书签名 `.app`；
2. `notarytool` 提交公证；
3. `stapler` 装订公证票据；
4. 在 GitHub Actions 中通过 Secrets 注入证书与 Apple ID 凭据。

没有上述凭据时不要伪造签名步骤，也不要建议用户关闭 Gatekeeper。

## 数据与诊断

- 数据目录：`~/Library/Application Support/YunX`
- 主日志：`~/Library/Application Support/YunX/files/yunx-desktop.log`
- JCEF 日志：`~/Library/Application Support/YunX/files/jcef.log`
- 覆盖数据目录：`YUNX_DESKTOP_DATA_DIR=/path/to/data`

JCEF 日志中的 Chromium 初始化错误通常与首次启动、下载原生包或应用位置有关。诊断前优先确认：

1. 应用是否位于 `/Applications`；
2. 是否选择了与芯片匹配的 DMG；
3. JCEF 原生包是否与当前架构一致；
4. 是否被安全软件拦截了 Chromium 子进程。
