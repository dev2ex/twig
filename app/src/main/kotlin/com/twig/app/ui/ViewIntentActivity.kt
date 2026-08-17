package com.twig.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import com.twig.app.MainActivity
import com.twig.app.OpenFiles
import com.twig.app.PlaylistStore
import com.twig.app.PlaylistTrack
import com.twig.app.Prefs
import com.twig.app.R
import com.twig.app.ShareSourceFileSystem
import com.twig.app.SortSpec
import com.twig.app.TwigApp
import com.twig.core.FsRegistry
import com.twig.core.XFile
import java.io.File

/**
 * 「用 Twig 打开」的入口:接住其他 App 发来的 ACTION_VIEW,把 file:// / content:// URI
 * 包成 [XFile] 后分发给内置查看器(文本/图片/音频/视频)或主界面(压缩包就地挂载展开)。
 *
 * 自身不显示任何界面(透明主题),分发完立刻 finish。
 *
 * ★ 目标查看器必须启动在**本 Activity 所在的任务栈里**(不加 FLAG_ACTIVITY_NEW_TASK):
 * content:// 的临时读权限随"接收方任务栈"存活,本 Activity finish 掉之后,只要同栈里还有
 * 我们的界面,权限就还在;一旦丢到新任务栈,查看器可能一读就 SecurityException。
 */
class ViewIntentActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TwigApp.registerBaseFs(this) // 冷启动时 FsRegistry 还是空的(主界面没起来过)
        val uri = intent?.data
        val file = uri?.let { runCatching { toXFile(it) }.getOrNull() }
        if (file == null) {
            Toast.makeText(this, R.string.err_unsupported_type, Toast.LENGTH_SHORT).show()
            finish(); return
        }
        dispatch(file, intent.type)
        finish()
    }

    /** file:// 直接落到本地来源;其余(content://)交给 [ShareSourceFileSystem] 承载。 */
    private fun toXFile(uri: Uri): XFile? = when (uri.scheme?.lowercase()) {
        "file" -> uri.path?.let { p ->
            val f = File(p)
            if (!f.isFile) null
            else XFile("file", f.absolutePath, isDir = false, size = f.length(), lastModified = f.lastModified())
        }
        "content" -> (FsRegistry.of(ShareSourceFileSystem.SCHEME) as ShareSourceFileSystem).wrap(uri)
        else -> null
    }

    /**
     * 分发:优先按文件名扩展名判断(与面板里点开同一套 [OpenFiles] 规则),
     * 扩展名认不出来时(content:// 的显示名可能没后缀)再退回调用方给的 MIME 大类。
     */
    private fun dispatch(file: XFile, mime: String?) {
        when {
            // apk 虽然也是 zip,但树里默认不当压缩包展开(见 PaneViewModel.expandableArchive),
            // 挂进去只会得到一行点不开的死行;交给系统安装器更合用场景
            OpenFiles.isApk(file) -> if (!OpenFiles.openWith(this, file)) HexViewerActivity.start(this, file)
            com.twig.fs.archive.Archives.isArchive(file) -> mountArchive(file)
            OpenFiles.isText(file) -> TextViewerActivity.start(this, file)
            OpenFiles.isImage(file) -> openImage(file)
            OpenFiles.isPlaylist(file) -> openM3u(file)
            OpenFiles.isAudio(file) -> openAudio(file)
            OpenFiles.isVideo(file) -> MediaPlayerActivity.start(this, file)
            else -> when (mime?.substringBefore('/')?.lowercase()) {
                "text" -> TextViewerActivity.start(this, file)
                "image" -> openImage(file)
                "audio" -> openAudio(file)
                "video" -> MediaPlayerActivity.start(this, file)
                else -> HexViewerActivity.start(this, file) // 认不出类型的至少能看字节
            }
        }
    }

    /** 本地图片顺带把同目录图片按面板排序一起带上,左右滑能翻;非本地来源只看这一张。 */
    private fun openImage(file: XFile) {
        val siblings = localSiblings(file) { OpenFiles.isImage(it) }
        val index = siblings.indexOfFirst { it.path == file.path }.coerceAtLeast(0)
        // 从别的应用进来,没有文件树可同步勾选,不给「选择」入口
        ImageViewerActivity.start(this, siblings.ifEmpty { listOf(file) }, index, allowSelect = false)
    }

    /**
     * 音频一律进音乐播放器(封面/歌词/波形/后台播放都在那边):本地的顺带把同目录音频
     * 入"当前播放",能上一首/下一首;content:// 只有孤零零一首,队列里就它一个。
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun openAudio(file: XFile) {
        val siblings = localSiblings(file) { OpenFiles.isAudio(it) }.ifEmpty { listOf(file) }
        val tracks = siblings.map { trackOf(it) }
        val startIndex = siblings.indexOfFirst { it.path == file.path }.coerceAtLeast(0)
        val listName = if (file.scheme == "file") {
            file.parentPath.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }
        } else file.name
        val now = PlaylistStore.setNow(this, listName, tracks)
        MusicEngine.play(this, now, startIndex, autoPlay = true)
        startActivity(Intent(this, MusicPlayerActivity::class.java))
    }

    /**
     * 播放列表曲目。content:// 走 "share" 种类([MusicEngine] 里对应 [ShareSourceFileSystem]),
     * 名字必须随身带——URI 的末段是 provider 的内部 id,取不出文件名更取不出扩展名。
     */
    private fun trackOf(f: XFile) = PlaylistTrack(
        kind = if (f.scheme == "file") "local" else "share",
        path = f.path, size = f.size, lastModified = f.lastModified,
        displayName = if (f.scheme == "file") "" else f.name,
    )

    /**
     * m3u/m3u8:只有本地列表解析得出东西(条目多为相对路径,且要能持久化进播放列表),
     * 其余来源当文本看。解析是本地小文件读,直接同步做——这个中转页本身就是"读完即走"。
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun openM3u(file: XFile) {
        if (file.scheme != "file") { TextViewerActivity.start(this, file); return }
        val tracks = com.twig.app.M3uPlaylist.parse(file).filter { it.scheme == "file" }.map { trackOf(it) }
        if (tracks.isEmpty()) {
            Toast.makeText(this, R.string.music_no_playable, Toast.LENGTH_SHORT).show()
            TextViewerActivity.start(this, file)
            return
        }
        val name = file.name.substringBeforeLast('.').ifEmpty { file.name }
        MusicEngine.play(this, PlaylistStore.setNow(this, name, tracks), 0, autoPlay = true)
        startActivity(Intent(this, MusicPlayerActivity::class.java))
    }

    /** 压缩包:回到主界面,在当前面板里把它挂成一行就地展开(与树里点开压缩包同一条路径)。 */
    private fun mountArchive(file: XFile) {
        startActivity(MainActivity.mountIntent(this, file))
    }

    /**
     * 同目录里同类文件(按面板当前排序);非本地来源返回空列表——外部传进来的
     * content:// 只是一个孤立条目,没有"同目录"可言。
     */
    private fun localSiblings(file: XFile, keep: (XFile) -> Boolean): List<XFile> {
        if (file.scheme != "file") return emptyList()
        val dir = XFile("file", file.parentPath, isDir = true)
        val kids = runCatching { FsRegistry.of(dir).list(dir) }.getOrDefault(emptyList())
        val showHidden = Prefs.showHidden(this)
        return SortSpec.load(this)
            .sort(kids.filter { !it.isDir && keep(it) && (showHidden || !it.name.startsWith(".")) })
    }
}
