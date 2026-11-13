# LibRaw

- Upstream: https://github.com/LibRaw/LibRaw
- Version: 0.23.0-Snapshot202611 (development snapshot, not a stable release)
- Commit: `a9a14b1970d188c25dade81f8326ff00a6e33649`
- License used by OpenBridge: LGPL-2.1 (upstream also offers CDDL-1.0).

`upstream/` is a Git submodule pinned to this unmodified upstream commit, including its licenses, notices and build sources.
The surrounding CMake file is OpenBridge's Android build adapter; its source list comes from upstream `Makefile.am`.
The library is built separately as `libopenbridge_libraw.so`, dynamically linked by `libopenbridge_raw.so`.
No LibRaw algorithms are copied into OpenBridge's own image pipeline.

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
the app's license links and bundled license assets together, then commit the new `upstream/` gitlink in the parent repository.
Dependabot proposes pin changes for review; accepting a new pin still requires these compatibility and device checks.

To rebuild or replace LibRaw, use an initialized checkout, edit its sources and build with the documented NDK/CMake toolchain.
GitHub's automatic source ZIP/TAR downloads and a plain parent `git archive` do not include submodule contents; use a recursive
clone. Any separately prepared source distribution must include this pinned dependency as well as the JNI adapter and build files.
Users can rebuild and sign the APK with their own key.
Do not substitute the maintainer's signing key; an alternative application ID or Android user may be used for independent installs.
