# Terminal: local shell, fonts, zoom

> One section of Twig's hard-won lessons. The index, the ship checklist and the
> shared conventions are in [CLAUDE.md](../../CLAUDE.md); the other sections live
> beside this file in [docs/lessons/](.).

(For the remote/SSH side see "SSH, SFTP and the terminal" above.)

- **The terminal is garbled after the screen comes back on (★ 2026-08-27,
  `TermSizeFreezeLayout`)**: turning the screen off relays out the window a couple of
  times and it **ends up at the size it started from** (the IME is dismissed and
  restored; with fullscreen on, the status bar returns for the keyguard and
  `applyFullscreen` hides it again on focus). termux resizes the emulator straight from
  `TerminalView.onSizeChanged`, and resizing the **alternate screen buffer** (tmux, htop,
  vim) destroys what is on it — the peer is supposed to repaint on SIGWINCH. With a
  round trip it never does: for SSH the resize is debounced 300 ms and de-duplicated, so
  A→B→A sends **nothing at all**, and even a local PTY only gets two SIGWINCHes whose net
  size is unchanged, which tmux answers with no redraw. So the mangled buffer stays on
  screen until *some* later size change makes the peer draw again — hence "hide and show
  the keyboard and it comes back".
  - The fix is to not let a window that is merely jittering reach the emulator:
    `TerminalView` is **final** (it cannot be subclassed to gate `onSizeChanged`), so a
    parent frame keeps handing the child the size it already has. It freezes on
    `onWindowFocusChanged(false)` — ★ **that arrives before `onPause`**, and the screen-off
    relayout can land in between — and is lifted once layout has been quiet for 250 ms
    after focus/resume (with a 1.5 s ceiling), applying the settled size in one step,
    which is a no-op when it is the size we started from. The soft keyboard does **not**
    take window focus, so the ordinary show/hide resize is untouched.
  - Explicit `updateSize()` callers (`attachSession` / `setTextSize` / `setTypeface`) are
    deliberately **not** gated: those run when we decide, not when the window jitters —
    and gating them would leave a freshly opened session stuck at 80x24 (`openNew` reads
    the emulator size 120 ms after attach to open the shell with it).
  - `TermSizeFreezeTest` asserts on the **child's real width/height** across a round trip,
    not on "was the flag set" — the former is what `onSizeChanged` reacts to.
  - ★★ **Only returning to the foreground may arm the thaw; a layout pass may postpone an
    armed one but must never start one** (second round, same day): leaving the app restores
    the status bar, and that relayout kept rescheduling the 250 ms debounce until it went
    quiet — ~300 ms later, with the user already in another app, the thaw fired and applied
    the shorter size. We then froze again on that **wrong** size, and coming back applied
    the real one: a blank strip about a status bar tall at the bottom, and the content
    visibly jumping. Note the freeze itself was working; what leaked was the *thaw*.
  - **This was settled by a log line, not by reasoning** (`logSize`, kept in the code —
    `adb logcat -s TwigTerm:I`, grep `size freeze`): the trace read
    `freeze 1934/38 → thaw 1835/36 (still in the background) → freeze 1835/36 → thaw
    1934/38`, which named the culprit immediately. The round before it had "fixed" the
    wrong thing (freezing earlier, in `onUserLeaveHint`) on a plausible but wrong story —
    the log shows focus loss always arrives first, so that hook never even ran. The exits
    (focus loss / `onPause` / user-leave) arrive in no fixed order, so correctness must not
    depend on their ordering.

- **Chinese looks fat and `●` looks tall and thin in the terminal (★ 2026-08-02)**: termux's
  `TerminalRenderer` fixes the column width at `measureText("X")`, and when the measured width of a
  drawn run does not match "columns × column width" it does `canvas.scale(ratio, 1f)` to squeeze or
  stretch it into the grid **horizontally only** (vertically never). Hence: the system `MONOSPACE`
  has no Han characters and falls back to Noto Sans CJK (Han = 1.0em) while the Latin X is only
  ≈0.6em, so a 2-column target of 1.2em against a measured 1.0em **stretches Chinese 20% wider** =
  fat; `●` (U+25CF) is East Asian **Ambiguous** and is absent from the `WcWidth` table (dumped it —
  the nearby ranges are 9725–9726 and friends) so it counts as 1 column, while the glyph is
  full-width → squeezed to 60% width at unchanged height = tall and thin.
  - **Changing the font is the only fix** (the scaling switch is internal to the renderer with no
    API; changing wcwidth would disagree with the remote `wcwidth` and misplace the cursor, which
    is worse). The criterion is that **the font's advance must be 0.5em** — then Latin is 1 column,
    the system's fallback Han is 1.0em = exactly 2 columns, and both scale factors return to 1.0.
    **It does not have to be a CJK monospace font**: `Iosevka` (0.5em, and it includes the
    geometric shapes block so `●` is also 0.5em) was measured perfect on 2026-08-02 and is far
    smaller than a full CJK font. A 0.6em monospace font (JetBrains Mono and friends) still
    deforms.
  - The font is not bundled (size first): `TerminalFont` imports via SAF → validates the sfnt magic
    + tries to load it → copies into `filesDir/fonts` (content:// permissions are unreliable across
    restarts, the same pattern as importing an SFTP private key).
