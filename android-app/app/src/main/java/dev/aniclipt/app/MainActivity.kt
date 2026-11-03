package dev.aniclipt.app

import android.os.Bundle
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.ui.CaptionStyleCompat
import coil.compose.AsyncImage
import dev.aniclipt.app.data.Anime
import dev.aniclipt.app.data.AnimeRepository
import dev.aniclipt.app.data.Episode
import dev.aniclipt.app.data.PlaybackSource
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import java.io.File

private val Ink = Color(0xFFF5F5F7)
private val Muted = Color(0xFF9A9AA2)
private val Canvas = Color(0xFF111113)
private val Panel = Color(0xFF202024)
private val Accent = Color(0xFF0A84FF)

class MainActivity : ComponentActivity() {
    private var activityIsResumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        getSharedPreferences("ani-cli-pt", MODE_PRIVATE).edit()
            .remove("opensubtitles_username")
            .remove("opensubtitles_password")
            .apply()
        window.statusBarColor = android.graphics.Color.rgb(17, 17, 19)
        window.navigationBarColor = android.graphics.Color.rgb(17, 17, 19)
        setContent { AniCliTheme { AnimeHome() } }
        removeInstalledUpdateFile()
    }

    override fun onResume() {
        super.onResume()
        activityIsResumed = true
        processPendingUpdateInstall()
    }

    override fun onPause() {
        activityIsResumed = false
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (activityIsResumed) processPendingUpdateInstall()
    }

    private fun processPendingUpdateInstall() {
        val prefs = getSharedPreferences("ani-cli-pt", MODE_PRIVATE)
        val launchRequested = intent?.getBooleanExtra(EXTRA_INSTALL_UPDATE, false) == true
        val permissionPending = prefs.getBoolean(ReleaseUpdateWorker.PREF_AWAITING_INSTALL_PERMISSION, false)
        if (!launchRequested && !permissionPending) return
        intent?.removeExtra(EXTRA_INSTALL_UPDATE)

        val apk = prefs.getString(ReleaseUpdateWorker.PREF_PENDING_APK, null)?.let(::File)
        if (apk == null || !apk.isFile) {
            prefs.edit().remove(ReleaseUpdateWorker.PREF_AWAITING_INSTALL_PERMISSION).apply()
            Toast.makeText(this, "O APK de atualização não está mais disponível. Tente novamente mais tarde.", Toast.LENGTH_LONG).show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            if (launchRequested) {
                prefs.edit().putBoolean(ReleaseUpdateWorker.PREF_AWAITING_INSTALL_PERMISSION, true).apply()
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } else {
                prefs.edit().putBoolean(ReleaseUpdateWorker.PREF_AWAITING_INSTALL_PERMISSION, false).apply()
                Toast.makeText(this, "Permita instalações do ani-cli-pt e toque no aviso novamente.", Toast.LENGTH_LONG).show()
            }
            return
        }

        prefs.edit().putBoolean(ReleaseUpdateWorker.PREF_AWAITING_INSTALL_PERMISSION, false).apply()
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME_TYPE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }.onFailure {
            Toast.makeText(this, "Não foi possível abrir o instalador do Android.", Toast.LENGTH_LONG).show()
        }
    }

    fun installPendingUpdateNow() {
        val installIntent = (intent ?: Intent(this, MainActivity::class.java)).putExtra(EXTRA_INSTALL_UPDATE, true)
        setIntent(installIntent)
        processPendingUpdateInstall()
    }

    @Suppress("DEPRECATION")
    private fun removeInstalledUpdateFile() {
        val prefs = getSharedPreferences("ani-cli-pt", MODE_PRIVATE)
        val pendingVersion = prefs.getString(ReleaseUpdateWorker.PREF_PENDING_VERSION, null) ?: return
        val installed = packageManager.getPackageInfo(packageName, 0)
        val installedVersion = installed.versionName.orEmpty()
        if (compareVersionNumbers(installedVersion, pendingVersion) >= 0) {
            prefs.getString(ReleaseUpdateWorker.PREF_PENDING_APK, null)?.let(::File)?.delete()
            intent?.removeExtra(EXTRA_INSTALL_UPDATE)
            prefs.edit()
                .remove(ReleaseUpdateWorker.PREF_PENDING_APK)
                .remove(ReleaseUpdateWorker.PREF_PENDING_VERSION)
                .remove(ReleaseUpdateWorker.PREF_AWAITING_INSTALL_PERMISSION)
                .remove(ReleaseUpdateWorker.PREF_AVAILABLE_VERSION)
                .remove(ReleaseUpdateWorker.PREF_DOWNLOAD_STATUS)
                .apply()
        }
    }

    private fun compareVersionNumbers(first: String, second: String): Int {
        val a = Regex("\\d+").findAll(first).map { it.value.toIntOrNull() ?: 0 }.toList()
        val b = Regex("\\d+").findAll(second).map { it.value.toIntOrNull() ?: 0 }.toList()
        for (index in 0 until maxOf(a.size, b.size)) {
            val result = a.getOrElse(index) { 0 }.compareTo(b.getOrElse(index) { 0 })
            if (result != 0) return result
        }
        return 0
    }

    companion object {
        const val EXTRA_INSTALL_UPDATE = "dev.aniclipt.app.INSTALL_UPDATE"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}

@Composable
private fun AniCliTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color.White,
            secondaryContainer = Color(0xFF24384F),
            onSecondaryContainer = Color(0xFFD6E9FF),
            background = Canvas,
            surface = Panel,
            onBackground = Ink,
            onSurface = Ink,
            onSurfaceVariant = Muted,
        ),
        content = content,
    )
}

