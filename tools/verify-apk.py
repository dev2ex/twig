#!/usr/bin/env python3
"""Check a release APK before it is published.

Every check here exists because the thing it looks for actually shipped, or came
close to shipping, at least once:

  version     a failed build can leave the *previous* APK in place and the
              pipeline still exits 0, so the file is asked what it is
  signature   without keystore.properties the release comes out unsigned, and a
              debug-signed build refuses to install over a real one
  sigblock    AGP puts an encrypted "Dependency metadata" blob (id 0x504b4453)
              in the signing block; F-Droid rejects an APK that carries it
  alignment   Android 15 refuses to map a library laid out for 4 KB pages — the
              feature does not get slower, it fails to load
  abis        a per-ABI split that quietly contains every ABI is not a split
  flavour     libre must not contain junrar (non-free); full must

Usage:  verify-apk.py --version-code 295 --version-name 1.10.0 APK [APK ...]
Exit status is non-zero if any check fails.
"""

from __future__ import annotations

import argparse
import os
import re
import struct
import subprocess
import sys
import zipfile

DEPENDENCY_METADATA_ID = 0x504B4453
MIN_PAGE_ALIGN = 0x4000  # 16 KB
DEFAULT_DN = "CN=Twig, O=Twig"


def build_tool(name: str) -> str:
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT", "")
    version = os.environ.get("TWIG_BUILD_TOOLS", "36.1.0")
    path = os.path.join(sdk, "build-tools", version, name)
    if not os.access(path, os.X_OK):
        sys.exit(f"build tool not found or not executable: {path}")
    return path


def load_segment_align(data: bytes) -> int:
    """Largest p_align over PT_LOAD. ELF32 and ELF64 lay the header out differently,
    and armeabi-v7a is ELF32 — reading it with 64-bit offsets throws."""
    is64 = data[4] == 2
    if is64:
        phoff = struct.unpack_from("<Q", data, 0x20)[0]
        entsize = struct.unpack_from("<H", data, 0x36)[0]
        count = struct.unpack_from("<H", data, 0x38)[0]
        align_off, read = 0x30, lambda o: struct.unpack_from("<Q", data, o)[0]
    else:
        phoff = struct.unpack_from("<I", data, 0x1C)[0]
        entsize = struct.unpack_from("<H", data, 0x2A)[0]
        count = struct.unpack_from("<H", data, 0x2C)[0]
        align_off, read = 0x1C, lambda o: struct.unpack_from("<I", data, o)[0]
    aligns = {
        read(phoff + i * entsize + align_off)
        for i in range(count)
        if struct.unpack_from("<I", data, phoff + i * entsize)[0] == 1  # PT_LOAD
    }
    return max(aligns) if aligns else 0


def signing_block_ids(path: str) -> list[int]:
    raw = open(path, "rb").read()
    magic = raw.rfind(b"APK Sig Block 42")
    if magic < 0:
        return []
    size = struct.unpack_from("<Q", raw, magic - 8)[0]
    off = magic + 16 - size - 8 + 8
    ids = []
    while off < magic - 8:
        length = struct.unpack_from("<Q", raw, off)[0]
        if length < 4 or off + 8 + length > len(raw):
            break
        ids.append(struct.unpack_from("<I", raw, off + 8)[0])
        off += 8 + length
    return ids


def expected_abis(name: str) -> set[str] | None:
    """Derived from the file name, so a mis-built split is caught rather than described."""
    for abi in ("arm64-v8a", "armeabi-v7a", "x86_64"):
        if f"-{abi}." in name:
            return {abi}
    return {"arm64-v8a", "x86_64"} if "-full." in name or "-libre." in name else None


def check(path: str, want_code: str, want_name: str, want_dn: str) -> list[str]:
    problems: list[str] = []
    name = os.path.basename(path)

    badging = subprocess.run(
        [build_tool("aapt"), "dump", "badging", path], capture_output=True, text=True
    ).stdout
    m = re.search(r"versionCode='(\d+)' versionName='([^']+)'", badging)
    if not m:
        problems.append("aapt could not read the version")
    elif (m.group(1), m.group(2)) != (want_code, want_name):
        problems.append(f"version is {m.group(2)}({m.group(1)}), expected {want_name}({want_code})")

    certs = subprocess.run(
        [build_tool("apksigner"), "verify", "--print-certs", path], capture_output=True, text=True
    )
    dns = [l.split("DN: ", 1)[1].strip() for l in certs.stdout.splitlines() if "DN:" in l]
    if certs.returncode != 0 or not dns:
        problems.append("not signed (missing keystore.properties?)")
    elif dns[0] != want_dn:
        problems.append(f"signed by {dns[0]!r}, expected {want_dn!r}")

    if DEPENDENCY_METADATA_ID in signing_block_ids(path):
        problems.append("carries AGP's Dependency metadata block; F-Droid will reject it")

    zf = zipfile.ZipFile(path)
    names = zf.namelist()

    misaligned = [
        n for n in names if n.endswith(".so") and load_segment_align(zf.read(n)) < MIN_PAGE_ALIGN
    ]
    if misaligned:
        problems.append("not laid out for 16 KB pages: " + ", ".join(misaligned))

    abis = {n.split("/")[1] for n in names if n.startswith("lib/")}
    want_abis = expected_abis(name)
    if want_abis is not None and abis != want_abis:
        problems.append(f"ABIs {sorted(abis)}, expected {sorted(want_abis)}")

    junrar = zf.read("classes.dex").decode("latin1").count("junrar")
    if "-libre" in name and junrar:
        problems.append(f"libre build references junrar {junrar}x — it must not")
    if "-full" in name and not junrar:
        problems.append("full build has no junrar reference — RAR support is missing")

    size_mb = os.path.getsize(path) / 1048576
    status = "FAIL" if problems else "ok"
    print(f"  [{status}] {name:34s} {size_mb:5.2f} MB  {','.join(sorted(abis))}")
    for p in problems:
        print(f"         - {p}")
    return problems


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--version-code", required=True)
    ap.add_argument("--version-name", required=True)
    ap.add_argument("--dn", default=os.environ.get("TWIG_SIGNER_DN", DEFAULT_DN))
    ap.add_argument("apks", nargs="+")
    args = ap.parse_args()

    failed = 0
    for apk in args.apks:
        if check(apk, args.version_code, args.version_name, args.dn):
            failed += 1
    if failed:
        print(f"\n{failed} of {len(args.apks)} APKs failed verification", file=sys.stderr)
        return 1
    print(f"\nall {len(args.apks)} APKs verified")
    return 0


if __name__ == "__main__":
    sys.exit(main())
