#include "prooftray/catalog.h"

#include <iostream>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace {

using prooftray::CatalogFilter;
using prooftray::MediaFormat;
using prooftray::PhotoAsset;
using prooftray::QueryCatalog;

void Expect(bool condition, const char* message) {
  if (!condition) throw std::runtime_error(message);
}

PhotoAsset Photo(std::string id, std::string name, int64_t time, MediaFormat format = MediaFormat::kJpeg) {
  PhotoAsset photo;
  photo.id = std::move(id);
  photo.name = std::move(name);
  photo.format = format;
  photo.captured_at_ms = time;
  photo.source_id = "Z6II-serial-1";
  photo.folder = "card-1/DCIM/100ND";
  photo.camera = "Nikon Z 6 II";
  photo.lens = "Nikkor Z 24-70mm";
  photo.width = 6000;
  photo.height = 4000;
  photo.size_bytes = 25000000;
  photo.iso = 800;
  photo.aperture = 2.8;
  photo.exposure_seconds = 0.004;
  photo.focal_length_mm = 50.0;
  photo.metadata_complete = true;
  return photo;
}

void PendingMetadataDoesNotOverrideExclusion() {
  auto known = Photo("known", "DSC_1000.JPG", 1000);
  auto unknown = Photo("unknown", "DSC_1001.JPG", 2000);
  unknown.iso.reset();
  CatalogFilter filter;
  filter.iso.minimum = 800;
  filter.iso.maximum = 800;
  auto result = QueryCatalog({known, unknown}, filter, false);
  Expect(result.matched_count == 1 && result.pending_metadata_count == 1 && result.groups.size() == 2, "Unknown ISO must remain pending");
  filter.include_unknown_metadata = false;
  result = QueryCatalog({known, unknown}, filter, false);
  Expect(result.groups.size() == 1 && result.pending_metadata_count == 1, "Excluding unknowns must retain the pending count");
  filter.rating.minimum = 4;
  result = QueryCatalog({unknown}, filter, false);
  Expect(result.groups.empty() && result.pending_metadata_count == 0, "Known exclusion must dominate unknown EXIF");
}

void CombinedRangesAndTags() {
  auto photo = Photo("photo", "Portrait.JPG", 1700000000000);
  photo.rating = 4;
  photo.tags = {"Travel", "Selected"};
  photo.pick_state = prooftray::PickState::kPicked;
  CatalogFilter filter;
  filter.format_mask = (1u << static_cast<unsigned>(MediaFormat::kJpeg)) | (1u << static_cast<unsigned>(MediaFormat::kRaw));
  filter.rating = {4, 5};
  filter.pick_mask = 1u << static_cast<unsigned>(prooftray::PickState::kPicked);
  filter.download_mask = 1u << static_cast<unsigned>(prooftray::DownloadState::kDownloaded);
  filter.captured_at_ms = {1700000000000, 1700000000000};
  filter.size_bytes = {25000000, 25000000};
  filter.aperture = {2.8, 2.8};
  filter.exposure_seconds = {0.004, 0.004};
  filter.focal_length_mm = {50.0, 50.0};
  filter.orientation = prooftray::Orientation::kLandscape;
  filter.camera = "nikon";
  filter.lens = "24-70";
  filter.required_tags = {"travel", "selected"};
  Expect(QueryCatalog({photo}, filter).matched_count == 1, "Inclusive bounds and all matching filter fields must pass");
  filter.required_tags.push_back("missing");
  Expect(QueryCatalog({photo}, filter).groups.empty(), "Required tags use ALL semantics");
}

void FilenameSearchDoesNotDependOnExif() {
  auto photo = Photo("file", "DSC_1000.PNG", 1000, MediaFormat::kPng);
  photo.camera.clear();
  photo.lens.clear();
  photo.metadata_complete = false;
  CatalogFilter filter;
  filter.text = "DSC_9999";
  const auto excluded = QueryCatalog({photo}, filter);
  Expect(excluded.groups.empty() && excluded.pending_metadata_count == 0, "Filename mismatch must not become unknown EXIF");
  filter.text = "dsc_1000";
  Expect(QueryCatalog({photo}, filter).matched_count == 1, "Filename matching must remain case-insensitive without EXIF");
}

