cmake_minimum_required(VERSION 4.2)
include(ExternalProject)

project(system C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_EXE_LINKER_FLAGS "-static")
set(CMAKE_SHARED_LINKER_FLAGS "-static")

set(LIBARCHIVE_BUILD
        ${CMAKE_BINARY_DIR}/libarchive-build
)

ExternalProject_Add(libarchive_build
        SOURCE_DIR ${CMAKE_SOURCE_DIR}/libraries/libarchive-3.8.9
        BINARY_DIR ${LIBARCHIVE_BUILD}

        CMAKE_ARGS
        -DCMAKE_BUILD_TYPE=Release
        -DCMAKE_C_COMPILER=musl-gcc
        -DBUILD_SHARED_LIBS=OFF
        -DENABLE_TEST=OFF
        -DENABLE_TAR=OFF
        -DENABLE_CPIO=OFF
        -DENABLE_CAT=OFF

        -DENABLE_ZLIB=ON
        -DENABLE_ZSTD=ON
        -DENABLE_BZip2=OFF
        -DENABLE_LZMA=OFF
        -DENABLE_LZ4=OFF
        -DENABLE_LIBB2=OFF
)

add_library(archive STATIC IMPORTED GLOBAL)

set_target_properties(archive PROPERTIES
        IMPORTED_LOCATION
        ${LIBARCHIVE_BUILD}/libarchive/libarchive.a
        INTERFACE_INCLUDE_DIRECTORIES
        ${CMAKE_SOURCE_DIR}/libraries/libarchive-3.8.9/libarchive
)

add_dependencies(archive libarchive_build)

add_subdirectory(src)