- **Local shell sessions (★ 2026-08-03)**: termux's `TerminalSession` already forks a local PTY with
  `JNI.createSubprocess`, and `libtermux.so` has always shipped in the APK with the
  terminal-emulator dependency (~10 KB each for arm64/x86_64), so the local terminal **does not
  need** the bridge thread, resize probing or auto-reconnect — those are all for SSH. Two timing
  facts you must know:
  - `TerminalView.attachSession` → `updateSize()` → `TerminalSession.updateSize()` **will call
    `initializeEmulator` itself (i.e. fork the shell) when `mEmulator == null`**. Calling it again
    manually after attach **forks a second process** and overwrites the first one's fd/emulator.
    Check `getEmulator() == null` before topping it up.
  - An SSH session injects the emulator before creating the view, but a local session only has one
    once the process is running — so `TermSession`'s `lateinit var emulator` throws
    `UninitializedPropertyAccessException` in UI callbacks (`maybeShowIme` and friends). Anywhere
    that might run before the session is ready must use `emulatorOrNull`.
  - Limitations: the process runs as the app's uid with no root; only the system mksh and toybox
    are available; API 29+ W^X means binaries in the app's private directory cannot be execve'd
    (interpreting a script with `sh xxx.sh` is fine).
  - **`$HOME` only applies to the shell itself; OpenSSH does not honor it**: `ssh`/`ssh-keygen`
    resolve `~` through `getpwuid(getuid())->pw_dir`, and on Android an app uid's `pw_dir` is
    always the unwritable `/data` → `Could not create directory '/data/.ssh'`. Keys can live in the
    app's private directory, but the path must be spelled out everywhere (`-f`, `-i`,
    `-o UserKnownHostsFile=`, `-F`, and no `~` inside config either). Current design: `SshHome`
    creates `filesDir/.ssh` (700) plus a `config` with fully absolute paths (600), and `.mkshrc`
    adds `-F` pointing at it for `ssh`/`scp`/`sftp`. ★ The config **must exist first** — with `-F`
    pointing at a nonexistent file, ssh errors out immediately, unlike a missing default
    `~/.ssh/config` which is silently skipped. `ssh-keygen` gets its default `-f` from a wrapper
    function rather than an alias: for `-R`/`-F` the `-f` means known_hosts, and a hard-coded alias
    would make `ssh-keygen -R host` rewrite the private key file.
  - **Cross-session history is impossible (★ measured and settled 2026-08-06, do not try again)**:
    Android's bundled `/system/bin/sh` (mksh R59) is compiled with `HAVE_PERSISTENT_HISTORY=0`, and
    `strings /system/bin/sh | grep -i hist` **does not even contain the string `HISTFILE`** (only
    `HISTSIZE`) — setting it neither writes nor reads (pre-creating the file and passing it as an
    environment variable still gives `fc -l` "no history (yet)"). History is a pure in-memory
    array, and `fc` has no loading command like bash's `history -r`. The app layer cannot fill the
    gap either, since the only channel in is PTY input and input means execution. Really doing it
    would mean bundling a shell with persistent history (in nativeLibraryDir to dodge W^X), which
    conflicts with size-first. Not done.
  - **Tab completion finds no commands = the PATH directories cannot be listed (★ 2026-08-06,
    `CmdShims`)**: the system PATH directories are `drwxr-x--x root:shell` (`/system/xbin` is
    `drwxr-x---`), so the app uid has only `x` (enter/execute by name), not `r` (list), and mksh's
    completion `opendir` reads out zero candidates. `/apex/…/bin` is 0755, which is why it looks
    like "some complete and some do not". **Everything is normal under `adb shell` because that
    user is in the `shell` group — do not take adb's behavior as the truth when diagnosing this**;
    to verify as the app uid you must temporarily install a debug build and use `run-as` (a release
    build refuses `run-as`). This is DAC, not SELinux, so **there is no avc denied in logcat**.
    The workaround (`CmdShims`) does not list directories, it asks by name: running `toybox` with
    no arguments prints every command name it supports (210 measured; most commands in
    `/system/bin` are symlinks to it), plus a hard-coded list of Android/third-party names probed
    by stat along PATH; every hit gets a symlink of the same name in `filesDir/bin`, and that
    directory is prepended to PATH. ★ Symlinks are **not subject to W^X**: after the kernel
    resolves it, the execve target is the real binary under `/system/bin`, not a file on `/data`.
    ★ Probing **must walk the whole PATH**: `ssh`/`scp`/`ssh-keygen` are measured to live in
    `/product/bin`, not `/system/bin`.
  - **Prefix history search is free**: mksh has `search-history-up`/`-down` (searching by whatever
    is typed before the cursor as a prefix) but binds them only to PageUp/PageDown
    (`^[[5~`/`^[[6~`), which a phone keyboard cannot reach. The `.mkshrc` template binds them to
    the arrow keys; on an empty line the behavior is identical to `up-history`, so it is a pure
    improvement. Bind **both** `^[[` (normal cursor keys) and `^[O` (application cursor keys, SS3)
    — binding the latter also makes mksh treat `^[O` as prefix-2, so the SS3 forms of Home/End
    start working too. ★ To inspect mksh bindings use
    `adb shell -t -t "sh -ic 'bind'"`: `bind` requires an **interactive** shell (`sh -c` reports
    "can't bind, not a tty"; having a pty is not enough), and in its output `^X` is how `^[[` is
    displayed.
