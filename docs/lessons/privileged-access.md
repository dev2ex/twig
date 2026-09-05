# Privileged access (root / Shizuku)

> One section of Twig's hard-won lessons. The index, the ship checklist and the
> shared conventions are in [CLAUDE.md](../../CLAUDE.md); the other sections live
> beside this file in [docs/lessons/](.).

**(★ 2026-08-16, `fs-local/priv/` + `com.twig.app.Privileged`)**

- **It is not a new scheme, it is a fallback inside `LocalFileSystem`**
  (`LocalFileSystem.elevation`, the same pluggable pattern as the `changed` hook). What the user
  wants is for **the "Root directory" tree to be navigable**, not a second identical privileged
  tree next to it; using the same `file` scheme means favorites, cross-backend copying,
  thumbnails and search all benefit with zero changes. **Elevation happens only at the step
  where the ordinary API failed** — anything readable normally never goes through it, since
  every command forks a process and listing `/sdcard` that way is dozens of times slower.
- **root and Shizuku converge at `PrivilegedLauncher`**: both provide "a privileged process with
  stdin/stdout", and below that they share the same `PrivilegedShell` + `PrivilegedFs`. The only
  difference is the uid (su = 0; Shizuku is usually shell = 2000, **but if Shizuku itself was
  started as root it is 0** — so status text must report the uid actually read back, not the
  mode the user picked).
- **Keep one long-lived shell instead of `su -c` per command**: every exec goes through an
  authorization check, and some Magisk versions pop the dialog repeatedly. Command boundaries
  come from `echo <unique marker> $?` after the command — a long-lived shell never gives EOF.
  **But binary streams must use a one-shot process** (`cat`), whose EOF really is the end of the
  file: the marker scheme is meaningless for file content (the content may contain the marker,
  may not contain a single newline, and cannot be decoded as text).
- **★ The stderr drain thread must never share the lock used by `exec`** (`errLock`): `exec`
  holds the lock while waiting for the command to finish, so with a shared lock the drain thread
  blocks → nobody reads stderr → the 64 KB pipe fills → the command never finishes → you can
  only wait for the timeout. `find /` printing a screen of Permission denied is enough to trigger
  it. Regression test: `a command flooding stderr still completes`.
- **List directories with
  `find -H <dir> -maxdepth 1 -mindepth 1 -exec stat -c '%f|%s|%Y|%n' {} +`**, not `ls -l` (the
  time format varies with locale, names with spaces are hard to split, and a symlink's ` -> `
  gets mixed into the name).
  ★ **`-H` is not optional**: by default find does not follow a symlink given as a starting
  point, and `-mindepth 1` then discards the only entry found, so `/sdcard` (a link to
  `/storage/emulated/0`) lists as empty **with exit code 0** — it looks like "an empty directory"
  rather than a failure. `%n` goes last because file names may contain the separator: parsing
  splits only the first three fields and takes the rest as the name. Symlinks need a second
  `stat -L` run to know whether the target is a directory (otherwise the tree offers no expand
  arrow), batched 200 at a time to stay under ARG_MAX.
- **`exists()` is on the hot path of batch copying**, so asking the shell whenever the local
  answer is false would add a thousand round trips when copying a thousand files to `/sdcard`.
  `maybeHidden()` filters first with `parent.canRead()` (one access(2), no process): if the
  parent is readable, "does not exist" is the truth.
- **`touch -t` interprets the time in the local timezone**, so formatting the timestamp as UTC
  shifts every file's mtime by a constant offset.
- **Shizuku's `newProcess` is private in 13.x** (upstream wants people to move to
  `bindUserService`), so it can only be called reflectively → R8 must keep method names for
  `rikka.shizuku.**` or it is a guaranteed NoSuchMethod. The three manifest pieces are all
  required: `ShizukuProvider` (exported=true + multiprocess=false), the
  `moe.shizuku.manager.permission.API_V23` permission, and `moe.shizuku.privileged.api` in
  `<queries>` — **without that last one, package visibility on targetSdk 30+ produces
  "installed but reported as not installed"**. The binder arrives asynchronously, so restoring
  the previous choice needs `addBinderReceivedListenerSticky`; you cannot query it at startup.
- **Authorization can only be granted by the user in the foreground**: Android 10+ blocks
  background Activity starts, and Magisk's dialog cannot be raised from the background — it
  degrades into a notification, and the symptom is "I tapped it and nothing happened".
- For true random reads / no parsing, Shizuku's official route is `bindUserService` (running our
  code inside the shell process so a ParcelFileDescriptor can be returned); but it **does not
  apply to root at all**, so adopting it would mean maintaining two unrelated backends and this
  abstraction layer would disappear. Evaluated and not done.
