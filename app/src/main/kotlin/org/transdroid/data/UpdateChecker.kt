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
            val normalized = tag.removePrefix("v")
            if (normalized == currentVersionName) null else AppUpdate(tag = tag, htmlUrl = url)
        }
    }

    companion object {
        const val LATEST_RELEASE = "https://api.github.com/repos/Gourab0002/transdroid/releases/latest"
    }
}
