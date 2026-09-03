cmake_minimum_required(VERSION 4.2)

project(system C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_FIND_PACKAGE_PREFER_CONFIG ON)

file(GLOB_RECURSE SOURCES CONFIGURE_DEPENDS
    "${CMAKE_CURRENT_SOURCE_DIR}/src/*.c"
)

add_executable(system ${SOURCES})

target_compile_options(system PRIVATE
    -O3
    -Os
    -ffunction-sections
    -fdata-sections
    -fno-unwind-tables
    -fno-asynchronous-unwind-tables
)

target_link_options(system PRIVATE
    -static
    -s
    -Wl,--gc-sections
    -flto
)

find_package(ZLIB REQUIRED)
if(NOT TARGET ZLIB::ZLIB AND TARGET ZLIB::ZLIBSTATIC)
    add_library(ZLIB::ZLIB ALIAS ZLIB::ZLIBSTATIC)
endif()

find_package(LibArchive REQUIRED)
target_link_libraries(system PRIVATE LibArchive::LibArchive)

find_package(yyjson CONFIG REQUIRED)
target_link_libraries(system PRIVATE yyjson::yyjson)