cmake_minimum_required(VERSION 3.10.2)
project(shir_native)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(DEMUCS_ROOT ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/demucs.cpp)
set(NYQUIST_ROOT ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/libnyquist)

file(GLOB DEMUCS_SOURCES "${DEMUCS_ROOT}/src/*.cpp")
file(GLOB NYQUIST_SOURCES "${NYQUIST_ROOT}/src/*.cpp")

add_library(shir-native SHARED
    native_separator.cpp
    ${DEMUCS_SOURCES}
    ${NYQUIST_SOURCES}
)

target_include_directories(shir-native PRIVATE
    "${DEMUCS_ROOT}/src"
    "${DEMUCS_ROOT}/vendor/eigen"
    "${NYQUIST_ROOT}/include"
    "${NYQUIST_ROOT}/src"
)

target_compile_definitions(shir-native PRIVATE
    EIGEN_DONT_PARALLELIZE
    EIGEN_DONT_VECTORIZE
)

target_compile_options(shir-native PRIVATE
    -O3
    -ffast-math
    -fvisibility=hidden
)

find_library(log-lib log)
target_link_libraries(shir-native ${log-lib})
