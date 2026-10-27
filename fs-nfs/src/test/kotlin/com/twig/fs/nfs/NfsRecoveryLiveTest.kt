package com.twig.fs.nfs

import com.twig.core.FsException
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.Socket

/** A local fault proxy forwards 127.0.0.1:2049 to a disposable NFSv4 export. */
class NfsRecoveryLiveTest {
    @Test fun directoryListingRecoversAfterSocketLossAndNetworkReturn() {
        assumeTrue(System.getenv("TWIG_NFS_FAULT_PROXY") == "true")
        val fs = NfsFileSystem("127.0.0.1", "/export", version = 4)
        command("ONLINE")
        try {
            val before = fs.list(fs.root())
            command("DROP")
            // A cached context's TCP socket is gone, but a new connection can succeed.
            assertEquals(before, fs.list(fs.root()))
            command("DROP")
            assertTrue(fs.stat("/")!!.isDir)
            command("DROP")
            assertTrue(fs.exists(fs.root()))
            // Mutations must fail rather than replaying an uncertain operation.
            val name = "twig-recovery-${System.nanoTime()}"
            command("DROP")
            try { fs.mkdir(fs.root(), name); fail("A failed mutation must not retry") }
            catch (_: FsException) { }
            val unexpected = fs.stat("/$name")
            if (unexpected != null) fs.delete(unexpected)
            assertNull(unexpected)
            command("OFFLINE")
            try { fs.list(fs.root()); fail("Offline listing must fail, not return empty") }
            catch (_: FsException) { }
            command("ONLINE")
            // The failed retry must also be discarded, so the same filesystem recovers.
            assertEquals(before, fs.list(fs.root()))
            fs.disconnect()
            // A fresh mount after disconnect must still work.
            assertEquals(before, fs.list(fs.root()))
        } finally {
            command("ONLINE")
            fs.disconnect()
        }
    }

    @Test fun failedWriteAndCloseDoNotReplayAndThePartialCanResumeAfterRecovery() {
        assumeTrue(System.getenv("TWIG_NFS_FAULT_PROXY") == "true")
        val fs = NfsFileSystem("127.0.0.1", "/export", version = 4)
        command("ONLINE")
        val directory = fs.mkdir(fs.root(), "twig-write-recovery-${System.nanoTime()}")
        val file = fs.createFile(directory, "partial.bin")
        val prefix = ByteArray(320_123) { (it * 17).toByte() }
        val suffix = ByteArray(480_000) { (it * 31).toByte() }
        val output = fs.openOutput(file)
        try {
            output.write(prefix)
            command("OFFLINE")
            assertThrows(FsException::class.java) { output.write(suffix) }
            // A close/commit failure must reach CopyEngine rather than marking this file complete.
            assertThrows(FsException::class.java) { output.close() }
            command("ONLINE")
            assertEquals(prefix.size.toLong(), fs.stat(file.path)!!.size)
            fs.appendPartial(file.copy(size = 1)).use { it.write(suffix) }
            assertArrayEquals(prefix + suffix, fs.openInput(file).use { it.readBytes() })
        } finally {
            command("ONLINE")
            runCatching { output.close() }
            try { fs.delete(directory) } finally { fs.disconnect() }
        }
    }

    private fun command(value: String) {
        Socket("127.0.0.1", 32049).use { socket ->
            socket.soTimeout = 5000
            socket.getOutputStream().write((value + "\n").toByteArray())
            assertEquals("OK", socket.getInputStream().bufferedReader().readLine())
        }
    }
}
