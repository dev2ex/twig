package com.twig.fs.network

import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AWS Signature Version 4 — S3 request signing (only the Authorization header,
 * not presigned URLs or chunked signatures).
 *
 * Why hand-written: the AWS SDK for Java alone pulls in a transitive dependency
 * footprint of over ten MB for its s3 module, larger than the entire APK; while
 * the primitives needed for signing (HMAC-SHA256 / SHA-256) ship with the JDK, and
 * the whole implementation is under two hundred lines.
 *
 * A few **easy-to-get-wrong** points in the spec — copying another encoder's
 * behavior here is how you introduce bugs:
 *  - URI encoding uses the RFC 3986 unreserved set (`A-Za-z0-9-_.~`);
 *    `URLEncoder` cannot be used — it encodes space as `+`, also encodes `~`,
 *    and does not encode `*`.
 *  - The already-encoded canonical URI **is not encoded a second time**
 *    (S3-specific; other AWS services require double encoding).
 *  - Canonical query is sorted by the **encoded** key; even when the value is
 *    empty, the `=` is kept.
 *  - Header values must be trimmed and consecutive internal whitespace collapsed
 *    to a single space.
 */
internal object Sigv4 {

    /** sha256("") — the payload hash when there is no request body. */
    const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    /**
     * Payload placeholder for streaming uploads. The request body is sent while
     * being read, so the hash cannot be computed at signing time; S3 accepts this
     * value (the AWS SDK also sends it over HTTPS).
     */
    const val UNSIGNED = "UNSIGNED-PAYLOAD"

    const val ALGORITHM = "AWS4-HMAC-SHA256"

    private val MULTI_SPACE = Regex(" +")

    /** `20130524T000000Z` — the format of x-amz-date. */
    fun amzDate(millis: Long): String = utcFormat("yyyyMMdd'T'HHmmss'Z'", millis)

    /**
     * Builds the canonical request. [query] and [headers] are passed as their
     * **unencoded** raw values; [canonicalUri] is the **already-encoded** path
     * (starting with `/`), since it is taken from the final URL.
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
     * Canonical query string. The caller also uses this as the **real** URL's
     * query — sharing one string on both sides avoids the "signed and sent use
     * different encodings" class of bugs that only blow up with special characters.
     */
    fun canonicalQuery(query: List<Pair<String, String>>): String = query
        .map { (k, v) -> uriEncode(k) to uriEncode(v) }
        .sortedWith(compareBy({ it.first }, { it.second }))
        .joinToString("&") { (k, v) -> "$k=$v" }

    /** String to sign; [amzDate] looks like `20130524T000000Z`. */
    fun stringToSign(canonicalRequest: String, amzDate: String, region: String, service: String): String =
        "$ALGORITHM\n$amzDate\n${scope(amzDate, region, service)}\n${sha256Hex(canonicalRequest.toByteArray())}"

    /** Full `Authorization` header value. */
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

    /** Derived signing key per date / region / service. */
    fun signingKey(secretKey: String, dateStamp: String, region: String, service: String): ByteArray {
        var k = hmac("AWS4$secretKey".toByteArray(), dateStamp.toByteArray())
        k = hmac(k, region.toByteArray())
        k = hmac(k, service.toByteArray())
        return hmac(k, "aws4_request".toByteArray())
    }

    private fun scope(amzDate: String, region: String, service: String): String =
        "${amzDate.take(8)}/$region/$service/aws4_request"

    /**
     * RFC 3986 encoding. When [encodeSlash] is false, `/` is kept as-is (used for
     * paths — the path separator itself must not be encoded, otherwise S3 cannot
     * locate the object).
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
     * Inverse of [uriEncode]: only `%XX` is restored, `+` is treated as a literal
     * plus sign.
     *
     * ★ Note that this is **not** the same as decoding object names in S3
     * responses — do not use it directly on Key values (those use form encoding,
     * where `+` means space, see `S3FileSystem.decodeKey`).
     * Request paths and response object names live in two distinct encoding spaces.
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
