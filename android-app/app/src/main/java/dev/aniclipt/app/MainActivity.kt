package dev.aniclipt.app

import android.os.Bundle
import android.os.SystemClock
import android.Manifest
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.util.Rational
import android.view.Gravity
import android.widget.Toast
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.text.CueGroup
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import androidx.media3.ui.CaptionStyleCompat
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import dev.aniclipt.app.data.Anime
import dev.aniclipt.app.data.AnimeRepository
import dev.aniclipt.app.data.IdentityRegistry
import dev.aniclipt.app.data.HomeSection
import dev.aniclipt.app.data.reuseEpisodeMetadata
import dev.aniclipt.app.data.AniSkipRepository
import dev.aniclipt.app.data.SkipInterval
import dev.aniclipt.app.data.activeSkipInterval
import dev.aniclipt.app.data.Episode
import dev.aniclipt.app.data.PlaybackSource
import dev.aniclipt.app.data.DubSuggestion
import dev.aniclipt.app.data.initialSynopsis
import dev.aniclipt.app.data.catalogTags
import dev.aniclipt.app.data.showInCatalog
import dev.aniclipt.app.data.visibleHomeSections
import dev.aniclipt.app.data.seasonWithRetainedArtwork
import dev.aniclipt.app.data.seasonDisplayLabel
import dev.aniclipt.app.data.seasonWatchLabel
import dev.aniclipt.app.data.imageUrlForWidth
import dev.aniclipt.app.data.explicitSeason
import dev.aniclipt.app.data.adjacentEpisode
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
import dev.aniclipt.app.ui.theme.*
import dev.aniclipt.app.ui.home.*
import dev.aniclipt.app.ui.episodes.*
import dev.aniclipt.app.ui.player.*
import dev.aniclipt.app.ui.settings.*

internal enum class ApiKeyValidation { UNKNOWN, EMPTY, CHECKING, VALID, INVALID, UNAVAILABLE }

internal data class WatchHistoryItem(
    val anime: Anime,
    val secondsWatched: Long,
    val lastEpisode: String,
    val lastEpisodeId: String,
    val lastEpisodeNumber: String,
    val lastEpisodeTitle: String,
    val resumePositionMs: Long,
    val episodeDurationMs: Long,
    val completedEpisodeIds: Set<String>,
    val lastWatchedMillis: Long,
    val lastEpisodeUid: String = "",
)

class MainActivity : ComponentActivity() {
    private var activityIsResumed = false
    private var pipEligible = false
    var isPlayerInPip by mutableStateOf(false)
        private set

    fun setPipEligible(eligible: Boolean) {
        if (pipEligible == eligible) return
        pipEligible = eligible
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) params.setAutoEnterEnabled(eligible)
            setPictureInPictureParams(params.build())
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.O until Build.VERSION_CODES.S && pipEligible) {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isPlayerInPip = isInPictureInPictureMode
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        getSharedPreferences("ani-cli-pt", MODE_PRIVATE).edit()
            .remove("opensubtitles_username")
            .remove("opensubtitles_password")
            .remove("tmdb_api_key")
            .apply()
        window.statusBarColor = android.graphics.Color.rgb(17, 17, 19)
        window.navigationBarColor = android.graphics.Color.rgb(17, 17, 19)
        setContent {
            val preferenceStore = remember { dev.aniclipt.app.data.local.UserPreferencesStore(this) }
            val userPreferences by preferenceStore.preferences.collectAsStateWithLifecycle(
                dev.aniclipt.app.data.local.UserPreferences())
            val uiScope = rememberCoroutineScope()
            var showSplash by remember { mutableStateOf(true) }
            AniCliTheme(userPreferences.lightTheme) {
                Box(Modifier.fillMaxSize()) {
                    AnimeHome(userPreferences.lightTheme, { value -> uiScope.launch { preferenceStore.setLightTheme(value) } })
                    if (showSplash) AnimatedLogoSplash(
                        modifier = Modifier.fillMaxSize().zIndex(2f),
                        onFinished = { showSplash = false },
                    )
                }
            }
        }
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

    override fun onDestroy() {
        if (isFinishing) PlaybackService.stop(this)
        super.onDestroy()
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
private fun AnimatedLogoSplash(modifier: Modifier = Modifier, onFinished: () -> Unit) {
    val scale = remember { Animatable(.78f) }
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        coroutineScope {
            launch { alpha.animateTo(1f, tween(230, easing = FastOutSlowInEasing)) }
            scale.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
        }
        scale.animateTo(1.06f, tween(170, easing = FastOutSlowInEasing))
        scale.animateTo(1f, tween(210, easing = FastOutSlowInEasing))
        delay(100)
        alpha.animateTo(0f, tween(190))
        onFinished()
    }
    Box(modifier.background(Color(0xFF111113)), contentAlignment = Alignment.Center) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(108.dp).graphicsLayer {
                scaleX = scale.value * 1.32f
                scaleY = scale.value * 1.32f
                this.alpha = alpha.value
            },
        )
    }
}

