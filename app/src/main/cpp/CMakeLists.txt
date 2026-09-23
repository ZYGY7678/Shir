cmake_minimum_required(VERSION 3.10.2)
project(shir_native)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(DEMUCS_ROOT ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/demucs.cpp)
set(NYQUIST_ROOT ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/libnyquist)

set(LIBNYQUIST_BUILD_EXAMPLE OFF CACHE BOOL "Disable libnyquist example" FORCE)
add_subdirectory("${NYQUIST_ROOT}" "${CMAKE_BINARY_DIR}/libnyquist" EXCLUDE_FROM_ALL)

file(GLOB DEMUCS_SOURCES
    "${DEMUCS_ROOT}/src/*.cpp"
    "${DEMUCS_ROOT}/src/*.c"
)

add_library(shir-native SHARED
    native_separator.cpp
    ${DEMUCS_SOURCES}
)

target_include_directories(shir-native PRIVATE
    "${DEMUCS_ROOT}/src"
    "${DEMUCS_ROOT}/vendor/eigen"
    "${NYQUIST_ROOT}/include"
)

# Keep inference single-threaded to avoid oversubscription on weak phones,
# but keep Eigen SIMD enabled. The old DONT_VECTORIZE flag forced scalar math.
target_compile_definitions(shir-native PRIVATE
    EIGEN_DONT_PARALLELIZE
)

target_compile_options(shir-native PRIVATE
    -O3
    -ffast-math
    -fomit-frame-pointer
    -fvisibility=hidden
    -fexceptions
    -frtti
)

if(ANDROID_ABI STREQUAL "armeabi-v7a")
    target_compile_options(shir-native PRIVATE -march=armv7-a -mfpu=neon -mfloat-abi=softfp)
elseif(ANDROID_ABI STREQUAL "x86")
    target_compile_options(shir-native PRIVATE -msse2 -mfpmath=sse)
endif()

find_library(log-lib log)
target_link_libraries(shir-native
    libnyquist
    ${log-lib}
)
