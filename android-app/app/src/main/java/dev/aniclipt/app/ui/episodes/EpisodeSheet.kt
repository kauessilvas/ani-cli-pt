package dev.aniclipt.app.ui.episodes

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
import dev.aniclipt.app.ui.episodes.seasonLabel

@Composable
private fun StableSeasonArtwork(url: String) {
    val context = LocalContext.current
    val optimized = imageUrlForWidth(url, 1280)
    val request = remember(context, optimized) { ImageRequest.Builder(context).data(optimized.ifBlank { null }).size(1280, 1280).build() }
    val candidate = rememberAsyncImagePainter(request)
    var displayed by remember { mutableStateOf("") }
    LaunchedEffect(optimized, candidate.state) {
        if (optimized.isNotBlank() && candidate.state is AsyncImagePainter.State.Success) displayed = optimized
        if (optimized.isBlank()) displayed = ""
    }
    Crossfade(targetState = displayed, label = "seasonArtwork") { image ->
        if (image.isNotBlank()) {
            val shownRequest = remember(context, image) { ImageRequest.Builder(context).data(image).size(1280, 1280).build() }
            Image(painter = rememberAsyncImagePainter(shownRequest), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

@Composable
private fun EpisodePreview(episode: Episode) {
    val context = LocalContext.current
    val thumbnail = imageUrlForWidth(episode.thumbnail, 342)
    var failedThumbnail by remember(episode.id, thumbnail) { mutableStateOf(false) }
    val selectedUrl = thumbnail.takeUnless { failedThumbnail }.orEmpty()
    val request = remember(context, selectedUrl) { ImageRequest.Builder(context).data(selectedUrl.ifBlank { null }).crossfade(180).build() }
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surface)))) {
        if (selectedUrl.isNotBlank()) AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), onError = {
            if (thumbnail.isNotBlank() && !failedThumbnail) failedThumbnail = true
        })
    }
}

