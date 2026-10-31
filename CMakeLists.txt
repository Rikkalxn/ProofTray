cmake_minimum_required(VERSION 3.22)
project(OpenBridge LANGUAGES CXX)

option(OPENBRIDGE_BUILD_TESTS "Build portable catalog tests" ON)
if(OPENBRIDGE_BUILD_TESTS AND NOT ANDROID)
    enable_testing()
endif()

add_subdirectory(core)

if(ANDROID)
    add_library(openbridge SHARED app/src/main/cpp/catalog_jni.cc app/src/main/cpp/ptp_jni.cc app/src/main/cpp/color_lut_jni.cc)
    target_link_libraries(openbridge PRIVATE openbridge_core jnigraphics)
    target_compile_features(openbridge PRIVATE cxx_std_20)
    set_target_properties(openbridge PROPERTIES CXX_EXTENSIONS OFF)
endif()

