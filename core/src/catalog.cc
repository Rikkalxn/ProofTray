#include "openbridge/catalog.h"

#include <algorithm>
#include <cmath>
#include <limits>
#include <map>
#include <numeric>
#include <set>
#include <stdexcept>
#include <tuple>
#include <type_traits>
#include <utility>

namespace openbridge {
namespace {

std::string FoldAscii(std::string value) {
  for (char& character : value) {
    if (character >= 'A' && character <= 'Z') character = static_cast<char>(character + ('a' - 'A'));
  }
  return value;
}

bool Contains(const std::string& value, const std::string& needle) {
  return FoldAscii(value).find(FoldAscii(needle)) != std::string::npos;
}

std::string Stem(const std::string& name) {
  const size_t dot = name.find_last_of('.');
  return FoldAscii(name.substr(0, dot));
}

template <typename T>
void ValidateRange(const Range<T>& range) {
  if (range.minimum && range.maximum && *range.minimum > *range.maximum) throw std::invalid_argument("Range minimum exceeds maximum");
  if constexpr (std::is_floating_point_v<T>) {
    if ((range.minimum && !std::isfinite(*range.minimum)) || (range.maximum && !std::isfinite(*range.maximum))) {
      throw std::invalid_argument("Filter ranges must be finite");
    }
  }
}

void Validate(const std::vector<PhotoAsset>& assets, const CatalogFilter& filter) {
  ValidateRange(filter.rating);
  ValidateRange(filter.captured_at_ms);
  ValidateRange(filter.size_bytes);
  ValidateRange(filter.iso);
  ValidateRange(filter.aperture);
  ValidateRange(filter.exposure_seconds);
  ValidateRange(filter.focal_length_mm);
  if ((filter.rating.minimum && *filter.rating.minimum < 0) || (filter.rating.maximum && *filter.rating.maximum > 5)) {
    throw std::invalid_argument("Rating bounds must be between 0 and 5");
  }
  if ((filter.format_mask >> 6) || (filter.pick_mask >> 3) || (filter.download_mask >> 3) ||
      static_cast<unsigned>(filter.orientation) > 3 || static_cast<unsigned>(filter.sort_order) > 4) {
    throw std::invalid_argument("Invalid filter enumeration");
  }
  std::set<std::string> identifiers;
  for (const auto& asset : assets) {
    if (asset.id.empty() || !identifiers.insert(asset.id).second) throw std::invalid_argument("Catalog IDs must be nonempty and unique");
    if (asset.rating < 0 || asset.rating > 5 || static_cast<unsigned>(asset.format) > 5 ||
        static_cast<unsigned>(asset.pick_state) > 2 || static_cast<unsigned>(asset.download_state) > 2) {
      throw std::invalid_argument("Invalid asset enumeration or rating");
    }
  }
}

bool InMask(uint32_t mask, unsigned value) {
  return mask == 0 || (mask & (1u << value)) != 0;
}

template <typename T>
MatchState MatchRange(std::optional<T> value, const Range<T>& range) {
  if (!range.minimum && !range.maximum) return MatchState::kMatched;
  if (!value) return MatchState::kPendingMetadata;
  if constexpr (std::is_floating_point_v<T>) {
    if (!std::isfinite(*value)) return MatchState::kPendingMetadata;
  }
  if ((range.minimum && *value < *range.minimum) || (range.maximum && *value > *range.maximum)) return MatchState::kExcluded;
  return MatchState::kMatched;
}

MatchState Merge(MatchState first, MatchState second) {
  if (first == MatchState::kExcluded || second == MatchState::kExcluded) return MatchState::kExcluded;
  if (first == MatchState::kPendingMetadata || second == MatchState::kPendingMetadata) return MatchState::kPendingMetadata;
  return MatchState::kMatched;
}

MatchState MatchTextField(const std::string& value, const std::string& needle) {
  if (needle.empty()) return MatchState::kMatched;
  if (value.empty()) return MatchState::kPendingMetadata;
  return Contains(value, needle) ? MatchState::kMatched : MatchState::kExcluded;
}

MatchState Match(const PhotoAsset& asset, const CatalogFilter& filter, MatchState pair_state) {
  if (!InMask(filter.format_mask, static_cast<unsigned>(asset.format)) ||
      !InMask(filter.pick_mask, static_cast<unsigned>(asset.pick_state)) ||
      !InMask(filter.download_mask, static_cast<unsigned>(asset.download_state))) {
    return MatchState::kExcluded;
  }
  MatchState state = MatchRange<int64_t>(asset.rating, filter.rating);
  state = Merge(state, MatchRange(asset.captured_at_ms, filter.captured_at_ms));
  state = Merge(state, MatchRange(asset.size_bytes, filter.size_bytes));
  state = Merge(state, MatchRange(asset.iso, filter.iso));
  state = Merge(state, MatchRange(asset.aperture, filter.aperture));
  state = Merge(state, MatchRange(asset.exposure_seconds, filter.exposure_seconds));
  state = Merge(state, MatchRange(asset.focal_length_mm, filter.focal_length_mm));
  state = Merge(state, MatchTextField(asset.camera, filter.camera));
  state = Merge(state, MatchTextField(asset.lens, filter.lens));
  for (const auto& tag : filter.required_tags) {
    const bool present = std::any_of(asset.tags.begin(), asset.tags.end(), [&](const auto& item) { return FoldAscii(item) == FoldAscii(tag); });
    if (!present) return MatchState::kExcluded;
  }
  if (!filter.text.empty()) {
    const bool found = Contains(asset.name, filter.text) ||
                       std::any_of(asset.tags.begin(), asset.tags.end(), [&](const auto& tag) { return Contains(tag, filter.text); });
    // Filename/tag search is independent of EXIF. Camera and lens have their own filters.
    if (!found) return MatchState::kExcluded;
  }
  if (filter.orientation != Orientation::kAny) {
    if (!asset.width || !asset.height || *asset.width <= 0 || *asset.height <= 0) {
      state = Merge(state, MatchState::kPendingMetadata);
    } else {
      const bool matches = (filter.orientation == Orientation::kLandscape && *asset.width > *asset.height) ||
                           (filter.orientation == Orientation::kPortrait && *asset.width < *asset.height) ||
                           (filter.orientation == Orientation::kSquare && *asset.width == *asset.height);
      if (!matches) return MatchState::kExcluded;
    }
  }
  if (filter.paired_raw_jpeg_only) state = Merge(state, pair_state);
  return state;
}

class Groups {
 public:
  explicit Groups(const std::vector<PhotoAsset>& assets) : assets_(assets), parent_(assets.size()), inferred_(assets.size(), false) {
    std::iota(parent_.begin(), parent_.end(), 0);
  }

