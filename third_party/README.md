# 第三方依赖管理

依赖固定到可追溯的版本；正常构建和 CI 不自动追踪上游最新代码。

| 依赖 | 管理方式 | 原因与更新方式 |
| --- | --- | --- |
| LibRaw | `libraw/upstream` Git submodule，父仓库记录完整提交号 | 完整使用独立上游库，需要持续更新机型和解码支持；升级时审查源码、接口、构建清单和样片。 |
| libtiff | `libtiff/upstream` Git submodule，固定 4.7.2 提交 | Log RGB16 TIFF 写出；仅启用 zlib/Deflate，升级说明见 `libtiff/README.md`。 |
| libheif、libde265 | `heif/libheif`、`heif/libde265` Git submodule，分别固定 1.23.7 和 1.1.3 | 相机 HEIF 软件解码。两个独立共享库仅启用 HEVC 解码后端。 |
| furble | 保留 `furble/` 中的 Blowfish 源码和 MIT 许可 | 仅使用两个源码文件及独立的配对哈希适配，不引入完整相机遥控固件；按需要人工核对相关上游改动。 |
| Adobe DNG SDK 参考呈现 | 保留适配后的 core 代码与许可、来源提交 | 属于移植的呈现阶段，并未链接完整 SDK；升级需要对照参考实现审查算法。 |
| AndroidX、Kotlin、Nordic BLE、JSch、Okio 等 | Gradle/Maven 固定版本和 Compose BOM | 包管理器负责依赖解析及传递依赖，源码 submodule 不代替这些制品。 |
| GitHub Actions | 工作流固定 action 的完整提交号 | 通过 Dependabot PR 审查升级，保持每次 CI 所用 action 可复现。 |

`.github/dependabot.yml` 每月检查 Git submodule、Gradle 和 GitHub Actions，提出升级 PR；不会自动合并或发布。
LibRaw 当前选择含 ILCE-7M5 支持的开发快照，具体版本、验证边界与升级清单见 [LibRaw 说明](libraw/README.md)。
submodule 本身不会修正机型支持；需要父仓库提交新的依赖版本，使用者再同步这个固定版本。

首次克隆使用 `git clone --recurse-submodules <仓库地址>`。已有工作区在 `git pull` 后执行：

```sh
git submodule update --init --recursive
```

查看当前版本可运行 `git submodule status`。不要在普通构建或发布流程中使用 `git submodule update --remote`。
独立核心测试只依赖保留在父仓库的 furble 摘取代码；Android RAW 和桌面 RAW 工具需要初始化 LibRaw。

GitHub Actions 的 Debug 产物附带 `ProofTray-source.tar.gz`，包含固定版本的 LibRaw、libtiff 和构建适配。
也可以递归克隆仓库来获取完整源码。GitHub 标签页自动生成的源码归档省略子模块内容。
如另行制作发布源码包，需要包含对应 LibRaw、libtiff 提交的完整源码、许可证，以及本仓库的 JNI 和构建适配文件。
