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
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import dev.aniclipt.app.data.HttpTransport
import dev.aniclipt.app.data.local.UserPreferencesStore
import java.net.URL
import java.util.concurrent.TimeUnit

private class UpdateFailure(val status: String, message: String) : Exception(message)

class ReleaseUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val preferences = UserPreferencesStore(applicationContext)
        preferences.migrateLegacyIfNeeded()
        val prefs = applicationContext.getSharedPreferences("ani-cli-pt", Context.MODE_PRIVATE)
        val automaticDownloadsEnabled = preferences.current().automaticReleaseUpdates
        val launchCheck = inputData.getBoolean(INPUT_LAUNCH_CHECK, false)
        val manualDownload = inputData.getBoolean(INPUT_MANUAL_DOWNLOAD, false)
        val shouldDownload = automaticDownloadsEnabled || manualDownload
        if (!shouldDownload && !launchCheck) return@withContext Result.success()
        val notificationsAllowed = Build.VERSION.SDK_INT < 33 ||
            applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (automaticDownloadsEnabled && !notificationsAllowed && !manualDownload) return@withContext Result.success()
        if (manualDownload) prefs.edit().putString(PREF_DOWNLOAD_STATUS, STATUS_DOWNLOADING).apply()

        runCatching {
            val release = HttpTransport.execute(LATEST_RELEASE_API,
                mapOf("Accept" to "application/vnd.github+json", "User-Agent" to "ani-cli-pt-android"),
                connectTimeoutMillis = 12_000, readTimeoutMillis = 12_000).use { response ->
                if (!response.isSuccessful) {
                    val status = if (response.code == 403 || response.code == 429) STATUS_FAILED_RATE_LIMIT else STATUS_FAILED_SERVER
                    throw UpdateFailure(status, "GitHub respondeu com HTTP ${response.code}")
                }
                JSONObject(response.body?.string().orEmpty())
            }

            val latestTag = release.optString("tag_name").removePrefix("v").ifBlank { error("O GitHub não informou uma versão de release.") }
            @Suppress("DEPRECATION")
            val installedInfo = applicationContext.packageManager.getPackageInfo(applicationContext.packageName, 0)
            val installedVersion = installedInfo.versionName.orEmpty()
            if (compareReleaseVersions(latestTag, installedVersion) > 0) {
                prefs.edit().putString(PREF_AVAILABLE_VERSION, latestTag).apply()
                if (!shouldDownload) return@runCatching
                if (manualDownload) prefs.edit().putString(PREF_DOWNLOAD_STATUS, STATUS_DOWNLOADING).apply()
                val assets = release.optJSONArray("assets") ?: throw UpdateFailure(STATUS_FAILED_ASSET, "A release não contém arquivos para download.")
                val apk = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
                    .firstOrNull { it.optString("name") == APK_ASSET_NAME }
                    ?: throw UpdateFailure(STATUS_FAILED_ASSET, "O APK esperado não está disponível nesta release.")
                val downloadUrl = apk.optString("browser_download_url")
                val parsedUrl = runCatching { URL(downloadUrl) }.getOrNull()
                if (parsedUrl?.protocol != "https" || parsedUrl.host != "github.com") throw UpdateFailure(STATUS_FAILED_INVALID, "Endereço do APK inválido.")
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
            onFailure = { failure ->
                if (failure is CancellationException) throw failure
                val status = when (failure) {
                    is UpdateFailure -> failure.status
                    is IOException -> STATUS_FAILED_NETWORK
                    else -> STATUS_FAILED
                }
                if (manualDownload) prefs.edit().putString(PREF_DOWNLOAD_STATUS, status).apply()
                if (status == STATUS_FAILED_NETWORK || status == STATUS_FAILED_SERVER || status == STATUS_FAILED_RATE_LIMIT) Result.retry() else Result.failure()
            },
        )
    }

    private fun downloadApk(url: String, destination: File, expectedDigest: String?) {
        val parent = destination.parentFile ?: throw UpdateFailure(STATUS_FAILED_STORAGE, "Pasta da atualização indisponível.")
        if (!parent.isDirectory && !parent.mkdirs()) throw UpdateFailure(STATUS_FAILED_STORAGE, "Não foi possível criar a pasta da atualização.")
        val temporary = File(destination.parentFile, "${destination.name}.part")
        try { HttpTransport.execute(url, mapOf("User-Agent" to "ani-cli-pt-android"),
            connectTimeoutMillis = 20_000, readTimeoutMillis = 40_000).use { response ->
            if (!response.isSuccessful) {
                val status = if (response.code == 403 || response.code == 429) STATUS_FAILED_RATE_LIMIT else STATUS_FAILED_SERVER
                throw UpdateFailure(status, "Download respondeu com HTTP ${response.code}")
            }
            (response.body?.byteStream() ?: throw UpdateFailure(STATUS_FAILED_SERVER, "Download sem conteúdo.")).use { input ->
                val output = try { FileOutputStream(temporary) } catch (failure: IOException) {
                    throw UpdateFailure(STATUS_FAILED_STORAGE, "Não foi possível criar o arquivo da atualização.")
                }
                output.use {
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        try { it.write(buffer, 0, count) } catch (failure: IOException) {
                            throw UpdateFailure(STATUS_FAILED_STORAGE, "Não foi possível gravar a atualização.")
                        }
                    }
                }
            }
            if (temporary.length() <= MIN_APK_SIZE) throw UpdateFailure(STATUS_FAILED_INVALID, "O APK baixado está incompleto.")
            if (expectedDigest != null && !sha256(temporary).equals(expectedDigest, ignoreCase = true)) throw UpdateFailure(STATUS_FAILED_INVALID, "A verificação do APK falhou.")
            if (!temporary.renameTo(destination)) throw UpdateFailure(STATUS_FAILED_STORAGE, "Não foi possível salvar o APK baixado.")
        } } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun validateApk(file: File, installedVersionCode: Long) {
        if (!file.isFile || file.length() <= MIN_APK_SIZE) throw UpdateFailure(STATUS_FAILED_INVALID, "APK incompleto.")
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = applicationContext.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw UpdateFailure(STATUS_FAILED_INVALID, "APK inválido.")
        val installed = applicationContext.packageManager.getPackageInfo(applicationContext.packageName, flags)
        val newSignatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        val oldSignatures = if (Build.VERSION.SDK_INT >= 28) installed.signingInfo?.apkContentsSigners else installed.signatures
        if (!isCompatibleUpdate(applicationContext.packageName, info.packageName, installedVersionCode,
                PackageInfoCompat.getLongVersionCode(info), oldSignatures?.toSet().orEmpty(), newSignatures?.toSet().orEmpty()))
            throw UpdateFailure(STATUS_FAILED_INVALID, "O pacote, a versão ou a assinatura da atualização não correspondem ao aplicativo instalado.")
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
        if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(applicationContext, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        try { NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification) } catch (_: SecurityException) { }
    }

    companion object {
        const val PREF_ENABLED = "automatic_release_updates"
        const val PREF_AVAILABLE_VERSION = "available_release_version"
        const val PREF_DOWNLOAD_STATUS = "update_download_status"
        const val STATUS_DOWNLOADING = "downloading"
        const val STATUS_READY = "ready"
        const val STATUS_UP_TO_DATE = "up_to_date"
        const val STATUS_FAILED = "failed"
        const val STATUS_FAILED_NETWORK = "failed_network"
        const val STATUS_FAILED_SERVER = "failed_server"
        const val STATUS_FAILED_RATE_LIMIT = "failed_rate_limit"
        const val STATUS_FAILED_ASSET = "failed_asset"
        const val STATUS_FAILED_INVALID = "failed_invalid"
        const val STATUS_FAILED_STORAGE = "failed_storage"
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
