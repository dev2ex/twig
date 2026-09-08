cmake_minimum_required(VERSION 3.22)
project(twigzstd C CXX)

set(ZSTD ${CMAKE_CURRENT_SOURCE_DIR}/zstd)
file(GLOB ZSTD_SRC ${ZSTD}/common/*.c ${ZSTD}/decompress/*.c)

add_library(twigzstd SHARED zstd_jni.cpp ${ZSTD_SRC})

target_include_directories(twigzstd PRIVATE
    ${ZSTD} ${ZSTD}/common ${ZSTD}/decompress)

# Pure C decompression: disable the x86_64 assembly optimisation (no .S), single-threaded
target_compile_definitions(twigzstd PRIVATE
    ZSTD_DISABLE_ASM
    ZSTD_MULTITHREAD=0
    XXH_NAMESPACE=TWIGZSTD_)


# ★ Reproducible builds: without this the GNU build-id differs between machines.
# The linker computes it before the .so is stripped, over an image that still
# carries absolute source paths in its debug info — so /home/me/... and
# /builds/ci/... hash differently, the strip then removes the paths but keeps the
# id, and two byte-identical libraries end up with 20 differing bytes. Mapping
# both prefixes to "." makes the id depend on content alone.
# Verify: build the same commit at two different paths, then
#   readelf -n <lib>.so | grep 'Build ID'   must match.
target_compile_options(twigzstd PRIVATE -O2 -fvisibility=hidden -Wno-unused-function
    -ffile-prefix-map=${CMAKE_SOURCE_DIR}=.
    -ffile-prefix-map=${CMAKE_BINARY_DIR}=.)

target_link_libraries(twigzstd log)

# ★ 16 KB page size (Android 15+). NDK r26 still links with a 4 KB max-page-size,
# and on a device with 16 KB pages the loader rejects such a library outright —
# the feature backed by it does not degrade, it fails to load. NDK r27 flipped
# the default; until this project moves to it, the flag has to be explicit.
# Verify with: readelf -lW <lib>.so | grep LOAD   (Align must be >= 0x4000)
target_link_options(twigzstd PRIVATE "-Wl,-z,max-page-size=16384")

