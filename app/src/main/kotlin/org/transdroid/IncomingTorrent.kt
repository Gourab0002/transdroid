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
package org.transdroid

/**
 * Classifies VIEW/SEND extras into a magnet, http(s) torrent URL, or content/file URI
 * the add-torrent screen can consume. Kept free of Android types so unit tests can cover it.
 */
object IncomingTorrent {

    fun fromView(dataString: String?): String? = dataString?.takeIf { isOpenable(it) }

    fun fromSend(extraText: String?, extraStreamUri: String?): String? {
        extraStreamUri?.takeIf { isOpenable(it) }?.let { return it }
        return extraText?.trim()?.takeIf { isTorrentUrl(it) }
    }

    fun isOpenable(value: String): Boolean =
        value.startsWith("magnet:") ||
            value.startsWith("content:") ||
            value.startsWith("file:") ||
            value.startsWith("http://") ||
            value.startsWith("https://")

    fun isTorrentUrl(value: String): Boolean =
        value.startsWith("magnet:") ||
            value.startsWith("http://") ||
            value.startsWith("https://")
}
