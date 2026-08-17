package com.twig.fs.network

import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AWS Signature Version 4 —— S3 请求签名(只做 Authorization 头这一种,
 * 不做 presigned URL 和 chunked 分块签名)。
 *
 * 手写的理由:AWS SDK for Java 光 s3 模块连着依赖十几 MB,比整个 APK 还大;
 * 而签名用到的原语(HMAC-SHA256 / SHA-256)JDK 自带,一共不到两百行。
 *
 * 规范里几个**照抄别的编码器就会错**的点:
 *  - URI 编码用的是 RFC 3986 的 unreserved 集合(`A-Za-z0-9-_.~`),
 *    `URLEncoder` 不能用:它把空格编成 `+`、`~` 也编、`*` 反而不编。
 *  - 已编码的 canonical URI **不再二次编码**(S3 独有;其他 AWS 服务要编两次)。
 *  - canonical query 按**编码后**的 key 排序,值为空也要留 `=`。
 *  - header 值要 trim 且把内部连续空格折叠成一个。
 */
internal object Sigv4 {

    /** sha256("") —— 无请求体时的 payload hash。 */
    const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    /**
     * 流式上传时的 payload 占位。请求体边读边发,签名那一刻算不出 hash;
     * S3 认这个值(AWS SDK 在 HTTPS 下也是这么发的)。
     */
    const val UNSIGNED = "UNSIGNED-PAYLOAD"

    const val ALGORITHM = "AWS4-HMAC-SHA256"

    private val MULTI_SPACE = Regex(" +")

    /** `20130524T000000Z` —— x-amz-date 的格式。 */
    fun amzDate(millis: Long): String = utcFormat("yyyyMMdd'T'HHmmss'Z'", millis)

    /**
     * 规范化请求。[query] 与 [headers] 传**未编码**的原值;
     * [canonicalUri] 传**已编码**的路径(以 `/` 开头),因为它是从最终 URL 里取的。
     */
    fun canonicalRequest(
        method: String,
        canonicalUri: String,
        query: List<Pair<String, String>>,
        headers: Map<String, String>,
        payloadSha: String,
    ): String {
        val sorted = headers.entries
            .map { it.key.lowercase(Locale.US) to it.value.trim().replace(MULTI_SPACE, " ") }
            .sortedBy { it.first }
        val canonHeaders = sorted.joinToString("") { (k, v) -> "$k:$v\n" }
        val signedHeaders = sorted.joinToString(";") { it.first }
        return "$method\n$canonicalUri\n${canonicalQuery(query)}\n$canonHeaders\n$signedHeaders\n$payloadSha"
    }

    /**
     * 规范化的 query string。调用方**同时**拿它当真实 URL 的 query 用——
     * 两边共用一份字符串,就不会出现"签的和发的编码不一致"这种只在
     * 特殊字符出现时才炸的问题。
     */
    fun canonicalQuery(query: List<Pair<String, String>>): String = query
        .map { (k, v) -> uriEncode(k) to uriEncode(v) }
        .sortedWith(compareBy({ it.first }, { it.second }))
        .joinToString("&") { (k, v) -> "$k=$v" }

    /** 待签字符串;[amzDate] 形如 `20130524T000000Z`。 */
    fun stringToSign(canonicalRequest: String, amzDate: String, region: String, service: String): String =
        "$ALGORITHM\n$amzDate\n${scope(amzDate, region, service)}\n${sha256Hex(canonicalRequest.toByteArray())}"

    /** 完整的 `Authorization` 头取值。 */
    fun authorization(
        method: String,
        canonicalUri: String,
        query: List<Pair<String, String>>,
        headers: Map<String, String>,
        payloadSha: String,
        amzDate: String,
        region: String,
        accessKey: String,
        secretKey: String,
        service: String = "s3",
    ): String {
        val canon = canonicalRequest(method, canonicalUri, query, headers, payloadSha)
        val toSign = stringToSign(canon, amzDate, region, service)
        val signed = headers.keys.map { it.lowercase(Locale.US) }.sorted().joinToString(";")
        val sig = hex(hmac(signingKey(secretKey, amzDate.take(8), region, service), toSign.toByteArray()))
        return "$ALGORITHM Credential=$accessKey/${scope(amzDate, region, service)}, " +
            "SignedHeaders=$signed, Signature=$sig"
    }

    /** 逐日/区域/服务派生的签名密钥。 */
    fun signingKey(secretKey: String, dateStamp: String, region: String, service: String): ByteArray {
        var k = hmac("AWS4$secretKey".toByteArray(), dateStamp.toByteArray())
        k = hmac(k, region.toByteArray())
        k = hmac(k, service.toByteArray())
        return hmac(k, "aws4_request".toByteArray())
    }

    private fun scope(amzDate: String, region: String, service: String): String =
        "${amzDate.take(8)}/$region/$service/aws4_request"

    /**
     * RFC 3986 编码。[encodeSlash] 为 false 时保留 `/`(用于路径,
     * 路径分隔符本身不能被编码,否则 S3 找不到对象)。
     */
    fun uriEncode(s: String, encodeSlash: Boolean = true): String {
        val sb = StringBuilder(s.length + 8)
        for (byte in s.toByteArray(Charsets.UTF_8)) {
            val v = byte.toInt() and 0xFF
            val c = v.toChar()
            when {
                c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' ||
                    c == '-' || c == '_' || c == '.' || c == '~' -> sb.append(c)
                c == '/' && !encodeSlash -> sb.append(c)
                else -> sb.append('%').append(HEX[v shr 4]).append(HEX[v and 0xF])
            }
        }
        return sb.toString()
    }

    /**
     * [uriEncode] 的逆运算:只还原 `%XX`,`+` 当字面加号看待。
     *
     * ★ 注意这跟**解码 S3 响应里的对象名**不是一回事,别拿它直接去解 Key
     * (那边是 form 编码,`+` 代表空格,见 `S3FileSystem.decodeKey`)。
     * 请求路径与响应对象名分属两个不同的编码空间。
     */
    fun uriDecode(s: String): String {
        if ('%' !in s) return s
        val out = java.io.ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val hex = if (c == '%' && i + 2 < s.length) s.substring(i + 1, i + 3).toIntOrNull(16) else null
            if (hex != null) {
                out.write(hex)
                i += 3
            } else {
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    fun sha256Hex(data: ByteArray): String =
        hex(MessageDigest.getInstance("SHA-256").digest(data))

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    private fun hmac(key: ByteArray, data: String): ByteArray = hmac(key, data.toByteArray())

    private val HEX = "0123456789ABCDEF".toCharArray()
    private val HEX_LOWER = "0123456789abcdef".toCharArray()

    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX_LOWER[v shr 4]).append(HEX_LOWER[v and 0xF])
        }
        return sb.toString()
    }

    private fun utcFormat(pattern: String, millis: Long): String =
        SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(millis))
}
