# libtiff

Log 中间文件使用 libtiff 4.7.2，以 Git submodule 固定到
`d01a94be176f5f6a87f7ee1c0b32e65416aa2b4d`（上游标签 `v4.7.2`）。
来源：https://gitlab.com/libtiff/libtiff 。上游源码不修改。

父目录的 CMake 适配只启用 zlib/Deflate 与基础 TIFF 支持，关闭工具、上游测试、文档、C++ 流接口和其他可选编解码器。
Android 使用 NDK 的 zlib；桌面可选测试需要本机已有的 ZLIB。静态库链接到 RAW JNI 库。
正常构建不下载依赖、不追踪最新提交；首次使用前执行 `git submodule update --init --recursive`。

写出器只处理应用生成的 RGB16 数据，使用有限条带内存。许可见 `upstream/LICENSE.md`，APK 中保留相同许可文本。
升级时检查 CMake 选项、`TIFFFdOpenExt`/错误处理接口、标签、16 位读回与取消路径。
