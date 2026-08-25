file(GLOB_RECURSE SOURCES CONFIGURE_DEPENDS
        "${CMAKE_CURRENT_SOURCE_DIR}/*.c"
)

add_executable(system ${SOURCES})
target_link_libraries(system PRIVATE archive)