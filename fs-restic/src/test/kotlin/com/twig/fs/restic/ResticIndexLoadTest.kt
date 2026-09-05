package com.twig.fs.restic

import com.twig.core.FileSystem
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * How many times the index is pulled off the wire.
 *
 * `index/` holds tens of files and tens of megabytes on a real repository, so reading it
 * is the single most expensive thing this reader does over SMB/SFTP. Two rules therefore
 * hold, and both are easy to break by accident:
 *
 *  - **Opening the repository must not touch it at all.** Listing snapshots only reads
 *    the standalone files under `snapshots/`; making the user wait for the index between
 *    "password accepted" and "here are your snapshots" is what 7f73d1d set out to fix by
 *    making the field `by lazy`.
 *  - **It must then be read exactly once**, no matter how many blobs are pulled after.
 */
class ResticIndexLoadTest {

    /** Counts reads of files under `index/`, delegating everything else to local. */
    private class CountingFs(private val local: LocalFileSystem) : FileSystem by local {
        val indexReads = AtomicInteger()
        override fun openInput(file: XFile): InputStream {
            if ("/index/" in file.path) indexReads.incrementAndGet()
            return local.openInput(file)
        }
    }

    private lateinit var repoDir: XFile
    private lateinit var fs: CountingFs

    @Before
    fun setup() {
        val local = LocalFileSystem()
        FsRegistry.register(local)
        fs = CountingFs(local)
        val path = javaClass.classLoader.getResource("repo")!!.path
        repoDir = XFile("file", File(path).absolutePath, isDir = true)
    }

    private fun open() = ResticRepo.open(fs, repoDir, "test123", TEST_ZSTD)

    @Test
    fun openingTheRepositoryDoesNotReadTheIndex() {
        val repo = open()
        assertEquals("opening must not touch index/", 0, fs.indexReads.get())
        // Listing snapshots must not need it either — those are standalone files
        assertEquals(1, repo.snapshots.size)
        assertEquals("listing snapshots must not touch index/", 0, fs.indexReads.get())
    }

    /**
     * With a cache in hand, a second open of the same repository fetches no index at all.
     * That is the whole point on a slow link: the tens of megabytes under index/ are
     * paid for once, ever, because content-addressed files can never change.
     */
    @Test
    fun aCachedIndexIsNotFetchedAgainOnTheNextOpen() {
        val store = HashMap<String, ByteArray>()
        val cache = object : ObjectCache {
            override fun read(key: String) = store[key]
            override fun write(key: String, bytes: ByteArray) { store[key] = bytes }
        }

        val first = ResticRepo.open(fs, repoDir, "test123", TEST_ZSTD, cache)
        first.childrenOfTree(first.snapshots.first().treeId)
        val afterFirstRepo = fs.indexReads.get()
        assertTrue("the first open must actually fetch it", afterFirstRepo > 0)
        assertTrue("and populate the cache", store.isNotEmpty())

        val second = ResticRepo.open(fs, repoDir, "test123", TEST_ZSTD, cache)
        second.childrenOfTree(second.snapshots.first().treeId)
        assertEquals("second open must hit the cache, not the network", afterFirstRepo, fs.indexReads.get())
    }

    /** A cache that never returns anything must not break correctness, only speed. */
    @Test
    fun aBrokenCacheStillReadsCorrectly() {
        val useless = object : ObjectCache {
            override fun read(key: String): ByteArray? = null
            override fun write(key: String, bytes: ByteArray) = throw RuntimeException("disk full")
        }
        val repo = ResticRepo.open(fs, repoDir, "test123", TEST_ZSTD, useless)
        val snap = repo.snapshots.first()
        val names = repo.childrenOfTree(snap.treeId).map { it.name }
        assertTrue("tmp" in names) // the fixture's backup tree root is /tmp/twig_rsrc/...
    }

    @Test
    fun theIndexIsReadOnceNoMatterHowManyBlobsFollow() {
        val repo = open()
        val snap = repo.snapshots.first()

        repo.childrenOfTree(snap.treeId)
        val afterFirst = fs.indexReads.get()

        // Walk the whole backup and read every file in it
        fun walk(treeId: String) {
            for (n in repo.childrenOfTree(treeId)) {
                if (n.isDir) n.subtree?.let { walk(it) } else repo.openFile(n).use { it.readBytes() }
            }
        }
        walk(snap.treeId)

        assertEquals("index/ must not be re-read per blob", afterFirst, fs.indexReads.get())
    }
}
