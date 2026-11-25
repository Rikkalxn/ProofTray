# ProofTray

[简体中文](README.md)

An Android app for culling and transferring photos from Nikon and Sony cameras, with basic color grading and LUT support.

## Download

Get `ProofTray-<version>-release.apk` from [Releases](../../releases/latest).

Requires Android 10 or later. Installing over an older version keeps your library, ratings and paired cameras.

## Features

- Nikon and Sony pairing, memory card browsing, selective original downloads
- Automatic compact JPEG transfer over Bluetooth after each shot (Nikon only)
- Transfer queue with cancel and retry, resumable downloads on supported cameras
- Ratings, tags, combined filters, RAW/JPEG pairing, burst grouping, synchronized comparison
- RGB histogram, per-channel curves, selective color, CUBE / HaldCLUT LUTs, `.oblook` presets
- Frame templates, batch JPEG / PNG export, 16-bit Log TIFF export from RAW
- Open a copy in another editor

## Tested devices

| Camera | Phone |
| --- | --- |
| Nikon Z6 II | Xiaomi 17 Pro Max (Android 16) |
| Sony A7M5 | Xiaomi 17 Pro Max (Android 16) |

## Usage

1. Start pairing from the camera's smartphone connection menu. In the app, tap Import → Add camera (导入 → 添加相机)
   and confirm the pairing code.
2. Tap Connect to camera Wi-Fi (连接相机 Wi-Fi) to browse the card. Long-press or tap Select (选择) to pick originals to import.
3. Check transfer progress in Tasks (任务). Rate, filter and compare photos in Library (图库).

## Building

| Dependency | Version |
| --- | --- |
| JDK | 17 |
| Android SDK / Build Tools | 36 / 36.0.0 |
| NDK | 28.2.13676358 |
| CMake | 3.22.1 |

```sh
git clone --recurse-submodules <repository-url>
./gradlew :app:assembleDebug      # Windows: .\gradlew.bat :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
Pushes and pull requests trigger the [Build](.github/workflows/build.yml) workflow, which builds the debug APK and runs JVM,
C++ core and Log TIFF tests.

## Source layout

| Path | Contents |
| --- | --- |
| `app/src/main/java/org/prooftray/app/` | Kotlin sources |
| `app/src/main/cpp/` | JNI adapters |
| `app/src/test/` | JVM tests |
| `core/` | C++20 core: catalog queries, protocol codecs, RAW, color. [Guide](core/README.md) |
| `third_party/` | LibRaw, libtiff, furble. [Dependency management](third_party/README.md) |
| `licenses/` | Third-party license texts |

The source package is `org.prooftray.app`. The application ID stays `org.openbridge.app` for in-place updates.
`core/` can be opened in CLion as a standalone project.

## Releasing

1. Under **Settings → Secrets and variables → Actions**, add:
   - `ANDROID_KEYSTORE_BASE64`: the release keystore, Base64-encoded
   - `ANDROID_KEYSTORE_PASSWORD`: the keystore password
2. Run **Actions → Release → Run workflow** on the default branch.

The workflow bumps the patch version and `versionCode` by one, signs the APK, pushes the `v<version>` tag and publishes the APK
and source archive. Rerunning on the same commit reuses its version.

## License

[Apache License 2.0](LICENSE). Third-party licenses are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
