#ifndef PROOFTRAY_CATALOG_H_
#define PROOFTRAY_CATALOG_H_

#include <cstddef>
#include <cstdint>
#include <optional>
#include <string>
#include <vector>

namespace prooftray {

enum class MediaFormat { kJpeg, kRaw, kPng, kHeif, kTiff, kOther };
enum class PickState { kUnmarked, kPicked, kRejected };
enum class DownloadState { kRemote, kQueued, kDownloaded };
enum class Orientation { kAny, kLandscape, kPortrait, kSquare };
enum class SortOrder { kNewest, kOldest, kRating, kName, kSize };
enum class MatchState { kExcluded, kMatched, kPendingMetadata };

template <typename T>
struct Range {
  std::optional<T> minimum;
  std::optional<T> maximum;
};

// Search text is UTF-8. Android normalizes Unicode case before crossing JNI;
// the portable core additionally folds ASCII case for non-Android callers.
struct PhotoAsset {
  std::string id;
  std::string name;
  MediaFormat format = MediaFormat::kOther;
  std::optional<int64_t> size_bytes;
  std::optional<int64_t> captured_at_ms;
  std::optional<int64_t> width;
  std::optional<int64_t> height;
  std::string camera;
  std::string lens;
  std::optional<int64_t> iso;
  std::optional<double> aperture;
  std::optional<double> exposure_seconds;
  std::optional<double> focal_length_mm;
  int rating = 0;
  PickState pick_state = PickState::kUnmarked;
  DownloadState download_state = DownloadState::kDownloaded;
  std::vector<std::string> tags;
  std::string source_id;
  std::string folder;
  std::string stack_id;
  bool metadata_complete = false;
};

struct CatalogFilter {
  std::string text;
  uint32_t format_mask = 0;
  Range<int64_t> rating{0, 5};
  uint32_t pick_mask = 0;
  uint32_t download_mask = 0;
  Range<int64_t> captured_at_ms;
  Range<int64_t> size_bytes;
  Range<int64_t> iso;
  Range<double> aperture;
  Range<double> exposure_seconds;
  Range<double> focal_length_mm;
  std::string camera;
  std::string lens;
  std::vector<std::string> required_tags;
  Orientation orientation = Orientation::kAny;
  bool paired_raw_jpeg_only = false;
  bool include_unknown_metadata = true;
  SortOrder sort_order = SortOrder::kNewest;
};

struct PhotoGroup {
  // An index into the input catalog, including when that anchor is filtered out.
  // This keeps group identity stable while a filter hides a group's other files.
  size_t anchor_index = 0;
  std::vector<size_t> members;
  bool inferred_burst = false;
};

struct CatalogResult {
  std::vector<PhotoGroup> groups;
  size_t matched_count = 0;
  size_t pending_metadata_count = 0;
};

// AND between fields, OR within masks, ALL required tags. Bounds are inclusive.
// Unknown selected EXIF fields produce pending, unless another field excludes.
// Throws std::invalid_argument for invalid ranges, enum values or duplicate IDs.
CatalogResult QueryCatalog(const std::vector<PhotoAsset>& assets, const CatalogFilter& filter, bool collapse_stacks = true);

}  // namespace prooftray

#endif  // PROOFTRAY_CATALOG_H_
