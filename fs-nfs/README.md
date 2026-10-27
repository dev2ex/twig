# NFS evaluation backend

Vendored libnfs: https://github.com/sahlberg/libnfs
Commit: 2d368ade3e30c4782d54a63efc4d029c6fc75dff

The library is LGPL-2.1-or-later; generated protocol sources are BSD licensed.
See src/main/cpp/libnfs/COPYING and the accompanying license files.
The upstream source files are unmodified. TLS, Kerberos, NFSv4.2, examples,
utilities and tests are disabled in the parent CMake configuration.

This evaluation supports NFSv3 and NFSv4.0 with AUTH_SYS (numeric UID/GID),
listing, stat, streaming reads/writes, positional reads, mkdir, rename and deletion.
Specify an export path explicitly; export discovery is not included. Servers must
permit unprivileged source ports (Linux exports: insecure). No root is needed.
Each open stream owns its context; the metadata context is serialized.

Metadata failures discard the cached context. Listing, stat and existence checks
may reconnect and retry once; failed retries are discarded too. Mutations and
active file streams report failures without automatic replay.

## Connection recovery regression

Build the JNI source for the host (with the matching libnfs static library and
the JDK's JNI headers) into `libtwig_nfs.so`. Against a disposable NFSv4 export
at `/export`, start `python3 tools/nfs-fault-proxy.py SERVER_ADDRESS`. The proxy
binds only loopback, using port 2049 for NFS and 32049 for test control.

Run `TWIG_NFS_NATIVE_DIR=/path/to/host-jni TWIG_NFS_FAULT_PROXY=true
./gradlew :fs-nfs:testReleaseUnitTest --tests '*NfsRecoveryLiveTest*'` (on one line).
This drops live TCP sockets and temporarily refuses new connections, covering
automatic read recovery, offline errors, recovery after the network returns,
mutation non-replay and a fresh mount. Ordinary round trips remain opt-in via
`TWIG_NFS_TEST_HOST` and `TWIG_NFS_TEST_EXPORT`.
