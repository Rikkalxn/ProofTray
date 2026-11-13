# OpenBridge

面向尼康相机的 Android 选片与传图应用：**先在相机预览中挑片，再下载选中的原图，交给常用后期工具。**

采用 Kotlin / Jetpack Compose 界面、C++20 核心与 JNI。支持 Android 10 及以上，当前构建包含 ARM64 和 x86_64。
当前开发预览版本为 **0.6.10**，变更与验证范围见 [发布说明](docs/releases/0.6.10.md)。

## 功能

- **相机传图**：BLE 配对引导、Wi-Fi AP / STA 连接、相机目录分页、预览及所选原片下载。
- **便携 JPEG 自动下载**：BLE 通知触发经典蓝牙 RFCOMM 传图，完成后恢复 BLE 待机；原图通过 Wi-Fi 传输。
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
- RAW 完整解码渲染、NEF 转 DNG、Lightroom 编辑预设复现和原图瓦片式 100% 检查尚未实现。
- JPEG / PNG / HEIF 转换依赖系统解码；RAW / TIFF 可按原格式导出，RAW 预览依赖相机缓存或内嵌 JPEG。
- 自动原图下载需要已有 Wi-Fi 连接，按本次会话基线识别新照片；跨重连期间的原片需要手动补导。
- 中断后的部分任务需要重连并显式重试，不承诺后台持续连接。

首次连接与验收步骤见 [相机测试说明](docs/camera-testing.md)，传输行为依据见 [协议参考](docs/protocol-references.md)。

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
环境配置、C++ 核心测试及 Release 签名打包见 [构建说明](docs/building.md)。

仓库包含 [GitHub Actions](.github/workflows/build.yml)：推送、PR 或手动触发时构建 Debug APK，并运行 JVM 与 C++ 核心测试。
在 GitHub 的 **Actions → Build → 对应运行 → Artifacts** 下载 APK 和测试报告；产物保留 14 天。
CI 递归检出固定版本的 submodule。GitHub 自动生成的源码 ZIP/TAR 不包含子模块，获取完整源码请使用递归克隆。
CI 使用临时调试签名，不适合作为稳定更新渠道，也不能保证覆盖已有安装；正式发布使用单独备份的 Release 密钥。

## 目录与文档

| 路径 | 内容 |
| --- | --- |
| `app/` | Android 界面、平台适配、JNI 和 JVM 测试 |
| `core/` | C++20 目录查询、协议编解码、LUT 与核心测试 |
| `third_party/` | LibRaw submodule、摘取适配的第三方组件及原许可证 |
| `design/brand/` | 项目图标源文件 |
| `tools/` | 可选的 Windows 环境、构建和发布脚本 |
| `docs/` | 构建、协议、产品设计与发布说明 |

- [C++ 核心接口](core/README.md)
- [产品范围与后续方向](docs/product-scope.md)
- [界面规范](docs/ui-design.md)
- [预览质量与回退策略](docs/preview-quality.md)
- [风格模板示例](docs/examples/soft.oblook)

## 许可证

项目自有代码与文档采用 [Apache License 2.0](LICENSE)，允许修改、商用及闭源分发，须遵守许可证中的声明保留等要求。
第三方组件保留原许可证；furble 的 Blowfish 实现及配对哈希适配涉及 MIT 授权，详见 [第三方声明](THIRD_PARTY_NOTICES.md)。

OpenBridge 是独立项目，与 Nikon 无隶属关系。官方应用代码、库和素材不随本项目分发。
