# Password encryption, config backup and the app lock

> One section of Twig's hard-won lessons. The index, the ship checklist and the
> shared conventions are in [CLAUDE.md](../../CLAUDE.md); the other sections live
> beside this file in [docs/lessons/](.).

**(★ 2026-08-25, `app/secure/` + `ui/SecurityUi`)** From 0.x through 1.2 everything was stored
**in plain text** in SharedPreferences (connection passwords/apiKeys/tokens, the restic password,
archive passwords, the sharing password). **This project is open source, so any hard-coded key is
the same as no key** — one decompile and you can read it. A key can only come from two places:
this device's secure hardware, or the user's head as a master password. **There is no third
option.**

- **Two key layers, and the layering exists for exactly one reason**: the DEK (256 random bits)
  encrypts every field and **never changes once generated**; wrapped around it is either a
  Keystore key (default) or scrypt (master password). **Turning the master password on or off
  only changes who wraps the DEK; not a single byte of field ciphertext is rewritten** — doing it
  the other way round (iterating all connections, decrypting and re-encrypting) leaves a mess of
  half plaintext and half ciphertext if it fails midway, with no rollback point. The regression
  test's criterion is exactly that **the JSON written to disk is byte-identical** (`SecretsTest`),
  not "can it still be read back".
- **The Keystore key deliberately does not set `setUserAuthenticationRequired`**: with it, every
  password read would require a fingerprint, and background services (WiFi sharing, progress
  reporting during continuous playback) have no way to show a prompt. Anyone wanting that
  strength should enable the master password — which does work for background services (unlock
  once and the DEK stays in process memory).
- **State the capability boundary; do not over-promise**: if `/data/data` is copied wholesale
  (adb pull, a full-device backup) there is genuinely no defense — the Keystore key is generated
  inside the TEE, `getEncoded()` is always null, and **it is not in those files at all**. But
  **once the device is rooted, an attacker can call Keystore as this app** and decrypt. That is
  the shared ceiling of all "invisible encryption" (Chrome and WeChat are no different). Going
  beyond it requires a master password.
- **Encryption hangs off the single read and single write**: `ConnectionStore.all` /
  `persist`. Every caller (the connection dialog, Jellyfin writing back a token, SFTP remembering
  a host key) needed no changes at all — but before doing this, confirm that the backend really
  has only one entry and exit point; if they are scattered, you will miss some.
- **No `enc1:` prefix = old plaintext, return as is. That is the entire migration logic** — no
  migration flag, no version number, no one-shot task.
- **★ On any encryption/decryption failure, return the input unchanged: better to store plaintext
  than to store ciphertext that cannot be opened**, since the latter means the user's password is
  simply gone. When the Keystore key is invalidated by the system (factory reset, or a lock-screen
  change on some devices), the symptom is "this entry's password has to be entered again" while
  the address, user name and label are all still there.
