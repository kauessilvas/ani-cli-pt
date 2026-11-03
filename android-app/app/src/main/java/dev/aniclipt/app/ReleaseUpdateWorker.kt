package dev.aniclipt.app

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
import androidx.core.content.pm.PackageInfoCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

class ReleaseUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = applicationContext.getSharedPreferences("ani-cli-pt", Context.MODE_PRIVATE)
        val automaticDownloadsEnabled = prefs.getBoolean(PREF_ENABLED, false)
        val launchCheck = inputData.getBoolean(INPUT_LAUNCH_CHECK, false)
        val manualDownload = inputData.getBoolean(INPUT_MANUAL_DOWNLOAD, false)
        val shouldDownload = automaticDownloadsEnabled || manualDownload
        if (!shouldDownload && !launchCheck) return@withContext Result.success()
        val notificationsAllowed = Build.VERSION.SDK_INT < 33 ||
            applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (automaticDownloadsEnabled && !notificationsAllowed && !manualDownload) return@withContext Result.success()
        if (manualDownload) prefs.edit().putString(PREF_DOWNLOAD_STATUS, STATUS_DOWNLOADING).apply()

        runCatching {
            val connection = URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection
            connection.connectTimeout = 12_000
            connection.readTimeout = 12_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "ani-cli-pt-android")
            val release = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            connection.disconnect()

            val latestTag = release.optString("tag_name").removePrefix("v").ifBlank { error("O GitHub não informou uma versão de release.") }
            @Suppress("DEPRECATION")
            val installedInfo = applicationContext.packageManager.getPackageInfo(applicationContext.packageName, 0)
            val installedVersion = installedInfo.versionName.orEmpty()
            if (compareVersions(latestTag, installedVersion) > 0) {
                prefs.edit().putString(PREF_AVAILABLE_VERSION, latestTag).apply()
                if (!shouldDownload) return@runCatching
                if (manualDownload) prefs.edit().putString(PREF_DOWNLOAD_STATUS, STATUS_DOWNLOADING).apply()
                val assets = release.optJSONArray("assets") ?: error("A release não contém arquivos para download.")
                val apk = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
                    .firstOrNull { it.optString("name") == APK_ASSET_NAME }
                    ?: error("O APK $APK_ASSET_NAME não está disponível nesta release.")
                val downloadUrl = apk.optString("browser_download_url")
                require(URL(downloadUrl).protocol == "https" && URL(downloadUrl).host == "github.com") { "Endereço do APK inválido." }
                val expectedDigest = apk.optString("digest").removePrefix("sha256:").takeIf { it.matches(Regex("[a-fA-F0-9]{64}")) }
                val updateFile = File(applicationContext.filesDir, "updates/ani-cli-pt-$latestTag.apk")
                val alreadyDownloaded = updateFile.isFile && expectedDigest != null && sha256(updateFile) == expectedDigest
                if (!alreadyDownloaded) downloadApk(downloadUrl, updateFile, expectedDigest)
                validateApk(updateFile, PackageInfoCompat.getLongVersionCode(installedInfo))
                prefs.edit()
                    .putString(PREF_PENDING_APK, updateFile.absolutePath)
                    .putString(PREF_PENDING_VERSION, latestTag)
                    .putString(PREF_DOWNLOAD_STATUS, STATUS_READY)
                    .apply()
                if (automaticDownloadsEnabled) notifyUpdate(latestTag)
            } else {
                prefs.edit().remove(PREF_AVAILABLE_VERSION).apply()
                if (manualDownload) prefs.edit().putString(PREF_DOWNLOAD_STATUS, STATUS_UP_TO_DATE).apply()
            }
        }.fold(
            onSuccess = { Result.success() },
            onFailure = {
                if (manualDownload) prefs.edit().putString(PREF_DOWNLOAD_STATUS, STATUS_FAILED).apply()
                Result.retry()
            },
        )
    }

    private fun downloadApk(url: String, destination: File, expectedDigest: String?) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, "${destination.name}.part")
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 40_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "ani-cli-pt-android")
        try {
            connection.inputStream.use { input -> FileOutputStream(temporary).use { output -> input.copyTo(output) } }
            require(temporary.length() > MIN_APK_SIZE) { "O APK baixado está incompleto." }
            if (expectedDigest != null) require(sha256(temporary).equals(expectedDigest, ignoreCase = true)) { "A verificação do APK falhou." }
            check(temporary.renameTo(destination)) { "Não foi possível salvar o APK baixado." }
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    @Suppress("DEPRECATION")
    private fun validateApk(file: File, installedVersionCode: Long) {
        require(file.isFile && file.length() > MIN_APK_SIZE) { "O APK da atualização não foi encontrado." }
        val info = applicationContext.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            ?: error("O arquivo baixado não é um APK válido.")
        require(info.packageName == applicationContext.packageName) { "O APK não pertence a este aplicativo." }
        require(PackageInfoCompat.getLongVersionCode(info) > installedVersionCode) { "A versão baixada não é mais recente." }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun notifyUpdate(version: String) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Atualizações do app", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Avisos quando uma nova versão do ani-cli-pt estiver disponível"
            })
        }
        val intent = PendingIntent.getActivity(
            applicationContext,
            NOTIFICATION_ID,
            Intent(applicationContext, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_INSTALL_UPDATE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Atualização pronta · $version")
            .setContentText("Toque para abrir o instalador e atualizar o app.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("O APK da nova versão já está baixado. Toque para abrir o instalador do Android."))
            .setContentIntent(intent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification) }
    }

    private fun compareVersions(first: String, second: String): Int {
        val a = Regex("\\d+").findAll(first).map { it.value.toIntOrNull() ?: 0 }.toList()
        val b = Regex("\\d+").findAll(second).map { it.value.toIntOrNull() ?: 0 }.toList()
        for (index in 0 until maxOf(a.size, b.size)) {
            val comparison = (a.getOrElse(index) { 0 }).compareTo(b.getOrElse(index) { 0 })
            if (comparison != 0) return comparison
        }
        return 0
    }

    companion object {
        const val PREF_ENABLED = "automatic_release_updates"
        const val PREF_AVAILABLE_VERSION = "available_release_version"
        const val PREF_DOWNLOAD_STATUS = "update_download_status"
        const val STATUS_DOWNLOADING = "downloading"
        const val STATUS_READY = "ready"
        const val STATUS_UP_TO_DATE = "up_to_date"
        const val STATUS_FAILED = "failed"
        private const val INPUT_LAUNCH_CHECK = "check_on_app_launch"
        private const val INPUT_MANUAL_DOWNLOAD = "download_manually"
        private const val LATEST_RELEASE_API = "https://api.github.com/repos/kauessilvas/ani-cli-pt/releases/latest"
        private const val APK_ASSET_NAME = "ani-cli-pt.apk"
        private const val MIN_APK_SIZE = 1_000_000L
        private const val CHANNEL_ID = "app-updates"
        private const val NOTIFICATION_ID = 0xA11
        private const val PERIODIC_WORK = "release-update-check"
        private const val ONE_TIME_WORK = "release-update-check-now"
        private const val LAUNCH_CHECK_WORK = "release-update-check-on-launch"
        private const val MANUAL_DOWNLOAD_WORK = "release-update-download-manual"
        const val PREF_PENDING_APK = "pending_update_apk"
        const val PREF_PENDING_VERSION = "pending_update_version"
        const val PREF_AWAITING_INSTALL_PERMISSION = "awaiting_update_install_permission"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ReleaseUpdateWorker>(12, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun checkNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<ReleaseUpdateWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(ONE_TIME_WORK, ExistingWorkPolicy.REPLACE, request)
        }

        fun checkOnLaunch(context: Context) {
            val request = OneTimeWorkRequestBuilder<ReleaseUpdateWorker>()
                .setInputData(Data.Builder().putBoolean(INPUT_LAUNCH_CHECK, true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(LAUNCH_CHECK_WORK, ExistingWorkPolicy.KEEP, request)
        }

        fun downloadManually(context: Context) {
            val request = OneTimeWorkRequestBuilder<ReleaseUpdateWorker>()
                .setInputData(Data.Builder().putBoolean(INPUT_MANUAL_DOWNLOAD, true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(MANUAL_DOWNLOAD_WORK, ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK)
            WorkManager.getInstance(context).cancelUniqueWork(ONE_TIME_WORK)
        }
    }
}
