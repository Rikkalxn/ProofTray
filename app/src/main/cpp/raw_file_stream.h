#ifndef PROOFTRAY_RAW_FILE_STREAM_H_
#define PROOFTRAY_RAW_FILE_STREAM_H_

#include <libraw/libraw.h>
#include <fcntl.h>
#include <unistd.h>

#include <cerrno>
#include <cstdio>
#include <stdexcept>
#include <system_error>

namespace prooftray {

// SAF grants access to an open descriptor, not necessarily to its path. Reuse LibRaw's buffered FILE I/O without reopening that path.
class RawFileStream final : public LibRaw_bigfile_datastream {
 public:
  explicit RawFileStream(int descriptor) : LibRaw_bigfile_datastream("") {
    if (descriptor < 0) throw std::invalid_argument("Invalid RAW file descriptor");
    const int owned = fcntl(descriptor, F_DUPFD_CLOEXEC, 0);
    if (owned < 0) throw std::system_error(errno, std::generic_category(), "Duplicate RAW descriptor");
    f = fdopen(owned, "rb");
    if (!f) {
      const int failure = errno;
      close(owned);
      throw std::system_error(failure, std::generic_category(), "Open RAW descriptor stream");
    }
    // The base destructor closes only our duplicate, including when the remaining constructor steps throw.
    if (fseeko(f, 0, SEEK_END) != 0) throw std::system_error(errno, std::generic_category(), "Seek RAW descriptor");
    _fsize = ftello(f);
    if (_fsize < 0) throw std::system_error(errno, std::generic_category(), "Read RAW descriptor size");
    if (_fsize == 0 || _fsize > 512LL * 1024 * 1024) throw std::runtime_error("RAW source is empty or exceeds 512 MiB");
    if (fseeko(f, 0, SEEK_SET) != 0) throw std::system_error(errno, std::generic_category(), "Rewind RAW descriptor");
  }

  RawFileStream(const RawFileStream&) = delete;
  RawFileStream& operator=(const RawFileStream&) = delete;
};

}  // namespace prooftray
#endif  // PROOFTRAY_RAW_FILE_STREAM_H_
