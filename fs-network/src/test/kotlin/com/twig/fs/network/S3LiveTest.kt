package com.twig.fs.network

import com.twig.core.XFile
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest

/**
 * 打真服务端的集成测试(MinIO / AWS S3 / R2 …)。**没配环境变量就整个跳过**,
 * 所以 `./gradlew test` 在任何机器上都照常绿。
 *
 * ```
 * TWIG_S3_ENDPOINT=http://127.0.0.1:9000 TWIG_S3_KEY=<key> \
 * TWIG_S3_SECRET=<secret> TWIG_S3_BUCKET=<bucket> \
 *   ./gradlew :fs-network:test --tests "*S3LiveTest*"
 * ```
 *
 * 为什么值得单独有这么一条:[S3FileSystemTest] 是拿 mockwebserver 对着**我们自己**
 * 的报文认知断言的,它证明不了签名能被真实服务端接受——SigV4 少签一个头、canonical
 * 字符串差一个字节,mock 一样绿,真机上却是清一色的 403 SignatureDoesNotMatch。
 *
 * 所有写操作都关在 [PREFIX] 下,跑完即删,不碰桶里其它东西。
 */
class S3LiveTest {

    private val endpoint = System.getenv("TWIG_S3_ENDPOINT")
    private val bucket = System.getenv("TWIG_S3_BUCKET").orEmpty()

    private lateinit var fs: S3FileSystem

    /** 本次跑的沙盒目录,跑完整棵删掉。 */
    private val dir get() = XFile(S3FileSystem.SCHEME, "/$PREFIX", isDir = true)

    @Before
    fun setup() {
        assumeFalse("TWIG_S3_ENDPOINT not set — skipping live S3 test", endpoint.isNullOrEmpty())
        fs = S3FileSystem(
            S3Config(
                endpoint = endpoint!!,
                accessKey = System.getenv("TWIG_S3_KEY").orEmpty(),
                secretKey = System.getenv("TWIG_S3_SECRET").orEmpty(),
                region = System.getenv("TWIG_S3_REGION") ?: "us-east-1",
                bucket = bucket,
                pathStyle = System.getenv("TWIG_S3_VHOST").isNullOrEmpty(),
            ),
        )
        runCatching { fs.delete(dir) }
        fs.mkdir(fs.root(), PREFIX)
    }

    @After
    fun tearDown() {
        if (this::fs.isInitialized) runCatching { fs.delete(dir) }
    }

    // ---- 读 ----

    /** 最基本的一条:签名能被真实服务端接受。挂在这里 = SigV4 有问题。 */
    @Test
    fun canListBucket() {
        val names = fs.list(fs.root()).map { it.name }
        assertTrue("bucket looks empty — seed files missing?", names.isNotEmpty())
        assertTrue(names.contains(PREFIX))
    }

    /** 桶留空时根目录列出所有桶(凭证得有 ListAllMyBuckets 权限)。 */
    @Test
    fun canListAllBuckets() {
        val all = S3FileSystem(
            S3Config(
                endpoint = endpoint!!,
                accessKey = System.getenv("TWIG_S3_KEY").orEmpty(),
                secretKey = System.getenv("TWIG_S3_SECRET").orEmpty(),
                region = System.getenv("TWIG_S3_REGION") ?: "us-east-1",
                bucket = "",
                pathStyle = System.getenv("TWIG_S3_VHOST").isNullOrEmpty(),
            ),
        )
        val buckets = all.list(all.root()).map { it.name }
        assertTrue("expected $bucket among $buckets", buckets.contains(bucket))
        assertTrue(all.list(all.root()).all { it.isDir })
    }

