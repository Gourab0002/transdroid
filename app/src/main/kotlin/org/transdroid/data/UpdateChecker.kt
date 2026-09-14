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
package org.transdroid.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

data class AppUpdate(
    val tag: String,
    val htmlUrl: String,
)

/** Checks GitHub Releases for a newer Transdroid 3 tag. */
class UpdateChecker(private val httpClient: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun latest(currentVersionName: String): AppUpdate? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(LATEST_RELEASE)
            .header("Accept", "application/vnd.github+json")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val obj = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
            val tag = obj["tag_name"]?.jsonPrimitive?.contentOrNull ?: return@withContext null
            val url = obj["html_url"]?.jsonPrimitive?.contentOrNull ?: return@withContext null
            if (!isNewerVersion(tag, currentVersionName)) null else AppUpdate(tag = tag, htmlUrl = url)
        }
    }

    companion object {
        const val LATEST_RELEASE = "https://api.github.com/repos/Gourab0002/transdroid/releases/latest"
    }
}

/**
 * True when [latestTag] (e.g. "v1.2.0") names a newer release than [currentVersionName]
 * (e.g. "1.0.0"). Compares numeric components so an older tag, a prerelease of the
 * current version, or the current version itself never reads as an update. Tags without
 * a leading version number fall back to plain inequality.
 */
internal fun isNewerVersion(latestTag: String, currentVersionName: String): Boolean {
    val latest = parseVersion(latestTag)
    val current = parseVersion(currentVersionName)
    if (latest == null || current == null) {
        return latestTag.removePrefix("v") != currentVersionName
    }
    val length = maxOf(latest.size, current.size)
    for (i in 0 until length) {
        val diff = (latest.getOrElse(i) { 0 }) - (current.getOrElse(i) { 0 })
        if (diff != 0) return diff > 0
    }
    return false
}

private val LEADING_VERSION = Regex("""^v?(\d+(?:\.\d+)*)""")

private fun parseVersion(tag: String): List<Int>? =
    LEADING_VERSION.find(tag.trim())?.groupValues?.get(1)
        ?.split('.')?.map { it.toIntOrNull() ?: return null }
