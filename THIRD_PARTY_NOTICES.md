# Third-party components

ProofTray's original code and documentation are licensed under the [Apache License, Version 2.0](LICENSE).
Third-party components retain the terms and copyright notices listed below. The Android build collects these notices
under `assets/licenses/`. They are available offline in Settings → About → Open source and licenses.
GitHub releases include the signed APK and a source archive with the pinned native dependencies and build adapters.
The CI debug package also includes the notices extracted from its APK.

## furble

The Blowfish implementation in `third_party/furble` and the smart-device pairing hash adaptation in
`core/src/nikon_pairing.cc` are based on [gkoh/furble](https://github.com/gkoh/furble), under the MIT license.
Copyright (c) 2020 Guo-Rong Koh. The full license is preserved in `third_party/furble/LICENSE` and included in the APK assets.
The upstream Blowfish source also retains its attribution to Bruce Schneier's published implementation.

The Nikon and Sony companion APKs are local protocol references. Their code, libraries and artwork are excluded from distributions.

## LibRaw

RAW development uses [LibRaw 0.23.0-Snapshot202611](https://github.com/LibRaw/LibRaw/tree/a9a14b1970d188c25dade81f8326ff00a6e33649),
commit `a9a14b1970d188c25dade81f8326ff00a6e33649`, under the upstream LGPL-2.1 option.
This pinned development snapshot includes Sony ILCE-7M5 support.
Copyright LibRaw LLC and the other contributors identified in its `COPYRIGHT` and source headers.
The unmodified upstream source and licenses are retained in the `third_party/libraw/upstream` Git submodule.
The APK includes the upstream copyright notice and both license options.
The DCB/FBDD and X3F component terms from the upstream source headers are included in `licenses/LibRaw-components.txt`.
LibRaw is a separate shared library. Initialize submodules to obtain the full pinned dependency alongside
ProofTray's JNI adapter and CMake configuration. Source distributions must include the submodule contents.
Rebuild/replacement instructions and optional codec exclusions are in `third_party/libraw/README.md`.

## libtiff

16-bit Log TIFF export uses [libtiff 4.7.2](https://gitlab.com/libtiff/libtiff/-/tree/v4.7.2),
commit `d01a94be176f5f6a87f7ee1c0b32e65416aa2b4d`, under the upstream libtiff license.
Copyright 1988-1997 Sam Leffler; Copyright 1991-1997 Silicon Graphics, Inc., and the contributors identified in source headers.
The unmodified source is pinned in `third_party/libtiff/upstream`. Its full license is retained in `upstream/LICENSE.md`
and packaged as `assets/licenses/libtiff-LICENSE.md`.
The library is statically linked into the RAW JNI library with zlib/Deflate enabled and other optional codecs disabled.
Android uses the platform zlib. Source distributions must include the pinned submodule and the parent CMake adapter.

## Adobe DNG SDK reference rendering

The default RAW SDR presentation adapts the exposure/shadows ramp, negative-exposure tone adjustment,
ACR3 default tone samples, RGB tone application and normalized color-space matrices from Adobe's DNG SDK reference renderer.
Copyright 2006-2023 Adobe Systems Incorporated. All Rights Reserved.
Adobe permits use, modification and distribution under the DNG SDK License Agreement in `licenses/Adobe-DNG-SDK.txt`.
The same agreement is packaged in `assets/licenses/Adobe-DNG-SDK.txt`.

The reference files are `source/dng_render.cpp`, `source/dng_reference.cpp` and `source/dng_color_space.cpp`.
[AOSP DNG SDK source revision](https://android.googlesource.com/platform/external/dng_sdk/+/de700ad461e35af50b28b861943a0b0753b10929/)
This is a portable adaptation of the matrix-only SDR presentation stage, not the complete SDK or Lightroom's processing engine.
RAW parsing, black/white normalization, camera white balance and demosaicing remain in LibRaw.
No Adobe camera profiles, proprietary Lightroom presets, HDR tables or ICC profile resources are bundled.

This product includes DNG technology under license by Adobe Systems Incorporated.

## Sony connectivity

Nordic's Android BLE Library 2.11.0 supplies the Android GATT request queue and coroutine bindings, under BSD-3-Clause.
The APK includes `assets/licenses/Nordic-BLE-BSD.txt`. [Upstream license](https://github.com/nordicsemi/Android-BLE-Library/blob/2.11.0/LICENSE)

JSch 2.28.8 provides SSH authentication and direct TCP channels. Its BSD license and the notices for JZlib and jBCrypt are included in
`assets/licenses/JSch-licenses.txt`. [Upstream license](https://github.com/mwiede/jsch/blob/jsch-2.28.8/LICENSE.txt)

Okio 3.16.2 provides buffered reads and timeouts under Apache-2.0. [Upstream license](https://github.com/square/okio/blob/3.16.2/LICENSE.txt)

The SSH session arrangement and PTP transfer-mode calls were cross-checked against the Apache-2.0 alpha2go project.
ProofTray uses its own PTP codec and does not link the Go module.
[Reference revision](https://github.com/Knallli/alpha2go/tree/02883425c9445c653b2950210187cdd1547320c2)

## Android runtime and build tools

AndroidX Activity, Compose, Lifecycle and ExifInterface use the Apache License 2.0.
The Kotlin standard library, kotlinx.coroutines and Okio also use Apache 2.0. The license text is included in
`assets/licenses/ProofTray-Apache-2.0.txt`. The resolved libraries retain their own copyright notices.
[AndroidX license](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/LICENSE.txt),
[Kotlin license](https://github.com/JetBrains/kotlin/blob/v2.2.21/license/LICENSE.txt),
[coroutines license](https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.2/LICENSE.txt).

The APK ships the Android NDK's shared LLVM C++ runtime (`libc++_shared.so`). The NDK toolchain notice includes
the Apache License 2.0 with LLVM Exceptions and the upstream component notices. The build copies `NOTICE.toolchain`
from the selected NDK into `assets/licenses/Android-NDK-NOTICE.txt`.

The Gradle Wrapper is distributed with the source under Apache 2.0. Its launcher scripts carry the upstream headers
and the Wrapper JAR contains `META-INF/LICENSE`. JUnit and JSON-java are JVM test dependencies.
