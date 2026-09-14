/*
 * Copyright 2010-2026 Eric Kok et al.
 *
 * Transdroid is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Transdroid is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Transdroid. If not, see <https://www.gnu.org/licenses/>.
 */
package org.transdroid.protocol.internal

import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource

/**
 * Parses XML from an untrusted server with DTDs and external entities disabled, ruling out XXE.
 * Feature URIs that a given factory does not recognize are skipped so Android's parser cannot
 * fail the whole call before parse (desktop tests use Xerces, which accepts the Apache URI).
 */
internal fun parseXmlSafely(xml: String): Document {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isExpandEntityReferences = false
        isXIncludeAware = false
        isNamespaceAware = false
        setFeatureQuietly(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeatureQuietly("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeatureQuietly("http://xml.org/sax/features/external-general-entities", false)
        setFeatureQuietly("http://xml.org/sax/features/external-parameter-entities", false)
        setFeatureQuietly("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    }
    return factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
}

private fun DocumentBuilderFactory.setFeatureQuietly(name: String, value: Boolean) {
    try {
        setFeature(name, value)
    } catch (_: Exception) {
        // Factory-specific; the remaining flags still apply
    }
}

internal fun Element.childElements(): List<Element> {
    val result = mutableListOf<Element>()
    var child: Node? = firstChild
    while (child != null) {
        if (child is Element) result.add(child)
        child = child.nextSibling
    }
    return result
}

/** The text of the first direct child element with this (possibly prefixed) tag name. */
internal fun Element.childText(tagName: String): String? =
    childElements().firstOrNull { it.tagName == tagName || it.tagName.substringAfter(':') == tagName }
        ?.textContent?.trim()?.takeIf { it.isNotEmpty() }