- **Do not encrypt the whole prefs file, only whitelisted fields.** `twig_prefs` holds row
  heights, grid column counts and which directories were expanded last time — encrypting them
  **shrinks the attack surface not at all** (the attack surface has only ever been "is the
  password in plain text"), while costing an IPC to `keystore2` on every read in the startup and
  scrolling paths.
  **`androidx.security-crypto` is even less of an option**: it drags in all of Tink, several
  hundred KB of APK, and Google has already deprecated it. Our own `Secrets` is 60 lines and adds
  0 to the APK (scrypt comes from bcprov, which `fs-restic`/`fs-network` already ship).
- **Backups (`.twigbak`) must be self-contained**: local ciphertext is encrypted with the
  Keystore key and cannot be decrypted on another device, so export **decrypts to plaintext and
  re-encrypts with the export password**, and import decrypts and re-encrypts with its own DEK
  before storing. Copying ciphertext across would be copying a pile of unopenable bytes —
  "another device" in `BackupTest` means clearing all prefs and using a fresh DEK.
- **The save location uses Twig's own directory picker, not SAF** (`PickerActivity.dirIntent`,
  2026-08-25): that way "where to save" and "where the tree can go" are the same thing, so a
  backup can be written straight to SMB/WebDAV/S3 without saving locally and copying. Writing goes
  through `FileSystem.openOutput`, the same path as `CopyEngine`, so cross-backend costs nothing.
  Import is the mirror image (`pathIntentAny` returns a path rather than a content://, so a remote
  file need not be materialized first).
  - `PickerActivity` could only pick files (directories always just expand), so a "pick a
    directory" mode was added: **OK returns the green-highlighted `currentDir`** rather than the
    checked items — the tree expands in place, and entering a directory already moves the
    highlight there, so requiring an extra check would just add a step.
  - ★ **"Pick a directory" already existed in `ShareTargetActivity`** (the "copy here" for content
    shared from other apps). The first version did not go looking and wrote a second one — **and
    wrote it worse**: the existing one disables the button live as `currentDir` changes, the new
    one let you tap and then toasted that it was not allowed. They are now merged into
    `enableOnWritableDir` in `ui/DirPick.kt` and shared.
    Lesson: **there is more than one place that picks a directory in an embedded pane**; before
    adding a third, look at that function. Relying on convention alone leads to divergence, and
    divergence here had no reason behind it beyond having been written twice.
  - ★ Do not use `fs.resolve()` to build the destination path: implementations differ
    (`SftpFileSystem`'s is "no stat, everything is a directory"), so using it to construct a
    **file** to write gives you something with `isDir = true`. If you need a path, build the
    `XFile` yourself and let the caller decide the type (the mirror image of the "never use
    resolve to test existence or type" rule).
  - **Do not overwrite a file with the same name**; auto-number duplicates instead: exporting
    twice in a day is normal, and the file being overwritten may be exactly the previous version
    the user still wanted.
- **Export encryption is optional.** When it is off, **no container is deliberately used** — it is
  just UTF-8 JSON: the only value of that form is "any tool can open and read it", and wrapping it
  in a custom format would erase that one advantage. Import distinguishes them by the first byte
  (`{` vs `T`).
- **★ Investigated and rejected: using an encrypted zip as the backup format** (the existing
  `ZipWriter`+`ZipAes` are right next door, so the code would be nearly free). It founders on
  **the WinZip AES specification hard-coding the KDF as PBKDF2-HMAC-SHA1 with 1000 iterations**
  (`ZipAes.ITERATIONS`); changing it means it is no longer a standard zip and 7-Zip cannot open
  it, which was the only advantage. On a single 4090 that is roughly 10⁷ attempts per second,
  while scrypt (N=32768, 32 MB memory-hard) is around 10³ — **a factor of about ten thousand**.
  A backup file's destiny is to be dropped into cloud storage and sent through chat apps, and once
  leaked it is an **offline, unlimited-retry** attack on every server credential you own; KDF
  strength is the only thing that matters in that scenario. An encrypted zip has another side
  effect too: **entry names and their individual sizes are in plain text**, whereas "pack first,
  then encrypt the whole thing" does not even reveal how many files there are.
- **The plaintext header goes into the GCM AAD as a whole**: the scrypt parameters are written
  into the file (they are needed to decrypt), but anyone lowering N to cheapen an attack only gets
  a file that can never be decrypted. ★ Test this with a **legal but different** N
  (32768 → 16384); writing 1 makes BC reject the parameters outright, so you would be testing
  parameter validation instead of "changing it breaks decryption".
- **token / hostKey / `twig_secure` are not included in backups**: a token necessarily becomes
  invalid on another device (carrying it over only means the first connection eats a 401 before
  logging in again); hostKey is SFTP's TOFU fingerprint, and carrying it over bypasses the
  "verify on first connect" defense; `twig_secure` is the Keystore-wrapped DEK, and **key material
  has no business leaving the device**.
- **Import merges, it does not replace**: connections with the same label and preferences with the
  same key are overwritten from the backup, and anything extra on the new device is kept. A backup
  is "bring the things from that machine over here", not "turn this machine into that one" — the
  latter has no way back if it was a mistake.
- **A generic prefs dump must carry types**: SharedPreferences values come in six types, and
  writing them back without type information makes `getBoolean` throw ClassCastException — the
  symptom being "after importing, the settings page crashes the moment it opens".
- **There is no back door for a forgotten master password, and not even a "reset" entry**
  (★ that item was removed on 2026-08-26). Keeping a Keystore fallback would mean the master
  password never really applied; and that "forgot it — reset" item sat on the **settings page**,
  which **someone who forgot the password cannot reach** (`SecurityUi.gate` stops them before the
  main UI). The only people who could actually reach it were "the phone is unlocked and in someone
  else's hands", so its net effect was **giving an attacker a one-tap button to wipe every saved
  password**. `SecureReset` was deleted entirely and `Secrets.reset` is kept only for resetting
  state in tests. The user's only route is to clear the app's data.
  ★ If it is ever added back, do not put it on the settings page — it belongs on **the unlock
  dialog**, which is where someone who forgot the password actually stops.
- **Fingerprint unlock = a second key for the same DEK, coexisting with the master password**
  (★ 2026-08-27, `ui/Biometrics` + `Secrets.BiometricKey`/`dek_bio`). Both keys open the same DEK
  and whichever is available is used; toggling it **rewrites no field ciphertext**, for the same
  reason as toggling the master password.
  - **This is not the same as the rejected "`dek_ks` fallback"**. `dek_ks` can be obtained
    **silently** as this app once the device is rooted, so keeping it would nullify the master
    password; `dek_bio` carries `setUserAuthenticationRequired(true)`, so every use requires the
    TEE to first obtain a real biometric authentication token and it cannot be retrieved silently.
    That is why it can coexist while `dek_ks` cannot.
  - **★ The master password is always the root; there is no "fingerprint only".** The fingerprint
    key can vanish at any time: the user enrolls a new fingerprint
    (`setInvalidatedByBiometricEnrollment(true)` invalidates the key on the spot — a feature:
    someone who can add a fingerprint to the device should not thereby get every server password),
    clears fingerprints, breaks the sensor, or restores onto a new phone. Without a master password
    behind it, every stored password is permanently unopenable — **and the reset entry has been
    removed**. So the toggle lives beneath the master password item, and turning the master
    password off deletes `dek_bio` with it.
  - **★ Every failure funnels into one path: fall back to the master password dialog.** Tapping
    "Use master password", pressing back, being locked out after too many attempts, an invalidated
    key — all handled identically; distinguishing them further only makes every call site write the
    same `when`. **There must never be a dead end where "the fingerprint does not work, so you
    cannot get in".** On an invalidated key we also delete `dek_bio`, so unlocking does not
    pointlessly try it every time.
  - **Use the platform `android.hardware.biometrics.BiometricPrompt` (API 28+), not
    `androidx.biometric`**: the latter drags in the fragment package for several hundred KB of APK,
    and all it buys is the API 23–27 devices. API 28 **has no `BiometricManager`**, so the question
    "are any fingerprints enrolled" can only be answered by trying to create the key (none enrolled
    → `InvalidAlgorithmParameterException`).
  - **The two-phase design is forced by the API**: `CryptoObject` wants a Cipher that is
    **already initialized but not yet finalized**, and only after authentication do we get to
    encrypt/decrypt. So `BioCrypto` only hands out a Cipher, and `bioSeal`/`bioUnlock` touch the
    data.
  - **★ The negative button's callback and `onAuthenticationError(ERROR_NEGATIVE_BUTTON)` both
    arrive**, so without de-duplication you stack up two password dialogs. Conversely, **do not
    override `onAuthenticationFailed`** — that means "this attempt was not recognized" and the
    prompt keeps waiting for the next one; treating it as a failure turns one crooked press into
    "back to the password dialog".
  - **★ `gate`'s "a dialog is already waiting" bookkeeping needs a separate entry for the
    fingerprint prompt** (`gateBio`, also a per-Activity WeakHashMap): the biometric prompt is not
    an `AlertDialog`, so `gateDialogs[act]?.isShowing` cannot see it, and gating in both `onCreate`
    and `onResume` pops two fingerprint prompts.
  - **What can and cannot be tested**: `MemoryBio` (an in-memory AES key requiring no
    authentication) covers the `Secrets` side — the two keys really do coexist, field ciphertext is
    not rewritten, an invalidated key falls back to the master password, and turning the master
    password off deletes it. The TEE/sensor half cannot be reproduced under Robolectric and can only
    be verified on a real device. Both assertions were verified by inversion.
- **The master password is an "app password": every exported Activity must pass through it**
  (`SecurityUi.gate`, 2026-08-25). Miss one and the master password only locks the front door —
  and each of these entry points can see files and reach servers: "Open with Twig" can open a
  viewer and mount an archive into the tree, "Copy here" can write into any directory, "Pick a
  file" exposes the whole tree, and **a remote-command shortcut runs SSH with the saved password
  directly**.
  - `EntryGateTest` pins this by **scanning the source** (every Activity marked
    `exported="true"` in the manifest must contain `SecurityUi.gate(`). It does not launch each
    Activity: that would require constructing six sets of intents/permissions/external
    dependencies, slow and fragile, while "a new entry point forgot to wire it up" is a static
    fact.
  - ★ **The two headless relay Activities `ViewIntentActivity` / `RunCommandActivity` used to be
    `Theme.Translucent` on a bare `Activity`**, while AppCompat's AlertDialog requires an AppCompat
    theme — showing the unlock dialog crashed (`You need to use a Theme.AppCompat theme`).
    `Theme.Twig.Translucent` (AppCompat DayNight + transparent) was added and the base class changed
    to `AppCompatActivity`; the test also pins "do not go back to the platform theme".
- **"Lock" and "Exit" are two different things** (`ui/AppExit.kt`): locking only drops the in-memory
  DEK and moves to the background — **music keeps playing, terminal sessions stay alive, sharing
  keeps serving** — and coming back just needs the password again; exiting stops all of that and
  calls `finishAffinity()`. They are separate menu items, and "Lock" is only visible when a master
  password is set (without one, locking would not require a password anyway). Exit **does not show
  a confirmation dialog when nothing is running** — at that point a confirmation prevents no loss.
  - Wording: not "Sign out" (that sounds like leaving an account, and Twig has no accounts) and not
    "Forgot password" (elsewhere that is the entry to *recovering* a password, the opposite
    meaning). "Lock" pairs with the unlock dialog's "Unlock Twig".
  - **Exit does not call `exitProcess`**: `SharedPreferences.apply()` writes asynchronously, and
    killing the process can lose preferences just written. After stopping services and calling
    `finishAffinity()` the process gets reclaimed anyway, and `Secrets.lock()` guarantees that
    **even if the process somehow survives, coming back still requires the password** — the
    security does not depend on "did the process really die", which we do not control.
  - **★★ Views and `lateinit` fields must all be created in `onCreate`; only "read the data" belongs
    in `gate`'s callback** — this was stepped on **twice**, the same pattern both times: **deferring
    initialization until after the unlock callback lets lifecycle callbacks run first**. While the
    unlock dialog is up, the Activity still goes through `onStart`/`onResume`: the first time it was
    `PaneFragment.adapter` (crash right after entering the master password), the second time
    `MusicPlayerActivity.plDrawer` (guaranteed when entering from the notification).
    `LockedEntryResumeTest` uses Robolectric to drive the Activity to RESUMED and pin this;
    inverted, it reports the same `lateinit ... has not been initialized`.
  - **★ "A dialog is already waiting" must be tracked per screen (`WeakHashMap<Activity, Dialog>`),
    not with one global boolean**: if a dialog never reaches `dismiss` (the Activity was destroyed),
    that flag stays true forever and **every subsequent gate returns immediately** — the master
    password quietly stops working with nothing on screen to show for it. This bug was found by a
    test going red first, not by thinking about it.
  - **★ Setting `inputType` on the password field is not enough; `setSingleLine()` overrides the dot
    transformation** (2026-08-25): internally it calls
    `setTransformationMethod(SingleLineTransformationMethod)`, so **the password sits on screen in
    plain text while `inputType` looks completely correct**. A password variation is single-line by
    definition, so do not say it again; to be safe, set `PasswordTransformationMethod` explicitly.
    The test's criterion is therefore `transformationMethod`, not `inputType` — the latter is green
    even on the buggy version.
  - **While locked, music keeps playing and the notification buttons keep working, but entering the
    player UI always requires the password**: the playlist, which server the tracks come from, and
    "Locate in file manager" are all behind it.
  - `MainActivity.onResume` needs a gate too: the one in `onCreate` only covers a cold start, while
    returning after a lock goes through onResume. `SecurityUi` uses a `prompting` flag to avoid
    stacking several unlock dialogs.
- **★ Unlocking must happen before pane initialization (learned on a real device 2026-08-25)**: as
  soon as a pane is built it reconnects the servers expanded last time under "remember position",
  and if things are still locked at that moment the "password" it reads is ciphertext — the
  FileSystem instance it builds carries that forever. **Unlocking only repairs the storage layer;
  already-registered instances do not fix themselves**, so the symptom is "after entering the master
  password, WebDAV / media server logins fail, but editing the connection and saving it without
  changing anything makes it work" (saving goes through `forgetServer`, which incidentally
  unregisters the broken instance — so it looks like a configuration problem).
  - The fundamental check lives in `Connections.ensure`: **an already-registered instance is reused
    only when the configuration has not changed**. The scheme is derived from the connection label,
    so changing a password leaves the label and therefore the scheme unchanged, and unconditional
    reuse hands back the instance built from the old configuration. This is the same pitfall as the
    comment on `PaneViewModel.forgetServer`, seen from the other side.
  - **The comparison must exclude `token` / `userId` / `hostKey`** — those are **learned after
    connecting**, not inputs needed to connect. Including them means every token write-back by
    Jellyfin counts as "the configuration changed" → disconnect → reconnect → write back again, in
    a loop. (Backup export excludes exactly the same fields, for a related reason.)
  - ★ **Deferring pane initialization until after RESUMED runs into another long-standing timing
    fragility** (which crashed immediately afterwards, 2026-08-25): the two panes'
    `onViewCreated` run **one at a time**, and the first one to be ready emits its first frame and
    goes `render → onClipTargetChanged → syncStripEnabled`, which asks the **other** side for
    `canModify()` — at which point its `adapter` (lateinit) has not been assigned, crashing on the
    spot (`lateinit property adapter has not been initialized`). This never happened before because
    the panes were committed in `Activity.onCreate`, so by the time there was state to render both
    were ready. The fix is `PaneFragment.isReady()` plus `::adapter.isInitialized` checks in
    `selectionOrCurrent`/`checkedFiles`, with the host doing `takeIf { it.isReady() }` before
    asking. **The full sequence cannot be reconstructed in a test** (`setMaxLifecycle(CREATED)`
    still creates the views), so `PaneReadyTest` pins the defense itself — inverted, it reports the
    exact exception the user saw.
  - The regression test `ConnectionsRebuildTest` uses WebDAV (its constructor issues no network
    request, while SMB/SFTP connect immediately) and asserts on **instance identity**
    (`assertNotSame`). ★ But a scheme is a String, so compare it with `assertEquals` —
    `assertSame` compares references and fails even when the contents match.
- **scrypt needs 32 MB and a few hundred milliseconds, and must never run on the main thread.**
  `SecurityUi.busy` is therefore **two-phase** (background `work` + main-thread `then`) rather than
  taking a suspend block — the latter is far too easy to write as "the whole block on
  Dispatchers.Main", which does not error, it just freezes the UI for half a second.