@Composable
private fun AnimeHome() {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
    val focusManager = LocalFocusManager.current
    val repository = remember { AnimeRepository() }
    val prefs = remember { context.getSharedPreferences("ani-cli-pt", 0) }
    val scope = rememberCoroutineScope()
    var availableUpdateVersion by remember { mutableStateOf(prefs.getString(ReleaseUpdateWorker.PREF_AVAILABLE_VERSION, null)) }
    var pendingUpdateVersion by remember { mutableStateOf(prefs.getString(ReleaseUpdateWorker.PREF_PENDING_VERSION, null)) }
    var updateDownloadStatus by remember { mutableStateOf(prefs.getString(ReleaseUpdateWorker.PREF_DOWNLOAD_STATUS, "") ?: "") }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
            when (key) {
                ReleaseUpdateWorker.PREF_AVAILABLE_VERSION -> availableUpdateVersion = sharedPreferences.getString(ReleaseUpdateWorker.PREF_AVAILABLE_VERSION, null)
                ReleaseUpdateWorker.PREF_PENDING_VERSION -> pendingUpdateVersion = sharedPreferences.getString(ReleaseUpdateWorker.PREF_PENDING_VERSION, null)
                ReleaseUpdateWorker.PREF_DOWNLOAD_STATUS -> updateDownloadStatus = sharedPreferences.getString(ReleaseUpdateWorker.PREF_DOWNLOAD_STATUS, "") ?: ""
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    var airing by remember { mutableStateOf<List<Anime>>(emptyList()) }
    var featured by remember { mutableStateOf<Anime?>(null) }
    var results by remember { mutableStateOf<List<Anime>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Anime?>(null) }
    var episodes by remember { mutableStateOf<List<Episode>>(emptyList()) }
    var seasonOptions by remember { mutableStateOf<List<Anime>>(emptyList()) }
    var currentSeason by remember { mutableStateOf<Anime?>(null) }
    var chosenEpisode by remember { mutableStateOf<Episode?>(null) }
    var source by remember { mutableStateOf<PlaybackSource?>(null) }
    var dubbed by remember { mutableStateOf(prefs.getBoolean("default_dubbed", false)) }
    var quality by remember { mutableStateOf(prefs.getString("quality", "best") ?: "best") }
    var subtitleSize by remember { mutableStateOf(prefs.getString("subtitle_size", "medium") ?: "medium") }
    var subtitleColor by remember { mutableStateOf(prefs.getString("subtitle_color", "white") ?: "white") }
    var subtitleFont by remember { mutableStateOf(prefs.getString("subtitle_font", "sans") ?: "sans") }
    var subtitleBackground by remember { mutableStateOf(prefs.getString("subtitle_background", "translucent") ?: "translucent") }
    var openSubtitlesApiKey by remember { mutableStateOf(prefs.getString("opensubtitles_api_key", "") ?: "") }
    var openSubtitlesEnabled by remember { mutableStateOf(prefs.getBoolean("opensubtitles_enabled", false)) }
    var favoriteView by remember { mutableStateOf(false) }
    var settingsView by remember { mutableStateOf(false) }
    var notificationsEnabled by remember { mutableStateOf(prefs.getBoolean("episode_notifications", false)) }
    var automaticUpdatesEnabled by remember { mutableStateOf(prefs.getBoolean(ReleaseUpdateWorker.PREF_ENABLED, false)) }
    var pendingNotificationFeature by remember { mutableStateOf<String?>(null) }
    var favorites by remember { mutableStateOf(prefs.getStringSet("favorites", emptySet())?.toSet() ?: emptySet()) }
    val savedAnime = remember { mutableStateMapOf<String, Anime>().apply {
        runCatching {
            val array = org.json.JSONArray(prefs.getString("favorite_items", "[]"))
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val anime = Anime(item.getString("slug"), item.getString("title"), item.optString("poster"), synopsis = item.optString("synopsis"))
                put(anime.slug, anime)
            }
        }
    } }
    var history by remember { mutableStateOf(prefs.getString("history", "") ?: "") }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            when (pendingNotificationFeature) {
                "episodes" -> {
                    notificationsEnabled = false
                    prefs.edit().putBoolean("episode_notifications", false).apply()
                    WorkManager.getInstance(context).cancelUniqueWork("favorite-episode-check")
                }
                "updates" -> {
                    automaticUpdatesEnabled = false
                    prefs.edit().putBoolean(ReleaseUpdateWorker.PREF_ENABLED, false).apply()
                    ReleaseUpdateWorker.cancel(context)
                }
            }
        }
        pendingNotificationFeature = null
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        notificationsEnabled = enabled
        prefs.edit().putBoolean("episode_notifications", enabled).apply()
        if (!enabled) {
            WorkManager.getInstance(context).cancelUniqueWork("favorite-episode-check")
            favorites.forEach { prefs.edit().remove("episode_snapshot:$it").apply() }
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingNotificationFeature = "episodes"
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        FavoriteEpisodeWorker.captureBaseline(context)
        FavoriteEpisodeWorker.schedule(context)
    }

    fun toggleFavorite(anime: Anime) {
        val next = favorites.toMutableSet().apply { if (!add(anime.slug)) remove(anime.slug) }
        favorites = next
        prefs.edit().putStringSet("favorites", next).apply()
        if (anime.slug in next) savedAnime[anime.slug] = anime else savedAnime.remove(anime.slug)
        if (anime.slug !in next) prefs.edit().remove("episode_snapshot:${anime.slug}").apply()
        val array = org.json.JSONArray()
        savedAnime.values.forEach { saved -> array.put(org.json.JSONObject().put("slug", saved.slug).put("title", saved.title).put("poster", saved.poster).put("synopsis", saved.synopsis)) }
        prefs.edit().putString("favorite_items", array.toString()).apply()
        if (anime.slug in next && notificationsEnabled) FavoriteEpisodeWorker.captureBaseline(context)
    }
    fun setAutomaticUpdatesEnabled(enabled: Boolean) {
        automaticUpdatesEnabled = enabled
        prefs.edit().putBoolean(ReleaseUpdateWorker.PREF_ENABLED, enabled).apply()
        if (!enabled) {
            ReleaseUpdateWorker.cancel(context)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingNotificationFeature = "updates"
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        ReleaseUpdateWorker.schedule(context)
        ReleaseUpdateWorker.checkNow(context)
    }
    fun downloadUpdate() {
        updateDownloadStatus = ReleaseUpdateWorker.STATUS_DOWNLOADING
        prefs.edit().putString(ReleaseUpdateWorker.PREF_DOWNLOAD_STATUS, updateDownloadStatus).apply()
        ReleaseUpdateWorker.downloadManually(context)
    }
    fun openSeason(season: Anime) {
        currentSeason = season
        episodes = emptyList()
        busy = true
        error = null
        scope.launch {
            runCatching { repository.episodes(season) }
                .onSuccess { episodes = it; if (it.isEmpty()) error = "Nenhum episódio disponível nesta temporada." }
                .onFailure { error = it.message ?: "Não foi possível carregar esta temporada." }
            busy = false
        }
    }
    fun openAnime(anime: Anime) {
        selected = anime
        currentSeason = anime
        seasonOptions = listOf(anime)
        episodes = emptyList()
        chosenEpisode = null
        busy = true
        error = null
        scope.launch {
            launch {
                if (anime.banner.isBlank()) runCatching { repository.artwork(anime) }.onSuccess { artwork ->
                    if (selected?.slug == anime.slug) selected = artwork
                }
            }
            launch {
                runCatching { repository.seasons(anime) }.onSuccess { options ->
                    if (selected?.slug == anime.slug) seasonOptions = options.ifEmpty { listOf(anime) }
                }
            }
            val episodeResult = runCatching { repository.episodes(anime) }
            episodeResult.onSuccess { episodes = it; if (it.isEmpty()) error = "Nenhum episódio disponível para este título." }
                .onFailure { error = it.message ?: "Não foi possível carregar os episódios." }
            busy = false
        }
    }
    fun search() {
        if (query.isBlank()) return
        focusManager.clearFocus(force = true)
        favoriteView = false
        busy = true
        error = null
        scope.launch {
            runCatching { repository.search(query.trim()) }
                .onSuccess { results = it; if (it.isEmpty()) error = "Nenhum resultado encontrado." }
                .onFailure { error = "Falha ao buscar o catálogo: ${it.message ?: "erro de rede"}" }
            busy = false
        }
    }
    fun play(anime: Anime, episode: Episode) {
        busy = true
        error = null
        scope.launch {
            runCatching { repository.resolve(anime, episode, dubbed, quality, openSubtitlesEnabled, openSubtitlesApiKey.trim()) }
                .onSuccess {
                    source = it
                    chosenEpisode = episode
                    val updated = (history.split(',').filter { item -> item.isNotBlank() && item != "${anime.slug}:${episode.number}" } + "${anime.slug}:${episode.number}").takeLast(500)
                    history = updated.joinToString(",")
                    prefs.edit().putString("history", history).apply()
                }
                .onFailure { error = it.message ?: "Não foi possível preparar a reprodução." }
            busy = false
        }
    }

    LaunchedEffect(Unit) {
        if (notificationsEnabled) FavoriteEpisodeWorker.schedule(context)
        if (automaticUpdatesEnabled) {
            ReleaseUpdateWorker.schedule(context)
            ReleaseUpdateWorker.checkNow(context)
        } else ReleaseUpdateWorker.checkOnLaunch(context)
        focusManager.clearFocus(force = true)
        busy = true
        runCatching { repository.airing() }
            .onSuccess { items ->
                airing = items
                results = items
                items.firstOrNull()?.let { first ->
                    featured = first
                    runCatching { repository.artwork(first) }.onSuccess { artwork -> featured = artwork }
                }
            }
            .onFailure { error = "Não foi possível carregar os animes em exibição: ${it.message ?: "erro de rede"}. Tente buscar um título." }
        busy = false
    }

    val visibleItems = when {
        favoriteView -> savedAnime.values.toList()
        query.isBlank() -> results.filterNot { it.slug == featured?.slug }
        else -> results
    }

    BackHandler(enabled = source != null || selected != null || settingsView) {
        if (source != null) source = null else if (selected != null) { selected = null; currentSeason = null; seasonOptions = emptyList(); error = null } else settingsView = false
    }

    Box(Modifier.fillMaxSize()) {
    Scaffold(containerColor = Canvas, bottomBar = {
        val navShape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
        NavigationBar(
            modifier = Modifier
                .then(if (isLandscape) Modifier.height(64.dp) else Modifier)
                .shadow(18.dp, navShape)
                .clip(navShape)
                .border(1.dp, Color.White.copy(alpha = 0.10f), navShape),
            containerColor = Color(0xE51C1C1E),
            contentColor = Ink,
            tonalElevation = 0.dp,
        ) {
            NavigationBarItem(selected = !favoriteView && !settingsView, onClick = { settingsView = false; favoriteView = false; results = airing }, icon = { Icon(Icons.Default.Search, "Explorar") }, label = if (isLandscape) null else {{ Text("Explorar") }})
            NavigationBarItem(selected = favoriteView, onClick = { settingsView = false; favoriteView = true }, icon = { Icon(Icons.Default.Favorite, "Favoritos") }, label = if (isLandscape) null else {{ Text("Favoritos") }})
            NavigationBarItem(selected = settingsView, onClick = { settingsView = true; favoriteView = false }, icon = { Icon(Icons.Default.Settings, "Configurações") }, label = if (isLandscape) null else {{ Text("Configurações") }})
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().widthIn(max = 960.dp).align(Alignment.CenterHorizontally).padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(13.dp)).background(Brush.linearGradient(listOf(Color(0xFF303036), Color(0xFF19191C)))), contentAlignment = Alignment.Center) {
                    Text("あ", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(10.dp))
                Text("ani-cli", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Ink)
                Text("pt", color = Accent, fontSize = 12.sp, modifier = Modifier.align(Alignment.Top).padding(top = 3.dp))
            }
            if (!settingsView) Row(Modifier.fillMaxWidth().widthIn(max = 960.dp).align(Alignment.CenterHorizontally).padding(horizontal = 16.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xC8202024)).border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Search, null, tint = Muted)
                TextField(value = query, onValueChange = { query = it }, modifier = Modifier.weight(1f), placeholder = { Text("Buscar anime...", color = Muted, maxLines = 1) }, singleLine = true, colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }))
                TextButton(onClick = { search() }) { Text("Buscar") }
            }
            if (settingsView) {
                SettingsScreen(
                    modifier = Modifier.weight(1f).widthIn(max = 760.dp).align(Alignment.CenterHorizontally),
                    quality = quality,
                    onQuality = { quality = it; prefs.edit().putString("quality", it).apply() },
                    dubbed = dubbed,
                    onDubbed = { dubbed = it; prefs.edit().putBoolean("default_dubbed", it).apply() },
                    notificationsEnabled = notificationsEnabled,
                    onNotifications = ::setNotificationsEnabled,
                    automaticUpdatesEnabled = automaticUpdatesEnabled,
                    onAutomaticUpdates = ::setAutomaticUpdatesEnabled,
                    availableUpdateVersion = availableUpdateVersion,
                    pendingUpdateVersion = pendingUpdateVersion,
                    updateDownloadStatus = updateDownloadStatus,
                    onDownloadUpdate = ::downloadUpdate,
                    onInstallUpdate = { (context as? MainActivity)?.installPendingUpdateNow() },
                    subtitleSize = subtitleSize,
                    onSubtitleSize = { subtitleSize = it; prefs.edit().putString("subtitle_size", it).apply() },
                    subtitleColor = subtitleColor,
                    onSubtitleColor = { subtitleColor = it; prefs.edit().putString("subtitle_color", it).apply() },
                    subtitleFont = subtitleFont,
                    onSubtitleFont = { subtitleFont = it; prefs.edit().putString("subtitle_font", it).apply() },
                    subtitleBackground = subtitleBackground,
                    onSubtitleBackground = { subtitleBackground = it; prefs.edit().putString("subtitle_background", it).apply() },
                    openSubtitlesApiKey = openSubtitlesApiKey,
                    onOpenSubtitlesApiKey = { openSubtitlesApiKey = it; prefs.edit().putString("opensubtitles_api_key", it).apply() },
                    openSubtitlesEnabled = openSubtitlesEnabled,
                    onOpenSubtitlesEnabled = { openSubtitlesEnabled = it; prefs.edit().putBoolean("opensubtitles_enabled", it).apply() },
                )
            } else LazyColumn(Modifier.weight(1f).widthIn(max = 960.dp).align(Alignment.CenterHorizontally), contentPadding = PaddingValues(bottom = 20.dp)) {
                if (!favoriteView && query.isBlank() && featured != null) {
                    item(key = "featured") {
                        Featured(
                            anime = featured!!,
                            onOpen = { openAnime(featured!!) },
                            onFavorite = { toggleFavorite(featured!!) },
                            isFavorite = featured!!.slug in favorites,
                        )
                    }
                }
                item(key = "catalog-heading") {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
                        Column(Modifier.weight(1f)) {
                            Text(if (favoriteView) "SUA BIBLIOTECA" else if (query.isBlank()) "NO AR AGORA" else "RESULTADOS", fontSize = 10.sp, color = Muted, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                            Text(if (favoriteView) "Favoritos" else if (query.isBlank()) "Em exibição" else "Busca", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        }
                        if (configuration.screenWidthDp >= 380) Text("Áudio original · PT-BR", fontSize = 11.sp, color = Muted, maxLines = 1)
                    }
                }
                if (busy && visibleItems.isEmpty()) {
                    item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp), color = Accent) }
                }
                error?.let { message ->
                    item(key = "error") { Text(message, Modifier.padding(horizontal = 20.dp, vertical = 12.dp), color = Color(0xFFFF8A80), fontSize = 13.sp) }
                }
                if (visibleItems.isEmpty() && !busy && error == null) {
                    item(key = "empty") { Text(if (favoriteView) "Seus animes favoritos aparecem aqui." else "Busque um título para começar.", Modifier.padding(20.dp), color = Muted) }
                }
                items(visibleItems, key = { it.slug }) { anime ->
                    AnimeRow(anime, anime.slug in favorites, onClick = { openAnime(anime) }, onFavorite = { toggleFavorite(anime) })
                }
            }
        }
    }

    if (selected != null && source == null) {
        val episodeAnime = currentSeason ?: selected!!
        EpisodeSheet(anime = episodeAnime, episodes = episodes, watched = history.split(',').filter { it.isNotBlank() }.toSet(), busy = busy, error = error, dubbed = dubbed, onDubbed = { dubbed = it; prefs.edit().putBoolean("default_dubbed", it).apply() }, quality = quality, onQuality = { quality = it; prefs.edit().putString("quality", it).apply() }, favorites = favorites, favoriteSlug = selected!!.slug, onFavorite = { toggleFavorite(selected!!) }, seasons = seasonOptions, onSeasonSelected = ::openSeason, onClose = { selected = null; currentSeason = null; seasonOptions = emptyList(); error = null }, onPlay = { episode -> play(episodeAnime, episode) })
    }
    if (source != null) {
        NativePlayer(source = source!!, onClose = { source = null }, subtitleSize = subtitleSize, subtitleColor = subtitleColor, subtitleFont = subtitleFont, subtitleBackground = subtitleBackground, modifier = Modifier.fillMaxSize())
    }
    }
}

