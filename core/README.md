# Catalog core

`include/openbridge/catalog.h` is the platform-independent C++20 interface. It has no Android, JNI, Kotlin or Qt dependency.
`QueryCatalog` performs filtering, sorting, RAW/JPEG pairing and optional burst folding. Input order is not a sorting tie-breaker: asset IDs are.

Filter semantics:

- Different fields combine with AND. Format / pick / download selections combine with OR. Required tags combine with AND.
- Numeric and capture-time range bounds are inclusive. Unspecified bounds are open.
- A missing selected metadata field is pending. A known mismatch in another field excludes the file even when some metadata is missing.
- Pending count includes pending rows hidden by `include_unknown_metadata=false`; matched count only includes definite matches.
- Free-text search covers filenames and tags only. Missing camera/lens EXIF does not make a filename mismatch pending.
- Tags are local user metadata. An empty tag collection is known empty, not missing EXIF.
- Unknown capture times and file sizes sort after known values in either chronological direction.

Grouping:

- RAW/JPEG pairing checks the full catalog before filtering. A RAW-only query can therefore match paired RAWs without displaying their JPEG partners.
- Pair keys require source, folder, camera, filename stem and exact capture timestamp. Ambiguous duplicate pairs remain unpaired.
- Disabling burst folding keeps verified RAW/JPEG pairs together.
- Explicit burst IDs require a matching source/folder/camera context and known capture timestamps.
- Heuristic bursts link adjacent distinct captures at most 1 second apart, in the same source/folder/camera/lens context.
- A heuristic is labeled `inferred_burst`; it is not proof of camera burst mode. Explicit groups are never joined to heuristic groups.
- Missing identity metadata prevents grouping, so local documents with unavailable EXIF can remain singletons.
- Group anchors use the lexicographically lowest member asset ID and remain stable when a filter hides that anchor.

Android adapter:

- `CatalogEngine.kt` lowercases searchable Unicode text using `Locale.ROOT`, batches metadata and invokes one native call.
- `catalog_jni.cc` validates array dimensions, enum values, tag ranges and nulls. It converts UTF-16 to UTF-8 and releases JNI references promptly.
- Wire integers use `Long.MIN_VALUE` for missing values; wire decimals use NaN. No image pixels or large image buffers cross this API.
- Returned integer-array version is 1. Header: version, matched count, pending count, group count.
- Each group contains anchor input index, inferred flag, member count, and member input indexes in display order.
- Keep the native method name if code shrinking is enabled in a future release.

`tests/catalog_test.cc` contains regression cases for missing metadata, range boundaries, combined filters, conservative grouping and invalid inputs.
They are opt-in with `OPENBRIDGE_BUILD_TESTS`. On 2026-10-28, the Windows host-debug build with GNU 13.1.0 passed CTest (1/1).

Open this `core` directory in CLion as a standalone CMake project; tests are enabled by default. Its preset is `core-debug`.
The parent project also includes this library with `add_subdirectory(core)` and has a separate `host-debug` preset.
See [CLion and Android build instructions](../docs/building.md) for the separate core-test and APK workflows.

Color processing:

- `color_lut.h` / `color_lut.cc` parse CUBE and decoded Hald RGBA8 data without Android dependencies.
- 1D curves interpolate independently; 3D tables use red-fastest tetrahedral interpolation.
- Pixel transforms apply linear-light exposure/channel warmth, encoded contrast/saturation, LUT, then global strength in SDR sRGB.
- RGBA8 processing preserves alpha and row padding. Premultiplied alpha is removed before grading and restored afterwards.
- The versioned little-endian LUT binary stores dimensions, domain and floats; titles and attribution live in Android metadata.
- `tests/color_lut_test.cc` adds malformed input, interpolation, binary round-trip, Hald ordering and alpha/stride regressions.
  On 2026-10-28, the Windows `core-verification` build passed all three CTest groups: catalog, PTP codec and color LUT.

Location protocol:

- `nikon_location.h` / `nikon_location.cc` encode the 41-byte camera location record independently of Android.
- Coordinates use degrees and fractional minutes; timestamps are UTC, with explicit presence and validity bits.
- Optional altitude/satellite fields are omitted when unavailable. Invalid samples cannot produce a valid GPS record.
- PTP command construction now supports the host-to-camera data direction used by location writes.
- `tests/nikon_location_test.cc` covers the wire representation and boundaries.
  On 2026-10-29, `core-verification` passed all four CTest groups, including location encoding.
