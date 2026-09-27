add_library(colorado STATIC
        colorado/cert.cpp
        colorado/colorado.cpp
        colorado/utils.cpp
)
target_include_directories(colorado PUBLIC colorado/)
target_compile_options(colorado PUBLIC -Oz -fvisibility=hidden)
target_include_directories(colorado PUBLIC integrity)

if (CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_compile_definitions(colorado PRIVATE NEKOGRAM_DEBUG_BUILD=1)
endif()
