# Keep the upstream project unmodified and the selected codec set local to this directory.
if(NOT EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/upstream/libtiff/tiffio.h")
    message(FATAL_ERROR "libtiff sources are missing. Run git submodule update --init --recursive from the repository root.")
endif()

set(CMAKE_POLICY_DEFAULT_CMP0077 NEW)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(tiff-static ON)
set(zlib ON)
find_package(ZLIB REQUIRED)
foreach(feature tiff-tools tiff-tests tiff-contrib tiff-docs tiff-install tiff-cxx tiff-opengl
        jpeg old-jpeg jbig lerc lzma zstd webp libdeflate pixarlog ccitt packbits lzw thunder next logluv mdi
        extra-warnings fatal-warnings)
    set(${feature} OFF)
endforeach()
add_subdirectory(upstream)
