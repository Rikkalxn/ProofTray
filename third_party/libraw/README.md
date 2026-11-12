# LibRaw

- Upstream: https://github.com/LibRaw/LibRaw
- Version: 0.22.3
- Commit: `cf02d66eb5c19d4e46df7aee0582e47a4918509a`
- License used by OpenBridge: LGPL-2.1 (upstream also offers CDDL-1.0).

`upstream/` contains the unmodified source archive of this commit, including its licenses, copyright notices and build sources.
The surrounding CMake file is OpenBridge's Android build adapter; its source list comes from upstream `Makefile.am`.
The library is built separately as `libopenbridge_libraw.so`, dynamically linked by `libopenbridge_raw.so`.
No LibRaw algorithms are copied into OpenBridge's own image pipeline.

This configuration uses the bundled decoders and Android's zlib. Optional RawSpeed, Adobe DNG SDK, proprietary camera codecs,
OpenMP and external JPEG/JPEG-XL integrations are not enabled. Unsupported RAW compression fails explicitly.

To rebuild or replace LibRaw, edit its sources here and build the Android project with the documented NDK/CMake toolchain.
The source release includes this entire directory and the JNI adapter. Users can rebuild and sign the APK with their own key.
Do not substitute the maintainer's signing key; an alternative application ID or Android user may be used for independent installs.
