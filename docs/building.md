# 构建与发布

Android 应用通过 Gradle / NDK 构建；C++ 核心也可使用桌面工具链独立构建和测试。
两种构建使用独立输出目录，无需更改已有 CLion 工具链，仓库中的 CMake Presets 为可选配置。

## Android 环境

| 组件 | 版本 |
| --- | --- |
| JDK | 17 |
| Gradle / Android Gradle Plugin | 8.13 / 8.13.2 |
| Android SDK | compileSdk / targetSdk 36，minSdk 29 |
| Build Tools | 36.0.0 |
| NDK | 28.2.13676358 |
| Android CMake | 3.22.1 |
| Kotlin | 2.2.21 |

安装 JDK 17，并将 `JAVA_HOME` 指向其根目录。
Android SDK 可通过 Android Studio 的 SDK Manager 或官方 Command-line Tools 安装；无需安装全局 Gradle。
使用命令行工具时，在已配置 `sdkmanager` 的终端安装项目需要的组件，并按提示阅读和接受许可：

```text
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0" "ndk;28.2.13676358" "cmake;3.22.1"
```

将 `ANDROID_HOME` 指向 SDK 根目录，或在仓库根目录创建仅供本机使用的 `local.properties`：

```properties
sdk.dir=C:/DevTools/Android/sdk
```

上述路径仅为示例，请替换为实际 SDK 路径。`local.properties` 已被 Git 忽略。
首次构建需要联网下载 Gradle 和 Maven 依赖。Android Studio 用户可直接打开仓库根目录，并将 Gradle JDK 设为 17。

## Debug APK

在仓库根目录运行：

```powershell
.\gradlew.bat :app:assembleDebug
```

Linux / macOS 对应命令为 `./gradlew :app:assembleDebug`。
输出：`app/build/outputs/apk/debug/app-debug.apk`，包含 `arm64-v8a` 和 `x86_64`。

若需要构建并安装到已授权 USB 调试的设备，显式运行 `:app:installDebug`。
覆盖更新要求相同签名；换电脑时应私下备份并恢复原调试证书，不要通过卸载或清除应用数据解决签名不匹配。

Windows 快捷脚本适用于以下已有工具布局：

```text
<InstallRoot>/jdk-17/
<InstallRoot>/sdk/
<InstallRoot>/user-home/
<InstallRoot>/gradle-cache/
```

```powershell
# 默认 InstallRoot 为 D:\Android，也可指定其他磁盘。
.\tools\build-android.ps1 -InstallRoot 'C:\DevTools\Android'

# 仅在当前终端启用这套工具环境。
. .\tools\android-env.ps1 -InstallRoot 'C:\DevTools\Android'
```

这些脚本不安装 SDK，也不修改系统全局 PATH。已有其他 SDK / JDK 布局时，直接使用 Gradle Wrapper 即可。

## C++ 核心与测试

CLion 可直接打开 `core/`，选择已有的 C++20 工具链和 Debug 配置，无需启用 Presets。
`openbridge_core` 为静态库；四个测试入口分别为 `openbridge_catalog_tests`、`openbridge_ptp_tests`、
`openbridge_color_tests`、`openbridge_location_tests`。

已安装 CMake 3.22+、Ninja 和 C++20 编译器时，可在仓库根目录显式执行：

```text
cmake -S core -B out/build/core-debug -G Ninja -DCMAKE_BUILD_TYPE=Debug -DOPENBRIDGE_BUILD_TESTS=ON
cmake --build out/build/core-debug
ctest --test-dir out/build/core-debug --output-on-failure
```

Windows 使用 MSVC 时从对应的 Developer PowerShell 运行；不同编译器不要共用构建目录。
核心测试不依赖 Android SDK。Android JVM 测试可通过 `gradlew.bat :app:testDebugUnitTest` 单独执行。
构建或单元测试通过不等于真机兼容性验收完成；设备结果见对应版本的 [发布说明](releases/0.6.10.md)。

## GitHub Actions

[Build 工作流](../.github/workflows/build.yml) 在推送、PR 和手动触发时执行，不需要配置 Secrets。
两个独立任务分别负责 Android Debug APK / JVM 测试，以及桌面 C++ 核心的四组 CTest。
Android 任务安装项目固定版本的 SDK、NDK 和 CMake，使用 JDK 17 与 Gradle Wrapper，并缓存 Gradle 依赖。

上传仓库后，在 **Actions → Build → 对应运行 → Artifacts** 下载：

- `OpenBridge-debug-<运行编号>`：Debug APK、许可证和第三方许可声明。
- `android-test-reports-<运行编号>`：JVM 测试报告（生成后即使后续步骤失败也保留）。
- `core-test-reports-<运行编号>`：C++ 测试结果和诊断日志。

产物保留 14 天；新提交会取消同分支尚未完成的旧任务。工作流使用只读仓库权限，不自动发布 Release。
CI 调试证书由临时构建环境生成，不保证跨运行或与本机 APK 的签名一致，不要用卸载或清除数据强行覆盖现有安装。
手动运行入口通常需工作流已进入仓库默认分支。首次实际运行结果以 GitHub Actions 页面为准。

## Release 发布包

Windows 发布脚本使用上述 `InstallRoot` 布局。先更新 `app/build.gradle.kts` 中的版本及 `docs/releases/<版本>.md`，
提交源码并保持 Git 工作区干净，再运行打包。仅首次创建发布签名时使用 `-InitializeSigningKey`：

```powershell
.\tools\package-release.ps1 -InstallRoot 'C:\DevTools\Android' -InitializeSigningKey
```

后续版本复用同一签名，去掉 `-InitializeSigningKey`。
发布密钥和随机密码保存在被 Git 忽略的 `private/signing/`，必须单独备份；换电脑时恢复原文件，不能重新生成替代密钥。
Release 与 Debug 使用同一包名、不同证书，不能直接互相覆盖；脚本不安装或卸载手机应用。

脚本执行 `:app:assembleRelease -x :app:lintVitalRelease`，随后对齐、签名和验签，不额外运行 lint / 测试套件。
已有匹配版本的 Release 构建时，可传 `-SkipBuild`。
输出到 `dist/OpenBridge-<版本>-release.zip`，包含签名 APK、对应提交的源码 ZIP、`LICENSE`、第三方声明、
发布说明、证书指纹、构建信息和 SHA-256 校验文件。脚本不会覆盖已有版本目录，也不会自动推送到 GitHub。

Release 不包含 `app/src/debug` 的诊断接收器；当前开发预览不启用 R8 混淆。
具体版本的 APK 与真机验收状态以发布说明为准。

## GitHub 仓库内容

仓库保留源码、测试、Gradle Wrapper、通用构建配置、必要脚本和产品文档。
本机 IDE 设置、构建缓存、发布产物、签名文件、官方 APK 分析及设备数据不进入 Git；APK 和发布 ZIP 应作为 Release 附件上传。
个人助手指令、旧交接记录和本机安装脚本仅在维护者磁盘保留，不是从 GitHub 构建项目所必需的文件。
依赖通过 Gradle 获取，不能因使用根目录的 Apache-2.0 许可证而移除第三方原有声明。

参考：[Android 命令行构建](https://developer.android.com/build/building-cmdline)、
[SDK Manager](https://developer.android.com/tools/sdkmanager)、
[Gradle 与原生库集成](https://developer.android.com/studio/projects/gradle-external-native-builds)。
