package com.twig.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnixPermissionsTest {
    @Test fun `ordinary and special permission bits match Unix notation`() {
        val cases = mapOf(
            "0000" to "---------", "0644" to "rw-r--r--", "0755" to "rwxr-xr-x",
            "4755" to "rwsr-xr-x", "4644" to "rwSr--r--", "2750" to "rwxr-s---",
            "2640" to "rw-r-S---", "1777" to "rwxrwxrwt", "1666" to "rw-rw-rwT",
            "7777" to "rwsrwsrwt",
        )
        for ((octal, symbolic) in cases) {
            val mode = octal.toInt(8)
            assertEquals(symbolic, UnixPermissions.symbolic(mode))
            assertEquals(mode, UnixPermissions.parseSymbolic(symbolic))
            assertEquals(octal, UnixPermissions.octal(mode))
            assertEquals(symbolic, UnixPermissions.symbolic(mode or 0xA000))
            assertEquals(octal, UnixPermissions.octal(mode or 0x4000))
        }
        for (invalid in listOf("", "rwxr-xr-x+", "rwsr-xr-s", "rwt------", "xwxr-xr-x"))
            assertNull(UnixPermissions.parseSymbolic(invalid))
    }
}
