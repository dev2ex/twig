package com.twig.app

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.twig.core.FileSystem
import com.twig.core.FsRegistry
import com.twig.fs.nfs.NfsFileSystem

/** Compiled only with -PwithNfs=true. */
internal object NfsSupport {
    const val enabled = true

    @Suppress("UNUSED_PARAMETER")
    fun connect(ctx: Context, conn: SavedConnection, scheme: String, onInfo: (String) -> Unit) {
        val fs = NfsFileSystem(conn.host, conn.share, conn.nfsVersion, conn.nfsUid, conn.nfsGid, scheme, conn.nfsPort)
        fs.connect()
        onInfo("NFS${conn.nfsVersion}")
        FsRegistry.register(fs)
    }

    fun disconnect(fs: FileSystem?) {
        if (fs is NfsFileSystem) runCatching { fs.disconnect() }
    }

    fun showEditor(activity: MainActivity, edit: SavedConnection?, save: (SavedConnection) -> Unit) = with(activity) {
        val d = com.twig.app.databinding.DialogNfsBinding.inflate(layoutInflater)
        edit?.let {
            d.etName.setText(it.name); d.etHost.setText(it.host); d.etPath.setText(it.share)
            d.etVersion.setText(it.nfsVersion.toString()); d.etPort.setText(it.nfsPort.toString())
            d.etUser.setText(it.nfsUid.toString()); d.etPass.setText(it.nfsGid.toString())
        }
        fun splitAddress() {
            val raw = d.etHost.text.toString()
            val endpoint = runCatching {
                com.twig.fs.nfs.NfsEndpoint.parse(raw, d.etPort.text.toString().toIntOrNull() ?: 2049)
            }.getOrNull() ?: return
            if (raw != endpoint.host) {
                d.etPort.setText(endpoint.port.toString())
                d.etHost.setText(endpoint.host)
                d.etHost.setSelection(d.etHost.length())
            }
        }
        d.etHost.setOnFocusChangeListener { _, focused -> if (!focused) splitAddress() }
        d.etHost.addTextChangedListener(object : android.text.TextWatcher {
            private var pasted = false
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { pasted = count > 1 }
            override fun afterTextChanged(s: android.text.Editable?) {
                // Do not split a manually typed port as soon as its first digit arrives.
                if (pasted) { pasted = false; splitAddress() }
            }
        })
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (edit == null) getString(R.string.action_add_nfs) else getString(R.string.server_edit))
            .setView(d.root)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_ok, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val host = d.etHost.text.toString().trim()
                val export = d.etPath.text.toString().trim()
                val version = d.etVersion.text.toString().toIntOrNull()
                val port = d.etPort.text.toString().toIntOrNull()
                val uid = d.etUser.text.toString().toIntOrNull()
                val gid = d.etPass.text.toString().toIntOrNull()
                if (port == null || port !in 1..65535) {
                    d.etPort.error = getString(R.string.nfs_port); return@setOnClickListener
                }
                val endpoint = runCatching { com.twig.fs.nfs.NfsEndpoint.parse(host, port) }.getOrNull()
                if (endpoint == null) { d.etHost.error = getString(R.string.nfs_host); return@setOnClickListener }
                if (!export.startsWith('/') || '\u0000' in export) {
                    d.etPath.error = getString(R.string.nfs_export); return@setOnClickListener
                }
                if (version != 3 && version != 4) {
                    d.etVersion.error = getString(R.string.nfs_version); return@setOnClickListener
                }
                if (uid == null || uid < 0) { d.etUser.error = getString(R.string.nfs_uid); return@setOnClickListener }
                if (gid == null || gid < 0) { d.etPass.error = getString(R.string.nfs_gid); return@setOnClickListener }
                save(SavedConnection("nfs", endpoint.host, share = export,
                    name = d.etName.text.toString().trim(), nfsVersion = version,
                    nfsUid = uid, nfsGid = gid, nfsPort = endpoint.port))
                dialog.dismiss()
            }
        }
        dialog.show()
    }
}
