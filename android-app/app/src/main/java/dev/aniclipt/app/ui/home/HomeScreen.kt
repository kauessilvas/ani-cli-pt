package dev.aniclipt.app.ui.home

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

@Composable
internal fun FeaturedHomeCarousel(
    featuredCandidates: List<Anime>,
    pagerState: androidx.compose.foundation.pager.PagerState,
    listState: LazyListState,
    height: androidx.compose.ui.unit.Dp,
    onVisible: (Anime) -> Unit,
    onOpen: (Anime) -> Unit,
    onFavorite: (Anime) -> Unit,
    isFavorite: (Anime) -> Boolean,
) {
    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth().height(height)) { page ->
        val anime = featuredCandidates[page]
        LaunchedEffect(anime.slug) { onVisible(anime) }
        Featured(anime, onOpen = { onOpen(anime) }, onFavorite = { onFavorite(anime) },
            isFavorite = isFavorite(anime), listState = listState, height = height)
    }
}

internal data class HomeScreenState(
    val featuredCandidates: List<Anime>,
    val visibleHistory: List<WatchHistoryItem>,
    val visibleItems: List<Anime>,
    val visibleSections: List<HomeSection>,
    val favoriteView: Boolean,
    val historyView: Boolean,
    val query: String,
    val loading: Boolean,
    val searching: Boolean,
    val error: String?,
    val lightTheme: Boolean,
    val hideAdult: Boolean,
    val isLandscape: Boolean,
    val screenHeightDp: Int,
    val screenWidthDp: Int,
)

