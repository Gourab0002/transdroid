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
package org.transdroid.background

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.transdroid.MainActivity
import org.transdroid.R
import org.transdroid.appContainer
import org.transdroid.protocol.AddOptions

/**
 * Fetches RSS feeds that have auto-download enabled, adds matching new items to the
 * active server, and optionally notifies about new titles.
 */
class RssCheckWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = applicationContext.appContainer
        val feeds = container.profilesRepository.feeds.first().filter { it.autoDownload }
        if (feeds.isEmpty()) return Result.success()
        val profile = container.activeProfile.first() ?: return Result.success()
        val notify = container.settingsRepository.notifyRss.first()
        val addedTitles = mutableListOf<String>()
        try {
            for (feed in feeds) {
                val channel = container.rssFetcher.fetch(feed.url)
                val cutoff = feed.lastAutoDownloadTimestamp ?: feed.lastViewedTimestamp ?: 0L
                val match = feed.matchContains.trim()
                val newcomers = channel.items.filter { item ->
                    val ts = item.timestamp ?: return@filter false
                    ts > cutoff &&
                        item.torrentUrl != null &&
                        (match.isEmpty() || item.title.contains(match, ignoreCase = true))
                }.sortedBy { it.timestamp }
                if (newcomers.isEmpty()) continue
                var newest = cutoff
                for (item in newcomers) {
                    val url = item.torrentUrl ?: continue
                    try {
                        container.adapterFor(profile).addByUrl(url, AddOptions())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Don't advance past a failed item: it stays above the cutoff
                        // and is retried on the next run instead of silently skipped.
                        continue
                    }
                    addedTitles += item.title
                    newest = maxOf(newest, item.timestamp ?: newest)
                }
                container.profilesRepository.saveFeed(feed.copy(lastAutoDownloadTimestamp = newest))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return Result.retry()
        }
        if (notify && addedTitles.isNotEmpty()) notifyAdded(applicationContext, addedTitles)
        return Result.success()
    }

    private fun notifyAdded(context: Context, titles: List<String>) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel(context)
        val tap = PendingIntent.getActivity(
            context,
            2,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                context.resources.getQuantityString(R.plurals.notification_rss_title, titles.size, titles.size),
            )
            .setContentText(titles.first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(titles.joinToString("\n")))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private fun ensureChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_rss),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    companion object {
        private const val WORK_NAME = "rss_auto_download"
        private const val CHANNEL_ID = "rss_new_items"
        private const val NOTIFICATION_ID = 2

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RssCheckWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