@Composable
private fun Featured(anime: Anime, onOpen: () -> Unit, onFavorite: () -> Unit, isFavorite: Boolean) {
    val configuration = LocalConfiguration.current
    val height = if (configuration.screenWidthDp > configuration.screenHeightDp) 190.dp else 264.dp
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp).height(height).clip(RoundedCornerShape(26.dp)).clickable(onClick = onOpen)) {
        AsyncImage(model = anime.banner.ifBlank { anime.poster }, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(.02f), Color.Black.copy(.30f), Color(0xF0111113)))))
        Column(Modifier.align(Alignment.BottomStart).padding(start = 22.dp, end = 20.dp, bottom = 22.dp)) {
            Text("ANIME DO MOMENTO", color = Color(0xFFB9D9FF), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Spacer(Modifier.height(6.dp))
            Text(anime.title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 2)
            Text(anime.synopsis.ifBlank { "Descubra episódios com áudio original e legendas em português quando disponíveis." }, color = Color(0xFFD1D1D6), fontSize = 12.sp, maxLines = 2)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onOpen, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF171719)), contentPadding = PaddingValues(horizontal = 16.dp)) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text("Ver episódios") }
                Spacer(Modifier.width(8.dp))
                FilledTonalIconButton(onClick = onFavorite, colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Color.White.copy(.15f), contentColor = Color.White)) { Icon(if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favoritar") }
            }
        }
    }
}

