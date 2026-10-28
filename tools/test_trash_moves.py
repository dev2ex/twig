#!/usr/bin/env python3
"""Exercise the production JNI syscall on Linux: conflicts, Unicode and concurrent moves."""
from pathlib import Path
import shutil
import subprocess
import tempfile


def main():
    root = Path(__file__).resolve().parent.parent
    javac = shutil.which("javac")
    compiler = shutil.which("cc")
    if not javac or not compiler:
        raise SystemExit("A JDK and C compiler are required")
    java_root = Path(javac).resolve().parent.parent
    with tempfile.TemporaryDirectory(prefix="twig-trash-native-") as directory:
        temp = Path(directory)
        subprocess.run([javac, "-d", str(temp), str(root / "tools/native-trash/TrashMoves.java")], check=True)
        for legacy in (False, True):
            library = temp / ("liblegacy.so" if legacy else "libtrashprobe.so")
            subprocess.run([
                compiler, "-shared", "-fPIC", "-O2", "-Wall", "-Wextra", "-Werror",
                *(["-DTWIG_TRASH_FORCE_LEGACY"] if legacy else []),
                "-I" + str(java_root / "include"), "-I" + str(java_root / "include/linux"),
                str(root / "app/src/main/cpp/trash.c"), "-o", str(library),
            ], check=True)
            print("Legacy reservation" if legacy else "Atomic rename", flush=True)
            subprocess.run([str(java_root / "bin/java"), "-cp", str(temp), "com.twig.app.TrashMoves", str(library)], check=True)


if __name__ == "__main__":
    main()
