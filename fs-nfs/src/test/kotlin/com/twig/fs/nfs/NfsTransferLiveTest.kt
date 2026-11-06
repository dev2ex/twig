package com.twig.fs.nfs

import com.twig.core.CopyEngine
import com.twig.core.FileSystem
import com.twig.core.FsException
import com.twig.core.FsRegistry
import com.twig.core.RandomSource
import com.twig.core.TransferCheckpoint
import com.twig.core.XFile
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/** Real NFS bytes and handles, with a deterministic source read failure between successful writes. */
class NfsTransferLiveTest {
    @Test fun nfs3ResumesThePartialAndProtectsTheOriginal() = transfer(3, skip = false)
    @Test fun nfs4ResumesThePartialAndProtectsTheOriginal() = transfer(4, skip = false)
    @Test fun nfs3SkipKeepsTheBadSourceAndMovesTheNextFile() = transfer(3, skip = true)
    @Test fun nfs4SkipKeepsTheBadSourceAndMovesTheNextFile() = transfer(4, skip = true)

    private fun transfer(version: Int, skip: Boolean) {
        val host = System.getenv("TWIG_NFS_TEST_HOST").orEmpty()
        assumeTrue(host.isNotEmpty())
        val fs = NfsFileSystem(host, System.getenv("TWIG_NFS_TEST_EXPORT") ?: "/export", version,
            scheme = "nfs-transfer-$version-${System.nanoTime()}",
            port = System.getenv("TWIG_NFS_TEST_PORT")?.toInt() ?: 2049)
        val directory = fs.mkdir(fs.root(), "twig-transfer-$version-${System.nanoTime()}")
        val destination = fs.mkdir(directory, "destination")
        val payload = ByteArray(800_123) { (it * 31).toByte() }
        val bad = fs.createFile(directory, "bad.bin")
        val good = fs.createFile(directory, "good.bin")
        val original = fs.createFile(destination, bad.name)
        fs.openOutput(bad).use { it.write(payload) }
        fs.openOutput(good).use { it.write(byteArrayOf(1, 2, 3)) }
        fs.openOutput(original).use { it.write(byteArrayOf(9, 8, 7)) }
        val firstRead = AtomicLong(-1)
        var failing = true
        val stop = 320_123L
        val source = object : FileSystem by fs {
            override val scheme = fs.scheme + "-source"
            override fun stat(path: String): XFile? = fs.stat(path)?.copy(scheme = scheme)
            override fun openRandom(file: XFile): RandomSource {
                val remote = fs.openRandom(file)
                return object : RandomSource by remote {
                    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                        if (file.path != bad.path) return remote.readAt(position, buffer, offset, length)
                        firstRead.compareAndSet(-1, position)
                        if (failing && position >= stop) throw FsException("Injected source failure")
                        return remote.readAt(position, buffer, offset,
                            if (failing) minOf(length.toLong(), stop - position).toInt() else length)
                    }
                }
            }
        }
        FsRegistry.register(fs)
        FsRegistry.register(source)
        try {
            val files = listOf(source.stat(bad.path)!!, source.stat(good.path)!!)
            val checkpoint = TransferCheckpoint()
            assertThrows(FsException::class.java) {
                CopyEngine.transfer(files, destination, move = true, checkpoint = checkpoint)
            }
            assertArrayEquals(byteArrayOf(9, 8, 7), fs.openInput(original).use { it.readBytes() })
            assertTrue(fs.exists(bad))
            assertTrue(fs.exists(good))
            val partial = checkpoint.partials.values.single().file
            assertEquals(stop, fs.stat(partial.path)!!.size)
            assertArrayEquals(payload.copyOf(stop.toInt()), fs.openInput(partial).use { it.readBytes() })
            val failure = checkpoint.failure!!
            assertEquals(bad.path, failure.source.path)
            assertEquals(destination.path, failure.directory.path)
            assertEquals(bad.name, failure.targetName)
            if (skip) checkpoint.skip(failure) else failing = false
            firstRead.set(-1)
            CopyEngine.transfer(files, destination, move = true, checkpoint = checkpoint)
            if (skip) {
                assertEquals(-1L, firstRead.get())
                assertTrue(fs.exists(bad))
                assertArrayEquals(byteArrayOf(9, 8, 7), fs.openInput(original).use { it.readBytes() })
            } else {
                assertEquals(stop, firstRead.get())
                assertFalse(fs.exists(bad))
                assertArrayEquals(payload, fs.openInput(original).use { it.readBytes() })
                assertFalse(fs.exists(partial))
            }
            assertFalse(fs.exists(good))
            val copiedGood = fs.list(destination).first { it.name == good.name }
            assertArrayEquals(byteArrayOf(1, 2, 3), fs.openInput(copiedGood).use { it.readBytes() })
        } finally {
            try { fs.delete(directory) } finally {
                FsRegistry.unregister(source.scheme)
                FsRegistry.unregister(fs.scheme)
                fs.disconnect()
            }
        }
    }
}