@Composable
private fun AnimeRow(anime: Anime, favorite: Boolean, onClick: () -> Unit, onFavorite: () -> Unit) {
    val compact = LocalConfiguration.current.screenWidthDp < 380
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = if (compact) 12.dp else 18.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(model = anime.poster, contentDescription = anime.title, modifier = Modifier.width(if (compact) 68.dp else 82.dp).height(if (compact) 96.dp else 112.dp).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop)
        Column(Modifier.weight(1f).padding(horizontal = if (compact) 10.dp else 14.dp)) {
            Text(anime.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 2)
            Text(anime.metadata.joinToString(" · ").ifBlank { "Em exibição" }, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            Text(anime.synopsis.ifBlank { "Áudio original · legendas PT-BR quando disponíveis" }, color = Muted, fontSize = 11.sp, maxLines = 2, modifier = Modifier.padding(top = 5.dp))
            Row(Modifier.padding(top = 7.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (compact) Tag("ORIGINAL · PT-BR → EN") else {
                    Tag("ÁUDIO ORIGINAL")
                    Tag("PT-BR → EN")
                }
            }
        }
        IconButton(onClick = onFavorite) { Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favoritar", tint = if (favorite) Color(0xFFFF6B82) else Muted) }
    }
}

@Composable
private fun Tag(text: String) {
    Text(text, modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(Color(0xFF303036)).padding(horizontal = 7.dp, vertical = 4.dp), fontSize = 8.sp, color = Color(0xFFD1D1D6), letterSpacing = .3.sp)
}

@Composable
private fun SettingsScreen(
    modifier: Modifier = Modifier,
    quality: String,
    onQuality: (String) -> Unit,
    dubbed: Boolean,
    onDubbed: (Boolean) -> Unit,
    notificationsEnabled: Boolean,
    onNotifications: (Boolean) -> Unit,
    automaticUpdatesEnabled: Boolean,
    onAutomaticUpdates: (Boolean) -> Unit,
    availableUpdateVersion: String?,
    pendingUpdateVersion: String?,
    updateDownloadStatus: String,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    subtitleSize: String,
    onSubtitleSize: (String) -> Unit,
    subtitleColor: String,
    onSubtitleColor: (String) -> Unit,
    subtitleFont: String,
    onSubtitleFont: (String) -> Unit,
    subtitleBackground: String,
    onSubtitleBackground: (String) -> Unit,
    openSubtitlesApiKey: String,
    onOpenSubtitlesApiKey: (String) -> Unit,
    openSubtitlesEnabled: Boolean,
    onOpenSubtitlesEnabled: (Boolean) -> Unit,
) {
    val qualityOptions = listOf("best" to "Automática (melhor disponível)", "1080p" to "1080p", "720p" to "720p", "480p" to "480p", "360p" to "360p")
    var qualityExpanded by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp)) {
        Text("PREFERÊNCIAS", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Text("Configurações", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 18.dp))
        Text("REPRODUÇÃO", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xB8202024)).border(1.dp, Color.White.copy(.08f), RoundedCornerShape(18.dp)).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().clickable { qualityExpanded = true }.padding(vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Qualidade padrão", fontWeight = FontWeight.SemiBold, fontSize = 14.sp); Text("Aplicada ao selecionar um episódio", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp)) }
                Text(qualityOptions.firstOrNull { it.first == quality }?.second?.substringBefore(" (") ?: quality, color = Color(0xFFB9D9FF), fontSize = 12.sp)
                DropdownMenu(expanded = qualityExpanded, onDismissRequest = { qualityExpanded = false }) {
                    qualityOptions.forEach { (value, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { onQuality(value); qualityExpanded = false }) }
                }
            }
            HorizontalDivider(color = Color.White.copy(.08f))
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 10.dp)) { Text("Preferir dublagem PT-BR", fontWeight = FontWeight.SemiBold, fontSize = 14.sp); Text("Sem faixa PT-BR identificada: usa áudio original", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp)) }
                Switch(checked = dubbed, onCheckedChange = onDubbed)
            }
        }
        Text("APARÊNCIA DAS LEGENDAS", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp, modifier = Modifier.padding(start = 4.dp, top = 22.dp, bottom = 8.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xB8202024)).border(1.dp, Color.White.copy(.08f), RoundedCornerShape(18.dp)).padding(horizontal = 16.dp)) {
            SubtitlePreferenceRow("Tamanho", subtitleSize, listOf("small" to "Pequeno", "medium" to "Médio", "large" to "Grande"), onSubtitleSize)
            HorizontalDivider(color = Color.White.copy(.08f))
            SubtitlePreferenceRow("Cor do texto", subtitleColor, listOf("white" to "Branco", "yellow" to "Amarelo", "cyan" to "Ciano", "green" to "Verde"), onSubtitleColor)
            HorizontalDivider(color = Color.White.copy(.08f))
            SubtitlePreferenceRow("Tipo de letra", subtitleFont, listOf("sans" to "Sem serifa", "serif" to "Serifada", "mono" to "Monoespaçada"), onSubtitleFont)
            HorizontalDivider(color = Color.White.copy(.08f))
            SubtitlePreferenceRow("Fundo", subtitleBackground, listOf("none" to "Sem fundo", "translucent" to "Translúcido", "dark" to "Escuro"), onSubtitleBackground)
        }
        Text("BUSCA DE LEGENDAS PT-BR", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp, modifier = Modifier.padding(start = 4.dp, top = 22.dp, bottom = 8.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xB8202024)).border(1.dp, Color.White.copy(.08f), RoundedCornerShape(18.dp)).padding(16.dp)) {
            Text("OpenSubtitles", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text("Ative para buscar legendas PT-BR externas quando a fonte do vídeo não tiver uma. Desativado, o app não consulta o OpenSubtitles.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 10.dp)) {
                    Text("Usar OpenSubtitles", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(if (openSubtitlesEnabled) "Busca externa ativada" else "Busca externa desativada", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
                }
                Switch(checked = openSubtitlesEnabled, onCheckedChange = onOpenSubtitlesEnabled)
            }
            OutlinedTextField(
                value = openSubtitlesApiKey,
                onValueChange = onOpenSubtitlesApiKey,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Chave de API") },
                placeholder = { Text("Insira sua chave OpenSubtitles") },
                visualTransformation = PasswordVisualTransformation(),
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Ink, unfocusedTextColor = Ink, focusedBorderColor = Accent, unfocusedBorderColor = Color.White.copy(.18f), focusedLabelColor = Accent, unfocusedLabelColor = Muted, cursorColor = Accent),
            )
            Text("A chave só é usada quando esta opção está ativada. Sem chave, a busca externa não funciona.", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
        }
        Text("NOTIFICAÇÕES", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp, modifier = Modifier.padding(start = 4.dp, top = 22.dp, bottom = 8.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xB8202024)).border(1.dp, Color.White.copy(.08f), RoundedCornerShape(18.dp)).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 10.dp)) { Text("Novos episódios", fontWeight = FontWeight.SemiBold, fontSize = 14.sp); Text("Avisar quando sair episódio de um anime favorito. Verificação periódica, em geral a cada 6 horas.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)) }
                Switch(checked = notificationsEnabled, onCheckedChange = onNotifications)
            }
        }
        Text("ATUALIZAÇÕES", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp, modifier = Modifier.padding(start = 4.dp, top = 22.dp, bottom = 8.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xB8202024)).border(1.dp, Color.White.copy(.08f), RoundedCornerShape(18.dp)).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 10.dp)) {
                    Text("Atualizações automáticas", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("O app sempre verifica ao abrir. Ative para também verificar e baixar em segundo plano. Se houver novidade, você pode baixar por aqui.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                    if (availableUpdateVersion != null) {
                        Text("Nova versão $availableUpdateVersion disponível.", color = Color(0xFF8FC5FF), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    }
                    if (pendingUpdateVersion != null) {
                        Text("Versão $pendingUpdateVersion pronta para instalar.", color = Color(0xFF8FC5FF), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    } else when (updateDownloadStatus) {
                        ReleaseUpdateWorker.STATUS_DOWNLOADING -> Text("Baixando atualização…", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                        ReleaseUpdateWorker.STATUS_FAILED -> Text("Não foi possível baixar. Tente novamente.", color = Color(0xFFFF9B9B), fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                        ReleaseUpdateWorker.STATUS_UP_TO_DATE -> Text("O app já está na versão mais recente.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }
                Switch(checked = automaticUpdatesEnabled, onCheckedChange = onAutomaticUpdates)
            }
            if (pendingUpdateVersion != null) {
                Button(onClick = onInstallUpdate, modifier = Modifier.fillMaxWidth().padding(top = 10.dp), colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White)) {
                    Text("Instalar atualização")
                }
            } else if (availableUpdateVersion != null) {
                Button(
                    onClick = onDownloadUpdate,
                    enabled = updateDownloadStatus != ReleaseUpdateWorker.STATUS_DOWNLOADING,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
                ) {
                    Text(if (updateDownloadStatus == ReleaseUpdateWorker.STATUS_FAILED) "Tentar baixar novamente" else "Baixar atualização")
                }
            }
        }
        Text("ani-cli-pt · 0.1.3", color = Muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 24.dp))
    }
}

@Composable
private fun SubtitlePreferenceRow(title: String, selected: String, options: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Box {
            TextButton(onClick = { expanded = true }) {
                Text(options.firstOrNull { it.first == selected }?.second ?: options.first().second, color = Color(0xFFB9D9FF), fontSize = 12.sp, maxLines = 1)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (value, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { onSelect(value); expanded = false })
                }
            }
        }
    }
}

