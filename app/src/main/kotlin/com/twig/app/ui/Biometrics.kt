package com.twig.app.ui

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import javax.crypto.Cipher

/**
 * Fingerprint unlock system glue layer.
 *
 * **Uses the platform `android.hardware.biometrics.BiometricPrompt` (API 28+); does
 * not pull in `androidx.biometric`** — the latter requires dragging in the fragment
 * package and adds several hundred KB to the APK, only to support API 23-27 devices.
 * Size-first, this trade is not worth it; those devices continue using only the master
 * password.
 *
 * The key side lives in [com.twig.app.secure.Secrets.BiometricKey]; this file only
 * handles "pop up the dialog and hand back the authenticated Cipher".
 */
object Biometrics {

    /** Whether this device can currently use fingerprint (hardware present + at least one enrolled + new enough OS). */
    fun available(ctx: Context): Boolean = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.P -> false
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> canAuthenticate(ctx)
        // API 28 has no BiometricManager. Whether a fingerprint is enrolled can only be
        // answered at key-generation time (KeyGenParameterSpec throws if not enrolled);
        // here we only check whether the hardware is present.
        else -> ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun canAuthenticate(ctx: Context): Boolean {
        val bm = ctx.getSystemService(android.hardware.biometrics.BiometricManager::class.java) ?: return false
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Must be Class 3 (STRONG): only that one is accepted for key-bound authentication;
            // weak biometrics (some face implementations) do not count.
            bm.canAuthenticate(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            bm.canAuthenticate()
        }
        return ok == android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * Pops up the authentication dialog. [onOk] receives the **already-authenticated** Cipher,
     * which can be passed straight to doFinal.
     *
     * **Every unsuccessful path funnels into [onFail]** (user tapped "Use master password",
     * pressed back, fingerprint retried too many times and got locked, sensor broken…): the
     * caller handles all of these the same way — fall back to the master password. Splitting
     * them further would just make each call site repeat the same when.
     *
     * ★ The negative button callback and `onAuthenticationError(ERROR_NEGATIVE_BUTTON)` arrive
     * **simultaneously**, so we must deduplicate ourselves, otherwise two password dialogs
     * stack on top of each other.
     */
    fun authenticate(
        act: AppCompatActivity,
        title: String,
        negative: String,
        cipher: Cipher,
        onOk: (Cipher) -> Unit,
        onFail: () -> Unit,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            onFail()
            return
        }
        prompt(act, title, negative, cipher, onOk, onFail)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun prompt(
        act: AppCompatActivity,
        title: String,
        negative: String,
        cipher: Cipher,
        onOk: (Cipher) -> Unit,
        onFail: () -> Unit,
    ) {
        var done = false
        val ok = { c: Cipher -> if (!done) { done = true; onOk(c) } }
        val fail = { if (!done) { done = true; onFail() } }
        val exec = act.mainExecutor
        runCatching {
            BiometricPrompt.Builder(act)
                .setTitle(title)
                .setNegativeButton(negative, exec) { _, _ -> fail() }
                .build()
                .authenticate(
                    BiometricPrompt.CryptoObject(cipher),
                    CancellationSignal(),
                    exec,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            val c = result.cryptoObject?.cipher
                            if (c != null) ok(c) else fail()
                        }

                        // Note: we deliberately do not override onAuthenticationFailed: that
                        // means "this attempt did not match", and the dialog keeps waiting for
                        // the next attempt — it should not be treated as failure and fall back
                        // to the password.
                        override fun onAuthenticationError(code: Int, msg: CharSequence?) = fail()
                    },
                )
        }.onFailure { fail() }
    }
}