@Composable
private fun EpisodeSheetSkeleton(title: String, landscape: Boolean, onClose: () -> Unit) {
    val placeholder = MaterialTheme.colorScheme.surfaceVariant
    Column(Modifier.fillMaxSize().background(Canvas)) {
        Box(Modifier.fillMaxWidth().height(if (landscape) 310.dp else 460.dp)
            .background(Brush.verticalGradient(listOf(placeholder, Panel, Canvas)))) {
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 12.dp, top = 6.dp)
                .clip(RoundedCornerShape(50)).background(Panel)) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Voltar", tint = Ink)
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text("Preparando informações", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
                LinearProgressIndicator(Modifier.width(144.dp).padding(top = 14.dp), color = Accent, trackColor = placeholder)
            }
        }
        repeat(3) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(130.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(placeholder))
                Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.fillMaxWidth(.82f).height(13.dp).clip(RoundedCornerShape(4.dp)).background(placeholder))
                    Box(Modifier.fillMaxWidth(.52f).height(11.dp).clip(RoundedCornerShape(4.dp)).background(placeholder.copy(alpha = .65f)))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EpisodeSheet(anime: Anime, episodes: List<Episode>, preparing: Boolean, watched: Set<String>, busy: Boolean, loadingEpisode: Episode?, onCancelLoading: () -> Unit, error: String?, dubbed: Boolean, onDubbed: (Boolean) -> Unit, favorites: Set<String>, favoriteSlug: String, onFavorite: () -> Unit, seasons: List<Anime>, onSeasonSelected: (Anime) -> Unit, onClose: () -> Unit, onPlay: (Episode) -> Unit, localizeEpisodeTitle: suspend (Episode) -> Episode, onEpisodeTitleLocalized: (Episode) -> Unit) {
    var seasonExpanded by remember { mutableStateOf(false) }
    var synopsisExpanded by remember(anime.slug) { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    val compact = configuration.screenWidthDp < 360
    val artwork = if (landscape) anime.banner.ifBlank { anime.poster } else anime.poster.ifBlank { anime.banner }
    val seasonScroll = rememberScrollState()
    val density = androidx.compose.ui.platform.LocalDensity.current
    LaunchedEffect(seasonExpanded, anime.slug, seasons.size) {
        if (seasonExpanded) {
            val selected = seasons.indexOfFirst { it.slug == anime.slug }.coerceAtLeast(0)
            if (selected > 0) {
                snapshotFlow { seasonScroll.maxValue }.first { it > 0 }
                seasonScroll.scrollTo(with(density) { (selected * 48).dp.toPx().toInt() }.coerceAtMost(seasonScroll.maxValue))
            }
        }
    }
    ModalBottomSheet(
        sheetState = sheetState, onDismissRequest = onClose, containerColor = Canvas,
        contentColor = Ink, dragHandle = null, shape = RoundedCornerShape(0.dp),
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        if (preparing) EpisodeSheetSkeleton(anime.title, landscape, onClose) else
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "hero") {
                Box(Modifier.fillMaxWidth().height(if (landscape) 310.dp else 460.dp)) {
                    StableSeasonArtwork(artwork)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(.50f), Color.Transparent, Color.Black.copy(.42f), Canvas))))
                    Row(Modifier.fillMaxWidth().align(Alignment.TopCenter).statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        IconButton(onClick = onClose, modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(.48f))) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Voltar", tint = Color.White)
                        }
                        IconButton(onClick = onFavorite, modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(.48f))) {
                            Icon(if (favoriteSlug in favorites) Icons.Default.Favorite else Icons.Default.FavoriteBorder, if (favoriteSlug in favorites) "Remover dos favoritos" else "Adicionar aos favoritos", tint = Color.White)
                        }
                    }
                    Column(Modifier.align(Alignment.BottomCenter).padding(horizontal = 24.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(anime.title, color = Color.White, fontSize = if (compact) 26.sp else 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, maxLines = 3, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        if (anime.metadata.isNotEmpty()) Text(anime.metadata.joinToString(" · "), color = Color.White.copy(.8f), fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(top = 8.dp))
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { episodes.firstOrNull()?.let(onPlay) }, enabled = episodes.isNotEmpty() && !busy, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black), shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 30.dp, vertical = 10.dp)) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null); Spacer(Modifier.width(6.dp)); Text(episodes.firstOrNull()?.let { seasonWatchLabel(anime, seasons, it.number) } ?: "Assistir", fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }
                }
            }
            if (anime.synopsis.isNotBlank()) item(key = "synopsis") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Text(anime.synopsis, color = Ink.copy(.85f), fontSize = 13.sp, lineHeight = 20.sp, maxLines = if (synopsisExpanded) Int.MAX_VALUE else 3, modifier = Modifier.fillMaxWidth().clickable { synopsisExpanded = !synopsisExpanded })
                    if (anime.synopsis.length > 140) TextButton(onClick = { synopsisExpanded = !synopsisExpanded }, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(38.dp)) { Text(if (synopsisExpanded) "Mostrar menos" else "Ver mais", fontSize = 12.sp) }
                }
            }
            if (anime.synopsis.isBlank()) item(key = "synopsis-unavailable") {
                Text("Sinopse em português indisponível no momento.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp))
            }
            item(key = "episode-controls") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    HorizontalDivider(color = Muted.copy(.22f))
                    Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Episódios", Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        if (seasons.size > 1) SeasonSelector(anime, seasons, seasonExpanded, onExpandedChange = { seasonExpanded = it },
                            onSeasonSelected = onSeasonSelected, scrollState = seasonScroll, maxHeight = (configuration.screenHeightDp * .52f).dp) else Text("${episodes.size} episódios", color = Muted, fontSize = 11.sp)
                    }
                    Row(Modifier.padding(top = 4.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !dubbed, onClick = { onDubbed(false) }, label = { Text(if (compact) "Original" else "Original · legendado", maxLines = 1) })
                        FilterChip(selected = dubbed, onClick = { onDubbed(true) }, label = { Text(if (compact) "PT-BR" else "Dublado PT-BR", maxLines = 1) })
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
                    if (loadingEpisode != null) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .65f)).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Accent, strokeWidth = 2.dp)
                            Column(Modifier.weight(1f).padding(start = 10.dp, top = 8.dp, bottom = 8.dp)) {
                                Text("Preparando episódio ${loadingEpisode.number}", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                Text("Buscando a melhor fonte disponível…", color = Muted, fontSize = 10.sp)
                            }
                            TextButton(onClick = onCancelLoading) { Text("Cancelar", fontSize = 12.sp) }
                        }
                    }
                    error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp)) }
                }
            }
            items(episodes, key = { "${anime.slug}:${it.id}" }) { episode ->
                LaunchedEffect(anime.slug, episode.id) {
                    val localized = localizeEpisodeTitle(episode)
                    if (localized.title != episode.title) onEpisodeTitleLocalized(localized)
                }
                val hasWatched = "${anime.releaseId}:${episode.episodeId}" in watched ||
                    "${anime.releaseId}:${episode.number}" in watched || "${anime.slug}:${episode.number}" in watched
                Row(Modifier.animateItem().fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp).clip(RoundedCornerShape(8.dp)).background(if (hasWatched) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .3f) else Color.Transparent).clickable(enabled = !busy) { onPlay(episode) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(if (compact) 116.dp else 145.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                        EpisodePreview(episode)
                        if (loadingEpisode?.id == episode.id) CircularProgressIndicator(modifier = Modifier.size(30.dp), color = Color.White, strokeWidth = 3.dp)
                        else Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(38.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(.55f)).padding(5.dp))
                    }
                    Text("EP.${episode.number}" + if (episode.title == "Episódio ${episode.number}" || episode.title.isBlank()) "" else " - ${episode.title}", modifier = Modifier.weight(1f).padding(start = 12.dp), fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 2)
                }
            }
        }
    }
}
