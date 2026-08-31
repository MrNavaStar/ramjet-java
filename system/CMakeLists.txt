cmake_minimum_required(VERSION 4.2)

project(system C)

set(CMAKE_C_STANDARD 11)

find_package(LibArchive CONFIG REQUIRED)

file(GLOB_RECURSE SOURCES CONFIGURE_DEPENDS
    "${CMAKE_CURRENT_SOURCE_DIR}/*.c"
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

target_link_libraries(system PRIVATE
    LibArchive::LibArchive
)