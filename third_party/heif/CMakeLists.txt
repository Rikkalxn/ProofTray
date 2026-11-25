# Keep the portable codec and its HEVC backend independently replaceable.
foreach(codec libde265 libheif)
    if(NOT EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/${codec}/CMakeLists.txt")
        message(FATAL_ERROR "Missing ${codec}. Run git submodule update --init --recursive from the repository root.")
    endif()
endforeach()

set(BUILD_SHARED_LIBS ON)
set(CMAKE_POLICY_DEFAULT_CMP0077 NEW)
if(CMAKE_SYSTEM_NAME STREQUAL "iOS")
    set(BUILD_FRAMEWORK ON)
endif()
if(ANDROID)
    add_compile_options("$<$<CONFIG:Debug>:-O2>")
endif()

# ENABLE_DECODER controls the dec265 command-line tool. The library always includes the decoder.
foreach(feature ENABLE_DECODER ENABLE_ENCODER ENABLE_SDL ENABLE_SHERLOCK265 ENABLE_INTERNAL_DEVELOPMENT_TOOLS WITH_FUZZERS)
    set(${feature} OFF)
endforeach()
add_subdirectory(libde265 EXCLUDE_FROM_ALL)
target_include_directories(de265 INTERFACE
    "$<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/libde265>" "$<BUILD_INTERFACE:${CMAKE_CURRENT_BINARY_DIR}/libde265>")

list(PREPEND CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/cmake")
set(CMAKE_FIND_PACKAGE_PREFER_CONFIG OFF)
set(WITH_LIBDE265 ON)
foreach(feature WITH_LIBDE265_PLUGIN ENABLE_PLUGIN_LOADING WITH_X265 WITH_KVAZAAR WITH_UVG266 WITH_VVDEC WITH_VVENC
        WITH_X264 WITH_OpenH264_DECODER WITH_DAV1D WITH_AOM_DECODER WITH_AOM_ENCODER WITH_SvtEnc WITH_RAV1E
        WITH_JPEG_DECODER WITH_JPEG_ENCODER WITH_OpenJPEG_DECODER WITH_OpenJPEG_ENCODER WITH_OPENJPH_ENCODER WITH_FFMPEG_DECODER
        WITH_UNCOMPRESSED_CODEC WITH_WEBCODECS WITH_LIBSHARPYUV WITH_HEADER_COMPRESSION WITH_GDK_PIXBUF WITH_EXAMPLES
        BUILD_DOCUMENTATION BUILD_DEVELOPMENT_TOOLS BUILD_TESTING)
    set(${feature} OFF)
endforeach()
add_subdirectory(libheif EXCLUDE_FROM_ALL)

if(ANDROID)
    # Android packages unversioned .so files. Keep their SONAMEs distinct from device and third-party codecs.
    foreach(codec heif de265)
        set_property(TARGET ${codec} PROPERTY VERSION)
        set_property(TARGET ${codec} PROPERTY SOVERSION)
    endforeach()
    set_target_properties(heif PROPERTIES OUTPUT_NAME prooftray_heif_codec)
    set_target_properties(de265 PROPERTIES OUTPUT_NAME prooftray_de265)
endif()

# Upstream publishes LIBHEIF_EXPORTS to consumers and its build-tree public headers live under libheif/api.
add_library(prooftray_heif_codec INTERFACE)
target_link_libraries(prooftray_heif_codec INTERFACE "$<LINK_ONLY:heif>")
target_include_directories(prooftray_heif_codec INTERFACE "${CMAKE_CURRENT_SOURCE_DIR}/libheif/libheif/api" "${CMAKE_CURRENT_BINARY_DIR}/libheif")
