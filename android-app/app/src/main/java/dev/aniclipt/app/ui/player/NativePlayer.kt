package dev.aniclipt.app.ui.player

import android.os.Bundle
import android.os.SystemClock
import android.Manifest
import android.app.Activity
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

import dev.aniclipt.app.*
import dev.aniclipt.app.ui.theme.*
import dev.aniclipt.app.ui.player.*

@Composable
internal fun LoadingPlayer(title: String, episode: Episode?, error: String?, awaitingChoice: Boolean, onClose: () -> Unit, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val controller = remember(activity) { activity?.window?.let { WindowInsetsControllerCompat(it, it.decorView) } }
    DisposableEffect(controller) {
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    // Consume touches across the whole loading screen so catalog cards behind it cannot open.
    Box(modifier.background(Color.Black).clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = {},
    )) {
        Row(Modifier.align(Alignment.TopStart).fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xCC1A1A1E), Color.Transparent))).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Default.ArrowBack, "Voltar aos episódios", tint = Color.White) }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
                episode?.let { Text("Episódio ${it.number}", color = Color.White.copy(.7f), fontSize = 11.sp) }
            }
        }
        Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (error == null && !awaitingChoice) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(38.dp), strokeWidth = 3.dp)
            Text(if (error != null) "Não foi possível carregar" else if (awaitingChoice) "Escolha uma opção de áudio" else "Preparando episódio", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(top = 18.dp))
            Text(error ?: if (awaitingChoice) "" else "Buscando a melhor fonte disponível…", color = Color.White.copy(.7f), fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
            if (error != null) TextButton(onClick = onRetry, modifier = Modifier.padding(top = 8.dp)) { Text("Tentar novamente", color = Color.White) }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun NativePlayer(source: PlaybackSource, anime: Anime?, episode: Episode?, startPositionMs: Long, quality: String, onQuality: (String) -> Unit, aspectRatio: String, onAspectRatio: (String) -> Unit, onWatchProgress: (Long, Long, Long, Boolean) -> Unit, onClose: () -> Unit, onPreviousEpisode: (() -> Unit)?, onNextEpisode: (() -> Unit)?, onSourceFailure: (Long) -> Unit, subtitleSize: String, subtitleColor: String, subtitleFont: String, subtitleBackground: String, subtitleOutlineColor: String, subtitleOutlineThickness: String, subtitlePosition: String, isInPip: Boolean = false, onPipEligibilityChanged: (Boolean) -> Unit = {}, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = context as? Activity
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val cutoutTop = WindowInsets.displayCutout.asPaddingValues().calculateTopPadding()
    val previousOrientation = remember(source.videoUrl) { activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    val playbackError = remember(source.videoUrl) { mutableStateOf<String?>(null) }
    var settingsMenuExpanded by remember(source.videoUrl) { mutableStateOf(false) }
    var settingsPage by remember(source.videoUrl) { mutableStateOf("root") }
    val settingsGearRotation by animateFloatAsState(if (settingsMenuExpanded) 45f else 0f, tween(200, easing = FastOutSlowInEasing), label = "settingsGearRotation")
    var positionMs by remember(source.videoUrl) { mutableLongStateOf(startPositionMs) }
    var durationMs by remember(source.videoUrl) { mutableLongStateOf(0L) }
    var seekFraction by remember(source.videoUrl) { mutableFloatStateOf(0f) }
    var seeking by remember(source.videoUrl) { mutableStateOf(false) }
    var speed by remember(source.videoUrl) { mutableFloatStateOf(1f) }
    var controlInteraction by remember(source.videoUrl) { mutableIntStateOf(0) }
    var controlsVisible by remember(source.videoUrl) { mutableStateOf(true) }
    var isPlaying by remember(source.videoUrl) { mutableStateOf(false) }
    var subtitleCueText by remember(source.videoUrl) { mutableStateOf("") }
    var skipIntervals by remember(anime?.slug, episode?.id) { mutableStateOf<List<SkipInterval>>(emptyList()) }
    val insetsController = remember(activity, source.videoUrl) {
        activity?.window?.let { WindowInsetsControllerCompat(it, it.decorView) }
    }
    DisposableEffect(insetsController) {
        insetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController?.hide(WindowInsetsCompat.Type.statusBars())
        onDispose { insetsController?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    SideEffect {
        if (isLandscape) insetsController?.show(WindowInsetsCompat.Type.navigationBars())
        else insetsController?.hide(WindowInsetsCompat.Type.navigationBars())
    }
    val selectedHeight = quality.removeSuffix("p").toIntOrNull()?.takeIf { it in source.availableVideoHeights }
    val currentWatchProgressCallback by rememberUpdatedState(onWatchProgress)
    var connectedPlayer by remember(source.videoUrl) { mutableStateOf<androidx.media3.session.MediaController?>(null) }
    val currentPipCallback by rememberUpdatedState(onPipEligibilityChanged)
    val currentSourceFailure by rememberUpdatedState(onSourceFailure)
    DisposableEffect(context, source.videoUrl, source.subtitleUrl) {
        PlaybackService.start(context, source, anime, episode, startPositionMs)
        val future = PlaybackService.connect(context)
        var disposed = false
        future.addListener({
            if (!disposed) runCatching { future.get() }.onSuccess { connectedPlayer = it }
        }, androidx.core.content.ContextCompat.getMainExecutor(context))
        onDispose {
            disposed = true
            currentPipCallback(false)
            connectedPlayer = null
            androidx.media3.session.MediaController.releaseFuture(future)
            activity?.requestedOrientation = previousOrientation
        }
    }
    val player = connectedPlayer
    if (player == null) {
        Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
        return
    }
    DisposableEffect(player) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                isPlaying = isPlayingNow
                currentPipCallback(isPlayingNow)
                if (!isPlayingNow) controlsVisible = true
            }
            override fun onCues(cueGroup: CueGroup) {
                subtitleCueText = cueGroup.cues.mapNotNull { cue -> cue.text?.toString()?.takeIf(String::isNotBlank) }.joinToString("\n")
            }
            override fun onPlayerError(error: PlaybackException) {
                val causeChain = generateSequence(error as Throwable?) { it.cause }.toList()
                val forbiddenRequest = causeChain.filterIsInstance<InvalidResponseCodeException>().firstOrNull()
                val isForbidden = forbiddenRequest != null || causeChain.any { it.message?.contains("403") == true }
                android.util.Log.e("AniCliPlayer", "Playback failed${forbiddenRequest?.let { " (${it.responseCode}) ${it.dataSpec.uri}" }.orEmpty()}", error)
                currentSourceFailure(player.currentPosition.coerceAtLeast(0L))
                playbackError.value = if (isForbidden) "A fonte recusou a reprodução. Volte e tente outra qualidade ou episódio." else "Não foi possível carregar este vídeo. Confira sua conexão e tente novamente."
            }
        }
        player.addListener(listener)
        isPlaying = player.isPlaying
        currentPipCallback(player.isPlaying)
        subtitleCueText = player.currentCues.cues.mapNotNull { it.text?.toString() }.joinToString("\n")
        onDispose {
            currentPipCallback(false)
            player.removeListener(listener)
        }
    }
    LaunchedEffect(quality, source.availableVideoHeights) {
        if (quality != "best" && selectedHeight == null) onQuality("best")
        PlaybackService.setQuality(context, if (selectedHeight == null) "best" else quality)
    }
    LaunchedEffect(player) {
        while (true) {
            if (!seeking) positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
            delay(500)
        }
    }
    LaunchedEffect(anime?.slug, anime?.anilistId, episode?.id) {
        skipIntervals = emptyList()
        if (anime != null && episode != null) {
            skipIntervals = runCatching { AniSkipRepository().intervals(anime, episode) }.getOrDefault(emptyList())
        }
    }
    LaunchedEffect(player, controlsVisible, isPlaying, controlInteraction, settingsMenuExpanded, seeking) {
        if (!controlsVisible || !isPlaying || settingsMenuExpanded || seeking) return@LaunchedEffect
        delay(4_500)
        controlsVisible = false
    }
    LaunchedEffect(isInPip) { if (isInPip) controlsVisible = false }
    LaunchedEffect(player, anime?.slug, episode?.id) {
        var lastTick = SystemClock.elapsedRealtime()
        var unsavedMillis = 0L
        var completionRecorded = false
        try {
            while (true) {
                delay(5_000)
                val now = SystemClock.elapsedRealtime()
                if (player.isPlaying) unsavedMillis += now - lastTick
                lastTick = now
                if (player.playbackState == androidx.media3.common.Player.STATE_ENDED && !completionRecorded) {
                    currentWatchProgressCallback(0L, 0L, player.duration.coerceAtLeast(0L), true)
                    completionRecorded = true
                }
                if (player.isPlaying && unsavedMillis >= 5_000L) {
                    val seconds = unsavedMillis / 1_000L
                    unsavedMillis %= 1_000L
                    currentWatchProgressCallback(seconds, player.currentPosition, player.duration.coerceAtLeast(0L), false)
                }
            }
        } finally {
            val now = SystemClock.elapsedRealtime()
            if (player.isPlaying) unsavedMillis += now - lastTick
            if (player.currentPosition > 0L) currentWatchProgressCallback(unsavedMillis / 1_000L, player.currentPosition, player.duration.coerceAtLeast(0L), false)
        }
    }
    val skipInterval = activeSkipInterval(skipIntervals, positionMs, durationMs)
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { viewContext -> PlayerView(viewContext).apply {
                    this.player = player
                    useController = false
                    resizeMode = videoResizeMode(aspectRatio)
                    subtitleView?.visibility = android.view.View.GONE
                } },
                update = { view ->
                    view.resizeMode = videoResizeMode(aspectRatio)
                    view.subtitleView?.visibility = android.view.View.GONE
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (!isInPip) Box(Modifier.fillMaxSize().clickable { controlsVisible = !controlsVisible; controlInteraction++ })
            val compactLandscapeSubtitle = shouldLiftSubtitle(subtitlePosition, isLandscape, controlsVisible && !isInPip, configuration.screenHeightDp)
            val subtitleAlignment = when {
                compactLandscapeSubtitle || subtitlePosition == "top" -> Alignment.TopCenter
                subtitlePosition == "center" -> Alignment.Center
                else -> Alignment.BottomCenter
            }
            AndroidView(
                factory = { viewContext -> OutlinedSubtitleTextView(viewContext) },
                update = { view ->
                    view.text = subtitleCueText
                    view.visibility = if (subtitleCueText.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
                    applySubtitleOverlayStyle(view, context, minOf(configuration.screenWidthDp, configuration.screenHeightDp), subtitleSize, subtitleColor, subtitleFont, subtitleBackground, subtitleOutlineColor, subtitleOutlineThickness)
                },
                modifier = Modifier.align(subtitleAlignment).fillMaxWidth().wrapContentHeight()
                    .padding(horizontal = 22.dp)
                    .padding(top = if (compactLandscapeSubtitle) 64.dp + cutoutTop else if (subtitlePosition == "top") if (controlsVisible && !isLandscape && skipInterval != null) 132.dp + cutoutTop else if (controlsVisible) 64.dp + cutoutTop else 18.dp else 0.dp, bottom = if (subtitlePosition == "bottom" && !compactLandscapeSubtitle) if (controlsVisible) 140.dp else if (isLandscape) 26.dp else 42.dp else 0.dp),
            )
            if (!isInPip && isLandscape && skipInterval != null) {
                SkipSegmentButton(
                    skipInterval,
                    onClick = { player.seekTo(skipInterval.endMs); positionMs = skipInterval.endMs; controlInteraction++ },
                    modifier = Modifier.align(Alignment.BottomEnd).windowInsetsPadding(WindowInsets.displayCutout)
                        .padding(end = 24.dp, bottom = if (controlsVisible) 142.dp else 72.dp),
                )
            }
            if (controlsVisible && !isInPip) {
                Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(.9f), Color.Black.copy(.55f), Color.Transparent)))
                    .windowInsetsPadding(WindowInsets.displayCutout)
                    .padding(start = if (isLandscape) 12.dp else 20.dp, end = if (isLandscape) 12.dp else 20.dp, top = 8.dp, bottom = if (isLandscape) 6.dp else 12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onClose) { Icon(Icons.Default.ArrowBack, "Voltar", tint = Color.White) }
                        Column(Modifier.weight(1f).padding(start = 4.dp)) {
                            Text(source.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
                            Text("Episódio ${source.episode}", color = Color.White.copy(.75f), fontSize = 11.sp, maxLines = 1)
                        }
                        Box {
                            IconButton(onClick = { settingsPage = "root"; settingsMenuExpanded = true; controlInteraction++ }) {
                                Icon(Icons.Default.Settings, "Configurações do player", modifier = Modifier.graphicsLayer { rotationZ = settingsGearRotation }, tint = Color.White)
                            }
                            DropdownMenu(
                                expanded = settingsMenuExpanded,
                                onDismissRequest = { settingsMenuExpanded = false; settingsPage = "root"; controlInteraction++ },
                                modifier = Modifier.width(260.dp).background(Color(0xFF202024)),
                            ) {
                                AnimatedContent(
                                    targetState = settingsPage,
                                    transitionSpec = {
                                        (slideInHorizontally(tween(180)) { if (targetState == "root") -it / 6 else it / 6 } + fadeIn(tween(180))) togetherWith
                                            (slideOutHorizontally(tween(140)) { if (targetState == "root") it / 6 else -it / 6 } + fadeOut(tween(140)))
                                    },
                                    label = "playerSettingsPage",
                                ) { page ->
                                    Column {
                                    if (page == "root") {
                                        Text("CONFIGURAÇÕES", modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                        DropdownMenuItem(text = { Text("Resolução") }, trailingIcon = { Text(qualityLabel(quality), color = Muted) }, onClick = { settingsPage = "quality" })
                                        DropdownMenuItem(text = { Text("Velocidade") }, trailingIcon = { Text(speedLabel(speed), color = Muted) }, onClick = { settingsPage = "speed" })
                                        DropdownMenuItem(text = { Text("Proporção de tela") }, trailingIcon = { Text(aspectRatioLabel(aspectRatio), color = Muted) }, onClick = { settingsPage = "aspect" })
                                    } else {
                                        val heading = when (page) { "quality" -> "Resolução"; "speed" -> "Velocidade"; else -> "Proporção de tela" }
                                        DropdownMenuItem(text = { Text("‹  $heading", fontWeight = FontWeight.SemiBold) }, onClick = { settingsPage = "root" })
                                        HorizontalDivider(color = Color.White.copy(alpha = .12f))
                                        when (page) {
                                            "quality" -> (listOf("best" to "Automática") + source.availableVideoHeights.map { "${it}p" to "${it}p" }).forEach { (value, label) ->
                                                DropdownMenuItem(text = { Text(label) }, trailingIcon = { if (quality == value) Icon(Icons.Default.Check, null, tint = Accent) }, onClick = { onQuality(value); settingsMenuExpanded = false; settingsPage = "root"; controlInteraction++ })
                                            }
                                            "speed" -> listOf(.25f, .5f, 1.5f, 2f).forEach { value ->
                                                DropdownMenuItem(text = { Text(speedLabel(value)) }, trailingIcon = { if (speed == value) Icon(Icons.Default.Check, null, tint = Accent) }, onClick = { speed = value; player.setPlaybackSpeed(value); settingsMenuExpanded = false; settingsPage = "root"; controlInteraction++ })
                                            }
                                            "aspect" -> listOf("fit" to "Ajustar", "crop" to "Preencher", "stretch" to "Esticar").forEach { (value, label) ->
                                                DropdownMenuItem(text = { Text(label) }, trailingIcon = { if (aspectRatio == value) Icon(Icons.Default.Check, null, tint = Accent) }, onClick = { onAspectRatio(value); settingsMenuExpanded = false; settingsPage = "root"; controlInteraction++ })
                                            }
                                        }
                                    }
                                    }
                                }
                            }
                        }
                        IconButton(onClick = { activity?.requestedOrientation = if (isLandscape) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE; controlInteraction++ }) {
                            Icon(Icons.Default.ScreenRotation, if (isLandscape) "Voltar para modo retrato" else "Girar para modo paisagem", tint = Color.White)
                        }
                    }
                    if (!isLandscape && skipInterval != null) {
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                            SkipSegmentButton(skipInterval, onClick = { player.seekTo(skipInterval.endMs); positionMs = skipInterval.endMs; controlInteraction++ })
                        }
                    }
                }
                Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = { onPreviousEpisode?.invoke() }, enabled = onPreviousEpisode != null) { Icon(Icons.Default.SkipPrevious, "Episódio anterior", tint = Color.White.copy(alpha = if (onPreviousEpisode != null) 1f else .35f), modifier = Modifier.size(28.dp)) }
                    IconButton(onClick = { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)); controlInteraction++ }) { Icon(Icons.Default.Replay10, "Voltar 10 segundos", tint = Color.White, modifier = Modifier.size(30.dp)) }
                    IconButton(onClick = { if (player.isPlaying) player.pause() else player.play(); controlInteraction++ }, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(.5f))) {
                        Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (isPlaying) "Pausar" else "Reproduzir", tint = Color.White, modifier = Modifier.size(40.dp))
                    }
                    IconButton(onClick = { player.seekTo((player.currentPosition + 10_000).let { if (durationMs > 0) it.coerceAtMost(durationMs) else it }); controlInteraction++ }) { Icon(Icons.Default.Forward10, "Avançar 10 segundos", tint = Color.White, modifier = Modifier.size(30.dp)) }
                    IconButton(onClick = { onNextEpisode?.invoke() }, enabled = onNextEpisode != null) { Icon(Icons.Default.SkipNext, "Próximo episódio", tint = Color.White.copy(alpha = if (onNextEpisode != null) 1f else .35f), modifier = Modifier.size(28.dp)) }
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(.9f)))).padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Slider(value = if (seeking) seekFraction else if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
                        onValueChange = { seeking = true; seekFraction = it },
                        onValueChangeFinished = { if (durationMs > 0) player.seekTo((durationMs * seekFraction).toLong()); seeking = false; controlInteraction++ },
                        enabled = durationMs > 0, colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(.35f)), modifier = Modifier.fillMaxWidth().height(32.dp).semantics { contentDescription = "Progresso do episódio" })
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatPlaybackTime(if (seeking) (durationMs * seekFraction).toLong() else positionMs), color = Color.White, fontSize = 11.sp)
                        Text(formatPlaybackTime(durationMs), color = Color.White.copy(.8f), fontSize = 11.sp)
                    }
                }
            }
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
}