- **★ "The settings row opens a dialog with no options at all" (hit on a real device
  2026-08-16)**: the dialog called both `setMessage()` (the explanation) and
  `setSingleChoiceItems()`. **An AlertDialog's content panel holds only one of them, and with
  both set the message wins and the list is never attached to the view hierarchy.** To have
  explanatory text alongside options, use `setCustomTitle()` (or a custom view) and leave the
  content panel to the list.
  - This one bug produced **three seemingly unrelated symptoms**: nothing selectable → therefore
    `requestPermission` was never called → therefore Twig never appeared in Shizuku's app list.
    At the time we went looking at the manifest declarations, the service AIDL and the R8
    mapping, all of which were fine — the direction was wrong from the start, and what we should
    have done was **render the UI and look at it** (the same lesson as "style changes on the
    share page must actually be rendered and looked at").
  - **The regression test's criterion must be "is it attached to the view hierarchy", not
    `listView != null`**: with a message set, AlertController **still constructs the ListView**
    with a fully populated adapter, it just never adds it to the panel. Asserting non-null keeps
    the test green on the buggy version (verified by inversion). See `PrivilegedDialogTest`.
- **The app's own `File("/").listFiles()` returns null on newer systems** (measured on
  Android 16), so the "Root directory" node is empty by nature and the privileged fallback is
  exactly what it is for — do not assume `/` is readable by everyone and call it "elevating for
  nothing".
- **Diagnostic logging must distinguish "non-zero exit = something went wrong" from "non-zero
  exit = the answer"** (`exec(quiet=)`): `[ -e path ]` returns 1 when the file does not exist,
  and `exists()` is on the batch-copy hot path, so one line per missing target drowns logcat;
  under `/` there are always a few broken links (`/adb_keys`, `/d`), so a batch `stat -L`
  necessarily exits 1 while the good entries still print. All of those pass `quiet = true`.
- In diagnostic strings, **do not print `checkSelfPermission()`'s numeric value**:
  `PERMISSION_GRANTED` happens to be **0**, and "granted=0" reads as "not granted", the exact
  opposite of the truth.
