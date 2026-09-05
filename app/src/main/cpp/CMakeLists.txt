cmake_minimum_required(VERSION 3.22)
project(twigpty C)

# Only PTY allocation; no third-party code linked (~10KB).
add_library(twigpty SHARED twigpty.c)

target_compile_options(twigpty PRIVATE -O2 -fvisibility=hidden)

# forkpty/openpty live in bionic, so no extra libutil is needed.
target_link_libraries(twigpty log)