  size_t Find(size_t index) {
    size_t root = index;
    while (parent_[root] != root) root = parent_[root];
    while (parent_[index] != index) {
      const size_t next = parent_[index];
      parent_[index] = root;
      index = next;
    }
    return root;
  }

  void Join(size_t first, size_t second, bool inferred = false) {
    first = Find(first);
    second = Find(second);
    if (assets_[second].id < assets_[first].id) std::swap(first, second);
    parent_[second] = first;
    inferred_[first] = inferred_[first] || inferred_[second] || inferred;
  }

  bool inferred(size_t index) { return inferred_[Find(index)]; }

 private:
  const std::vector<PhotoAsset>& assets_;
  std::vector<size_t> parent_;
  std::vector<bool> inferred_;
};

bool HasGroupingIdentity(const PhotoAsset& asset) {
  return !asset.source_id.empty() && !asset.folder.empty() && !asset.camera.empty() && asset.captured_at_ms.has_value();
}

std::vector<MatchState> PairPhotos(const std::vector<PhotoAsset>& assets, Groups& groups) {
  using Key = std::tuple<std::string, std::string, std::string, std::string, int64_t>;
  std::map<Key, std::vector<size_t>> buckets;
  std::vector<MatchState> paired(assets.size(), MatchState::kExcluded);
  for (size_t index = 0; index < assets.size(); ++index) {
    const auto& asset = assets[index];
    if (asset.format != MediaFormat::kRaw && asset.format != MediaFormat::kJpeg) continue;
    if (!HasGroupingIdentity(asset) || Stem(asset.name).empty()) {
      paired[index] = MatchState::kPendingMetadata;
      continue;
    }
    buckets[{asset.source_id, asset.folder, FoldAscii(asset.camera), Stem(asset.name), *asset.captured_at_ms}].push_back(index);
  }
  for (const auto& [key, members] : buckets) {
    // Ambiguous duplicates (e.g. two same-name RAW imports) must not become a pair.
    if (members.size() != 2 || assets[members[0]].format == assets[members[1]].format) continue;
    groups.Join(members[0], members[1]);
    paired[members[0]] = paired[members[1]] = MatchState::kMatched;
  }
  return paired;
}

void CollapseBursts(const std::vector<PhotoAsset>& assets, Groups& groups) {
  using ExplicitKey = std::tuple<std::string, std::string, std::string, std::string>;
  using Context = std::tuple<std::string, std::string, std::string, std::string>;
  std::map<ExplicitKey, size_t> explicit_groups;
  std::map<Context, std::vector<size_t>> candidates;
  std::set<size_t> captures;
  for (size_t index = 0; index < assets.size(); ++index) {
    const auto& asset = assets[index];
    if (!HasGroupingIdentity(asset)) continue;
    if (!asset.burst_id.empty()) {
      const ExplicitKey key{asset.source_id, asset.folder, FoldAscii(asset.camera), asset.burst_id};
      auto [entry, inserted] = explicit_groups.emplace(key, index);
      if (!inserted) groups.Join(entry->second, index);
    }
  }
  std::set<size_t> explicit_roots;
  for (size_t index = 0; index < assets.size(); ++index) {
    if (!assets[index].burst_id.empty()) explicit_roots.insert(groups.Find(index));
  }
  for (size_t index = 0; index < assets.size(); ++index) {
    const auto& asset = assets[index];
    if (!HasGroupingIdentity(asset) || !asset.burst_id.empty()) continue;
    const size_t capture = groups.Find(index);
    if (explicit_roots.contains(capture)) continue;
    if (!captures.insert(capture).second) continue;
    candidates[{asset.source_id, asset.folder, FoldAscii(asset.camera), FoldAscii(asset.lens)}].push_back(index);
  }
  for (auto& [context, indices] : candidates) {
    std::sort(indices.begin(), indices.end(), [&](size_t first, size_t second) {
      return std::tie(assets[first].captured_at_ms, assets[first].id) < std::tie(assets[second].captured_at_ms, assets[second].id);
    });
    for (size_t position = 1; position < indices.size(); ++position) {
      const auto& previous = assets[indices[position - 1]];
      const auto& current = assets[indices[position]];
      const int64_t before = *previous.captured_at_ms;
      const int64_t after = *current.captured_at_ms;
      // Same-stem ambiguities are not evidence of a burst. Near timestamps alone
      // are only a heuristic, so mark inferred groups separately for the UI.
      if (Stem(previous.name) != Stem(current.name) && before <= std::numeric_limits<int64_t>::max() - 1000 && after <= before + 1000) {
        groups.Join(indices[position - 1], indices[position], true);
      }
    }
  }
}

template <typename T>
int CompareOptional(const std::optional<T>& first, const std::optional<T>& second, bool descending) {
  if (!first || !second) return first.has_value() == second.has_value() ? 0 : (first ? -1 : 1);
  if (*first == *second) return 0;
  const int ascending = *first < *second ? -1 : 1;
  return descending ? -ascending : ascending;
}

bool SortBefore(const PhotoAsset& first, const PhotoAsset& second, SortOrder order) {
  int comparison = 0;
  switch (order) {
    case SortOrder::kNewest:
    case SortOrder::kOldest:
      comparison = CompareOptional(first.captured_at_ms, second.captured_at_ms, order == SortOrder::kNewest);
      break;
    case SortOrder::kRating:
      comparison = first.rating == second.rating ? 0 : (first.rating > second.rating ? -1 : 1);
      break;
    case SortOrder::kName:
      comparison = FoldAscii(first.name).compare(FoldAscii(second.name));
      break;
    case SortOrder::kSize:
      comparison = CompareOptional(first.size_bytes, second.size_bytes, true);
      break;
  }
  return comparison == 0 ? first.id < second.id : comparison < 0;
}

}  // namespace

CatalogResult QueryCatalog(const std::vector<PhotoAsset>& assets, const CatalogFilter& filter, bool collapse_bursts) {
  Validate(assets, filter);
  Groups groups(assets);
  const auto paired = PairPhotos(assets, groups);
  if (collapse_bursts) CollapseBursts(assets, groups);
  CatalogResult result;
  std::vector<size_t> included;
  for (size_t index = 0; index < assets.size(); ++index) {
    const MatchState state = Match(assets[index], filter, paired[index]);
    if (state == MatchState::kMatched) ++result.matched_count;
    if (state == MatchState::kPendingMetadata) ++result.pending_metadata_count;
    if (state == MatchState::kMatched || (state == MatchState::kPendingMetadata && filter.include_unknown_metadata)) included.push_back(index);
  }
  std::sort(included.begin(), included.end(), [&](size_t first, size_t second) {
    return SortBefore(assets[first], assets[second], filter.sort_order);
  });
  std::map<size_t, size_t> output_groups;
  for (size_t index : included) {
    const size_t anchor = groups.Find(index);
    auto [entry, inserted] = output_groups.emplace(anchor, result.groups.size());
    if (inserted) result.groups.push_back({anchor, {}, groups.inferred(anchor)});
    result.groups[entry->second].members.push_back(index);
  }
  return result;
}

}  // namespace openbridge