- **Privileged terminal (`PrivShell`, 2026-08-16)**: the local terminal already has a **real
  PTY** allocated by termux's `JNI.createSubprocess`, so "open a terminal as a privileged user"
  only changes what runs — resize, job control and full-screen programs all keep working, and
  **no bridge thread is needed** (that is an SSH-only requirement).
  - **root**: swap the shell binary from `/system/bin/sh` to `su`, and that is it. We do not
    pass `-p` — support for it varies across su implementations (Magisk/KernelSU/APatch) and
    those that do not know it exit with an error.
  - **Shizuku**: the rish route **is a dead end** (settled 2026-08-16; the code remains in
    `PrivShell` but `available()` always returns false for SHIZUKU). rish's dex can only be
    placed in the app's private directory, and **`untrusted_app` is not allowed to execute or
    load files labeled `app_data_file`** (SELinux's form of W^X), so `app_process` throws
    `ClassNotFoundException` outright.
    ★ **How it was pinned down**: the same dex (same md5), same environment, same command, run
    under `u:r:su:s0` (adb root), `u:r:runas_app:s0` (a debug build via `run-as`, **exactly the
    same uid as the app**) and `u:r:untrusted_app:s0` (the app itself) — the first two load it
    fine and only the third fails, so the variable narrows down to **the SELinux domain alone**.
    Termux can use rish because it has stayed on targetSdk 28 for years.
    **The current design uses `bindUserService`**, see below.
  - `librish.so` contains `grantpt`/`unlockpt`/`setsid`/`ioctl` — it **allocates a real PTY on
    the privileged side** and uses the pipes merely as transport, structurally the same as SSH.
    So the belief that "Shizuku can only give us pipes, a real terminal is impossible" was
    **wrong**; do not settle such questions by reasoning, **dump the .so's symbols and look**.
  - **★ Current design: `bindUserService` + forkpty on the privileged side** (working on real
    hardware 2026-08-17: `priv/TwigPrivService` + `priv/PrivService` + `cpp/twigpty.c`).
    It comes down to one thing: **whose code does the privileged process load**. rish requires us
    to drop Shizuku's dex into our private directory and load it (blocked); `bindUserService` has
    Shizuku start **our own APK** with app_process (already in `/data/app`, labeled
    `apk_data_file`), which is compliant by construction. The chain is:
    app --bind--> privileged process (uid 0/2000) --forkpty--> `sh`, with the master fd passed
    back to the app via `ParcelFileDescriptor`. Once we have the fd it is structurally identical
    to an SSH session (inject the emulator + two bridge threads) and reuses existing code.
    - **We ship our own `libtwigpty.so` (7 KB on arm64) rather than reusing termux's `JNI`**:
      the latter is package-private and its static initializer hard-codes
      `loadLibrary("termux")`, while in the privileged process `nativeLibraryDir` is an **empty
      directory** (the `.so` lives uncompressed inside the APK, `extractNativeLibs=false` being
      the modern default), so that step must fail — and **a class whose static initializer has
      thrown is permanently broken, with no chance to retry**. Our class **has no static
      initializer**, the load path is supplied by the caller, and the privileged process extracts
      the `.so` from the APK into `/data/local/tmp/twig` and `System.load`s the absolute path.
    - ★ **The extracted `.so` must never be writable, even for an instant.** The first version
      landed it as `-rwxrwxrwx` (root's umask + `setExecutable(true,false)`), and it is then
      loaded into a **root process** — that is a genuine local privilege escalation path, not
      merely the system's warning about
      `Attempt to load writable file ... will throw on a future Android version`. The correct
      sequence is **write to a temp name → tighten permissions → atomic rename**: writing the
      target file directly leaves a window between creation and chmod during which it is
      world-writable. Tighten to **"nobody can write"**, not "owner only" — Shizuku may run as
      root this time and as shell the next, and after an owner change it could not read the file
      it left behind. (Deleting depends on the **directory's** write permission, independent of
      the file's own bits, so a read-only file can still be replaced.)
    - ★ **`daemon(false)` does not guarantee the process dies.** When the app is upgraded or
      killed the binder is already gone, so Shizuku's `destroy()` **never arrives**, leaving a
      resident root process (measured 2026-08-17: Shizuku's log says "Remove service record …
      all connections are gone" while `ps` still shows `com.twig.app:priv` alive —
      **"the record was removed" is not "the process ended"**; do not take the log at face
      value). The fix is that on connect the app `attach()`es a `Binder` that exists purely to
      be alive, the helper `linkToDeath`s onto it, and calls `System.exit(0)` when the app
      disappears.
    - **Inject the real fd into `mTerminalFileDescriptor` reflectively**: termux's
      `TerminalSession.updateSize` already uses that field for `setPtyWindowSize`, so after
      injection **window synchronization needs no code at all** — rotation and keyboard
      show/hide deliver SIGWINCH automatically. SSH needs probing and memory only because there
      is no local fd on that side.
    - **After fork, only async-signal-safe work is allowed**: all strings are converted to C
      arrays before the fork (a JNI call might need a lock that another thread happened to hold
      at fork time). The signal mask and SIGPIPE/SIGINT must also be reset — the JVM blocks a
      pile of signals, and a shell inheriting that looks dead to Ctrl+C.
    - `PrivService` **caches the connection** so several sessions share one privileged process
      (starting one takes hundreds of milliseconds); `daemon(false)` ties it to the app so no
      resident process is left on the user's device; when changing `TwigPrivService`'s behavior,
      bump `VERSION` or you may connect to a process started by the old code.
    - There are two normal shutdown paths, neither of which is a fault: the shell exits by itself
      → reading the master side throws **EIO**; the user ends the session → `close()` interrupts
      the blocked read.
  - **`localEnv()` replaces the whole environment rather than adding to it** — without
    `ANDROID_ROOT`/`ANDROID_DATA`/`ANDROID_ART_ROOT`/`BOOTCLASSPATH`, **anything that starts ART
    exits instantly with no output**, leaving only termux's "[Process completed]" on screen,
    which looks like "command not found". This affects far more than rish: `am`, `pm`, `dumpsys`
    and `settings` are all wrapper scripts around `app_process`.
  - **Do not auto-close a local session that dies on startup**: the reason is printed on that very
    screen, and closing it flashes back to the file list and loses the only clue. Local sessions
    that exit within 3 seconds are now kept (see `QUICK_EXIT_MS`).
  - ★ **Android 14+ does not allow `app_process` to load a writable dex**, so after landing it the
    write bit must be cleared (`setWritable(false,false)`); that is also why it can only live in
    the app's private directory — permission bits cannot be changed elsewhere. `app_process` is a
    system binary and is not subject to W^X; the dex is data it reads, not the target of an
    execve.
  - `RISH_APPLICATION_ID` must be **the package holding the Shizuku grant**, i.e. ourselves
    (Shizuku authorizes by uid, and the uid running rish is that one).
  - **The privileged terminal must be its own menu item with the identity spelled out in the
    text**; do not quietly turn the existing "Open terminal here" into root — the user believes
    they are trying commands under the app's uid, when in reality one `rm` wipes the device.
    When it cannot start, say which identity failed to start, and **never fall back silently to
    an ordinary shell**.
- Tests: `PrivilegedShellTest` / `LocalFileSystemElevationTest` run with a **real `/bin/sh`** as
  the launcher (su and Shizuku differ only in "how the process is started"; the framework,
  quoting and parsing — where the bugs live — are the same code). ★ **What cannot be tested**:
  the real permission asymmetry. On a development machine the fallback shell has the same uid,
  fails on exactly the same paths, and no difference can be constructed. That half can only be
  verified on a rooted device or with real Shizuku.