private fun qualityLabel(quality: String): String = when (quality) {
    "best" -> "Automática"
    else -> quality
}

private fun seasonLabel(anime: Anime, seasons: List<Anime>): String {
    val explicitNumber = Regex("(?:season\\s*(\\d+)|(\\d+)(?:st|nd|rd|th)\\s+season|\\bs\\s*(\\d+)\\b)", RegexOption.IGNORE_CASE)
        .find(anime.title)?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }?.toIntOrNull()
    val inferredNumber = seasons.indexOfFirst { it.slug == anime.slug }.takeIf { it >= 0 }?.plus(1)
    return "Temporada ${explicitNumber ?: inferredNumber ?: 1}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EpisodeSheet(anime: Anime, episodes: List<Episode>, watched: Set<String>, busy: Boolean, error: String?, dubbed: Boolean, onDubbed: (Boolean) -> Unit, quality: String, onQuality: (String) -> Unit, favorites: Set<String>, favoriteSlug: String, onFavorite: () -> Unit, seasons: List<Anime>, onSeasonSelected: (Anime) -> Unit, onClose: () -> Unit, onPlay: (Episode) -> Unit) {
    var picked by remember(anime.slug) { mutableStateOf<Episode?>(null) }
    var qualityExpanded by remember { mutableStateOf(false) }
    var seasonExpanded by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    val compact = configuration.screenWidthDp < 360
    ModalBottomSheet(sheetState = sheetState, onDismissRequest = onClose, containerColor = Color(0xFF202024), contentColor = Ink, dragHandle = { BottomSheetDefaults.DragHandle(color = Muted) }) {
        Column(Modifier.fillMaxHeight(if (landscape) .98f else .92f).padding(horizontal = if (compact) 12.dp else 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(model = anime.poster, contentDescription = anime.title, modifier = Modifier.size(if (landscape) 54.dp else 76.dp, if (landscape) 72.dp else 100.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text("ANI-CLI-PT  /  ANIME", color = Accent, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
                    Text(anime.title, fontWeight = FontWeight.Bold, fontSize = if (landscape) 16.sp else 20.sp, maxLines = if (landscape) 1 else 2)
                    Text(anime.metadata.joinToString(" · "), color = Muted, fontSize = 11.sp)
                    Text(anime.synopsis.ifBlank { "Sinopse não disponível." }, color = Color(0xFFB8B8C0), fontSize = 11.sp, maxLines = if (landscape) 1 else 3, modifier = Modifier.padding(top = 6.dp))
                }
                IconButton(onClick = onFavorite) { Icon(if (favoriteSlug in favorites) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favoritar", tint = if (favoriteSlug in favorites) Color(0xFFFF6B82) else Muted) }
            }
            HorizontalDivider(Modifier.padding(vertical = 14.dp), color = Color(0xFF38383D))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("GUIA DE EPISÓDIOS", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp); Text("Escolha onde começar", fontWeight = FontWeight.SemiBold, fontSize = 17.sp) }
                if (seasons.size > 1) {
                    Box {
                        Column(horizontalAlignment = Alignment.End) {
                            TextButton(onClick = { seasonExpanded = true }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                                Text(seasonLabel(anime, seasons), color = Color(0xFFB9D9FF), fontSize = 11.sp, maxLines = 1)
                            }
                            Text("${episodes.size} episódios", color = Muted, fontSize = 10.sp)
                        }
                        DropdownMenu(expanded = seasonExpanded, onDismissRequest = { seasonExpanded = false }) {
                            seasons.forEach { season ->
                                DropdownMenuItem(
                                    text = { Text(seasonLabel(season, seasons)) },
                                    onClick = { seasonExpanded = false; if (season.slug != anime.slug) onSeasonSelected(season) },
                                )
                            }
                        }
                    }
                } else Text("${episodes.size} episódios", color = Muted, fontSize = 11.sp)
            }
            BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                val narrow = maxWidth < 380.dp
                if (narrow) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(selected = !dubbed, onClick = { onDubbed(false) }, label = { Text(if (compact) "Original" else "Original · legendado", maxLines = 1) })
                            Spacer(Modifier.width(6.dp))
                            FilterChip(selected = dubbed, onClick = { onDubbed(true) }, label = { Text(if (compact) "PT-BR" else "Dublado PT-BR") })
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Box {
                                TextButton(onClick = { qualityExpanded = true }) { Text("${qualityLabel(quality)} ▾", fontSize = 10.sp) }
                                DropdownMenu(expanded = qualityExpanded, onDismissRequest = { qualityExpanded = false }) { listOf("best" to "Automática", "1080p" to "1080p", "720p" to "720p", "480p" to "480p", "360p" to "360p").forEach { (value, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { onQuality(value); qualityExpanded = false }) } }
                            }
                        }
                    }
                } else Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = !dubbed, onClick = { onDubbed(false) }, label = { Text("Original · legendado", maxLines = 1) })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = dubbed, onClick = { onDubbed(true) }, label = { Text("Dublado PT-BR") })
                    Spacer(Modifier.weight(1f))
                    Box {
                        TextButton(onClick = { qualityExpanded = true }) { Text("${qualityLabel(quality)} ▾", fontSize = 10.sp) }
                        DropdownMenu(expanded = qualityExpanded, onDismissRequest = { qualityExpanded = false }) { listOf("best" to "Automática", "1080p" to "1080p", "720p" to "720p", "480p" to "480p", "360p" to "360p").forEach { (value, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { onQuality(value); qualityExpanded = false }) } }
                    }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
            error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp)) }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 8.dp)) {
                items(episodes, key = { it.id }) { episode ->
                    val isSelected = picked?.id == episode.id
                    val hasWatched = "${anime.slug}:${episode.number}" in watched
                    val rowShape = RoundedCornerShape(13.dp)
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(rowShape)
                        .background(if (hasWatched) Color(0xFF20382F) else Color(0xFF29292E))
                        .border(if (isSelected) 1.dp else 0.dp, if (isSelected) Color(0xFF5598E8) else Color.Transparent, rowShape)
                        .clickable { picked = episode }.padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(episode.number.padStart(2, '0'), modifier = Modifier.clip(RoundedCornerShape(9.dp)).background(if (hasWatched) Color(0xFF28513F) else if (isSelected) Color(0xFF153451) else Color(0xFF38383D)).padding(horizontal = 10.dp, vertical = 8.dp), color = if (hasWatched) Color(0xFF9CE0B9) else if (isSelected) Color(0xFFB9D9FF) else Muted, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text("Episódio ${episode.number}", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            if (episode.title != "Episódio ${episode.number}") Text(episode.title, color = Muted, fontSize = 10.sp, maxLines = 1)
                        }
                        Button(onClick = { onPlay(episode) }, enabled = !busy, shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp), modifier = Modifier.widthIn(min = 80.dp)) { Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(2.dp)); Text("Assistir", fontSize = 10.sp, maxLines = 1) }
                    }
                }
            }
        }
    }
}