    /**
     * 名字里带空格 / 加号 / 百分号 / 中文的对象都要能**列出来并且读得到**。
     * 读得到才是关键:名字解码错的话列表看着没问题,一点开就 404。
     */
    @Test
    fun handlesAwkwardObjectNames() {
        val names = listOf("plain.txt", "with space.txt", "a+b.txt", "100% done #1.txt", "报告.txt")
        for ((i, n) in names.withIndex()) {
            fs.openOutput(XFile(S3FileSystem.SCHEME, "/$PREFIX/$n", isDir = false)).use {
                it.write("body-$i".toByteArray())
            }
        }
        assertEquals(names.sortedBy { it.lowercase() }, fs.list(dir).map { it.name }.sortedBy { it.lowercase() })

        for ((i, n) in names.withIndex()) {
            val got = fs.openInput(XFile(S3FileSystem.SCHEME, "/$PREFIX/$n", isDir = false))
                .use { String(it.readBytes()) }
            assertEquals("reading back '$n'", "body-$i", got)
        }
    }

    /** HTTP Range 定位读:播放器 seek 与网络视频缩略图全靠它。 */
    @Test
    fun randomAccessReadsExactBytes() {
        val data = ByteArray(300_000) { (it * 7 % 251).toByte() }
        val f = XFile(S3FileSystem.SCHEME, "/$PREFIX/range.bin", isDir = false)
        fs.openOutput(f).use { it.write(data) }

        fs.openRandom(f.copy(size = data.size.toLong())).use { src ->
            val buf = ByteArray(1000)
            val n = src.readAt(123_456, buf, 0, 1000)
            assertTrue(n > 0)
            assertArrayEquals(data.copyOfRange(123_456, 123_456 + n), buf.copyOf(n))

            // 往回跳:池里没有吻合位置的流,必须重开一个 Range 请求
            val back = ByteArray(16)
            val m = src.readAt(10, back, 0, 16)
            assertArrayEquals(data.copyOfRange(10, 10 + m), back.copyOf(m))
        }
    }

    // ---- 写 ----

    @Test
    fun smallUploadRoundTrips() {
        val f = XFile(S3FileSystem.SCHEME, "/$PREFIX/small.txt", isDir = false)
        fs.openOutput(f).use { it.write("hello s3".toByteArray()) }
        assertTrue(fs.exists(f))
        assertEquals("hello s3", fs.openInput(f).use { String(it.readBytes()) })
        assertEquals(8L, fs.list(dir).first { it.name == "small.txt" }.size)
    }

    /** 超过一片(8 MiB)会转分片上传——真服务端才会校验 ETag 与分片顺序。 */
    @Test
    fun multipartUploadRoundTrips() {
        val data = ByteArray(9 * 1024 * 1024) { (it * 31 % 251).toByte() }
        val f = XFile(S3FileSystem.SCHEME, "/$PREFIX/big.bin", isDir = false)
        fs.openOutput(f).use { it.write(data) }

        assertEquals(data.size.toLong(), fs.list(dir).first { it.name == "big.bin" }.size)
        val got = fs.openInput(f).use { it.readBytes() }
        // 内容比对用摘要:9 MB 的数组直接 assertArrayEquals,失败时输出能刷满整个屏幕
        assertEquals(sha256(data), sha256(got))
    }

    @Test
    fun emptyFileRoundTrips() {
        val f = XFile(S3FileSystem.SCHEME, "/$PREFIX/empty.txt", isDir = false)
        fs.openOutput(f).use { }
        assertTrue(fs.exists(f))
        assertEquals(0, fs.openInput(f).use { it.readBytes() }.size)
    }

    // ---- 目录 ----

    /** S3 没有目录,空目录靠占位符撑着——建完必须还看得见。 */
    @Test
    fun emptyDirectorySurvivesListing() {
        fs.mkdir(dir, "empty-dir")
        val d = fs.list(dir).firstOrNull { it.name == "empty-dir" }
        assertTrue("empty directory disappeared", d != null && d.isDir)
        // 占位符本身不该作为一个 0 字节文件冒出来
        assertTrue(fs.list(d!!).isEmpty())
    }

