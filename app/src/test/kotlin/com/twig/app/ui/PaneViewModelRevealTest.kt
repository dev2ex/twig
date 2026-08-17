package com.twig.app.ui

import android.app.Application
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import com.twig.app.ConnectionStore
import com.twig.app.Connections
import com.twig.app.Favorite
import com.twig.app.FavoritesStore
import com.twig.app.HistoryEntry
import com.twig.app.SavedConnection
import com.twig.core.FsRegistry
import com.twig.core.XFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * `PaneViewModel` 的**定位(revealPath)与收藏解析**链路——上一批(恢复/展开)之外
 * 剩下的那两条,C2 方案 b 要动的正是它们。
 *
 * 网络分支靠 [FakeFileSystem] 冒充服务器:提前注册到目标 scheme 上,
 * `Connections.ensure` 的"已注册就复用"就会直接返回,不会去 new 真的 FtpFileSystem,
 * 于是这些用例既不碰网络也跑得飞快。
 *
 * **没有覆盖的**:收藏的 restic 分支。那条要一个真的加密仓库(scrypt 派生 + 解密
 * config)才能走通,成本远高于收益;`ResticRepo` 本身在 :fs-restic 里已有测试。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PaneViewModelRevealTest {

    private lateinit var app: Application
    private lateinit var vm: PaneViewModel
    private lateinit var ext: File
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        Dispatchers.setMain(dispatcher)
        ext = Environment.getExternalStorageDirectory()
        vm = PaneViewModel(app).apply { io = dispatcher }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun rowKeys() = vm.state.value.rows.map { it.key }

    /**
     * 造一台"服务器":存进 ConnectionStore,并把假 fs 注册到它的确定性 scheme 上。
     * [host] 每个用例给不一样的值—— FsRegistry 是进程级单例,同一个测试类里的用例
     * 共享它,scheme 撞了会互相干扰。
     */
    private fun fakeServer(
        host: String,
        dirs: Map<String, List<String>>,
        files: Map<String, String> = emptyMap(),
    ): Pair<SavedConnection, FakeFileSystem> {
        val conn = SavedConnection(type = "ftp", host = host, port = 21, user = "u")
        ConnectionStore.save(app, conn)
        val fs = FakeFileSystem(Connections.schemeOf(conn), dirs, files)
        FsRegistry.register(fs)
        return conn to fs
    }

    // ---- revealPath:本地 ----

    @Test
    fun `定位到深层本地目录会把沿途每一级都展开`() = runTest(dispatcher) {
        val deep = File(ext, "a/b/c").apply { mkdirs() }
        File(deep, "target.txt").writeText("x")

        vm.revealPath(XFile("file", deep.path, isDir = true))
        advanceUntilIdle()

        val keys = rowKeys()
        for (p in listOf("${ext.path}/a", "${ext.path}/a/b", "${ext.path}/a/b/c")) {
            assertTrue("$p 该在树上", "f:file:$p" in keys)
        }
        assertEquals("f:file:${deep.path}", vm.state.value.currentKey)
        assertTrue("target.txt" in vm.state.value.rows
            .filterIsInstance<PaneViewModel.FileNode>().map { it.file.name })
    }

    /** 手风琴:定位之后,不在这条链上的其它分支要被折起来。 */
    @Test
    fun `定位会折叠掉不在链上的其它分支`() = runTest(dispatcher) {
        val other = File(ext, "旁支").apply { mkdirs() }
        File(other, "o.txt").writeText("o")
        val target = File(ext, "目标/里层").apply { mkdirs() }

        vm.bootstrap(listOf("file\t${ext.path}", "file\t${other.path}"))
        advanceUntilIdle()
        assertTrue("f:file:${other.path}" in rowKeys())

        vm.revealPath(XFile("file", target.path, isDir = true))
        advanceUntilIdle()

        assertTrue("f:file:${target.path}" in rowKeys())
        assertFalse("旁支的子项不该还摊着", "o.txt" in vm.state.value.rows
            .filterIsInstance<PaneViewModel.FileNode>().map { it.file.name })
    }

    /** [focus] 给出时,滚动锚点指向那个文件,而高亮框仍框住目录本身。 */
    @Test
    fun `带 focus 定位时滚动锚点是文件_高亮仍是目录`() = runTest(dispatcher) {
        val d = File(ext, "带焦点").apply { mkdirs() }
        val f = File(d, "song.mp3").apply { writeText("m") }

        vm.revealPath(
            XFile("file", d.path, isDir = true),
            focus = XFile("file", f.path, isDir = false),
        )
        advanceUntilIdle()

        assertEquals("f:file:${d.path}", vm.state.value.currentKey)
        assertEquals("f:file:${f.path}", vm.state.value.scrollKey)
    }

    // ---- revealPath:网络 ----

    /**
     * 本次会话还没展开过这台服务器时,revealPath 要能**按 scheme 反查已保存连接**、
     * 当场把它连起来,而不是直接报错。分组行与服务器行都要展开,链上每一级也要。
     */
    @Test
    fun `定位到没连过的服务器目录会自动建连接并逐级展开`() = runTest(dispatcher) {
        val (conn, fs) = fakeServer(
            "reveal-host",
            dirs = mapOf(
                "/" to listOf("pub"),
                "/pub" to listOf("docs"),
                "/pub/docs" to listOf("readme.txt"),
            ),
            files = mapOf("/pub/docs/readme.txt" to "hi"),
        )
        val scheme = Connections.schemeOf(conn)

        vm.revealPath(XFile(scheme, "/pub/docs", isDir = true))
        advanceUntilIdle()

        val keys = rowKeys()
        assertTrue("FTP 分组要展开", "g:ftp" in keys)
        assertTrue("服务器行要展开", "s:${conn.label()}" in keys)
        assertTrue("f:$scheme:/pub" in keys)
        assertTrue("f:$scheme:/pub/docs" in keys)
        assertEquals("f:$scheme:/pub/docs", vm.state.value.currentKey)
        assertTrue("readme.txt" in vm.state.value.rows
            .filterIsInstance<PaneViewModel.FileNode>().map { it.file.name })
        // 确实是逐级列的,不是一步跳过去
        assertTrue(fs.listed.containsAll(listOf("/", "/pub", "/pub/docs")))
    }

    /** 连接已被删除时不能崩,也不该留下半截状态。 */
    @Test
    fun `定位到已删除连接的目录只报错不崩`() = runTest(dispatcher) {
        vm.revealPath(XFile("ftpdeadbeef", "/x", isDir = true))
        advanceUntilIdle()
        assertNotNull("该给出错误提示", vm.state.value.error)
    }

    // ---- 最近位置 ----

    @Test
    fun `最近位置能跳回某台服务器上的目录`() = runTest(dispatcher) {
        val (conn, _) = fakeServer(
            "history-host",
            dirs = mapOf("/" to listOf("data"), "/data" to listOf()),
        )
        val scheme = Connections.schemeOf(conn)

        assertTrue(vm.revealHistory(HistoryEntry("dir", "/data", conn.label())))
        advanceUntilIdle()

        assertEquals("f:$scheme:/data", vm.state.value.currentKey)
    }

    @Test
    fun `最近位置指向已删除的连接时返回 false`() = runTest(dispatcher) {
        assertFalse(vm.revealHistory(HistoryEntry("dir", "/x", "ftp://nobody@nowhere:21")))
    }

    // ---- 收藏 ----

    @Test
    fun `展开指向服务器目录的收藏会连上并列出子项`() = runTest(dispatcher) {
        val (conn, _) = fakeServer(
            "fav-host",
            dirs = mapOf("/" to listOf("share"), "/share" to listOf("f.txt")),
            files = mapOf("/share/f.txt" to "c"),
        )
        val fav = Favorite(label = "远端", kind = "conn", path = "/share", connLabel = conn.label())
        FavoritesStore.add(app, fav)

        vm.bootstrap(listOf("group\tfav"))
        advanceUntilIdle()

        val node = vm.state.value.rows.filterIsInstance<PaneViewModel.FavoriteNode>()
            .first { it.fav.id == fav.id }
        var ok: Boolean? = null
        vm.toggleFavorite(node, password = null) { r, _ -> ok = r }
        advanceUntilIdle()

        assertEquals(true, ok)
        assertTrue("f.txt" in vm.state.value.rows
            .filterIsInstance<PaneViewModel.FileNode>().map { it.file.name })
        // 收藏行本身就是那个目录,当前目标该同步成它
        assertEquals("/share", vm.state.value.currentDir?.path)
    }

    @Test
    fun `收藏指向的连接已删除时报错而不是崩`() = runTest(dispatcher) {
        val fav = Favorite(label = "孤儿", kind = "conn", path = "/x", connLabel = "ftp://gone@gone:21")
        FavoritesStore.add(app, fav)

        vm.bootstrap(listOf("group\tfav"))
        advanceUntilIdle()

        val node = vm.state.value.rows.filterIsInstance<PaneViewModel.FavoriteNode>()
            .first { it.fav.id == fav.id }
        var ok: Boolean? = null
        var err: String? = null
        vm.toggleFavorite(node, password = null) { r, e -> ok = r; err = e }
        advanceUntilIdle()

        assertEquals(false, ok)
        assertNotNull(err)
    }

    // ---- forgetServer ----

    /**
     * ★ 回归(2026-08-03 第三批修的既有 bug):改完服务器配置后必须把 scheme 从
     * FsRegistry 注销掉。scheme 是按连接标签确定性生成的,只改密码时标签不变、
     * scheme 也不变,不注销的话 `Connections.ensure` 的"已注册就复用"会一直把
     * **旧配置建的**实例还回来——改了密码却不生效,直到重启应用。
     */
    @Test
    fun `forgetServer 会把 scheme 从注册表里注销`() = runTest(dispatcher) {
        val (conn, _) = fakeServer("forget-host", dirs = mapOf("/" to listOf()))
        val scheme = Connections.schemeOf(conn)

        vm.revealPath(XFile(scheme, "/", isDir = true))
        advanceUntilIdle()
        assertTrue("展开后该已注册", FsRegistry.all().any { it.scheme == scheme })

        vm.forgetServer(conn.label())
        advanceUntilIdle()

        assertFalse(
            "注销后不能还留在注册表里,否则改了配置也不生效",
            FsRegistry.all().any { it.scheme == scheme },
        )
    }
}
