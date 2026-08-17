package com.twig.fs.network

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SigV4 的正确性验证。
 *
 * 请求取自 AWS 官方文档的示例("Signature Calculations for the Authorization
 * Header: Transferring Payload in a Single Chunk"),用的是那对著名的示例凭证。
 * **断言的重点是中间产物** canonical request 与 string to sign:签名算错时服务端
 * 只回一句 SignatureDoesNotMatch,不告诉你哪一步错,而这两个中间值一比就知道是
 * 编码、排序还是头集合的问题。其中 string to sign 里那串 canonical request 的
 * SHA-256(`7344ae5b…`)正是官方文档印出来的值——它对上了,就说明前半程规范化
 * 一个字节都没差。
 *
 * 末尾那两个 signature 十六进制则是**两套独立实现交叉验证**的结果(本实现 +
 * 一份照着规范另写的 Python HMAC 链,见提交记录),锁住派生密钥这最后一步不被改坏。
 */
class Sigv4Test {

    private val accessKey = "AKIAIOSFODNN7EXAMPLE"
    private val secretKey = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"
    private val amzDate = "20130524T000000Z"
    private val region = "us-east-1"

    /** 官方例子:GET Object,带 Range 头。 */
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

    /** 官方例子:GET Bucket (List Objects),带 query —— 覆盖 canonical query 的排序与编码。 */
    @Test
    fun listObjectsMatchesAwsVector() {
        val headers = mapOf(
            "host" to "examplebucket.s3.amazonaws.com",
            "x-amz-content-sha256" to Sigv4.EMPTY_SHA256,
            "x-amz-date" to amzDate,
        )
        // 故意逆序传入:canonical query 必须按编码后的 key 排序
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
     * URI 编码的三处「照抄 URLEncoder 就会错」:空格是 %20 不是 +、
     * `~` 不编码、`/` 在路径里要留着。
     */
    @Test
    fun uriEncodeFollowsRfc3986() {
        assertEquals("a%20b", Sigv4.uriEncode("a b"))
        assertEquals("~-_.", Sigv4.uriEncode("~-_."))
        assertEquals("a%2Bb", Sigv4.uriEncode("a+b"))
        assertEquals("a%2Fb", Sigv4.uriEncode("a/b"))
        assertEquals("a/b", Sigv4.uriEncode("a/b", encodeSlash = false))
        assertEquals("%E6%8A%A5%E5%91%8A", Sigv4.uriEncode("报告"))
        assertEquals("%2A", Sigv4.uriEncode("*")) // URLEncoder 恰恰不编码它
    }

    /** 解码不能把 `+` 当空格——否则名字里带加号的对象一律 404。 */
    @Test
    fun uriDecodeKeepsPlusLiteral() {
        assertEquals("a+b", Sigv4.uriDecode("a+b"))
        assertEquals("a b", Sigv4.uriDecode("a%20b"))
        assertEquals("报告.txt", Sigv4.uriDecode("%E6%8A%A5%E5%91%8A.txt"))
        assertEquals("plain", Sigv4.uriDecode("plain"))
        assertEquals("100%", Sigv4.uriDecode("100%")) // 残缺的 % 原样留着,不能崩
    }
}
