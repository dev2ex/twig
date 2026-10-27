# NFS evaluation backend

Vendored libnfs: https://github.com/sahlberg/libnfs
Commit: 2d368ade3e30c4782d54a63efc4d029c6fc75dff

The library is LGPL-2.1-or-later; generated protocol sources are BSD licensed.
See src/main/cpp/libnfs/COPYING and the accompanying license files.
The upstream source files are unmodified. TLS, Kerberos, NFSv4.2, examples,
utilities and tests are disabled in the parent CMake configuration.

This evaluation supports NFSv3 and NFSv4.0 with AUTH_SYS (numeric UID/GID),
listing, stat, streaming reads/writes, append, positional reads, mkdir, rename and deletion.
Specify an export path explicitly; export discovery is not included. Servers must
permit unprivileged source ports (Linux exports: insecure). No root is needed.
Each open stream owns its context; the metadata context is serialized.
The connection editor separates the address and NFS port (default 2049), and
splits pasted `host:port` or `[IPv6]:port` addresses. Unbracketed IPv6 remains a
host literal. NFSv3 still uses rpcbind to discover mountd; the configured port
only overrides the NFS data service. Set `TWIG_NFS_TEST_PORT` for live tests on
a non-default NFS port.

Metadata failures discard the cached context. Listing, stat and existence checks
may reconnect and retry once; failed retries are discarded too. Mutations and
active file streams report failures without automatic replay.
Stream length comes from the opened file handle, so a concurrent rename or path
replacement cannot substitute another file's size. Native error messages use
ordinary UTF-8 decoding, including paths with supplementary Unicode characters.

The backend opts into `ResumableWrite`: failed copies can retry their owned partial
files without truncating the bytes already written. Append opens without truncation
and obtains its starting offset from the opened handle, ignoring stale listing sizes.
The shared copy engine supplies failed-target display, manual retries and skip-and-continue;
mutations and active stream writes are still never replayed automatically.

## Connection recovery regression

Build the JNI source for the host (with the matching libnfs static library and
the JDK's JNI headers) into `libtwig_nfs.so`. Against a disposable NFSv4 export
at `/export`, start `python3 tools/nfs-fault-proxy.py SERVER_ADDRESS`. The proxy
binds only loopback, using port 2049 for NFS and 32049 for test control.

Run `TWIG_NFS_NATIVE_DIR=/path/to/host-jni TWIG_NFS_FAULT_PROXY=true
./gradlew :fs-nfs:testReleaseUnitTest --tests '*NfsRecoveryLiveTest*'` (on one line).
This drops live TCP sockets and temporarily refuses new connections, covering
automatic read recovery, offline errors, recovery after the network returns,
mutation non-replay, failed write/close propagation, manual append after recovery,
and a fresh mount. Ordinary round trips remain opt-in via
`TWIG_NFS_TEST_HOST` and `TWIG_NFS_TEST_EXPORT`. `NfsTransferLiveTest` also exercises
NFSv3/v4 copies with an injected source error: original-target protection, exact
partial bytes, retry reads beginning at the saved offset, and skipping a broken
source while moving the next file.
