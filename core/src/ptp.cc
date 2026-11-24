#include "prooftray/ptp.h"

#include <array>
#include <cstdio>
#include <stdexcept>

namespace prooftray::ptp {
namespace {

class Reader {
 public:
  explicit Reader(std::span<const uint8_t> data) : data_(data) {}
  uint32_t Number(size_t count) {
    if (count > 4 || count > data_.size() - position_) throw std::invalid_argument("Truncated PTP dataset");
    uint32_t result = 0;
    for (size_t index = 0; index < count; ++index) result |= uint32_t{data_[position_++]} << (index * 8);
    return result;
  }
  std::u16string String() {
    const auto count = Number(1);
    std::u16string result;
    for (uint32_t index = 0; index < count; ++index) {
      const auto character = static_cast<char16_t>(Number(2));
      if (index + 1 == count) {
        if (character != 0) throw std::invalid_argument("PTP string has no terminator");
      } else {
        result.push_back(character);
      }
    }
    return result;
  }
  std::vector<uint32_t> Array(size_t width) {
    const auto count = Number(4);
    if (count > 1000000 || count > (data_.size() - position_) / width) throw std::invalid_argument("Invalid PTP array length");
    std::vector<uint32_t> values;
    values.reserve(count);
    for (uint32_t index = 0; index < count; ++index) values.push_back(Number(width));
    return values;
  }

 private:
  std::span<const uint8_t> data_;
  size_t position_ = 0;
};

void Put(std::vector<uint8_t>& output, uint32_t value, size_t count) {
  for (size_t index = 0; index < count; ++index) output.push_back(static_cast<uint8_t>(value >> (index * 8)));
}

std::string Quote(const std::u16string& input) {
  // ASCII JSON also avoids modified-UTF-8 ambiguity at the JNI boundary.
  std::string result = "\"";
  for (char16_t character : input) {
    if (character >= 32 && character < 127 && character != '"' && character != '\\') {
      result.push_back(static_cast<char>(character));
    } else {
      std::array<char, 7> escaped{};
      std::snprintf(escaped.data(), escaped.size(), "\\u%04x", static_cast<unsigned>(character));
      result += escaped.data();
    }
  }
  return result + '"';
}

}  // namespace

std::vector<uint8_t> Initialize(std::span<const uint8_t> guid, const std::u16string& client_name) {
  if (guid.size() != 16 || client_name.size() > 127) throw std::invalid_argument("Invalid PTP/IP client identity");
  std::vector<uint8_t> output;
  Put(output, static_cast<uint32_t>(30 + client_name.size() * 2), 4);
  Put(output, 1, 4);
  output.insert(output.end(), guid.begin(), guid.end());
  for (char16_t character : client_name) Put(output, character, 2);
  Put(output, 0, 2);
  Put(output, 0x00010000, 4);
  return output;
}

std::vector<uint8_t> Command(uint16_t operation, uint32_t transaction, std::span<const uint32_t> parameters, bool /*receive_data*/, bool send_data) {
  if (parameters.size() > 5) throw std::invalid_argument("PTP supports at most five parameters");
  std::vector<uint8_t> output;
  Put(output, static_cast<uint32_t>(18 + parameters.size() * 4), 4);
  Put(output, 6, 4);
  // PTP/IP DataOut is 1 for no outgoing data, including camera-to-host transfers.
  Put(output, send_data ? 2 : 1, 4);
  Put(output, operation, 2);
  Put(output, transaction, 4);
  for (uint32_t parameter : parameters) Put(output, parameter, 4);
  return output;
}

std::vector<uint32_t> Handles(std::span<const uint8_t> data) { return Reader(data).Array(4); }

std::string DeviceInfoJson(std::span<const uint8_t> data) {
  Reader reader(data);
  reader.Number(2);
  const uint32_t vendor = reader.Number(4);
  reader.Number(2);
  reader.String();
  reader.Number(2);
  const auto operations = reader.Array(2);
  for (int index = 0; index < 4; ++index) reader.Array(2);
  const auto manufacturer = reader.String();
  const auto model = reader.String();
  const auto version = reader.String();
  const auto serial = reader.String();
  std::string json = "{\"vendor\":" + std::to_string(vendor) + ",\"manufacturer\":" + Quote(manufacturer);
  json += ",\"model\":" + Quote(model) + ",\"version\":" + Quote(version) + ",\"serial\":" + Quote(serial) + ",\"operations\":[";
  for (size_t index = 0; index < operations.size(); ++index) {
    if (index) json += ',';
    json += std::to_string(operations[index]);
  }
  return json + "]}";
}

std::string ObjectInfoJson(std::span<const uint8_t> data) {
  Reader reader(data);
  const auto storage = reader.Number(4);
  const auto format = reader.Number(2);
  const auto protection = reader.Number(2);
  const auto size = reader.Number(4);
  reader.Number(2);
  reader.Number(4);
  reader.Number(4);
  reader.Number(4);
  const auto width = reader.Number(4);
  const auto height = reader.Number(4);
  reader.Number(4);
  const auto parent = reader.Number(4);
  reader.Number(2);
  reader.Number(4);
  const auto sequence = reader.Number(4);
  const auto name = reader.String();
  const auto captured = reader.String();
  const auto modified = reader.String();
  std::string json = "{\"storage\":" + std::to_string(storage) + ",\"format\":" + std::to_string(format);
  json += ",\"protection\":" + std::to_string(protection) + ",\"size\":" + std::to_string(size);
  json += ",\"width\":" + std::to_string(width) + ",\"height\":" + std::to_string(height);
  json += ",\"parent\":" + std::to_string(parent) + ",\"sequence\":" + std::to_string(sequence);
  return json + ",\"name\":" + Quote(name) + ",\"captured\":" + Quote(captured) + ",\"modified\":" + Quote(modified) + '}';
}

}  // namespace prooftray::ptp
