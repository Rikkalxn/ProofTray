# ProofTray

[English](README.en.md)

用于尼康和索尼相机选片、传图的 Android 应用，支持基础调色和 LUT。

## 下载

从 [Releases](../../releases/latest) 获取 `ProofTray-<版本>-release.apk`。

需要 Android 10 或更高版本。覆盖安装旧版本会保留图库、评分和已配对的相机。

0.10.1 起应用 ID 改为 `org.prooftray.app`。0.10.0 及更早的版本无法覆盖升级，需要重新安装并配对相机，原有图库和评分不会迁移。

## 功能

- 尼康、索尼相机配对，浏览存储卡，选择性下载原片
- 每次拍摄后通过蓝牙自动传输便携 JPEG（仅尼康）
- 传输队列，支持取消和重试，兼容机型可续传
- 评分、标签、组合筛选、RAW/JPEG 配对、连拍分组、同步比较
- RGB 直方图、逐通道曲线、分色调整、CUBE / HaldCLUT LUT、`.ptlook` 预设
- 边框模板、批量导出 JPEG / PNG、从 RAW 导出 16 位 Log TIFF
- 在其他编辑器中打开副本

## 已测试设备

| 相机 | 手机 |
| --- | --- |
| Nikon Z6 II | 小米 17 Pro Max（Android 16） |
| Sony A7M5 | 小米 17 Pro Max（Android 16） |

## 使用

1. 在相机的智能手机连接菜单中开始配对。在应用中点「导入 → 添加相机」，确认配对码。
2. 点「连接相机 Wi-Fi」浏览存储卡。长按或点「选择」挑选要导入的原片。
3. 在「任务」中查看传输进度，在「图库」中评分、筛选和比较照片。

## 构建

| 依赖 | 版本 |
| --- | --- |
| JDK | 17 |
| Android SDK / Build Tools | 36 / 36.0.0 |
| NDK | 28.2.13676358 |
| CMake | 3.22.1 |

```sh
git clone --recurse-submodules <仓库地址>
./gradlew :app:assembleDebug      # Windows: .\gradlew.bat :app:assembleDebug
```

APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。
推送和 PR 会触发 [Build](.github/workflows/build.yml) 工作流，构建调试 APK 并运行 JVM、C++ 核心和 Log TIFF 测试。

## 源码结构

| 路径 | 内容 |
| --- | --- |
| `app/src/main/java/org/prooftray/app/` | Kotlin 源码 |
| `app/src/main/cpp/` | JNI 适配层 |
| `app/src/test/` | JVM 测试 |
| `core/` | C++20 核心：目录查询、协议编解码、RAW、调色。[说明](core/README.md) |
| `third_party/` | LibRaw、libtiff、furble。[依赖管理](third_party/README.md) |
| `licenses/` | 第三方许可文本 |

源码包名和应用 ID 均为 `org.prooftray.app`。`core/` 可以作为独立项目在 CLion 中打开。

## 发布

1. 在 **Settings → Secrets and variables → Actions** 中添加：
   - `ANDROID_KEYSTORE_BASE64`：Base64 编码的 Release 密钥库，密钥别名为 `prooftray`
   - `ANDROID_KEYSTORE_PASSWORD`：密钥库密码，同时用作密钥密码
2. 在默认分支上运行 **Actions → Release → Run workflow**。

工作流会将补丁版本号和 `versionCode` 各加 1，签名 APK，推送 `v<版本>` 标签，并发布 APK 和源码包。
在同一提交上重新运行会沿用该版本。

## License

[Apache License 2.0](LICENSE)。第三方许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
