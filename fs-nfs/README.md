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
