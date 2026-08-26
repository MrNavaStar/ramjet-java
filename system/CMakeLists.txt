cmake_minimum_required(VERSION 4.2)
include(ExternalProject)
include(CheckFunctionExists)

project(system C)

set(CMAKE_C_STANDARD 11)
set(BUILD_SHARED_LIBS OFF)
set(CMAKE_C_COMPILER gcc)
set(CMAKE_EXE_LINKER_FLAGS "-static")
set(CMAKE_SHARED_LINKER_FLAGS "-static")

add_compile_options(
        -ffunction-sections
        -fdata-sections
)

add_link_options(
        -Wl,--gc-sections
)

add_subdirectory(src)
