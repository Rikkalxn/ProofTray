# LibRaw

- Upstream: https://github.com/LibRaw/LibRaw
- Version: 0.23.0-Snapshot202611 (development snapshot, not a stable release)
- Commit: `a9a14b1970d188c25dade81f8326ff00a6e33649`
- ProofTray uses the LGPL-2.1 option. Upstream also offers CDDL-1.0.

`upstream/` is a Git submodule pinned to this unmodified upstream commit, including its licenses, notices and build sources.
The surrounding CMake file is ProofTray's build adapter. Its source list comes from upstream `Makefile.am`.
The library is built separately as `libopenbridge_libraw.so`, dynamically linked by `libprooftray_raw.so`.

The previous 0.22.3 release does not contain Sony ILCE-7M5 support. This revision includes upstream change
[`8eb34338`](https://github.com/LibRaw/LibRaw/commit/8eb3433879bddceb5c0098281c2f855bd9115e0c):
camera ID, model normalization, metadata/crop handling and the dedicated color matrix. It avoids the old `ILCE-7` prefix fallback.
The snapshot also adds built-in Sony ARW6 and Nikon HE decoders; both sources are included in the build adapter.
The upgrade has had source/API checks only; Android builds and device color/compression validation remain the maintainer's next step.

This configuration uses the bundled decoders and Android's zlib. Optional RawSpeed, Adobe DNG SDK, proprietary camera codecs,
OpenMP and external JPEG/JPEG-XL integrations are not enabled. Unsupported RAW compression fails explicitly.

After cloning or pulling the parent repository, initialize the recorded dependency revision:

```sh
git submodule update --init --recursive
```

This checks out the parent's recorded commit; it does not upgrade to upstream HEAD. Normal builds and CI never use `--remote`.
To upgrade, fetch upstream in `upstream/`, inspect the intended commit and check it out detached. Review the diff from the old pin,
especially camera tables, public APIs, `Makefile.am` and licenses. Update this adapter, this revision note, `THIRD_PARTY_NOTICES.md`,
and the DCB/FBDD and X3F notices in `licenses/LibRaw-components.txt` along with the parent's `upstream/` gitlink.
The Android build copies `COPYRIGHT`, `LICENSE.LGPL` and `LICENSE.CDDL` directly from the pinned checkout.
Dependabot proposes pin changes for review; accepting a new pin still requires these compatibility and device checks.

To rebuild or replace LibRaw, use an initialized checkout, edit its sources and build with the documented NDK/CMake toolchain.
GitHub's automatic source ZIP/TAR downloads and a plain parent `git archive` do not include submodule contents; use a recursive
clone. Any separately prepared source distribution must include this pinned dependency as well as the JNI adapter and build files.
Users can rebuild and sign the APK with their own key.
Do not substitute the maintainer's signing key; an alternative application ID or Android user may be used for independent installs.
