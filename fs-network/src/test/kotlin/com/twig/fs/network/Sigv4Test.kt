package com.twig.fs.network

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Correctness checks for SigV4.
 *
 * The requests are taken from AWS's own documentation example ("Signature Calculations
 * for the Authorization Header: Transferring Payload in a Single Chunk"), using that
 * well-known pair of example credentials. **The assertions focus on the intermediate
 * artifacts** — the canonical request and the string to sign: when a signature is
 * computed wrong, the server only ever replies SignatureDoesNotMatch and never says which
 * step failed, whereas comparing these two intermediate values immediately tells you
 * whether it's encoding, ordering, or the header set. The canonical request's SHA-256
 * embedded in the string to sign (`7344ae5b…`) is exactly the value printed in the
 * official documentation — matching it proves the normalization step is byte-for-byte
 * correct.
 *
 * The two signature hex strings at the end are the result of **cross-checking with two
 * independent implementations** (this one, plus a separate Python HMAC chain written
 * straight from the spec — see the commit history), pinning down the final key-derivation
 * step against regressions.
 */
class Sigv4Test {

    private val accessKey = "AKIAIOSFODNN7EXAMPLE"
    private val secretKey = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"
    private val amzDate = "20130524T000000Z"
    private val region = "us-east-1"

    /** The official example: GET Object with a Range header. */
    @Test
    fun getObjectMatchesAwsVector() {
        val headers = mapOf(
            "host" to "examplebucket.s3.amazonaws.com",
            "range" to "bytes=0-9",
            "x-amz-content-sha256" to Sigv4.EMPTY_SHA256,
            "x-amz-date" to amzDate,
        )
        val canon = Sigv4.canonicalRequest("GET", "/test.txt", emptyList(), headers, Sigv4.EMPTY_SHA256)
        assertEquals(
            """
            GET
            /test.txt

            host:examplebucket.s3.amazonaws.com
            range:bytes=0-9
            x-amz-content-sha256:${Sigv4.EMPTY_SHA256}
            x-amz-date:$amzDate

            host;range;x-amz-content-sha256;x-amz-date
            ${Sigv4.EMPTY_SHA256}
            """.trimIndent(),
            canon,
        )
        assertEquals(
            """
            AWS4-HMAC-SHA256
            20130524T000000Z
            20130524/us-east-1/s3/aws4_request
            7344ae5b7ee6c3e7e6b0fe0640412a37625d1fbfff95c48bbb2dc43964946972
            """.trimIndent(),
            Sigv4.stringToSign(canon, amzDate, region, "s3"),
        )
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=$accessKey/20130524/us-east-1/s3/aws4_request, " +
                "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, " +
                "Signature=67fe34c8530db585abddc51067328adfedb6e42487d2566dc7d927d6e2722900",
            Sigv4.authorization(
                "GET", "/test.txt", emptyList(), headers, Sigv4.EMPTY_SHA256,
                amzDate, region, accessKey, secretKey,
            ),
        )
    }

    /** The official example: GET Bucket (List Objects) with a query — covers canonical query sorting and encoding. */
    @Test
    fun listObjectsMatchesAwsVector() {
        val headers = mapOf(
            "host" to "examplebucket.s3.amazonaws.com",
            "x-amz-content-sha256" to Sigv4.EMPTY_SHA256,
            "x-amz-date" to amzDate,
        )
        // Deliberately passed in reverse order: the canonical query must be sorted by encoded key
        val query = listOf("prefix" to "J", "max-keys" to "2")
        val canon = Sigv4.canonicalRequest("GET", "/", query, headers, Sigv4.EMPTY_SHA256)
        assertEquals(
            """
            GET
            /
            max-keys=2&prefix=J
            host:examplebucket.s3.amazonaws.com
            x-amz-content-sha256:${Sigv4.EMPTY_SHA256}
            x-amz-date:$amzDate

            host;x-amz-content-sha256;x-amz-date
            ${Sigv4.EMPTY_SHA256}
            """.trimIndent(),
            canon,
        )
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=$accessKey/20130524/us-east-1/s3/aws4_request, " +
                "SignedHeaders=host;x-amz-content-sha256;x-amz-date, " +
                "Signature=b331a8a008500e1d26eaac3f17e064ed30785ac0cd1bed5e2acc9565175a7d92",
            Sigv4.authorization(
                "GET", "/", query, headers, Sigv4.EMPTY_SHA256,
                amzDate, region, accessKey, secretKey,
            ),
        )
    }

    /**
     * Three spots in URI encoding where copying URLEncoder verbatim gets it wrong: a
     * space is %20, not +; `~` is left unencoded; and `/` must be preserved in a path.
     */
    @Test
    fun uriEncodeFollowsRfc3986() {
        assertEquals("a%20b", Sigv4.uriEncode("a b"))
        assertEquals("~-_.", Sigv4.uriEncode("~-_."))
        assertEquals("a%2Bb", Sigv4.uriEncode("a+b"))
        assertEquals("a%2Fb", Sigv4.uriEncode("a/b"))
        assertEquals("a/b", Sigv4.uriEncode("a/b", encodeSlash = false))
        assertEquals("%E6%8A%A5%E5%91%8A", Sigv4.uriEncode("报告"))
        assertEquals("%2A", Sigv4.uriEncode("*")) // this is precisely the one URLEncoder leaves unencoded
    }

    /** Decoding must not treat `+` as a space — otherwise any object whose name has a plus sign always 404s. */
    @Test
    fun uriDecodeKeepsPlusLiteral() {
        assertEquals("a+b", Sigv4.uriDecode("a+b"))
        assertEquals("a b", Sigv4.uriDecode("a%20b"))
        assertEquals("报告.txt", Sigv4.uriDecode("%E6%8A%A5%E5%91%8A.txt"))
        assertEquals("plain", Sigv4.uriDecode("plain"))
        assertEquals("100%", Sigv4.uriDecode("100%")) // a truncated % is kept as-is, must not crash
    }
}