@Composable
private fun AnimeHome(lightTheme: Boolean, onLightTheme: (Boolean) -> Unit) {
    val context = LocalContext.current
    val preferenceStore = remember(context) { dev.aniclipt.app.data.local.UserPreferencesStore(context) }
    val userPreferences by preferenceStore.preferences.collectAsStateWithLifecycle(
        dev.aniclipt.app.data.local.UserPreferences())
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
    val focusManager = LocalFocusManager.current
    val prefs = remember { context.getSharedPreferences("ani-cli-pt", 0) }
    val database = remember(context) { dev.aniclipt.app.data.local.AppDatabase.get(context) }
    val identityRegistry = remember(database) { IdentityRegistry(database = database) }
    var localDataReady by remember { mutableStateOf(false) }
    LaunchedEffect(database) {
        dev.aniclipt.app.data.local.LegacyDataMigration(context).migrateIfNeeded()
        dev.aniclipt.app.data.local.UserPreferencesStore(context).migrateLegacyIfNeeded()
        identityRegistry.bootstrap()
        localDataReady = true
    }
    if (!localDataReady) {
        Box(Modifier.fillMaxSize().background(dev.aniclipt.app.ui.theme.Canvas), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = dev.aniclipt.app.ui.theme.Accent)
        }
        return
    }
    val repository = remember(identityRegistry, database) { AnimeRepository(identityRegistry, database) }
    val localStore = remember(database) { dev.aniclipt.app.data.local.LocalAnimeStore(database) }
    val homeViewModel: dev.aniclipt.app.ui.home.HomeViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = remember(repository, identityRegistry, localStore) { object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                dev.aniclipt.app.ui.home.HomeViewModel(repository, identityRegistry, localStore) as T
        } },
    )
    val homeUi by homeViewModel.uiState.collectAsStateWithLifecycle()
    val episodeViewModel: dev.aniclipt.app.ui.episodes.EpisodeViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = remember(repository) { object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                dev.aniclipt.app.ui.episodes.EpisodeViewModel(repository) as T
        } },
    )
    val episodeUi by episodeViewModel.uiState.collectAsStateWithLifecycle()
    val playerViewModel: dev.aniclipt.app.ui.player.PlayerViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = remember(repository) { object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                dev.aniclipt.app.ui.player.PlayerViewModel(repository) as T
        } },
    )
    val playerUi by playerViewModel.uiState.collectAsStateWithLifecycle()
    val airing = homeUi.airing
    val featured = homeUi.featured
    val homeSections = homeUi.sections
    val results = homeUi.results
    val query = homeUi.query
    val searchLoading = homeUi.searching
    val scope = rememberCoroutineScope()
    fun scheduleDisplayPrefetch(anime: Anime) = homeViewModel.onAnimeVisible(anime)
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
    val recentSearchRows by localStore.recentSearches.collectAsStateWithLifecycle(emptyList())
    val searchHistory = recentSearchRows.map { it.term }
    var searchFocused by remember { mutableStateOf(false) }
    var searchExpanded by remember { mutableStateOf(false) }
    var hideAdult by remember { mutableStateOf(userPreferences.hideAdult) }
    val catalogListState = rememberLazyListState()
    val searchFocusRequester = remember { FocusRequester() }
    val preparingDetails = episodeUi.preparing
    val selected = episodeUi.anime
    val episodes = episodeUi.episodes
    val seasonOptions = episodeUi.seasons
    val currentSeason = episodeUi.season
    val chosenEpisode = playerUi.episode
    val loadingEpisode = playerUi.episode.takeIf { playerUi.loading }
    val source = playerUi.source
    val playerOpen = playerUi.anime != null
    val playerLoadError = playerUi.error
    val dubSuggestion = playerUi.dubSuggestion
    val startPositionMs = playerUi.resumePositionMs
    var dubbed by remember { mutableStateOf(userPreferences.defaultDubbed) }
    var videoAspectRatio by remember { mutableStateOf(userPreferences.videoAspectRatio) }
    var quality by remember { mutableStateOf(userPreferences.quality) }
    var subtitleSize by remember { mutableStateOf(userPreferences.subtitleSize) }
    var subtitleColor by remember { mutableStateOf(userPreferences.subtitleColor) }
    var subtitleFont by remember { mutableStateOf(userPreferences.subtitleFont) }
    var subtitleBackground by remember { mutableStateOf(userPreferences.subtitleBackground) }
    var subtitleOutlineColor by remember { mutableStateOf(userPreferences.subtitleOutlineColor) }
    var subtitleOutlineThickness by remember { mutableStateOf(userPreferences.subtitleOutlineThickness) }
    var subtitlePosition by remember { mutableStateOf(userPreferences.subtitlePosition) }
    var openSubtitlesApiKey by remember { mutableStateOf(userPreferences.openSubtitlesApiKey) }
    var apiKeyValidation by remember { mutableStateOf(ApiKeyValidation.UNKNOWN) }
    var apiKeyValidationGeneration by remember { mutableIntStateOf(0) }
    var openSubtitlesEnabled by remember { mutableStateOf(userPreferences.openSubtitlesEnabled) }
    var favoriteView by remember { mutableStateOf(false) }
    var historyView by remember { mutableStateOf(false) }
    var pendingHistoryRemoval by remember { mutableStateOf<WatchHistoryItem?>(null) }
    var pendingFavoriteRemoval by remember { mutableStateOf<Anime?>(null) }
    var settingsView by remember { mutableStateOf(false) }
    var notificationsEnabled by remember { mutableStateOf(userPreferences.episodeNotifications) }
    var automaticUpdatesEnabled by remember { mutableStateOf(userPreferences.automaticReleaseUpdates) }
    LaunchedEffect(userPreferences) {
        hideAdult = userPreferences.hideAdult
        dubbed = userPreferences.defaultDubbed
        videoAspectRatio = userPreferences.videoAspectRatio
        quality = userPreferences.quality
        subtitleSize = userPreferences.subtitleSize
        subtitleColor = userPreferences.subtitleColor
        subtitleFont = userPreferences.subtitleFont
        subtitleBackground = userPreferences.subtitleBackground
        subtitleOutlineColor = userPreferences.subtitleOutlineColor
        subtitleOutlineThickness = userPreferences.subtitleOutlineThickness
        subtitlePosition = userPreferences.subtitlePosition
        openSubtitlesApiKey = userPreferences.openSubtitlesApiKey
        openSubtitlesEnabled = userPreferences.openSubtitlesEnabled
        notificationsEnabled = userPreferences.episodeNotifications
        automaticUpdatesEnabled = userPreferences.automaticReleaseUpdates
    }
    var pendingNotificationFeature by remember { mutableStateOf<String?>(null) }
    val favorites = homeUi.favorites.map { it.releaseId }.toSet()
    val favoriteMetadataRows by localStore.favoriteMetadata.collectAsStateWithLifecycle(emptyList())
    val favoriteMetadata = favoriteMetadataRows.groupBy { it.releaseId }
    val savedAnime = homeUi.favorites.associate { favorite -> favorite.releaseId to Anime(
        favorite.slug, favorite.title, favorite.poster, synopsis = favorite.synopsis,
        anilistId = favorite.anilistId, isAdult = favorite.isAdult,
        workId = favorite.workId, releaseId = favorite.releaseId,
        metadata = favoriteMetadata[favorite.releaseId].orEmpty().map { it.value },
    ) }
    val watchedRows by localStore.watchedEpisodes.collectAsStateWithLifecycle(emptyList())
    val history = watchedRows.joinToString(",") { "${it.releaseId}:${it.episodeKey}" }
    val watchRows by localStore.watchHistory.collectAsStateWithLifecycle(emptyList())
    val completedRows by localStore.completedEpisodes.collectAsStateWithLifecycle(emptyList())
    val watchHistory = watchRows.map { row -> WatchHistoryItem(
        anime = Anime(row.slug, row.title, row.poster, anilistId = row.anilistId,
            isAdult = row.isAdult, workId = row.workId, releaseId = row.releaseId),
        secondsWatched = row.secondsWatched, lastEpisode = row.lastEpisode,
        lastEpisodeId = row.lastEpisodeId, lastEpisodeUid = row.lastEpisodeUid,
        lastEpisodeNumber = row.lastEpisodeNumber, lastEpisodeTitle = row.lastEpisodeTitle,
        resumePositionMs = row.resumePositionMs, episodeDurationMs = row.episodeDurationMs,
        completedEpisodeIds = completedRows.filter { it.releaseId == row.releaseId }.map { it.episodeKey }.toSet(),
        lastWatchedMillis = row.lastWatchedMillis,
    ) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        when (pendingNotificationFeature) {
            "episodes" -> if (granted) {
                notificationsEnabled = true
                scope.launch {
                    preferenceStore.setEpisodeNotifications(true)
                    FavoriteEpisodeWorker.captureBaseline(context)
                    FavoriteEpisodeWorker.schedule(context)
                }
            } else {
                notificationsEnabled = false
                scope.launch { preferenceStore.setEpisodeNotifications(false) }
                WorkManager.getInstance(context).cancelUniqueWork("favorite-episode-check")
            }
            "updates" -> if (granted) {
                automaticUpdatesEnabled = true
                scope.launch {
                    preferenceStore.setAutomaticReleaseUpdates(true)
                    ReleaseUpdateWorker.schedule(context)
                    ReleaseUpdateWorker.checkNow(context)
                }
            } else {
                automaticUpdatesEnabled = false
                scope.launch { preferenceStore.setAutomaticReleaseUpdates(false) }
                ReleaseUpdateWorker.cancel(context)
            }
        }
        pendingNotificationFeature = null
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        if (enabled && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingNotificationFeature = "episodes"
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        notificationsEnabled = enabled
        scope.launch {
            preferenceStore.setEpisodeNotifications(enabled)
            if (enabled) {
                FavoriteEpisodeWorker.captureBaseline(context)
                FavoriteEpisodeWorker.schedule(context)
            }
        }
        if (!enabled) {
            WorkManager.getInstance(context).cancelUniqueWork("favorite-episode-check")
            return
        }
    }

    fun applyFavoriteToggle(anime: Anime) {
        val adding = favorites.none { identityRegistry.canonicalReleaseId(it) == identityRegistry.canonicalReleaseId(anime.releaseId) }
        homeViewModel.onFavoriteToggle(anime)
        if (adding && notificationsEnabled) FavoriteEpisodeWorker.captureBaseline(context)
    }
    fun toggleFavorite(anime: Anime) {
        val key = identityRegistry.canonicalReleaseId(identityRegistry.assign(listOf(anime)).first().releaseId)
        if (favorites.any { identityRegistry.canonicalReleaseId(it) == key }) pendingFavoriteRemoval = anime else applyFavoriteToggle(anime)
    }
    fun setAutomaticUpdatesEnabled(enabled: Boolean) {
        if (enabled && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingNotificationFeature = "updates"
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        automaticUpdatesEnabled = enabled
        scope.launch {
            preferenceStore.setAutomaticReleaseUpdates(enabled)
            if (enabled) {
                ReleaseUpdateWorker.schedule(context)
                ReleaseUpdateWorker.checkNow(context)
            }
        }
        if (!enabled) {
            ReleaseUpdateWorker.cancel(context)
            return
        }
    }
    fun downloadUpdate() {
        updateDownloadStatus = ReleaseUpdateWorker.STATUS_DOWNLOADING
        prefs.edit().putString(ReleaseUpdateWorker.PREF_DOWNLOAD_STATUS, updateDownloadStatus).apply()
        ReleaseUpdateWorker.downloadManually(context)
    }
    fun validateApiKey(value: String, debounce: Boolean = true) {
        val key = value.trim()
        val requestId = ++apiKeyValidationGeneration
        if (key.isBlank()) {
            apiKeyValidation = ApiKeyValidation.EMPTY
            return
        }
        apiKeyValidation = ApiKeyValidation.CHECKING
        scope.launch {
            if (debounce) delay(700)
            if (requestId != apiKeyValidationGeneration) return@launch
            val result = runCatching { repository.validateOpenSubtitlesApiKey(key) }.getOrNull()
            if (requestId == apiKeyValidationGeneration && key == openSubtitlesApiKey.trim()) {
                apiKeyValidation = when (result) {
                    true -> ApiKeyValidation.VALID
                    false -> ApiKeyValidation.INVALID
                    null -> ApiKeyValidation.UNAVAILABLE
                }
            }
        }
    }
    fun search(term: String, debounce: Boolean = false) {
        homeViewModel.onSearch(term, debounce)
    }
    fun persistSearchHistory(entries: List<String>) {
        scope.launch { localStore.replaceRecentSearches(entries) }
    }
    fun recordSearch(term: String) {
        scope.launch { localStore.addRecentSearch(term) }
    }
    fun recordWatchProgress(anime: Anime, episode: Episode, seconds: Long, positionMs: Long, durationMs: Long, completed: Boolean = false) {
        if (seconds <= 0 && positionMs <= 0 && !completed) return
        val identified = identityRegistry.assign(listOf(anime)).first()
        val releaseKey = identityRegistry.canonicalReleaseId(identified.releaseId)
        val episodeKey = episode.episodeId.ifBlank { episode.number }
        val current = watchHistory.firstOrNull { identityRegistry.canonicalReleaseId(it.anime.releaseId) == releaseKey }
        val episodeTitle = episode.title.takeIf { it != "Episódio ${episode.number}" }.orEmpty()
        val item = dev.aniclipt.app.data.local.WatchHistoryEntity(
            releaseId = releaseKey, workId = identified.workId, slug = identified.slug,
            title = identified.title, poster = anime.poster.ifBlank { current?.anime?.poster.orEmpty() },
            anilistId = identified.anilistId, isAdult = identified.isAdult,
            secondsWatched = 0L,
            lastEpisode = "Episódio ${episode.number}" + episodeTitle.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty(),
            lastEpisodeId = episode.id,
            lastEpisodeUid = episode.episodeId,
            lastEpisodeNumber = episode.number,
            lastEpisodeTitle = episodeTitle,
            resumePositionMs = savedResumePosition(positionMs, completed),
            episodeDurationMs = durationMs.coerceAtLeast(0L),
            lastWatchedMillis = System.currentTimeMillis(),
        )
        scope.launch {
            identityRegistry.awaitPersisted()
            localStore.recordWatchProgress(item, seconds, episodeKey, completed)
        }
    }
    fun cancelPlaybackRequest() {
        (context as? MainActivity)?.setPipEligible(false)
        PlaybackService.stop(context)
        playerViewModel.close()
    }
    fun closePlayer() = cancelPlaybackRequest()
    fun openSeason(season: Anime) {
        cancelPlaybackRequest()
        episodeViewModel.selectSeason(season)
    }
    fun openAnime(anime: Anime) {
        if (query.isNotBlank()) recordSearch(query)
        cancelPlaybackRequest()
        episodeViewModel.open(anime)
    }
    fun play(anime: Anime, episode: Episode, resumePositionMs: Long = 0L) {
        (context as? MainActivity)?.setPipEligible(false)
        PlaybackService.stop(context)
        playerViewModel.play(anime, episode, dubbed, openSubtitlesEnabled, openSubtitlesApiKey,
            resumePositionMs)
    }
    fun resumeHistory(item: WatchHistoryItem) {
        if (item.lastEpisodeId.isBlank() && item.lastEpisodeNumber.isBlank()) {
            openAnime(item.anime)
            return
        }
        episodeViewModel.open(item.anime)
        PlaybackService.stop(context)
        playerViewModel.resume(item.anime, item.lastEpisodeId, item.lastEpisodeUid,
            item.lastEpisodeNumber, item.lastEpisodeTitle.ifBlank { item.lastEpisode },
            item.resumePositionMs, dubbed, openSubtitlesEnabled, openSubtitlesApiKey)
    }

    LaunchedEffect(Unit) {
        if (notificationsEnabled) FavoriteEpisodeWorker.schedule(context)
        if (automaticUpdatesEnabled) {
            ReleaseUpdateWorker.schedule(context)
            ReleaseUpdateWorker.checkNow(context)
        } else ReleaseUpdateWorker.checkOnLaunch(context)
        focusManager.clearFocus(force = true)
        homeViewModel.start()
    }
    LaunchedEffect(homeViewModel) {
        homeViewModel.events.collect { event ->
            when (event) { is dev.aniclipt.app.ui.home.HomeEvent.OpenAnime -> openAnime(event.anime) }
        }
    }
    LaunchedEffect(episodeViewModel) {
        episodeViewModel.events.collect { event ->
            when (event) { is dev.aniclipt.app.ui.episodes.EpisodeEvent.Play -> play(event.anime, event.episode) }
        }
    }

    LaunchedEffect(settingsView, openSubtitlesApiKey) {
        if (settingsView && openSubtitlesApiKey.isNotBlank() && apiKeyValidation == ApiKeyValidation.UNKNOWN) {
            validateApiKey(openSubtitlesApiKey, debounce = false)
        }
    }

    val featuredCandidates = airing.filter { showInCatalog(it, hideAdult) }.take(5).map { item ->
        featured?.takeIf { it.slug == item.slug && showInCatalog(it, hideAdult) } ?: item
    }
    val featuredPagerState = rememberPagerState(pageCount = { featuredCandidates.size })
    val visibleFeatured = featuredCandidates.firstOrNull()
    val visibleSections = visibleHomeSections(homeSections, airing, hideAdult)
    val visibleHistory = watchHistory.filter { showInCatalog(it.anime, hideAdult) }
    val visibleItems = (when {
        favoriteView -> savedAnime.values.toList()
        historyView -> emptyList()
        query.isBlank() -> results.filterNot { it.slug == visibleFeatured?.slug }
        else -> results
    }).filter { showInCatalog(it, hideAdult) }
    val headerOverHero = !settingsView && !favoriteView && !historyView && !searchExpanded && query.isBlank() && visibleFeatured != null
    val headerAlpha by remember(catalogListState, headerOverHero) { derivedStateOf {
        if (!headerOverHero || catalogListState.firstVisibleItemIndex > 0) 1f
        else (catalogListState.firstVisibleItemScrollOffset / 25).coerceIn(0, 20) / 20f
    } }
    val headerColor = if (headerOverHero) (if (lightTheme) Canvas else Panel).copy(alpha = headerAlpha * .96f) else Canvas
    val headerInk = if (headerOverHero && (!lightTheme || headerAlpha < .65f)) Color.White else Ink
    LaunchedEffect(favoriteView, historyView, settingsView, query.isBlank()) { catalogListState.scrollToItem(0) }

    fun closeSearch() {
        searchExpanded = false
        focusManager.clearFocus(force = true)
        if (query.isNotEmpty()) search("")
    }
    LaunchedEffect(searchExpanded) {
        if (searchExpanded) searchFocusRequester.requestFocus()
    }
    BackHandler(enabled = playerOpen || selected != null || searchExpanded || settingsView || historyView) {
        if (playerOpen) closePlayer()
        else if (selected != null) { cancelPlaybackRequest(); episodeViewModel.close() }
        else if (searchExpanded) closeSearch()
        else if (settingsView) settingsView = false else historyView = false
    }

    Box(Modifier.fillMaxSize()) {
    Scaffold(containerColor = Canvas, bottomBar = {
        val navShape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
        val navigationColor by animateColorAsState(if (lightTheme) Panel.copy(alpha = .96f) else Color(0xE51C1C1E), label = "navigationColor")
        NavigationBar(
            modifier = Modifier
                .then(if (isLandscape) Modifier.height(64.dp) else Modifier)
                .shadow(18.dp, navShape)
                .clip(navShape)
                .border(1.dp, Color.White.copy(alpha = 0.10f), navShape),
            containerColor = navigationColor,
            contentColor = Ink,
            tonalElevation = 0.dp,
        ) {
            NavigationBarItem(selected = !favoriteView && !historyView && !settingsView, onClick = { closeSearch(); settingsView = false; favoriteView = false; historyView = false; search("") }, icon = { Icon(Icons.Default.Search, "Explorar") }, label = if (isLandscape) null else {{ Text("Explorar") }})
            NavigationBarItem(selected = favoriteView, onClick = { closeSearch(); settingsView = false; historyView = false; favoriteView = true }, icon = { Icon(Icons.Default.Favorite, "Favoritos") }, label = if (isLandscape) null else {{ Text("Favoritos") }})
            NavigationBarItem(selected = historyView, onClick = { closeSearch(); settingsView = false; favoriteView = false; historyView = true }, icon = { Icon(Icons.Default.History, "Histórico") }, label = if (isLandscape) null else {{ Text("Histórico") }})
            NavigationBarItem(selected = settingsView, onClick = { closeSearch(); settingsView = true; favoriteView = false; historyView = false }, icon = { Icon(Icons.Default.Settings, "Configurações") }, label = if (isLandscape) null else {{ Text("Configurações") }})
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
        Column(Modifier.fillMaxSize()) {
            if (!headerOverHero) Spacer(Modifier.height(64.dp))
            if (settingsView) {
                SettingsScreen(
                    lightTheme = lightTheme, onLightTheme = onLightTheme,
                    hideAdult = hideAdult, onHideAdult = { hideAdult = it; scope.launch { preferenceStore.setHideAdult(it) } },
                    modifier = Modifier.weight(1f).widthIn(max = 760.dp).align(Alignment.CenterHorizontally),
                    dubbed = dubbed,
                    onDubbed = { dubbed = it; scope.launch { preferenceStore.setDefaultDubbed(it) } },
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
                    onSubtitleSize = { subtitleSize = it; scope.launch { preferenceStore.setSubtitleSize(it) } },
                    subtitleColor = subtitleColor,
                    onSubtitleColor = { subtitleColor = it; scope.launch { preferenceStore.setSubtitleColor(it) } },
                    subtitleFont = subtitleFont,
                    onSubtitleFont = { subtitleFont = it; scope.launch { preferenceStore.setSubtitleFont(it) } },
                    subtitleBackground = subtitleBackground,
                    onSubtitleBackground = { subtitleBackground = it; scope.launch { preferenceStore.setSubtitleBackground(it) } },
                    subtitleOutlineColor = subtitleOutlineColor,
                    onSubtitleOutlineColor = { subtitleOutlineColor = it; scope.launch { preferenceStore.setSubtitleOutlineColor(it) } },
                    subtitleOutlineThickness = subtitleOutlineThickness,
                    onSubtitleOutlineThickness = { subtitleOutlineThickness = it; scope.launch { preferenceStore.setSubtitleOutlineThickness(it) } },
                    subtitlePosition = subtitlePosition,
                    onSubtitlePosition = { subtitlePosition = it; scope.launch { preferenceStore.setSubtitlePosition(it) } },
                    openSubtitlesApiKey = openSubtitlesApiKey,
                    apiKeyValidation = apiKeyValidation,
                    onOpenSubtitlesApiKey = { openSubtitlesApiKey = it; scope.launch { preferenceStore.setOpenSubtitlesApiKey(it) }; validateApiKey(it) },
                    onValidateOpenSubtitlesApiKey = { validateApiKey(openSubtitlesApiKey, debounce = false) },
                    openSubtitlesEnabled = openSubtitlesEnabled,
                    onOpenSubtitlesEnabled = { openSubtitlesEnabled = it; scope.launch { preferenceStore.setOpenSubtitlesEnabled(it) } },
                )
            } else if (searchExpanded && query.isBlank()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    if (searchHistory.isEmpty()) Text("Suas pesquisas recentes aparecerão aqui.", Modifier.padding(top = 40.dp), color = Muted, fontSize = 14.sp)
                }
            } else HomeScreen(
                state = HomeScreenState(featuredCandidates, visibleHistory, visibleItems, visibleSections,
                    favoriteView, historyView, query, homeUi.loading, homeUi.searching, homeUi.error,
                    lightTheme, hideAdult, isLandscape, configuration.screenHeightDp, configuration.screenWidthDp),
                modifier = Modifier.weight(1f).widthIn(max = 960.dp).align(Alignment.CenterHorizontally),
                catalogListState = catalogListState, featuredPagerState = featuredPagerState,
                isFavorite = { anime -> favorites.any { identityRegistry.canonicalReleaseId(it) == identityRegistry.canonicalReleaseId(anime.releaseId) } },
                onAnimeVisible = ::scheduleDisplayPrefetch,
                onSectionAnimeVisible = homeViewModel::onSectionAnimeVisible,
                onAnimeClick = homeViewModel::onAnimeClick,
                onFavoriteToggle = ::toggleFavorite,
                onResumeHistory = ::resumeHistory,
                onRemoveHistory = { pendingHistoryRemoval = it },
            )
        }
            Row(
                Modifier.fillMaxWidth().widthIn(max = 960.dp).align(Alignment.TopCenter).background(headerColor).height(64.dp).padding(horizontal = 14.dp).animateContentSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!searchExpanded) {
                    Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(Brush.linearGradient(listOf(Color(0xFF303036), Color(0xFF19191C)))), contentAlignment = Alignment.Center) {
                        Text("あ", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(7.dp))
                    Text("ani-cli", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = headerInk, maxLines = 1)
                    Text("pt", color = Accent, fontSize = 10.sp, modifier = Modifier.align(Alignment.Top).padding(top = 16.dp))
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { settingsView = false; favoriteView = false; historyView = false; searchExpanded = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Search, contentDescription = "Abrir pesquisa", tint = headerInk)
                    }
                } else {
                    IconButton(onClick = { closeSearch() }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Fechar pesquisa", tint = headerInk)
                    }
                    Row(Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (lightTheme) Color(0xFFE9E9ED) else Color(0xFF1C1C20))
                        .border(1.dp, if (searchFocused) Accent.copy(alpha = .65f) else Ink.copy(alpha = .08f), RoundedCornerShape(12.dp))
                        .padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Search, contentDescription = null, tint = Muted, modifier = Modifier.size(19.dp))
                        Spacer(Modifier.width(8.dp))
                        BasicTextField(
                            value = query, onValueChange = { favoriteView = false; historyView = false; search(it, debounce = true) },
                            modifier = Modifier.weight(1f).fillMaxHeight().focusRequester(searchFocusRequester).onFocusChanged { searchFocused = it.isFocused }, singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(color = Ink, fontSize = 14.sp), cursorBrush = SolidColor(Accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { recordSearch(query); focusManager.clearFocus(force = true); search(query) }),
                            decorationBox = { innerTextField -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                                if (query.isEmpty()) Text("Buscar anime...", color = Muted, fontSize = 14.sp, maxLines = 1)
                                innerTextField()
                            } },
                        )
                        AnimatedVisibility(visible = searchLoading) {
                            CircularProgressIndicator(Modifier.padding(horizontal = 8.dp).size(18.dp).semantics { contentDescription = "Buscando animes" }, color = Accent, strokeWidth = 2.dp)
                        }
                        if (query.isNotEmpty()) IconButton(onClick = { search("") }, modifier = Modifier.size(46.dp)) {
                            Icon(Icons.Default.Close, "Limpar pesquisa", tint = Muted, modifier = Modifier.size(19.dp))
                        } else Spacer(Modifier.width(12.dp))
                    }
                }
            }
            if (searchExpanded && query.isBlank() && searchHistory.isNotEmpty()) {
                Surface(
                    modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().widthIn(max = 960.dp).padding(top = 64.dp).zIndex(2f),
                    shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
                    color = Panel, contentColor = Ink, shadowElevation = 12.dp,
                ) {
                    Column(Modifier.heightIn(max = (configuration.screenHeightDp * .52f).dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 12.dp)) {
                        Row(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(32.dp).clip(RoundedCornerShape(50)).background(Accent.copy(alpha = .13f)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.History, null, tint = Accent, modifier = Modifier.size(17.dp))
                            }
                            Text("Pesquisas recentes", modifier = Modifier.weight(1f).padding(start = 10.dp), color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                        searchHistory.forEach { term ->
                            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp)).clickable {
                                recordSearch(term); focusManager.clearFocus(force = true); search(term)
                            }.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.History, contentDescription = null, tint = Muted, modifier = Modifier.size(17.dp))
                                Text(term, modifier = Modifier.weight(1f).padding(start = 12.dp), color = Ink, fontSize = 14.sp, maxLines = 1)
                                IconButton(onClick = { scope.launch { localStore.removeRecentSearch(term) } }, modifier = Modifier.size(48.dp)) {
                                    Icon(Icons.Default.Close, contentDescription = "Remover $term do histórico", tint = Muted, modifier = Modifier.size(17.dp))
                                }
                            }
                        }
                        HorizontalDivider(color = Muted.copy(alpha = .16f), modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Surface(onClick = { persistSearchHistory(emptyList()) }, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .7f), border = androidx.compose.foundation.BorderStroke(1.dp, Muted.copy(alpha = .2f))) {
                                Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = Muted, modifier = Modifier.size(18.dp))
                                    Text("Limpar histórico", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    dubSuggestion?.let { suggestion ->
        AlertDialog(onDismissRequest = { closePlayer() }, title = { Text("Dublagem disponível") }, text = { Text(if (suggestion.english != null) "Não encontramos legenda PT-BR. Quer assistir dublado em português ou continuar com legendas em inglês?" else "Não encontramos legenda PT-BR ou inglês. Quer assistir dublado em português?") }, confirmButton = { TextButton(onClick = playerViewModel::chooseDubbed) { Text("Assistir dublado") } }, dismissButton = { Row { TextButton(onClick = { closePlayer() }) { Text("Cancelar") }; if (suggestion.english != null) TextButton(onClick = playerViewModel::chooseEnglish) { Text("Em inglês") } } })
    }

    pendingFavoriteRemoval?.let { anime ->
        AlertDialog(onDismissRequest = { pendingFavoriteRemoval = null }, title = { Text("Remover dos favoritos?") }, text = { Text("${anime.title} será removido dos favoritos.") }, confirmButton = { TextButton(onClick = { applyFavoriteToggle(anime); pendingFavoriteRemoval = null }) { Text("Remover") } }, dismissButton = { TextButton(onClick = { pendingFavoriteRemoval = null }) { Text("Cancelar") } })
    }

    pendingHistoryRemoval?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingHistoryRemoval = null },
            title = { Text("Remover do histórico?") },
            text = { Text("O progresso e os episódios vistos de ${entry.anime.title} serão removidos deste aparelho.") },
            confirmButton = { TextButton(onClick = {
                scope.launch { localStore.removeWatchHistory(identityRegistry.canonicalReleaseId(entry.anime.releaseId)) }
                pendingHistoryRemoval = null
            }) { Text("Remover") } },
            dismissButton = { TextButton(onClick = { pendingHistoryRemoval = null }) { Text("Cancelar") } },
        )
    }

    if (selected != null && !playerOpen) {
        val episodeAnime = currentSeason ?: selected!!
        EpisodeSheet(
            anime = episodeAnime, episodes = episodes,
            preparing = preparingDetails,
            watched = history.split(',').filter { it.isNotBlank() }.flatMap { entry ->
                val delimiter = entry.lastIndexOf(':')
                if (delimiter < 0) listOf(entry) else listOf(entry, "${identityRegistry.canonicalReleaseId(entry.substring(0, delimiter))}:${entry.substring(delimiter + 1)}")
            }.toSet(),
            busy = episodeUi.busy, loadingEpisode = loadingEpisode,
            onCancelLoading = ::cancelPlaybackRequest, error = episodeUi.error,
            dubbed = dubbed, onDubbed = { dubbed = it; scope.launch { preferenceStore.setDefaultDubbed(it) } },
            favorites = favorites.map(identityRegistry::canonicalReleaseId).toSet(), favoriteSlug = identityRegistry.canonicalReleaseId(selected!!.releaseId), onFavorite = { toggleFavorite(selected!!) },
            seasons = seasonOptions, onSeasonSelected = ::openSeason,
            onClose = { cancelPlaybackRequest(); episodeViewModel.close() },
            onPlay = { episode -> episodeViewModel.onEpisodeClick(episode) },
            localizeEpisodeTitle = { repository.localizeEpisodeTitle(episodeAnime, it) },
            onEpisodeTitleLocalized = episodeViewModel::onEpisodeLocalized,
        )
    }
    if (playerOpen && source == null) {
        val pendingAnime = playerUi.anime
        LoadingPlayer(
            title = pendingAnime?.title.orEmpty(), episode = chosenEpisode,
            error = playerLoadError, awaitingChoice = dubSuggestion != null,
            onClose = { closePlayer() },
            onRetry = playerViewModel::retry,
            modifier = Modifier.fillMaxSize(),
        )
    }
    if (playerOpen && source != null) {
        val playingAnime = playerUi.anime
        val historyAnime = playingAnime?.copy(poster = playingAnime.poster.ifBlank { selected?.poster.orEmpty() })
        val playingEpisode = chosenEpisode
        AniCliTheme { NativePlayer(
            source = source!!,
            anime = historyAnime,
            episode = playingEpisode,
            startPositionMs = startPositionMs,
            quality = quality,
            onQuality = { quality = it; scope.launch { preferenceStore.setQuality(it) } },
            aspectRatio = videoAspectRatio,
            onAspectRatio = { videoAspectRatio = it; scope.launch { preferenceStore.setVideoAspectRatio(it) } },
            onWatchProgress = { seconds, position, duration, completed ->
                if (historyAnime != null && playingEpisode != null) recordWatchProgress(historyAnime, playingEpisode, seconds, position, duration, completed)
            },
            onClose = { closePlayer() },
            onPreviousEpisode = adjacentEpisode(episodes, playingEpisode, -1)?.let { previous -> { if (historyAnime != null) play(historyAnime, previous) } },
            onNextEpisode = adjacentEpisode(episodes, playingEpisode, 1)?.let { next -> { if (historyAnime != null) play(historyAnime, next) } },
            onSourceFailure = { position ->
                (context as? MainActivity)?.setPipEligible(false)
                PlaybackService.stop(context)
                playerViewModel.onSourceFailure(position)
            },
            isInPip = (context as? MainActivity)?.isPlayerInPip == true,
            onPipEligibilityChanged = { (context as? MainActivity)?.setPipEligible(it) },
            subtitleSize = subtitleSize,
            subtitleColor = subtitleColor,
            subtitleFont = subtitleFont,
            subtitleBackground = subtitleBackground,
            subtitleOutlineColor = subtitleOutlineColor,
            subtitleOutlineThickness = subtitleOutlineThickness,
            subtitlePosition = subtitlePosition,
            modifier = Modifier.fillMaxSize(),
        ) }
    }
    }
}

internal fun formatPlaybackTime(milliseconds: Long): String {
    val totalSeconds = (milliseconds / 1_000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val hours = minutes / 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes % 60, seconds) else "%02d:%02d".format(minutes, seconds)
}
