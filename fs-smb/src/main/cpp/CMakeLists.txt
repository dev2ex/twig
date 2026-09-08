cmake_minimum_required(VERSION 3.22)
project(samba_jni C CXX)

set(LIBSMB2_DIR ${CMAKE_CURRENT_SOURCE_DIR}/libsmb2)

# ── libsmb2 static library ──────────────────────────────────────────────────
# Exclude: krb5-wrapper.c (Kerberos), aes_apple.c (macOS), aes_reference.c
#          (redundant with aes.c which has the optimised implementation)
file(GLOB LIBSMB2_SRCS ${LIBSMB2_DIR}/lib/*.c)
list(FILTER LIBSMB2_SRCS EXCLUDE REGEX
    "(krb5-wrapper|aes_apple)\\.c$")

add_library(smb2_static STATIC ${LIBSMB2_SRCS})

target_include_directories(smb2_static PUBLIC
    ${LIBSMB2_DIR}/include
    ${LIBSMB2_DIR}/include/smb2
    ${CMAKE_CURRENT_SOURCE_DIR}        # for our config.h
)

target_compile_definitions(smb2_static PRIVATE
    HAVE_CONFIG_H
    _GNU_SOURCE
    # Disable Kerberos / GSSAPI
    NO_LIBKRB5
)

target_compile_options(smb2_static PRIVATE
    -O2 -fvisibility=hidden
    -Wno-unused-parameter -Wno-sign-compare
    -Wno-implicit-function-declaration
    -include time.h

    -ffile-prefix-map=${CMAKE_SOURCE_DIR}=.
    -ffile-prefix-map=${CMAKE_BINARY_DIR}=.)

# ── JNI shared library ──────────────────────────────────────────────────────
add_library(samba_jni SHARED samba_jni.cpp)

target_include_directories(samba_jni PRIVATE
    ${LIBSMB2_DIR}/include
    ${LIBSMB2_DIR}/include/smb2
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_compile_definitions(samba_jni PRIVATE HAVE_CONFIG_H _GNU_SOURCE)


# ★ Reproducible builds: without this the GNU build-id differs between machines.
# The linker computes it before the .so is stripped, over an image that still
# carries absolute source paths in its debug info — so /home/me/... and
# /builds/ci/... hash differently, the strip then removes the paths but keeps the
# id, and two byte-identical libraries end up with 20 differing bytes. Mapping
# both prefixes to "." makes the id depend on content alone.
# Verify: build the same commit at two different paths, then
#   readelf -n <lib>.so | grep 'Build ID'   must match.
target_compile_options(samba_jni PRIVATE -O2 -fvisibility=hidden
    -ffile-prefix-map=${CMAKE_SOURCE_DIR}=.
    -ffile-prefix-map=${CMAKE_BINARY_DIR}=.)

target_link_libraries(samba_jni
    smb2_static
    log          # Android __android_log_print
)

# ★ 16 KB page size (Android 15+). NDK r26 still links with a 4 KB max-page-size,
# and on a device with 16 KB pages the loader rejects such a library outright —
# the feature backed by it does not degrade, it fails to load. NDK r27 flipped
# the default; until this project moves to it, the flag has to be explicit.
# Verify with: readelf -lW <lib>.so | grep LOAD   (Align must be >= 0x4000)
target_link_options(samba_jni PRIVATE "-Wl,-z,max-page-size=16384")