@Composable
internal fun HomeScreen(
    state: HomeScreenState,
    modifier: Modifier,
    catalogListState: LazyListState,
    featuredPagerState: androidx.compose.foundation.pager.PagerState,
    isFavorite: (Anime) -> Boolean,
    onAnimeVisible: (Anime) -> Unit,
    onSectionAnimeVisible: (Anime) -> Unit,
    onAnimeClick: (Anime) -> Unit,
    onFavoriteToggle: (Anime) -> Unit,
    onResumeHistory: (WatchHistoryItem) -> Unit,
    onRemoveHistory: (WatchHistoryItem) -> Unit,
) {
LazyColumn(modifier, state = catalogListState, contentPadding = PaddingValues(bottom = 20.dp)) {
                if (!state.favoriteView && !state.historyView && state.query.isBlank() && state.featuredCandidates.isNotEmpty()) {
                    item(key = "featured") {
                        val heroHeight = if (state.isLandscape) 270.dp else (state.screenHeightDp * .67f).coerceIn(420f, 620f).dp
                        FeaturedHomeCarousel(state.featuredCandidates, featuredPagerState, catalogListState, heroHeight,
                            onVisible = { onAnimeVisible(it) }, onOpen = { onAnimeClick(it) },
                            onFavorite = { onFavoriteToggle(it) }, isFavorite = { anime -> isFavorite(anime) })
                    }
                }
                if (!state.favoriteView && !state.historyView && state.query.isBlank() && state.visibleHistory.isNotEmpty()) {
                    item(key = "continue-heading") {
                        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp)) {
                            Text("Continue assistindo", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                            Text("De onde você parou", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 3.dp))
                        }
                    }
                    item(key = "continue-rail") {
                        ContinueWatchingRow(state.visibleHistory, onClick = { onResumeHistory(it) })
                    }
                }
                item(key = "catalog-heading") {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
                        Column(Modifier.weight(1f)) {
                            if (state.favoriteView || state.historyView || state.query.isNotBlank()) Text(if (state.favoriteView) "SUA BIBLIOTECA" else if (state.historyView) "CONTINUE DE ONDE PAROU" else "RESULTADOS", fontSize = 10.sp, color = Muted, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                            Text(if (state.favoriteView) "Favoritos" else if (state.historyView) "Histórico" else if (state.query.isBlank()) "Em exibição" else "Busca", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        }

                    }
                }
                if ((state.loading || state.searching) && state.visibleItems.isEmpty() && !state.historyView) {
                    item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp), color = Accent) }
                }
                state.error?.let { message ->
                    item(key = "error") { Text(message, Modifier.padding(horizontal = 20.dp, vertical = 12.dp), color = if (state.lightTheme) Color(0xFFB3261E) else Color(0xFFFF8A80), fontSize = 13.sp) }
                }
                if (((state.historyView && state.visibleHistory.isEmpty()) || (!state.historyView && state.visibleItems.isEmpty())) && (state.historyView || !(state.loading || state.searching)) && state.error == null) {
                    item(key = "empty") { Text(if (state.hideAdult && (state.favoriteView || state.historyView)) "Nenhum título visível. Confira o filtro de conteúdo adulto nas configurações." else if (state.favoriteView) "Seus animes favoritos aparecem aqui." else if (state.historyView) "Os animes que você assistir aparecerão aqui com o tempo reproduzido." else "Busque um título para começar.", Modifier.padding(20.dp), color = Muted) }
                }
                if (!state.favoriteView && !state.historyView && state.query.isBlank() && state.visibleItems.isNotEmpty()) {
                    item(key = "posters") { LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(state.visibleItems, key = { it.slug }) { anime ->
                            LaunchedEffect(anime.slug) { onAnimeVisible(anime) }
                            PosterCard(anime, onClick = { onAnimeClick(anime) })
                        }
                    } }
                } else if (state.favoriteView) {
                    val columns = if (state.screenWidthDp >= 700) 4 else if (state.screenWidthDp >= 520) 3 else 2
                    items(state.visibleItems.chunked(columns), key = { group -> "favorites-${group.first().slug}" }) { group ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            group.forEach { anime ->
                                FavoriteGridCard(anime, modifier = Modifier.weight(1f), onClick = { onAnimeClick(anime) }, onFavorite = { onFavoriteToggle(anime) })
                            }
                            repeat(columns - group.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                } else items(state.visibleItems, key = { it.slug }) { anime ->
                    LaunchedEffect(anime.slug) { onAnimeVisible(anime) }
                    Box(Modifier.animateItem()) { AnimeRow(anime, isFavorite(anime), onClick = { onAnimeClick(anime) }, onFavorite = { onFavoriteToggle(anime) }) }
                }
                if (!state.favoriteView && !state.historyView && state.query.isBlank()) state.visibleSections.forEachIndexed { index, section ->
                    item(key = "section-heading-$index") {
                        Text(section.title, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 12.dp), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                    item(key = "section-rail-$index") {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(section.items, key = { "section-$index-${it.slug}" }) { anime ->
                                LaunchedEffect(anime.slug) { onAnimeVisible(anime) }
                                LaunchedEffect(anime.slug) { onSectionAnimeVisible(anime) }
                                PosterCard(anime, onClick = { onAnimeClick(anime) })
                            }
                        }
                    }
                }
                if (state.historyView) {
                    items(state.visibleHistory, key = { "watch-${it.anime.slug}" }) { entry ->
                        Box(Modifier.animateItem()) { WatchHistoryRow(entry, onClick = { onResumeHistory(entry) }, onRemove = { onRemoveHistory(entry) }) }
                    }
                }
            }
}

