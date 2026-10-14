cmake_minimum_required(VERSION 3.22)
project(wlbridge C CXX)
add_library(wlbridge SHARED wlbridge.c)
# Pixel copies and compositing run per frame; debug builds would otherwise use -O0.
target_compile_options(wlbridge PRIVATE -O3 -Wall)
target_link_libraries(wlbridge android log)

# whisper.cpp transcribes OmaWatch voice on the phone (omawatch D14). Pinned release and hash.
include(FetchContent)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
# ggml's own thread pool; no libomp to ship.
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
FetchContent_Declare(whisper
    URL https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v1.9.5.tar.gz
    URL_HASH SHA256=ff1a9053feb509ff9d7729703355541ae9690073a6b1c40eb692c962e0dc1720)
FetchContent_MakeAvailable(whisper)
# The side-by-side .mod build is a debug build; whisper at -O0 is too slow to talk to.
foreach(target whisper ggml ggml-base ggml-cpu)
    if(TARGET ${target})
        target_compile_options(${target} PRIVATE -O3)
    endif()
endforeach()

add_library(omawhisper SHARED omawhisper.cpp)
target_compile_options(omawhisper PRIVATE -O3 -Wall -fvisibility=hidden)
target_link_options(omawhisper PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
target_link_libraries(omawhisper whisper log)
