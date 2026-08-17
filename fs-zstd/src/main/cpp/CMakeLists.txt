cmake_minimum_required(VERSION 3.22)
project(twigzstd C CXX)

set(ZSTD ${CMAKE_CURRENT_SOURCE_DIR}/zstd)
file(GLOB ZSTD_SRC ${ZSTD}/common/*.c ${ZSTD}/decompress/*.c)

add_library(twigzstd SHARED zstd_jni.cpp ${ZSTD_SRC})

target_include_directories(twigzstd PRIVATE
    ${ZSTD} ${ZSTD}/common ${ZSTD}/decompress)

# 纯 C 解压:关闭 x86_64 汇编优化(不引入 .S),单线程
target_compile_definitions(twigzstd PRIVATE
    ZSTD_DISABLE_ASM
    ZSTD_MULTITHREAD=0
    XXH_NAMESPACE=TWIGZSTD_)

target_compile_options(twigzstd PRIVATE -O2 -fvisibility=hidden -Wno-unused-function)

target_link_libraries(twigzstd log)
