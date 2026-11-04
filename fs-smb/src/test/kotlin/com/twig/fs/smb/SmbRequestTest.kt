package com.twig.fs.smb

import com.twig.core.FsException
import org.junit.Assert.*
import org.junit.Test

class SmbRequestTest {
    private var calls = 0
    private var reconnects = 0
    private var error = ""
    private var denied = false
    private var reconnectsSuccessfully = true

    private fun <T> request(failed: (T) -> Boolean, op: () -> T): T = smbRequest(
        "SMB test", failed, { error }, { denied }, {
            reconnects++
            error = ""
            reconnectsSuccessfully
        }, { calls++; op() },
    )

    private fun failure(block: () -> Unit): String {
        val exception = assertThrows(FsException::class.java, block)
        return exception.message!!
    }

    @Test fun `success does not reconnect`() {
        assertEquals(7, request<Int>({ it < 0 }) { 7 })
        assertEquals(1, calls)
        assertEquals(0, reconnects)
    }

    @Test fun `server denial retains its error without reconnecting`() {
        denied = true
        val message = failure { request<Int>({ it < 0 }) { error = "ACCESS_DENIED"; -13 } }
        assertEquals("ACCESS_DENIED", message)
        assertEquals(0, reconnects)
    }

    @Test fun `failed reconnect retains original listing error`() {
        reconnectsSuccessfully = false
        val message = failure { request<String?>({ it == null }) { error = "listing rejected"; null } }
        assertEquals("listing rejected", message)
        assertEquals(1, calls)
    }

    @Test fun `successful reconnect retries file open once`() {
        assertEquals(42L, request<Long>({ it == 0L }) { if (calls == 1) 0L else 42L })
        assertEquals(2, calls)
        assertEquals(1, reconnects)
    }

    @Test fun `retry error takes precedence`() {
        val message = failure {
            request<Int>({ it < 0 }) { error = if (calls == 1) "disconnected" else "NAME_COLLISION"; -1 }
        }
        assertEquals("NAME_COLLISION", message)
        assertEquals(2, calls)
    }

    @Test fun `blank retry error keeps original error and result`() {
        val message = failure {
            request<Int>({ it < 0 }) { error = if (calls == 1) "original failure" else "  "; -5 }
        }
        assertTrue(message.contains("original failure"))
        assertTrue(message.contains("-5"))
    }

    @Test fun `blank native errors still report operation and result`() {
        val message = failure { request<Long>({ it == 0L }) { 0L } }
        assertTrue(message.contains("SMB test"))
        assertTrue(message.contains("result 0"))
        assertEquals(2, calls)
    }
}
