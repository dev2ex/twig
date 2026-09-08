cmake_minimum_required(VERSION 3.22)
project(twigpty C)

# Only PTY allocation; no third-party code linked (~10KB).
add_library(twigpty SHARED twigpty.c)


# ★ Reproducible builds: without this the GNU build-id differs between machines.
# The linker computes it before the .so is stripped, over an image that still
# carries absolute source paths in its debug info — so /home/me/... and
# /builds/ci/... hash differently, the strip then removes the paths but keeps the
# id, and two byte-identical libraries end up with 20 differing bytes. Mapping
# both prefixes to "." makes the id depend on content alone.
# Verify: build the same commit at two different paths, then
#   readelf -n <lib>.so | grep 'Build ID'   must match.
target_compile_options(twigpty PRIVATE -O2 -fvisibility=hidden
    -ffile-prefix-map=${CMAKE_SOURCE_DIR}=.
    -ffile-prefix-map=${CMAKE_BINARY_DIR}=.)

# forkpty/openpty live in bionic, so no extra libutil is needed.
target_link_libraries(twigpty log)

# ★ 16 KB page size (Android 15+). NDK r26 still links with a 4 KB max-page-size,
# and on a device with 16 KB pages the loader rejects such a library outright —
# the feature backed by it does not degrade, it fails to load. NDK r27 flipped
# the default; until this project moves to it, the flag has to be explicit.
# Verify with: readelf -lW <lib>.so | grep LOAD   (Align must be >= 0x4000)
target_link_options(twigpty PRIVATE "-Wl,-z,max-page-size=16384")

# ── libtermux: Termux's terminal JNI, built from vendored source ────────────
# ★ Not taken from the terminal-emulator AAR any more. Its prebuilt .so is
# 4 KB-aligned in every published version, and Android 15 refuses to map such a
# library rather than merely running it slower — so the terminal would crash on
# a 16 KB device. Same source, same symbols, relinked for 16 KB.
# See termux/README.md for provenance and for what to delete once upstream
# ships an aligned build. The AAR's own .so is excluded in app/build.gradle.kts.
add_library(termux SHARED termux/termux.c)

target_compile_options(termux PRIVATE -O2
    -ffile-prefix-map=${CMAKE_SOURCE_DIR}=.
    -ffile-prefix-map=${CMAKE_BINARY_DIR}=.)

target_link_options(termux PRIVATE "-Wl,-z,max-page-size=16384")
