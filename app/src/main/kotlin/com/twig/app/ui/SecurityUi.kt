package com.twig.app.ui

import android.content.Context
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.twig.app.R
import com.twig.app.secure.Backup
import com.twig.app.secure.Secrets
import com.twig.core.FsRegistry
import com.twig.core.XFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UI portion of the master password and config backup.
 *
 * Lives in its own file instead of being crammed into [SettingsActivity] — that one is
 * already over 500 lines, and this whole piece (password fields, confirm dialog, scrypt
 * on a background thread, result toast) is a self-contained block. The two SAF launchers
 * must be registered on the Activity, so only those stay in the settings page.
 */
object SecurityUi {

    /** Master password must be at least this long. Won't stop a weak password, but stops "typed two characters by accident and hit OK". */
    private const val MIN_LEN = 6

    // ---- Master password ----

    fun masterSubtitle(ctx: Context): String = ctx.getString(
        when {
            !Secrets.hasMasterPassword(ctx) -> R.string.settings_master_pw_off
            Secrets.bioEnabled(ctx) -> R.string.settings_master_pw_on_bio
            else -> R.string.settings_master_pw_on
        },
    )

    fun showMaster(act: AppCompatActivity, done: () -> Unit) {
        if (!Secrets.hasMasterPassword(act)) {
            enable(act, done)
            return
        }
        // Fingerprint is only an option when "master password is on AND this device supports it" —
        // it's a shortcut for the master password, not a replacement (see [enableBio]'s note).
        val bio = Biometrics.available(act)
        val items = mutableListOf(
            act.getString(R.string.master_pw_change),
            act.getString(R.string.master_pw_disable),
        )
        if (bio) {
            items += act.getString(
                if (Secrets.bioEnabled(act)) R.string.master_pw_bio_disable else R.string.master_pw_bio_enable,
            )
        }
        AlertDialog.Builder(act)
            .setTitle(R.string.settings_master_pw)
            .setItems(items.toTypedArray()) { _, w ->
                when (w) {
                    0 -> change(act, done)
                    1 -> disable(act, done)
                    else -> if (Secrets.bioEnabled(act)) turnOffBio(act, done) else enableBio(act, done)
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /**
     * Enable fingerprint unlock — adds **another** key for the DEK, **alongside** the
     * master password; whichever one we get first is the one we use.
     *
     * This is NOT "use fingerprint instead of master password": the fingerprint key can
     * disappear at any time (a new fingerprint is enrolled and the old one is invalidated
     * automatically, fingerprints cleared, sensor broken, data restored on a new device),
     * so **the master password is always the root**, and forgetting it has no escape.
     * That is why the entry hangs under the master password option — only after enabling
     * the master password can we even talk about enabling this.
     *
     * Requires the current session to be unlocked (so we can get the DEK). The settings
     * page sits behind [gate], so this precondition holds automatically.
     */
    private fun enableBio(act: AppCompatActivity, done: () -> Unit) {
        val cipher = Secrets.bioSealCipher()
        if (cipher == null) {
            // Most of the time this means no fingerprint is enrolled in the system —
            // the key-creation step throws.
            toast(act, act.getString(R.string.master_pw_bio_unavailable))
            return
        }
        Biometrics.authenticate(
            act,
            act.getString(R.string.master_pw_bio_enable),
            act.getString(R.string.dialog_cancel),
            cipher,
            onOk = { c ->
                val ok = Secrets.bioSeal(act, c)
                toast(act, act.getString(if (ok) R.string.master_pw_bio_enabled else R.string.master_pw_bio_unavailable))
                done()
            },
            onFail = {},
        )
    }

    private fun turnOffBio(act: AppCompatActivity, done: () -> Unit) {
        Secrets.disableBio(act)
        toast(act, act.getString(R.string.master_pw_bio_disabled))
        done()
    }

    private fun enable(act: AppCompatActivity, done: () -> Unit) {
        val (view, fields) = form(
            act,
            act.getString(R.string.master_pw_note),
            listOf(act.getString(R.string.master_pw_new), act.getString(R.string.master_pw_repeat)),
        )
        AlertDialog.Builder(act)
            .setTitle(R.string.master_pw_enable)
            .setView(view)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val a = fields[0].text.toString()
                val b = fields[1].text.toString()
                when {
                    a.length < MIN_LEN -> toast(act, act.getString(R.string.master_pw_too_short))
                    a != b -> toast(act, act.getString(R.string.master_pw_mismatch))
                    else -> busy(act, { Secrets.enableMasterPassword(act, a.toCharArray()) }) { ok ->
                        toast(act, act.getString(if (ok) R.string.master_pw_enabled else R.string.master_pw_failed))
                        done()
                    }
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun change(act: AppCompatActivity, done: () -> Unit) {
        val (view, fields) = form(
            act,
            null,
            listOf(
                act.getString(R.string.master_pw_current),
                act.getString(R.string.master_pw_new),
                act.getString(R.string.master_pw_repeat),
            ),
        )
        AlertDialog.Builder(act)
            .setTitle(R.string.master_pw_change)
            .setView(view)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val old = fields[0].text.toString()
                val a = fields[1].text.toString()
                val b = fields[2].text.toString()
                when {
                    a.length < MIN_LEN -> toast(act, act.getString(R.string.master_pw_too_short))
                    a != b -> toast(act, act.getString(R.string.master_pw_mismatch))
                    else -> busy(act, { Secrets.changeMasterPassword(act, old.toCharArray(), a.toCharArray()) }) { ok ->
                        toast(act, act.getString(if (ok) R.string.master_pw_enabled else R.string.master_pw_wrong))
                        done()
                    }
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /**
     * Disable the master password. **Even if this process is already unlocked, we still
     * require re-entering the password** — otherwise anyone holding an already-unlocked
     * phone can simply turn the protection off, and that is exactly what the master
     * password exists to prevent.
     */
    private fun disable(act: AppCompatActivity, done: () -> Unit) {
        val (view, fields) = form(act, null, listOf(act.getString(R.string.master_pw_current)))
        AlertDialog.Builder(act)
            .setTitle(R.string.master_pw_disable)
            .setView(view)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val pw = fields[0].text.toString().toCharArray()
                busy(act, { Secrets.disableMasterPassword(act, pw) }) { ok ->
                    toast(act, act.getString(if (ok) R.string.master_pw_disabled else R.string.master_pw_wrong))
                    done()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /**
     * The unlock dialog currently parked on each Activity. Both `onCreate` and `onResume`
     * come through gate, so without a record we'd stack two dialogs on top of each other.
     *
     * ★ **Don't use a global boolean**: if the dialog never reaches `dismiss` (Activity
     * destroyed, interface reclaimed by the system), that flag stays at true forever, and
     * then **every entry point's gate returns immediately** — the master password silently
     * stops working, with no sign at all. Use a [WeakHashMap] keyed by Activity; once the
     * Activity is gone, the record goes with it.
     */
    private val gateDialogs = java.util.WeakHashMap<AppCompatActivity, AlertDialog>()

    /**
     * The screens currently showing the fingerprint prompt. Same reasoning as [gateDialogs]
     * (record per screen, not a global boolean); just that the fingerprint prompt is not
     * an [AlertDialog], so the `isShowing` check does not cover it.
     */
    private val gateBio = java.util.WeakHashMap<AppCompatActivity, Boolean>()

    /**
     * Entry guard: **if the master password is on, unlock first — every way into Twig is the same**.
     *
     * The main screen, the "copy to here" share-in from another app, "pick file with Twig",
     * "open with Twig", desktop shortcuts — these are all entry points that can see files
     * and reach servers. Miss one, and the master password locks only the front door. So
     * the check sits here; when adding an entry, just copy a line.
     *
     * [onUnlocked] only runs after a real unlock; cancel calls `finish()`, no "still works
     * while locked" middle state.
     */
    fun gate(act: AppCompatActivity, onUnlocked: () -> Unit) {
        if (!Secrets.locked(act)) {
            onUnlocked()
            return
        }
        // This screen already has a dialog waiting (password or fingerprint)
        if (gateDialogs[act]?.isShowing == true || gateBio[act] == true) return
        promptUnlock(act) { ok -> if (ok) onUnlocked() else act.finish() }
    }

    /**
     * Unlock this process. **If fingerprint is enabled, show the fingerprint prompt first,
     * and always hang a "use master password" underneath.**
     *
     * ★ Whenever the fingerprint path can't go through, always fall back to the password
     * dialog — the user tapped "use master password", pressed back, was locked out after
     * too many wrong attempts, the key was invalidated by the system (a new fingerprint
     * was enrolled), … all are handled the same way. **There must be no dead end where
     * "fingerprint unavailable" means "can't get in"**: we just removed the reset entry
     * point, so in that case the user genuinely has no other way.
     */
    fun promptUnlock(act: AppCompatActivity, onDone: (Boolean) -> Unit) {
        val cipher = if (Secrets.bioEnabled(act) && Biometrics.available(act)) {
            Secrets.bioOpenCipher(act)
        } else {
            null
        }
        if (cipher == null) {
            passwordUnlock(act, onDone)
            return
        }
        gateBio[act] = true
        Biometrics.authenticate(
            act,
            act.getString(R.string.master_pw_unlock_title),
            act.getString(R.string.master_pw_use_pw),
            cipher,
            onOk = { c ->
                gateBio.remove(act)
                if (Secrets.bioUnlock(act, c)) onDone(true) else passwordUnlock(act, onDone)
            },
            onFail = {
                gateBio.remove(act)
                passwordUnlock(act, onDone)
            },
        )
    }

    /**
     * Master password unlock dialog. Cancel gives no escape — without the DEK, no saved
     * password is readable, and letting the user keep going with a pile of "password is
     * empty" connections only makes them think their config is gone.
     */
    private fun passwordUnlock(act: AppCompatActivity, onDone: (Boolean) -> Unit) {
        val (view, fields) = form(
            act,
            act.getString(R.string.master_pw_unlock_note),
            listOf(act.getString(R.string.master_pw_current)),
        )
        AlertDialog.Builder(act)
            .setTitle(R.string.master_pw_unlock_title)
            .setView(view)
            .setCancelable(false)
            .setPositiveButton(R.string.dialog_ok, null) // Pass null first, take over below — otherwise a typo closes the dialog.
            .setNegativeButton(R.string.dialog_cancel) { _, _ -> onDone(false) }
            .setOnDismissListener { gateDialogs.remove(act) }
            .show()
            .also { dlg ->
                gateDialogs[act] = dlg
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val pw = fields[0].text.toString().toCharArray()
                    busy(act, { Secrets.unlock(act, pw) }) { ok ->
                        if (ok) {
                            dlg.dismiss()
                            onDone(true)
                        } else {
                            toast(act, act.getString(R.string.master_pw_wrong))
                        }
                    }
                }
            }
    }

    // ---- Backup ----

    /**
     * Ask for the export password, then hand the result to [launch] (the caller uses it
     * to open SAF's "create file"). Empty password = plain-text export, in which case
     * **show the warning first and make the user confirm again**.
     */
    fun askExportPassword(act: AppCompatActivity, launch: (CharArray?) -> Unit) {
        if (Secrets.locked(act)) {
            toast(act, act.getString(R.string.backup_locked))
            return
        }
        val (view, fields) = form(
            act,
            act.getString(R.string.backup_export_note),
            listOf(act.getString(R.string.backup_pw_hint)),
        )
        AlertDialog.Builder(act)
            .setTitle(R.string.backup_export_title)
            .setView(view)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val pw = fields[0].text.toString()
                if (pw.isEmpty()) {
                    AlertDialog.Builder(act)
                        .setTitle(R.string.backup_export_title)
                        .setMessage(R.string.backup_export_warn)
                        .setPositiveButton(R.string.dialog_ok) { _, _ -> launch(null) }
                        .setNegativeButton(R.string.dialog_cancel, null)
                        .show()
                } else {
                    launch(pw.toCharArray())
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /**
     * Once the directory is chosen, actually write it out. **Goes through `FileSystem.openOutput`,
     * not SAF** — so the backup can be written straight to SMB/WebDAV/S3, matching every
     * place the file tree can reach, with no need to land it locally first and copy again.
     *
     * scrypt takes a few hundred milliseconds; the whole thing goes on a background thread.
     */
    fun runExport(act: AppCompatActivity, dir: XFile, pw: CharArray?) {
        act.lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val fs = FsRegistry.of(dir)
                    val target = freeName(fs, dir)
                    fs.openOutput(target).use { Backup.export(act, it, pw) }
                    target.path
                }
            }
            r.fold(
                onSuccess = { toast(act, act.getString(R.string.backup_exported_at, it)) },
                onFailure = { toast(act, act.getString(R.string.backup_export_failed, it.message ?: "")) },
            )
        }
    }

    /**
     * Pick a filename that isn't taken yet.
     *
     * **Never overwrite a same-named file**: exporting twice in a day is common (change a
     * setting, export again), and the file being overwritten might be exactly the previous
     * version the user wanted to keep — backups in particular have no reason to overwrite
     * by default.
     */
    private fun freeName(fs: com.twig.core.FileSystem, dir: XFile): XFile {
        val base = "twig-" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val parent = dir.path.trimEnd('/')
        // ★ Build the XFile ourselves, not through fs.resolve(): that method's semantics
        // vary by implementation (SftpFileSystem's is "no stat, every path is a directory"),
        // so using it to build a **file** we'd then write to gives us an isDir = true thing.
        // We just need a path here; we decide the type.
        fun at(name: String) = XFile(dir.scheme, "$parent/$name", isDir = false)
        var f = at("$base.${Backup.EXT}")
        var n = 2
        while (runCatching { fs.exists(f) }.getOrDefault(false)) {
            f = at("$base-$n.${Backup.EXT}")
            n++
        }
        return f
    }

    /** After a file is chosen: read it in to decide if it's encrypted; only ask for a password if it is. */
    fun runImport(act: AppCompatActivity, file: XFile, done: () -> Unit) {
        act.lifecycleScope.launch {
            val raw = withContext(Dispatchers.IO) {
                runCatching { FsRegistry.of(file).openInput(file).use { Backup.readAll(it) } }.getOrNull()
            }
            if (raw == null) {
                toast(act, act.getString(R.string.backup_import_failed, ""))
                return@launch
            }
            if (Backup.isEncrypted(raw)) {
                val (view, fields) = form(
                    act,
                    act.getString(R.string.backup_import_pw_note),
                    listOf(act.getString(R.string.master_pw_current)),
                )
                AlertDialog.Builder(act)
                    .setTitle(R.string.backup_import_pw_title)
                    .setView(view)
                    .setPositiveButton(R.string.dialog_ok) { _, _ ->
                        doImport(act, raw, fields[0].text.toString().toCharArray(), done)
                    }
                    .setNegativeButton(R.string.dialog_cancel, null)
                    .show()
            } else {
                doImport(act, raw, null, done)
            }
        }
    }

    private fun doImport(act: AppCompatActivity, raw: ByteArray, pw: CharArray?, done: () -> Unit) {
        busy(act, { runCatching { Backup.import(act, raw, pw) } }) { r ->
            r.fold(
                onSuccess = {
                    AlertDialog.Builder(act)
                        .setTitle(R.string.settings_backup_import)
                        .setMessage(
                            act.getString(R.string.backup_imported, it.connections, it.passwords, it.keys, it.prefs) +
                                "\n\n" + act.getString(R.string.backup_restart_note),
                        )
                        .setPositiveButton(R.string.dialog_ok, null)
                        .show()
                    done()
                },
                onFailure = {
                    val msg = when (it) {
                        is Backup.BadPassword -> act.getString(R.string.backup_bad_password)
                        is Backup.BadFormat -> act.getString(R.string.backup_bad_format)
                        else -> act.getString(R.string.backup_import_failed, it.message ?: "")
                    }
                    toast(act, msg)
                },
            )
        }
    }

    // ---- Small tools ----

    /**
     * Explanatory text + a number of password input fields.
     *
     * ★ The note **does not go through `setMessage`**: AlertDialog's content panel and the
     * custom view are two separate panels; message + setView can coexist, but on the same
     * screen we've already stepped on the "message wins, the list doesn't render at all"
     * trap (see the privileged-access section of `SettingsActivity`). Sticking the note
     * and the inputs into setView together removes the structural possibility of a panel
     * fight.
     */
    private fun form(ctx: Context, note: String?, hints: List<String>): Pair<View, List<EditText>> {
        val dp = ctx.resources.displayMetrics.density
        val fields = ArrayList<EditText>()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (12 * dp).toInt(), (20 * dp).toInt(), 0)
            if (note != null) {
                addView(
                    TextView(ctx).apply {
                        text = note
                        textSize = 13f
                        setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                        setPadding(0, 0, 0, (8 * dp).toInt())
                    },
                )
            }
            hints.forEach { h ->
                val et = EditText(ctx).apply {
                    hint = h
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    // ★ The password transformation must be set **explicitly**, and don't call
                    // setSingleLine(): internally that method calls
                    // `setTransformationMethod(SingleLineTransformationMethod)`, which
                    // **completely overrides** the dot transformation that inputType brought
                    // in — the password sits in plaintext on screen, while inputType looks
                    // perfectly right. password variation is already single-line, no need
                    // to ask again.
                    transformationMethod = PasswordTransformationMethod.getInstance()
                }
                fields += et
                addView(et, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        return box to fields
    }

    /**
     * scrypt uses 32 MB of memory and takes a few hundred milliseconds — **[work] must run
     * on a background thread**. Show a non-cancellable dialog meanwhile, so the user
     * doesn't think nothing happened and tap again.
     *
     * Split into [work] (background) and [then] (main thread) instead of one suspend
     * block: the latter is too easy to write as "the whole thing runs on Dispatchers.Main",
     * which is exactly what this function avoids, and it doesn't error — the UI just
     * freezes for half a second.
     */
    private fun <T> busy(act: AppCompatActivity, work: () -> T, then: (T) -> Unit) {
        val dlg = AlertDialog.Builder(act)
            .setMessage(R.string.backup_working)
            .setCancelable(false)
            .show()
        act.lifecycleScope.launch {
            val r = withContext(Dispatchers.Default) { runCatching { work() } }
            runCatching { dlg.dismiss() }
            r.fold(then) { toast(act, it.message ?: act.getString(R.string.master_pw_failed)) }
        }
    }

    private fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
}
