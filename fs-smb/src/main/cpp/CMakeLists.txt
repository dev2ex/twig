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
)

# ── JNI shared library ──────────────────────────────────────────────────────
add_library(samba_jni SHARED samba_jni.cpp)

target_include_directories(samba_jni PRIVATE
    ${LIBSMB2_DIR}/include
    ${LIBSMB2_DIR}/include/smb2
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_compile_definitions(samba_jni PRIVATE HAVE_CONFIG_H _GNU_SOURCE)

target_compile_options(samba_jni PRIVATE -O2 -fvisibility=hidden)

target_link_libraries(samba_jni
    smb2_static
    log          # Android __android_log_print
)
