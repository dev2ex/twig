cmake_minimum_required(VERSION 3.22)
project(twig_nfs C)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(ENABLE_TLS OFF CACHE STRING "" FORCE)
set(ENABLE_KRB5 OFF CACHE STRING "" FORCE)
set(ENABLE_NFS4_2 OFF CACHE BOOL "" FORCE)
set(ENABLE_MULTITHREADING OFF CACHE BOOL "" FORCE)
set(ENABLE_UTILS OFF CACHE BOOL "" FORCE)
set(ENABLE_EXAMPLES OFF CACHE BOOL "" FORCE)
set(ENABLE_TESTS OFF CACHE BOOL "" FORCE)
set(ENABLE_DOCUMENTATION OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
add_compile_options(-Os -ffunction-sections -fdata-sections -fvisibility=hidden
    "-ffile-prefix-map=${CMAKE_SOURCE_DIR}=."
    "-ffile-prefix-map=${CMAKE_BINARY_DIR}=.")
add_subdirectory(libnfs)

add_library(twig_nfs SHARED nfs_jni.c)
target_include_directories(twig_nfs PRIVATE libnfs/include)
target_link_libraries(twig_nfs nfs)
target_link_options(twig_nfs PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