- **The accessory keys were clipped at a large system font scale (★ 2026-08-27)**: the
  eight keys per row are `width=0, weight=1`, so on a 360dp screen a cell is only ~45dp —
  while the **default button style spends 16dp of padding on each side** (and reserves an
  88dp `minWidth`), leaving barely 13dp for "SHIFT"/"PGUP". At font scale 1.0 it just about
  survives; the labels are `sp`, so raising the system font size clips them. The cells are
  adjacent, so the gap between labels is spacing enough — the padding and both minimums are
  zeroed, which alone buys back 32dp.
  ★ **Per-button auto-sizing is the wrong second half** (tried and reverted the same day):
  `TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(8, 12, 1, SP)` does keep every
  label whole, but each key shrinks **independently** — measured at font scale 1.5, "SHIFT"
  lands on 9sp next to "ESC" still at 12sp, and a grid of uniform keys in a ragged mix of
  sizes reads as "the labels all got smaller". A key bar must share **one** size: `TermKeyFit`
  picks the largest at which the **widest** label fits a cell (measured **bold** — a modifier
  key turns bold while armed, its widest state), and `fitExtraKeyText` applies it to every
  button from a layout listener. It runs during layout, so it must settle: the text size does
  not change the cells, so the next pass computes the same value and the `abs(... ) > 0.5f`
  guard stops the `requestLayout` loop.
  ★ `TermKeyFitTest` must run with `@GraphicsMode(NATIVE)`: Robolectric's stub paint reports
  **one pixel per character**, so every label "fits" at any size and the whole assertion is
  vacuous (the first run of this measurement printed `paint=5.0` for "SHIFT" and was believed
  for a moment).
- **Pinch zoom in the terminal did not change the font size (★ 2026-08-02)**: termux's
  `mScaleFactor` is a **cumulative** factor, and it is overwritten by the return value of
  `TerminalViewClient.onScale`. The old implementation used a fixed initial font size as the base
  and returned 1.0f every time, resetting the accumulation, so one gesture only ever set the same
  `base × threshold` value → pinching did nothing. The base must be **the current font size**.
  Two related fixes: `setTextSize` does not `requestLayout()`, so the `OnGlobalLayoutListener` path
  that syncs the remote PTY **never fires** (the remote keeps emitting at the old cols/rows and
  never receives SIGWINCH, leaving stale content on screen) — hook termux's `onEmulatorSet`
  callback, which fires when rows/columns actually change; and `updateSize()`'s `invalidate()` is
  inside the "rows/columns changed" branch (`setTypeface` compensates for this itself,
  `setTextSize` did not), so changing the font size must issue one itself.

