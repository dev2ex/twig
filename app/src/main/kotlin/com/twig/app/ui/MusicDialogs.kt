package com.twig.app.ui

import android.app.Activity
import android.content.Context
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.twig.app.FileInfo
import com.twig.app.Playlist
import com.twig.app.PlaylistStore
import com.twig.app.PlaylistTrack
import com.twig.app.R
import com.twig.core.XFile

/** 音乐界面共用的对话框:音乐信息 / 分享 / 发送到播放列表(可新建)。
 *  [showInfo] 与来源无关(内容全由 [FileInfo] 给),图片查看器也复用它,只换标题。 */
object MusicDialogs {

    fun showInfo(activity: Activity, file: XFile, titleRes: Int = R.string.music_info) {
        Thread {
            val details = runCatching { FileInfo.load(activity, file) }.getOrNull()
            val msg = details?.sections?.joinToString("\n\n") { s ->
                s.title + "\n" + s.rows.joinToString("\n") { "  ${it.first}: ${it.second}" }
            } ?: file.name
            activity.runOnUiThread {
                if (activity.isFinishing) return@runOnUiThread
                AlertDialog.Builder(activity)
                    .setTitle(titleRes)
                    .setMessage(msg)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * 跳到文件管理并在树中展开定位到该曲目所在目录、滚动到曲目本身
     * (网络来源需该服务器本次会话已连接过)。
     */
    fun locateInFileManager(ctx: Context, file: XFile) {
        ctx.startActivity(
            com.twig.app.MainActivity.revealIntent(ctx, file.scheme, file.parentPath, file.path),
        )
    }

    fun share(ctx: Context, file: XFile) = com.twig.app.OpenFiles.share(ctx, file)

    /** 选择目标列表(或新建)把 [tracks] 加入。 */
    fun sendToPlaylist(activity: Activity, tracks: List<PlaylistTrack>) {
        val lists = PlaylistStore.all(activity).filter { it.id != Playlist.NOW }
            .sortedBy { if (it.isFav) 0 else 1 }
        val names = lists.map { it.name } + activity.getString(R.string.music_new_playlist)
        AlertDialog.Builder(activity)
            .setTitle(R.string.music_send_to)
            .setItems(names.toTypedArray()) { _, which ->
                if (which < lists.size) {
                    PlaylistStore.addTracks(activity, lists[which].id, tracks)
                } else {
                    promptNewList(activity) { name ->
                        PlaylistStore.create(activity, name, tracks)
                    }
                }
            }
            .show()
    }

    fun promptNewList(activity: Activity, onName: (String) -> Unit) {
        val input = EditText(activity).apply { hint = activity.getString(R.string.music_new_playlist_hint) }
        AlertDialog.Builder(activity)
            .setTitle(R.string.music_new_playlist)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) onName(name)
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }
}
