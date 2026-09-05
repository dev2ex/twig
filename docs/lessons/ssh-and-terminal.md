# SSH, SFTP and the terminal

> One section of Twig's hard-won lessons. The index, the ship checklist and the
> shared conventions are in [CLAUDE.md](../../CLAUDE.md); the other sections live
> beside this file in [docs/lessons/](.).

- **SSHJ + BouncyCastle**: the "BC" bundled with Android is a stripped build (no X25519),
  so the handshake fails with `no such algorithm x25519`.
  `SftpFileSystem.ensureFullBouncyCastle()` replaces it with the full provider, and the
  dependencies explicitly include `bcprov-jdk18on`. Do not remove either.
- **SSH terminal** (`TerminalActivity` + `com/termux/`): Termux's `TerminalSession` is
  final and bound to a local PTY. Here **no local process is started**: we reflectively
  inject our own `TerminalEmulator` (`mEmulator`) and set `mShellPid = 1` so key presses
  get queued, then a bridge thread moves them to the SSHJ shell; reading the
  package-private queue works because `TermBridge` lives in the same package.
- **Terminal resize, "it disconnects on the first keypress" — the truth (★ settled after
  three incidents, 2026-07-16)**: twice we shipped "watch layout → forward window-change"
  and twice it disconnected instantly. Both times the blame landed on "some servers can't
  handle window-change" — **completely wrong; neither the server nor termux-view was
  involved**.
  - **The root cause is the main thread**: the layout listener/Handler callback runs on
    the main thread, and `changeWindowDimensions` ends in a socket write, so it throws
    `NetworkOnMainThreadException`. SSHJ's `TransportImpl.write` advances the outbound
    packet sequence number and cipher stream state **before** writing to the socket — so
    when the exception fires, the packet was never sent but the state already moved by
    one, and the connection is "poisoned". The next legitimate outbound packet (the first
    keypress, from the background bridge thread) has the wrong sequence number, the server
    fails the MAC check and closes the TCP connection (`Broken transport; encountered
    EOF`). Hence the exact symptom "dies on first input", on **every** server.
  - **Swallowing the exception cost twice over**: the `runCatching` silently ate the NMTE,
    which not only threw away the only clue but disguised a *harmful* failure as a
    *harmless* one (`sent = false`).
  - **The blind spot in probing (why 0.30.0 didn't auto-fall-back but looped
    input→drop→reconnect forever)**: the probe verdict hung off `if (sent)`, so a "failed"
    send never produced a verdict → the capability stayed at `unknown` forever → after
    reconnect it poisoned and dropped again, and the `broken` fallback never triggered
    once. Lesson: **degradation/fallback logic must cover the failure path — "send failed"
    ≠ "nothing happened"; a failure can already have had side effects**.
  - **Rule**: every outbound SSHJ call (resize included) may only run on a background
    thread, and network-layer exceptions are never swallowed silently (a failed resize now
    prints `twig: window-change failed` to logcat under `W/System.err`).
  - **Current design** (`TerminalActivity.syncRemoteSize()`, verified in 0.30.1 to fix
    htop's blank margins): debounce layout by 300 ms → send the resize on a background
    thread; remember a three-state capability per server (scheme) in Prefs — `unknown`
    means the first send is the probe, a disconnect within 3 s marks it `broken` and it is
    never sent again (so a server that genuinely cannot take resizes does not fall into a
    disconnect loop), and surviving marks it `ok`. `ShellSession` serializes all outbound
    traffic (stdin + resize) under one lock (concurrent writes scramble the cipher's
    counter → MAC failure → disconnect).
- **Terminal auto-reconnect**: SSHJ connections use a 15 s keepalive
  (`SftpFileSystem.newAuthedClient()`) to mitigate mobile NAT/carrier gateways silently
  dropping idle connections. On a real disconnect, `TerminalActivity.reconnect()` retries
  with backoff three times instead of declaring the session over. The stdin bridge thread
  is started exactly once per session lifetime (bound to `TermSession.gen` rather than
  capturing the old connection in a closure), so a reconnect does not start a second
  thread competing for the key queue and dropping keystrokes.
- **Multiple terminal sessions**: `TermManager` (static) holds the session list and a top
  Spinner switches between them; every session has its own SSH connection and emulator,
  and keeps accumulating output in the background when switched away. The accessory key
  buttons must be `isFocusable = false`, otherwise they steal focus from the terminal and
  key routing goes wrong.

