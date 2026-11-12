package dev.aniclipt.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.aniclipt.app.data.Anime
import dev.aniclipt.app.data.AnimeRepository
import dev.aniclipt.app.data.IdentityRegistry
import dev.aniclipt.app.data.local.AppDatabase
import dev.aniclipt.app.data.local.LegacyDataMigration
import dev.aniclipt.app.data.local.LocalAnimeStore
import dev.aniclipt.app.data.local.UserPreferencesStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class FavoriteEpisodeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val preferences = UserPreferencesStore(applicationContext)
        preferences.migrateLegacyIfNeeded()
        if (!preferences.current().episodeNotifications) return@withContext Result.success()
        val database = AppDatabase.get(applicationContext)
        LegacyDataMigration(applicationContext, database).migrateIfNeeded()
        val dao = database.appDao()
        val store = LocalAnimeStore(database)
        val favorites = dao.favorites().filterNot { item ->
            item.slug == item.releaseId && UUID_PATTERN.matches(item.slug)
        }.map { item ->
            Anime(item.slug, item.title, item.poster, synopsis = item.synopsis, anilistId = item.anilistId,
                isAdult = item.isAdult, workId = item.workId, releaseId = item.releaseId)
        }
        if (favorites.isEmpty()) return@withContext Result.success()

        val notificationsAllowed = Build.VERSION.SDK_INT < 33 || applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        var hadFailure = false
        val identities = IdentityRegistry(database = database).also { it.bootstrap() }
        val repository = AnimeRepository(identities, database)
        favorites.forEach { anime ->
            val result = try { kotlin.Result.success(repository.episodes(anime)) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { kotlin.Result.failure(failure) }
            result.onFailure { hadFailure = true }
            result.onSuccess { episodes ->
                val current = episodes.map { it.id }.toSet()
                val freshIds = store.replaceFavoriteEpisodeSnapshot(anime.slug, current)
                if (freshIds.isNotEmpty() && notificationsAllowed) {
                    val fresh = episodes.filter { it.id in freshIds }
                    postNotification(anime, fresh.size, fresh.first().number)
                }
            }
        }
        identities.awaitPersisted()
        if (hadFailure) Result.retry() else Result.success()
    }

    private fun postNotification(anime: Anime, count: Int, latestEpisode: String) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Novos episódios", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Avisos sobre novos episódios dos animes favoritos"
            })
        }
        val intent = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)
        val pendingIntent = intent?.let { PendingIntent.getActivity(applicationContext, anime.slug.hashCode(), it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
        val detail = if (count == 1) "Episódio $latestEpisode disponível" else "$count episódios novos; o mais recente é o $latestEpisode"
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(anime.title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(applicationContext, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        try { NotificationManagerCompat.from(applicationContext).notify(anime.slug.hashCode(), notification) } catch (_: SecurityException) { }
    }

    companion object {
        private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
        private const val CHANNEL_ID = "favorite-episodes"
        private const val UNIQUE_WORK = "favorite-episode-check"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<FavoriteEpisodeWorker>(6, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(UNIQUE_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun captureBaseline(context: Context) {
            val request = OneTimeWorkRequestBuilder<FavoriteEpisodeWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork("favorite-episode-baseline", ExistingWorkPolicy.KEEP, request)
        }
    }
}
