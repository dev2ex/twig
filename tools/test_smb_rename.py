#!/usr/bin/env python3
"""Compile and run libsmb2 rename/create/delete callback regressions on Linux."""
from pathlib import Path
import subprocess
import tempfile


def main():
    root = Path(__file__).resolve().parent.parent
    native = root / "fs-smb/src/main/cpp"
    library = native / "libsmb2"
    with tempfile.TemporaryDirectory(prefix="twig-smb-rename-") as directory:
        executable = Path(directory) / "rename-errors"
        subprocess.run([
            "cc", "-DHAVE_CONFIG_H", "-D_GNU_SOURCE", "-include", "time.h",
            "-ffunction-sections", "-fdata-sections", "-Wl,--gc-sections",
            "-I", str(native), "-I", str(library / "include"),
            "-I", str(library / "include/smb2"), "-I", str(library / "lib"),
            str(root / "tools/native-smb/rename-errors.c"),
            str(library / "lib/errors.c"), "-o", str(executable),
        ], check=True)
        subprocess.run([str(executable)], check=True)


if __name__ == "__main__":
    main()
