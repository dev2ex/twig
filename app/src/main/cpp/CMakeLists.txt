cmake_minimum_required(VERSION 3.22)
project(twigpty C)

# Only PTY allocation; no third-party code linked (~10KB).
add_library(twigpty SHARED twigpty.c)

target_compile_options(twigpty PRIVATE -O2 -fvisibility=hidden)

# forkpty/openpty live in bionic, so no extra libutil is needed.
target_link_libraries(twigpty log)

# ★ 16 KB page size (Android 15+). NDK r26 still links with a 4 KB max-page-size,
# and on a device with 16 KB pages the loader rejects such a library outright —
# the feature backed by it does not degrade, it fails to load. NDK r27 flipped
# the default; until this project moves to it, the flag has to be explicit.
# Verify with: readelf -lW <lib>.so | grep LOAD   (Align must be >= 0x4000)
target_link_options(twigpty PRIVATE "-Wl,-z,max-page-size=16384")

