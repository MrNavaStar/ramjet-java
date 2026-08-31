cmake_minimum_required(VERSION 4.2)

project(system C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_FIND_PACKAGE_PREFER_CONFIG ON)

file(GLOB_RECURSE SOURCES CONFIGURE_DEPENDS
    "${CMAKE_CURRENT_SOURCE_DIR}/src/*.c"
)

add_executable(system ${SOURCES})

target_compile_options(system PRIVATE
    -ffunction-sections
    -fdata-sections
)

target_link_options(system PRIVATE
    -static
    -Wl,--gc-sections
)

find_package(ZLIB REQUIRED)
if(NOT TARGET ZLIB::ZLIB AND TARGET ZLIB::ZLIBSTATIC)
    add_library(ZLIB::ZLIB ALIAS ZLIB::ZLIBSTATIC)
endif()

find_package(LibArchive REQUIRED)
target_link_libraries(system PRIVATE LibArchive::LibArchive)