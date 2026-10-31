# Third-party components

OpenBridge's original code and documentation are licensed under the Apache License, Version 2.0; see `LICENSE`.
Third-party code retains its original license and attribution, including the MIT-licensed portions described below.
Dependencies obtained through Gradle and the Gradle Wrapper retain their respective licenses.

## furble

The Blowfish implementation in `third_party/furble` and the smart-device pairing hash adaptation in
`core/src/nikon_pairing.cc` are based on [gkoh/furble](https://github.com/gkoh/furble), under the MIT license.
Copyright (c) 2020 Guo-Rong Koh. The full license is preserved in `third_party/furble/LICENSE` and included in the APK assets.
The upstream Blowfish source also retains its attribution to Bruce Schneier's published implementation.

Official Nikon APK analysis is kept under `private/` for local protocol verification only.
No official application code, native library, artwork or resource is linked into OpenBridge.
