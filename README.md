# ProofTray

ProofTray 是面向尼康和索尼相机的 Android 选片与传图应用。可以先浏览相机预览，再下载选中的原片，进行整理、调色或导出。

采用 Kotlin / Jetpack Compose 界面、C++20 核心与 JNI。支持 Android 10 及以上，当前构建包含 ARM64 和 x86_64。
当前版本号和支持的 ABI 见 [Android 模块配置](app/build.gradle.kts)。

## 功能

- **相机传图**支持尼康与索尼各自的连接流程、相机目录分页、预览及所选原片下载。
- **尼康便携 JPEG 自动下载**由 BLE 通知触发，经经典蓝牙 RFCOMM 传图，完成后恢复 BLE 待机。原图通过 Wi-Fi 传输。
- **选片与管理**：图库 / 相机导入分开，组合筛选、评分、标签、RAW/JPEG 配对、连拍分组和多图同步比较。
- **传输队列**：记录导入历史，支持取消、重试，以及机型支持时的原图分段续传。
- **调色与导出**：CUBE / HaldCLUT、`.oblook` 风格模板、批量原格式导出及 JPEG / PNG 转换。
- **系统编辑**：创建照片副本，再交给手机上安装的编辑器；原图不授予写权限。

## 使用与兼容性

连接相机后，在“导入”页浏览预览并选择照片；下载后的原片在“图库”中管理。
相机导入默认排除已导入照片，可切换到“全部 / 已导入”重新选择。
“从图库移除”只改变应用内列表；删除手机文件需要应用及系统确认，不删除相机原件。

当前实测基准为 **Nikon Z6 II + 小米 Android 16**。
已验证连接后的待传列表下载、BLE 通知触发新照片下载，以及待传广播触发重连；便携 JPEG 实际尺寸为 1620×1080。
新拍 RAW 对应的小图是机内生成的 JPEG 副本，不代表 RAW 已备份。
这些结果来自对应的调试构建，不能据此保证所有尼康机型、固件或 Release 安装环境均已验证。

已知边界：

- 更多机型、GPS、长时间后台运行及实际功耗仍需实测。
- JPEG、PNG 和 HEIF 转换使用系统解码。RAW 显影使用 LibRaw，支持的机型和压缩格式取决于仓库固定的版本。
- RAW 支持调色和 16 位 Log TIFF 导出。NEF 转 DNG、Lightroom 编辑预设复现和原图瓦片式 100% 检查仍待实现。
- 自动原图下载需要已有 Wi-Fi 连接，按本次会话基线识别新照片；跨重连期间的原片需要手动补导。
- 中断后的部分任务需要重连并显式重试，不承诺后台持续连接。

## 构建

需要 JDK 17、Android SDK 36、Build Tools 36.0.0、NDK 28.2.13676358 和 Android CMake 3.22.1。
使用仓库自带的 Gradle Wrapper；Android Studio 可选，CLion 可独立打开 `core/`。

首次克隆请使用 `git clone --recurse-submodules <仓库地址>`；已有工作区在拉取更新后执行：

```sh
git submodule update --init --recursive
```

LibRaw 由 submodule 固定到经审查的上游提交，包含 ILCE-7M5 支持。此命令恢复仓库指定的版本，不会自动升级到最新上游。
依赖选择和升级方式见 [第三方依赖管理](third_party/README.md)。

配置 `JAVA_HOME` 和 Android SDK 路径后，在仓库根目录运行：

```powershell
.\gradlew.bat :app:assembleDebug
```

Linux / macOS 使用 `./gradlew :app:assembleDebug`。Debug APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。
桌面端可用自己的 CLion 工具链打开 `core/`。也可用 CMake 构建和运行独立核心测试，见 [核心说明](core/README.md)。

推送、PR 或手动触发构建 Debug APK，同时运行 JVM、C++ 核心和 Log TIFF 测试。见 [GitHub Actions 工作流](.github/workflows/build.yml)。
在 GitHub 的 **Actions → Build → 对应运行 → Artifacts** 下载 APK、随包许可、含子模块的对应源码和测试报告。产物保留 14 天。
CI 递归检出固定版本的 submodule。GitHub 自动生成的源码 ZIP/TAR 不包含子模块，获取完整源码请使用递归克隆。
CI 使用临时调试签名，不适合作为稳定更新渠道，也不能保证覆盖已有安装；正式发布使用单独备份的 Release 密钥。

## 目录与文档

| 路径 | 内容 |
| --- | --- |
| `app/src/main/java/org/prooftray/app/` | Kotlin 应用入口，按 camera、data、domain、color、frame 和 ui 分包 |
| `app/src/main/cpp/` | Kotlin 与原生核心之间的 JNI 适配 |
| `app/src/test/`、`app/src/debug/` | JVM 测试和调试构建专用入口 |
| `core/include/prooftray/`、`core/src/` | C++20 目录查询、协议编解码、RAW 和调色接口及实现 |
| `core/tests/`、`core/tools/` | 原生测试和可选的桌面 RAW 校准工具 |
| `third_party/` | 固定版本的 LibRaw、libtiff 子模块及 furble 源码和许可 |
| `licenses/` | 上游未单独提供或通过 Maven 获取的组件声明，以及 Adobe DNG SDK 许可 |

源码包名为 `org.prooftray.app`。已发布应用继续使用安装 ID `org.openbridge.app` 和原启动组件，以支持覆盖更新及现有桌面快捷方式。
数据库名、相机预览 URI、风格与边框模板格式标识也沿用已发布值。桌面 CMake 目标名和选项兼容已有 CLion 配置。
Debug 构建的相机诊断广播组件为 `org.openbridge.app/org.prooftray.app.debug.CameraDebugReceiver`。

## 许可证

项目自有代码与文档采用 [Apache License 2.0](LICENSE)。
第三方组件各自的授权、来源和版权信息见 [第三方声明](THIRD_PARTY_NOTICES.md)。应用内可在「设置 → 关于 → 开源与许可」离线查看随包文本。

ProofTray 由独立开发者维护。尼康和索尼官方应用仅用于本地协议核对。