    @Test
    fun nestedDirectoriesFoldIntoTree() {
        fs.mkdir(dir, "a")
        val a = XFile(S3FileSystem.SCHEME, "/$PREFIX/a", isDir = true)
        fs.mkdir(a, "b")
        fs.openOutput(XFile(S3FileSystem.SCHEME, "/$PREFIX/a/b/leaf.txt", isDir = false))
            .use { it.write("leaf".toByteArray()) }

        assertEquals(listOf("b"), fs.list(a).map { it.name })
        val b = fs.list(a).first()
        assertTrue(b.isDir)
        assertEquals(listOf("leaf.txt"), fs.list(b).map { it.name })
    }

    @Test
    fun deleteRemovesWholeSubtree() {
        fs.mkdir(dir, "doomed")
        val d = XFile(S3FileSystem.SCHEME, "/$PREFIX/doomed", isDir = true)
        fs.openOutput(XFile(S3FileSystem.SCHEME, "/$PREFIX/doomed/x.txt", isDir = false))
            .use { it.write("x".toByteArray()) }
        fs.delete(d)
        assertFalse(fs.list(dir).any { it.name == "doomed" })
    }

    // ---- 改名 / 移动 ----

    @Test
    fun renameMovesObjectServerSide() {
        val f = XFile(S3FileSystem.SCHEME, "/$PREFIX/before.txt", isDir = false)
        fs.openOutput(f).use { it.write("keep me".toByteArray()) }

        val after = fs.rename(f, "after.txt")
        assertEquals("/$PREFIX/after.txt", after.path)
        assertEquals("keep me", fs.openInput(after).use { String(it.readBytes()) })
        assertFalse(fs.exists(f))
    }

    @Test
    fun renameDirectoryTakesItsContents() {
        fs.mkdir(dir, "old")
        fs.openOutput(XFile(S3FileSystem.SCHEME, "/$PREFIX/old/inside.txt", isDir = false))
            .use { it.write("inside".toByteArray()) }

        fs.rename(XFile(S3FileSystem.SCHEME, "/$PREFIX/old", isDir = true), "new")
        val names = fs.list(dir).map { it.name }
        assertTrue(names.contains("new"))
        assertFalse(names.contains("old"))
        assertEquals(
            "inside",
            fs.openInput(XFile(S3FileSystem.SCHEME, "/$PREFIX/new/inside.txt", isDir = false))
                .use { String(it.readBytes()) },
        )
    }

    @Test
    fun moveWithinIsServerSide() {
        fs.mkdir(dir, "dest")
        val f = XFile(S3FileSystem.SCHEME, "/$PREFIX/movable.txt", isDir = false)
        fs.openOutput(f).use { it.write("moved".toByteArray()) }

        val ok = fs.moveWithin(f, XFile(S3FileSystem.SCHEME, "/$PREFIX/dest", isDir = true), "movable.txt")
        assertTrue(ok)
        assertFalse(fs.exists(f))
        assertEquals(
            "moved",
            fs.openInput(XFile(S3FileSystem.SCHEME, "/$PREFIX/dest/movable.txt", isDir = false))
                .use { String(it.readBytes()) },
        )
    }

    /** 接口约定:同名目标已存在必须抛,不得静默吃掉那个文件。 */
    @Test
    fun renameOntoExistingFails() {
        val a = XFile(S3FileSystem.SCHEME, "/$PREFIX/a.txt", isDir = false)
        val b = XFile(S3FileSystem.SCHEME, "/$PREFIX/b.txt", isDir = false)
        fs.openOutput(a).use { it.write("A".toByteArray()) }
        fs.openOutput(b).use { it.write("B".toByteArray()) }

        assertTrue(runCatching { fs.rename(a, "b.txt") }.isFailure)
        assertEquals("B", fs.openInput(b).use { String(it.readBytes()) }) // 没被覆盖
        assertTrue(fs.exists(a))
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") {
        "%02x".format(it)
    }

    companion object {
        private const val PREFIX = "_twig_livetest"
    }
}
