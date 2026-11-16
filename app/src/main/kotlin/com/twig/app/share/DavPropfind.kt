package com.twig.app.share

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/** Property selection for PROPFIND. Empty bodies have allprop semantics (RFC 4918). */
internal data class DavPropfind(
    val all: Boolean = true,
    val namesOnly: Boolean = false,
    val properties: Set<Property> = emptySet(),
) {
    data class Property(val namespace: String, val name: String) {
        fun xml(value: String): String {
            if (namespace.isEmpty()) return "<$name xmlns=\"\">$value</$name>"
            val prefix = when (namespace) {
                "DAV:" -> "D"
                "http://www.w3.org/XML/1998/namespace" -> "xml"
                else -> "P"
            }
            val ns = if (prefix != "P") "" else " xmlns:P=\"${ShareHandler.xml(namespace)}\""
            return "<$prefix:$name$ns>$value</$prefix:$name>"
        }
    }

    companion object {
        fun read(input: InputStream): DavPropfind {
            // Bound untrusted XML before parsing, and never process a DTD or entities.
            val bytes = input.readBytesLimited(65_536)
            if (bytes.isEmpty()) return DavPropfind()
            val parser = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                setInput(bytes.inputStream(), null)
            }
            var mode: String? = null
            var root = false
            val properties = LinkedHashSet<Property>()
            while (true) {
                when (parser.nextToken()) {
                    XmlPullParser.END_DOCUMENT -> break
                    XmlPullParser.DOCDECL -> throw IllegalArgumentException("DTD is not allowed")
                    XmlPullParser.START_TAG -> {
                        if (parser.depth == 1) {
                            require(parser.namespace == "DAV:" && parser.name == "propfind")
                            root = true
                        } else if (parser.depth == 2) {
                            require(parser.namespace == "DAV:")
                            val name = parser.name
                            require(name in setOf("allprop", "propname", "prop", "include"))
                            if (name == "include") require(mode == "allprop")
                            else { require(mode == null); mode = name }
                        } else if (parser.depth == 3 && (mode == "prop" || mode == "allprop")) {
                            properties += Property(parser.namespace.orEmpty(), parser.name)
                        }
                    }
                }
            }
            require(root && mode != null)
            return DavPropfind(mode == "allprop", mode == "propname", properties)
        }

        private fun InputStream.readBytesLimited(limit: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val n = read(buffer, 0, minOf(buffer.size, limit + 1 - out.size()))
                if (n < 0) break
                out.write(buffer, 0, n)
                require(out.size() <= limit) { "PROPFIND body too large" }
            }
            return out.toByteArray()
        }
    }
}
