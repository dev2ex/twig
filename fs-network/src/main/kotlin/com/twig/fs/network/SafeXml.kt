package com.twig.fs.network

import org.w3c.dom.Document
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * XML parsing for server responses (WebDAV multistatus, S3 listings) with DOCTYPEs and
 * external entities refused.
 *
 * A malicious or compromised server can put a DOCTYPE with external entities in its reply
 * and have the client read local files or make requests on its behalf (XXE). Android's own
 * parser does not resolve external entities today, but that is an implementation detail of
 * the platform, not a promise — and the JVM parser these modules are tested on does resolve
 * them. Each setting is applied on its own: Android's factory throws on features it does not
 * know, and one unsupported switch must not cost the others.
 */
internal object SafeXml {

    fun parse(input: InputStream, namespaceAware: Boolean): Document {
        val dbf = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = namespaceAware }
        for ((feature, on) in FEATURES) runCatching { dbf.setFeature(feature, on) }
        runCatching { dbf.isXIncludeAware = false }
        runCatching { dbf.isExpandEntityReferences = false }
        return dbf.newDocumentBuilder().parse(input)
    }

    private val FEATURES = listOf(
        "http://apache.org/xml/features/disallow-doctype-decl" to true,
        "http://xml.org/sax/features/external-general-entities" to false,
        "http://xml.org/sax/features/external-parameter-entities" to false,
        "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
    )
}
