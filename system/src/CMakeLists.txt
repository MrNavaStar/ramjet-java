file(GLOB_RECURSE SOURCES CONFIGURE_DEPENDS
        "${CMAKE_CURRENT_SOURCE_DIR}/*.c"
)

add_executable(system ${SOURCES})

find_package(LibArchive REQUIRED)
target_link_libraries(system PRIVATE ${LIBARCHIVE_STATIC})
target_link_options(system PRIVATE -static)