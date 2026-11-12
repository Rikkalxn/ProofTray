# Third-party components

ProofTray's original code and documentation are licensed under the Apache License, Version 2.0 (`LICENSE`).
Third-party code retains its original license and attribution, including the MIT-licensed portions described below.
Dependencies obtained through Gradle and the Gradle Wrapper retain their respective licenses.

## furble

The Blowfish implementation in `third_party/furble` and the smart-device pairing hash adaptation in
`core/src/nikon_pairing.cc` are based on [gkoh/furble](https://github.com/gkoh/furble), under the MIT license.
Copyright (c) 2020 Guo-Rong Koh. The full license is preserved in `third_party/furble/LICENSE` and included in the APK assets.
The upstream Blowfish source also retains its attribution to Bruce Schneier's published implementation.

The Nikon and Sony companion APKs are local protocol references. Their code, libraries and artwork are excluded from distributions.

## LibRaw

RAW development uses [LibRaw 0.22.3](https://github.com/LibRaw/LibRaw/releases/tag/0.22.3),
commit `cf02d66eb5c19d4e46df7aee0582e47a4918509a`, under the upstream LGPL-2.1 option.
Copyright LibRaw LLC and the other contributors identified in its `COPYRIGHT` and source headers.
The unmodified upstream source and licenses are retained in `third_party/libraw/upstream`.
License texts and copyright information are also included in the APK assets.
LibRaw is a separate shared library. ProofTray's JNI adapter and CMake configuration are provided in the source release.
Rebuild/replacement instructions and optional codec exclusions are in `third_party/libraw/README.md`.

## Adobe DNG SDK reference rendering

The default RAW SDR presentation adapts the exposure/shadows ramp, negative-exposure tone adjustment,
ACR3 default tone samples, RGB tone application and normalized color-space matrices from Adobe's DNG SDK reference renderer.
Copyright 2006-2023 Adobe Systems Incorporated. All Rights Reserved.
Adobe permits use, modification and distribution under the DNG SDK License Agreement in `licenses/Adobe-DNG-SDK.txt`.
The same agreement is packaged in `app/src/main/assets/licenses/Adobe-DNG-SDK.txt`.

Source: AOSP `platform/external/dng_sdk`, revision `de700ad461e35af50b28b861943a0b0753b10929`,
`source/dng_render.cpp`, `source/dng_reference.cpp` and `source/dng_color_space.cpp`.
This is a portable adaptation of the matrix-only SDR presentation stage, not the complete SDK or Lightroom's processing engine.
RAW parsing, black/white normalization, camera white balance and demosaicing remain in LibRaw.
No Adobe camera profiles, proprietary Lightroom presets, HDR tables or ICC profile resources are bundled.

This product includes DNG technology under license by Adobe Systems Incorporated.

## Sony connectivity

Nordic's Android BLE Library 2.11.0 supplies the Android GATT request queue and coroutine bindings, under BSD-3-Clause.
The APK includes `assets/licenses/Nordic-BLE-BSD.txt`. [Upstream license](https://github.com/nordicsemi/Android-BLE-Library/blob/2.11.0/LICENSE)

JSch 2.28.8 provides SSH authentication and direct TCP channels. Its BSD license and bundled component notices are included in
`assets/licenses/JSch-licenses.txt`. [Upstream license](https://github.com/mwiede/jsch/blob/jsch-2.28.8/LICENSE.txt)

Okio 3.16.2 provides buffered reads and timeouts under Apache-2.0. [Upstream license](https://github.com/square/okio/blob/3.16.2/LICENSE.txt)

The SSH session arrangement and PTP transfer-mode calls were cross-checked against the Apache-2.0 alpha2go project.
ProofTray uses its own PTP codec and does not link the Go module.
[Reference revision](https://github.com/Knallli/alpha2go/tree/02883425c9445c653b2950210187cdd1547320c2)
