cmake_minimum_required(VERSION 3.22)
project(twigpty C)

# 只有 PTY 分配这一件事,不链接任何第三方代码(约 10KB)。
add_library(twigpty SHARED twigpty.c)

target_compile_options(twigpty PRIVATE -O2 -fvisibility=hidden)

# forkpty/openpty 在 bionic 里,不需要额外的 libutil。
target_link_libraries(twigpty log)