@Composable
private fun PosterCard(anime: Anime, onClick: () -> Unit) {
    Column(Modifier.width(160.dp).animateContentSize().clickable(onClick = onClick)) {
        AsyncImage(model = anime.poster, contentDescription = "Capa de ${anime.title}", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(230.dp).clip(RoundedCornerShape(12.dp)))
        Text(anime.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, modifier = Modifier.padding(top = 8.dp))
        val tags = catalogTags(anime)
        if (tags.isNotEmpty()) Text(tags.joinToString(" · "), color = Muted, fontSize = 11.sp, maxLines = 2, modifier = Modifier.padding(top = 3.dp, bottom = 16.dp))
        else Spacer(Modifier.height(16.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnimeRow(anime: Anime, favorite: Boolean, onClick: () -> Unit, onFavorite: () -> Unit) {
    val compact = LocalConfiguration.current.screenWidthDp < 380
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = if (compact) 12.dp else 18.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(model = anime.poster, contentDescription = anime.title, modifier = Modifier.width(if (compact) 68.dp else 82.dp).height(if (compact) 96.dp else 112.dp).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop)
        Column(Modifier.weight(1f).padding(horizontal = if (compact) 10.dp else 14.dp)) {
            Text(anime.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 2)
            Text(anime.metadata.firstOrNull { it in setOf("AnimeFire", "AnimesDigital", "AnimeDrive", "HiAnime") } ?: "Detalhes no episódio", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            Text(anime.synopsis.ifBlank { "Sinopse indisponível no momento" }, color = Muted, fontSize = 11.sp, maxLines = 2, modifier = Modifier.padding(top = 5.dp))
            FlowRow(Modifier.padding(top = 7.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                catalogTags(anime).forEach { Tag(it.uppercase()) }
            }
        }
        IconButton(onClick = onFavorite) { Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, if (favorite) "Remover ${anime.title} dos favoritos" else "Adicionar ${anime.title} aos favoritos", tint = if (favorite) Color(0xFFFF6B82) else Muted) }
    }
}

@Composable
private fun FavoriteGridCard(anime: Anime, modifier: Modifier = Modifier, onClick: () -> Unit, onFavorite: () -> Unit) {
    Column(modifier.animateContentSize().clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            AsyncImage(model = anime.poster, contentDescription = "Capa de ${anime.title}", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            IconButton(onClick = onFavorite, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(.6f))) {
                Icon(Icons.Default.Favorite, "Remover ${anime.title} dos favoritos", tint = Color.White)
            }
        }
        Text(anime.title, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 2, modifier = Modifier.padding(top = 8.dp))
        val tags = catalogTags(anime)
        if (tags.isNotEmpty()) Text(tags.joinToString(" · "), color = Muted, fontSize = 11.sp, maxLines = 2, modifier = Modifier.padding(top = 3.dp, bottom = 8.dp))
        else Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun WatchHistoryRow(item: WatchHistoryItem, onClick: () -> Unit, onRemove: () -> Unit) {
    val compact = LocalConfiguration.current.screenWidthDp < 380
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(if (compact) 115.dp else 145.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            AsyncImage(model = item.anime.poster, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(.55f)).padding(5.dp))
            if (item.episodeDurationMs > 0L) {
                val progress = (item.resumePositionMs.toFloat() / item.episodeDurationMs).coerceIn(0f, 1f)
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(.3f))) {
                    Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(Accent))
                }
            }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(item.anime.title, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 2)
            Text(item.lastEpisode.ifBlank { "Retomar reprodução" }, color = Muted, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(top = 5.dp))
            if (item.episodeDurationMs > 0L) Text("${formatPlaybackTime(item.resumePositionMs)} / ${formatPlaybackTime(item.episodeDurationMs)}", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
            val hasInProgressEpisode = item.lastEpisodeId.isNotBlank() || item.lastEpisodeNumber.isNotBlank()
    val episodesStarted = item.completedEpisodeIds.size + if (hasInProgressEpisode && item.lastEpisodeUid !in item.completedEpisodeIds && item.lastEpisodeId !in item.completedEpisodeIds) 1 else 0
            Text("$episodesStarted ${if (episodesStarted == 1) "episódio" else "episódios"} · ${formatWatchedTime(item.secondsWatched)}", color = Muted, fontSize = 10.sp, maxLines = 1, modifier = Modifier.padding(top = 3.dp))
        }
        IconButton(onClick = onRemove) { Icon(Icons.Default.DeleteOutline, "Remover ${item.anime.title} do histórico", tint = Muted) }
    }
}

@Composable
private fun HistoryStat(label: String) {
    Text(label, modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(Color(0xFF29292E)).padding(horizontal = 7.dp, vertical = 4.dp), color = Ink, fontSize = 10.sp, maxLines = 1)
}

private fun formatWatchedTime(seconds: Long): String {
    val minutes = seconds / 60
    if (minutes < 1) return if (seconds > 0) "${seconds}s" else "0 min"
    val hours = minutes / 60
    val remainingMinutes = minutes % 60
    return if (hours > 0) "${hours}h ${remainingMinutes}min" else "${minutes}min"
}


@Composable
private fun Tag(text: String) {
    Text(text, modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(Color(0xFF303036)).padding(horizontal = 7.dp, vertical = 4.dp), fontSize = 8.sp, color = Color(0xFFD1D1D6), letterSpacing = .3.sp)
}