@Composable
private fun NativePlayer(source: PlaybackSource, onClose: () -> Unit, subtitleSize: String, subtitleColor: String, subtitleFont: String, subtitleBackground: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = context as? Activity
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val previousOrientation = remember(source.videoUrl) { activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    val lifecycleOwner = LocalLifecycleOwner.current
    val playbackError = remember(source.videoUrl) { mutableStateOf<String?>(null) }
    val player = remember(source.videoUrl) {
        val origin = runCatching { android.net.Uri.parse(source.referer).let { "${it.scheme}://${it.host}" } }.getOrDefault(source.referer)
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36",
            "Referer" to source.referer,
            "Origin" to origin,
            "Accept" to "*/*",
            "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.7",
        )
        val dataSource = DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers).setAllowCrossProtocolRedirects(true)
        val mediaItem = MediaItem.Builder().setUri(source.videoUrl).apply {
            source.subtitleUrl?.let { subtitle ->
                val path = android.net.Uri.parse(subtitle).path.orEmpty()
                val isSubRip = path.endsWith(".srt", true) || source.subtitleLabel.contains("OpenSubtitles", true)
                val mime = if (isSubRip) MimeTypes.APPLICATION_SUBRIP else MimeTypes.TEXT_VTT
                val language = if (source.subtitleLabel.contains("inglês", true)) "en" else "pt-BR"
                setSubtitleConfigurations(listOf(MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(subtitle)).setMimeType(mime).setLanguage(language).setLabel(source.subtitleLabel).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()))
            }
        }.build()
        ExoPlayer.Builder(context).setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSource)).build().apply {
            addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    val causeChain = generateSequence(error as Throwable?) { it.cause }.toList()
                    val forbiddenRequest = causeChain.filterIsInstance<InvalidResponseCodeException>().firstOrNull()
                    val isForbidden = forbiddenRequest != null || causeChain.any { it.message?.contains("403") == true }
                    android.util.Log.e("AniCliPlayer", "Playback failed${forbiddenRequest?.let { " (${it.responseCode}) ${it.dataSpec.uri}" }.orEmpty()}", error)
                    playbackError.value = if (isForbidden) "A fonte recusou a reprodução. Volte e tente outra qualidade ou episódio." else "Não foi possível carregar este vídeo. Confira sua conexão e tente novamente."
                }
            })
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player, lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) { androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> player.pause(); androidx.lifecycle.Lifecycle.Event.ON_RESUME -> if (player.playWhenReady) player.play(); else -> Unit }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.release()
            activity?.requestedOrientation = previousOrientation
        }
    }
    Column(modifier.background(Color.Black).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().background(Color(0xFF171719)).padding(horizontal = 12.dp, vertical = if (isLandscape) 2.dp else 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Default.ArrowBack, "Voltar", tint = Ink) }
            Column(Modifier.padding(start = 4.dp)) { Text(source.title, color = Ink, fontWeight = FontWeight.SemiBold, maxLines = 1); Text("Episódio ${source.episode} · ${source.audioLabel} · ${source.subtitleLabel}", color = Muted, fontSize = 10.sp, maxLines = 1) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { activity?.requestedOrientation = if (isLandscape) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }) {
                Icon(Icons.Default.ScreenRotation, if (isLandscape) "Voltar para modo retrato" else "Girar para modo paisagem", tint = Ink)
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { viewContext -> PlayerView(viewContext).apply {
                    this.player = player
                    useController = true
                    resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                    applySubtitlePreferences(this, isLandscape, subtitleSize, subtitleColor, subtitleFont, subtitleBackground)
                } },
                update = { view -> applySubtitlePreferences(view, isLandscape, subtitleSize, subtitleColor, subtitleFont, subtitleBackground) },
                modifier = Modifier.fillMaxSize(),
            )
            playbackError.value?.let { message ->
                val compactActions = LocalConfiguration.current.screenWidthDp < 380
                Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xE5222227)).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Não foi possível reproduzir", color = Ink, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text(message, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                    if (compactActions) {
                        Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Voltar aos episódios") }
                            Button(onClick = { playbackError.value = null; player.seekTo(0); player.prepare(); player.playWhenReady = true }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White)) { Text("Tentar novamente") }
                        }
                    } else Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onClose) { Text("Voltar aos episódios") }
                        Button(onClick = { playbackError.value = null; player.seekTo(0); player.prepare(); player.playWhenReady = true }, colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White)) { Text("Tentar novamente") }
                    }
                }
            }
        }
        if (!isLandscape) Text("A reprodução usa a fonte disponível para este episódio.", Modifier.align(Alignment.CenterHorizontally).padding(bottom = 24.dp), color = Muted, fontSize = 11.sp)
    }
}

private fun applySubtitlePreferences(view: PlayerView, isLandscape: Boolean, size: String, color: String, font: String, background: String) {
    view.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
    val foregroundColor = when (color) {
        "yellow" -> android.graphics.Color.YELLOW
        "cyan" -> android.graphics.Color.CYAN
        "green" -> android.graphics.Color.GREEN
        else -> android.graphics.Color.WHITE
    }
    val backgroundColor = when (background) {
        "none" -> android.graphics.Color.TRANSPARENT
        "dark" -> 0xE6000000.toInt()
        else -> 0x99000000.toInt()
    }
    val typeface = when (font) {
        "serif" -> Typeface.SERIF
        "mono" -> Typeface.MONOSPACE
        else -> Typeface.SANS_SERIF
    }
    view.subtitleView?.apply {
        setApplyEmbeddedStyles(false)
        setApplyEmbeddedFontSizes(false)
        setStyle(CaptionStyleCompat(foregroundColor, backgroundColor, android.graphics.Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_OUTLINE, android.graphics.Color.BLACK, typeface))
        setFractionalTextSize(when (size) { "small" -> 0.043f; "large" -> 0.066f; else -> 0.0533f })
        setBottomPaddingFraction(if (isLandscape) 0.18f else 0.10f)
    }
}