void PairingUsesFullCatalogAndIdentity() {
  auto raw = Photo("raw", "DSC_1000.NEF", 1000, MediaFormat::kRaw);
  auto jpeg = Photo("jpeg", "DSC_1000.JPG", 1000);
  CatalogFilter filter;
  auto result = QueryCatalog({raw, jpeg}, filter, false);
  Expect(result.groups.size() == 2, "RAW/JPEG representations must stay separate until the user groups them");
  filter.paired_raw_jpeg_only = true;
  filter.format_mask = 1u << static_cast<unsigned>(MediaFormat::kRaw);
  result = QueryCatalog({raw, jpeg}, filter, false);
  Expect(result.matched_count == 1 && result.groups[0].members[0] == 0, "RAW-only filtering must still find the JPEG partner");
  jpeg.source_id = "different-camera";
  Expect(QueryCatalog({raw, jpeg}, filter, false).groups.empty(), "Same filenames on another source must never pair");
  raw.camera.clear();
  result = QueryCatalog({raw}, filter, false);
  Expect(result.pending_metadata_count == 1, "Unknown pair identity must not be treated as known unpaired");
}

void ManualStacksAndStableOrdering() {
  auto first = Photo("a", "DSC_1000.JPG", 1000);
  auto second = Photo("b", "DSC_1001.JPG", 2000);
  auto unknown = Photo("c", "DSC_1002.JPG", 3000);
  unknown.captured_at_ms.reset();
  auto result = QueryCatalog({unknown, second, first}, {}, true);
  Expect(result.groups.size() == 3, "Close capture times must not create a group");
  Expect(result.groups[0].members[0] == 1 && result.groups[1].members[0] == 2, "Newest sorting must remain stable");
  Expect(result.groups[2].members[0] == 0, "Unknown timestamp must sort last");
  first.stack_id = "stack-a";
  second.stack_id = "stack-b";
  Expect(QueryCatalog({first, second}, {}, true).groups.size() == 2, "Different user groups must remain separate");
  second.stack_id = first.stack_id;
  result = QueryCatalog({first, second}, {}, true);
  Expect(result.groups.size() == 1 && !result.groups[0].inferred_burst, "Only explicit user groups may collapse");
  Expect(QueryCatalog({first, second}, {}, false).groups.size() == 2, "Disabling folding must reveal every member");
  second.camera.clear();
  second.source_id = "another-source";
  second.captured_at_ms.reset();
  Expect(QueryCatalog({first, second}, {}, true).groups.size() == 1, "A manual group does not require matching camera metadata");
  first.stack_id.clear();
  second.stack_id.clear();
  first.name = "DSC_1000_OpenBridge_2M.jpg";
  second.name = "DSC_1000.NEF";
  second.format = MediaFormat::kRaw;
  second.captured_at_ms = first.captured_at_ms;
  Expect(QueryCatalog({first, second}, {}, true).groups.size() == 2, "BLE copies and later RAW imports must not become inferred bursts");
}

void InvalidInputsAreRejected() {
  const auto photo = Photo("same", "DSC_1000.JPG", 1000);
  bool duplicate_rejected = false;
  try {
    QueryCatalog({photo, photo}, {});
  } catch (const std::invalid_argument&) {
    duplicate_rejected = true;
  }
  Expect(duplicate_rejected, "Duplicate IDs must fail instead of hiding files");
  CatalogFilter filter;
  filter.iso = {1600, 800};
  bool range_rejected = false;
  try {
    QueryCatalog({photo}, filter);
  } catch (const std::invalid_argument&) {
    range_rejected = true;
  }
  Expect(range_rejected, "Reversed filter ranges must fail explicitly");
}

}  // namespace

int main() {
  try {
    PendingMetadataDoesNotOverrideExclusion();
    CombinedRangesAndTags();
    FilenameSearchDoesNotDependOnExif();
    PairingUsesFullCatalogAndIdentity();
    ManualStacksAndStableOrdering();
    InvalidInputsAreRejected();
    std::cout << "Catalog tests passed\n";
    return 0;
  } catch (const std::exception& error) {
    std::cerr << error.what() << '\n';
    return 1;
  }
}
