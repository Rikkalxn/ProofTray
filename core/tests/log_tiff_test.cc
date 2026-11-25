#include "prooftray/log_tiff.h"

#include <tiffio.h>

#include <array>
#include <cmath>
#include <cstdio>
#include <iostream>
#include <limits>
#include <memory>
#include <set>
#include <stdexcept>
#include <string>
#include <vector>

#ifdef _WIN32
#include <io.h>
#endif

namespace {

void Check(bool value, const char* message) { if (!value) throw std::runtime_error(message); }
template <typename Work> void Reject(Work work) {
  bool rejected = false;
  try { work(); } catch (const std::exception&) { rejected = true; }
  Check(rejected, "Expected Log TIFF rejection");
}

using File = std::unique_ptr<FILE, decltype(&std::fclose)>;
File Temporary() {
  File file(std::tmpfile(), std::fclose);
  Check(file != nullptr, "Cannot create temporary TIFF");
  return file;
}
int Descriptor(FILE* file) {
#ifdef _WIN32
  return _fileno(file);
#else
  return fileno(file);
#endif
}

void ReadBack(prooftray::LutEncoding encoding, double gray_code) {
  auto file = Temporary();
  constexpr int width = 128, height = 2;
  prooftray::WriteLogTiff(Descriptor(file.get()), width, height, {encoding}, [](int x, int y) -> prooftray::Rgb {
    const float value = y == 0 ? .081f : .081f + x * .00002f;
    return {value, value, value};
  }, [] { return false; });
  Check(std::fseek(file.get(), 0, SEEK_SET) == 0, "Writer closed its borrowed descriptor");
  std::unique_ptr<TIFF, decltype(&TIFFCleanup)> tiff(TIFFFdOpen(Descriptor(file.get()), "readback", "r"), TIFFCleanup);
  Check(tiff != nullptr, "Cannot read generated TIFF");
  uint32_t actual_width = 0, actual_height = 0;
  uint16_t bits = 0, samples = 0, format = 0, orientation = 0;
  Check(TIFFGetField(tiff.get(), TIFFTAG_IMAGEWIDTH, &actual_width) && actual_width == width, "Wrong TIFF width");
  Check(TIFFGetField(tiff.get(), TIFFTAG_IMAGELENGTH, &actual_height) && actual_height == height, "Wrong TIFF height");
  Check(TIFFGetField(tiff.get(), TIFFTAG_BITSPERSAMPLE, &bits) && bits == 16, "Log output must be 16-bit");
  Check(TIFFGetField(tiff.get(), TIFFTAG_SAMPLESPERPIXEL, &samples) && samples == 3, "Expected RGB, without alpha");
  Check(TIFFGetField(tiff.get(), TIFFTAG_SAMPLEFORMAT, &format) && format == SAMPLEFORMAT_UINT, "Expected unsigned samples");
  Check(TIFFGetField(tiff.get(), TIFFTAG_ORIENTATION, &orientation) && orientation == ORIENTATION_TOPLEFT, "Wrong orientation");
  uint32_t profile_length = 0;
  void* profile = nullptr;
  Check(!TIFFGetField(tiff.get(), TIFFTAG_ICCPROFILE, &profile_length, &profile), "Log must not carry a display ICC profile");
  uint16_t *red = nullptr, *green = nullptr, *blue = nullptr;
  Check(!TIFFGetField(tiff.get(), TIFFTAG_TRANSFERFUNCTION, &red, &green, &blue), "Unexpected display transfer function");
  uint64_t exif = 0;
  Check(!TIFFGetField(tiff.get(), TIFFTAG_EXIFIFD, &exif), "Unexpected display EXIF IFD");
  char* description = nullptr;
  Check(TIFFGetField(tiff.get(), TIFFTAG_IMAGEDESCRIPTION, &description), "Missing Log declaration");
  const std::string metadata(description);
  Check(metadata.find("\"ProofTrayLog\":1") != std::string::npos &&
        metadata.find("\"referenceCalibrated\":false") != std::string::npos &&
        metadata.find("\"range\":\"full\"") != std::string::npos, "Incomplete Log declaration");
  std::vector<uint16_t> row(width * 3);
  Check(TIFFReadScanline(tiff.get(), row.data(), 0, 0) == 1, "Cannot decode gray row");
  const auto expected = std::lround(gray_code / 1023. * 65535.);
  for (const auto value : row) Check(std::abs(long(value) - expected) <= 1, "Gray code was rescaled or display encoded");
  Check(TIFFReadScanline(tiff.get(), row.data(), 1, 0) == 1, "Cannot decode fine ramp");
  std::set<uint16_t> levels;
  for (int x = 0; x < width; ++x) {
    Check(row[x * 3] == row[x * 3 + 1] && row[x * 3] == row[x * 3 + 2], "Neutral ramp became tinted");
    if (x > 0) Check(row[x * 3] >= row[(x - 1) * 3], "Ramp is not monotonic");
    levels.insert(row[x * 3]);
  }
  Check(levels.size() > 64, "Log values were quantized to 8/10 bits before TIFF writing");
}

void CancellationAndValidation() {
  auto file = Temporary();
  int polls = 0, pixels = 0;
  Reject([&] { prooftray::WriteLogTiff(Descriptor(file.get()), 16, 4, {}, [&](int, int) -> prooftray::Rgb {
    ++pixels;
    return {.081f, .081f, .081f};
  }, [&] { return ++polls == 3; }); });
  Check(pixels == 16, "Cancellation must be observed between rows");
  Check(std::fseek(file.get(), 0, SEEK_SET) == 0, "Cancellation closed the borrowed file");
  const auto no_pixel = [](int, int) -> prooftray::Rgb { throw std::runtime_error("Unexpected pixel read"); };
  Reject([&] { prooftray::WriteLogTiff(-1, 1, 1, {}, no_pixel, [] { return false; }); });
  Reject([&] { prooftray::WriteLogTiff(Descriptor(file.get()), 1, 1, {prooftray::LutEncoding::Direct}, no_pixel, [] { return false; }); });
  Reject([&] { prooftray::WriteLogTiff(Descriptor(file.get()), 1, 1, {prooftray::LutEncoding::Linear709}, no_pixel, [] { return false; }); });
  auto invalid = prooftray::LogExportSettings{};
  invalid.gain_ev = std::numeric_limits<float>::quiet_NaN();
  Reject([&] { prooftray::WriteLogTiff(Descriptor(file.get()), 1, 1, invalid, no_pixel, [] { return false; }); });
}

}  // namespace

int main() {
  try {
    ReadBack(prooftray::LutEncoding::SLog3Cine, 420.);
    ReadBack(prooftray::LutEncoding::NLog, 372.0321288231996);
    CancellationAndValidation();
    std::cout << "Log TIFF tests passed\n";
    return 0;
  } catch (const std::exception& error) { std::cerr << error.what() << '\n'; return 1; }
}
