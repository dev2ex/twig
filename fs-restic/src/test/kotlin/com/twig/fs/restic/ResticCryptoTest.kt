package com.twig.fs.restic

import com.twig.core.FsException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ResticCryptoTest {

    @Test
    fun `base64 decodes the standard alphabet and ignores padding and line breaks`() {
        assertArrayEquals("hello?>".toByteArray(), ResticCrypto.base64("aGVsbG8/Pg=="))
        assertArrayEquals("hello?>".toByteArray(), ResticCrypto.base64("aGVs\r\nbG8/\nPg=="))
    }

    /** A damaged key file used to crash with ArrayIndexOutOfBounds on the first non-ASCII character. */
    @Test
    fun `a character outside the alphabet is reported as corrupt data, not a crash`() {
        assertThrows(FsException::class.java) { ResticCrypto.base64("aGVs€bG8=") }
        assertThrows(FsException::class.java) { ResticCrypto.base64("aGVs*bG8=") }
    }
}
