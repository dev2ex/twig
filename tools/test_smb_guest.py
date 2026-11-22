#!/usr/bin/env python3
"""Build vendored libsmb2 and test guest/anonymous access with local Docker Samba.

Requires Linux, cc, Docker and a cached dperson/samba image. Uses disposable
containers without published ports or host-directory mounts. Run from any cwd:
    python3 tools/test_smb_guest.py
"""
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import uuid


def docker(*args):
    return subprocess.check_output(["docker", *args], text=True).strip()


def fixture(name, *, anonymous=False, signing=False):
    docker(
        "run", "--pull=never", "-d", "--name", name, "dperson/samba", "-p",
        "-u", "fixture;fixture-pass",
        "-s", "Pub;/share;yes;no;yes",
        "-s", "Private;/private;yes;no;no;fixture",
        "-g", "server min protocol = SMB2",
        "-g", "server max protocol = SMB3_11",
        "-g", "map to guest = " + ("Never" if anonymous else "Bad User"),
        "-g", "server signing = " + ("mandatory" if signing else "auto"),
    )
    host = docker("inspect", "--format", "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}", name)
    deadline = time.monotonic() + 20
    while True:
        try:
            with socket.create_connection((host, 445), timeout=1):
                return host
        except OSError:
            if time.monotonic() >= deadline:
                raise RuntimeError(f"Samba fixture {name} did not start")
            time.sleep(0.2)


def main():
    root = Path(__file__).resolve().parent.parent
    native = root / "fs-smb/src/main/cpp"
    library = native / "libsmb2"
    sources = sorted(p for p in (library / "lib").glob("*.c")
                     if p.name not in {"krb5-wrapper.c", "aes_apple.c"})
    names = [f"twig-smb-guest-{uuid.uuid4().hex[:10]}-{i}" for i in range(3)]
    with tempfile.TemporaryDirectory(prefix="twig-smb-guest-") as directory:
        executable = Path(directory) / "guest-access"
        subprocess.run([
            "cc", "-O1", "-DHAVE_CONFIG_H", "-D_GNU_SOURCE", "-DNO_LIBKRB5",
            "-include", "time.h", "-include", "stddef.h",
            "-Wno-implicit-function-declaration",
            "-I", str(native), "-I", str(library / "include"),
            "-I", str(library / "include/smb2"),
            str(root / "tools/native-smb/guest-access.c"),
            *(str(p) for p in sources), "-o", str(executable),
        ], check=True)
        try:
            hosts = [fixture(names[0]), fixture(names[1], anonymous=True), fixture(names[2], signing=True)]
            subprocess.run([str(executable), *hosts], check=True, timeout=180)
        finally:
            for name in names:
                subprocess.run(["docker", "rm", "-f", name], stdout=subprocess.DEVNULL,
                               stderr=subprocess.DEVNULL)


if __name__ == "__main__":
    main()
