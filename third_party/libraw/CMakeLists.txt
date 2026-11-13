# LibRaw's pinned submodule; source list from its unmodified Makefile.am.
if(NOT EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/upstream/libraw/libraw.h")
    message(FATAL_ERROR "LibRaw sources are missing. Run git submodule update --init --recursive from the repository root.")
endif()

set(LIBRAW_SOURCES
    upstream/src/libraw_c_api.cpp
    upstream/src/libraw_datastream.cpp
    upstream/src/decoders/canon_600.cpp
    upstream/src/decoders/crx.cpp
    upstream/src/decoders/pana8.cpp
    upstream/src/decoders/decoders_dcraw.cpp
    upstream/src/decoders/sonycc.cpp
    upstream/src/decoders/sony_arw6.cpp
    upstream/src/decompressors/losslessjpeg.cpp
    upstream/src/decoders/decoders_libraw_dcrdefs.cpp
    upstream/src/decoders/olympus14.cpp
    upstream/src/decoders/nikonhe.cpp
    upstream/src/decoders/decoders_libraw.cpp
    upstream/src/decoders/dng.cpp
    upstream/src/decoders/fp_dng.cpp
    upstream/src/decoders/fuji_compressed.cpp
    upstream/src/decoders/generic.cpp
    upstream/src/decoders/kodak_decoders.cpp
    upstream/src/decoders/load_mfbacks.cpp
    upstream/src/decoders/smal.cpp
    upstream/src/decoders/unpack_thumb.cpp
    upstream/src/decoders/unpack.cpp
    upstream/src/demosaic/aahd_demosaic.cpp
    upstream/src/demosaic/ahd_demosaic.cpp
    upstream/src/demosaic/dcb_demosaic.cpp
    upstream/src/demosaic/dht_demosaic.cpp
    upstream/src/demosaic/misc_demosaic.cpp
    upstream/src/demosaic/xtrans_demosaic.cpp
    upstream/src/integration/dngsdk_glue.cpp
    upstream/src/integration/rawspeed_glue.cpp
    upstream/src/metadata/adobepano.cpp
    upstream/src/metadata/canon.cpp
    upstream/src/metadata/ciff.cpp
    upstream/src/metadata/cr3_parser.cpp
    upstream/src/metadata/epson.cpp
    upstream/src/metadata/exif_gps.cpp
    upstream/src/metadata/fuji.cpp
    upstream/src/metadata/identify_tools.cpp
    upstream/src/metadata/identify.cpp
    upstream/src/metadata/kodak.cpp
    upstream/src/metadata/leica.cpp
    upstream/src/metadata/makernotes.cpp
    upstream/src/metadata/mediumformat.cpp
    upstream/src/metadata/minolta.cpp
    upstream/src/metadata/misc_parsers.cpp
    upstream/src/metadata/nikon.cpp
    upstream/src/metadata/normalize_model.cpp
    upstream/src/metadata/olympus.cpp
    upstream/src/metadata/hasselblad_model.cpp
    upstream/src/metadata/p1.cpp
    upstream/src/metadata/pentax.cpp
    upstream/src/metadata/samsung.cpp
    upstream/src/metadata/sony.cpp
    upstream/src/metadata/tiff.cpp
    upstream/src/postprocessing/aspect_ratio.cpp
    upstream/src/postprocessing/dcraw_process.cpp
    upstream/src/postprocessing/mem_image.cpp
    upstream/src/postprocessing/postprocessing_aux.cpp
    upstream/src/postprocessing/postprocessing_utils_dcrdefs.cpp
    upstream/src/postprocessing/postprocessing_utils.cpp
    upstream/src/preprocessing/ext_preprocess.cpp
    upstream/src/preprocessing/raw2image.cpp
    upstream/src/preprocessing/subtract_black.cpp
    upstream/src/tables/cameralist.cpp
    upstream/src/tables/colorconst.cpp
    upstream/src/tables/colordata.cpp
    upstream/src/tables/wblists.cpp
    upstream/src/utils/curves.cpp
    upstream/src/utils/decoder_info.cpp
    upstream/src/utils/init_close_utils.cpp
    upstream/src/utils/open.cpp
    upstream/src/utils/phaseone_processing.cpp
    upstream/src/utils/read_utils.cpp
    upstream/src/utils/thumb_utils.cpp
    upstream/src/utils/utils_dcraw.cpp
    upstream/src/utils/utils_libraw.cpp
    upstream/src/write/apply_profile.cpp
    upstream/src/write/file_write.cpp
    upstream/src/write/tiff_writer.cpp
    upstream/src/x3f/x3f_parse_process.cpp
    upstream/src/x3f/x3f_utils_patched.cpp
)

add_library(openbridge_libraw SHARED ${LIBRAW_SOURCES})
target_include_directories(openbridge_libraw PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/upstream")
target_compile_features(openbridge_libraw PUBLIC cxx_std_11)
target_compile_definitions(openbridge_libraw PRIVATE LIBRAW_BUILDLIB USE_ZLIB)
if(MSVC)
    target_compile_options(openbridge_libraw PRIVATE /O2)
else()
    target_compile_options(openbridge_libraw PRIVATE -O2)
endif()
if(WIN32)
    target_compile_definitions(openbridge_libraw PRIVATE _USE_MATH_DEFINES=)
    find_package(ZLIB REQUIRED)
    target_link_libraries(openbridge_libraw PRIVATE ZLIB::ZLIB ws2_32)
else()
    target_link_libraries(openbridge_libraw PRIVATE z m)
endif()
set_target_properties(openbridge_libraw PROPERTIES CXX_EXTENSIONS OFF POSITION_INDEPENDENT_CODE ON)
