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

internal class OutlinedSubtitleTextView(context: android.content.Context) : TextView(context) {
    var outlineColor: Int = android.graphics.Color.BLACK
    var outlineWidthPx: Float = 0f

    override fun onDraw(canvas: Canvas) {
        val textLayout = layout
        if (textLayout != null && text.isNotEmpty() && outlineWidthPx > 0f) {
            val saveCount = canvas.save()
            canvas.translate(compoundPaddingLeft.toFloat(), extendedPaddingTop.toFloat())
            val originalStyle = paint.style
            val originalColor = paint.color
            val originalWidth = paint.strokeWidth
            val originalJoin = paint.strokeJoin
            paint.style = Paint.Style.STROKE
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeWidth = outlineWidthPx
            paint.color = outlineColor
            textLayout.draw(canvas)
            paint.style = originalStyle
            paint.color = originalColor
            paint.strokeWidth = originalWidth
            paint.strokeJoin = originalJoin
            canvas.restoreToCount(saveCount)
        }
        super.onDraw(canvas)
    }
}

internal fun applySubtitleOverlayStyle(
    view: OutlinedSubtitleTextView,
    context: android.content.Context,
    shortestScreenDp: Int,
    size: String,
    color: String,
    font: String,
    backgroundMode: String,
    outlineColorKey: String,
    outlineThickness: String,
) {
    val foregroundColor = when (color) {
        "yellow" -> android.graphics.Color.YELLOW
        "cyan" -> android.graphics.Color.CYAN
        "green" -> android.graphics.Color.GREEN
        else -> android.graphics.Color.WHITE
    }
    val outlineColorInt = when (outlineColorKey) {
        "white" -> android.graphics.Color.WHITE
        "blue" -> android.graphics.Color.rgb(10, 132, 255)
        "yellow" -> android.graphics.Color.YELLOW
        "red" -> android.graphics.Color.RED
        else -> android.graphics.Color.BLACK
    }
    val backgroundColor = when (backgroundMode) {
        "none" -> android.graphics.Color.TRANSPARENT
        "dark" -> 0xE6000000.toInt()
        else -> 0x99000000.toInt()
    }
    val typeface = when (font) {
        "serif" -> Typeface.SERIF
        "mono" -> Typeface.MONOSPACE
        else -> Typeface.SANS_SERIF
    }
    val density = context.resources.displayMetrics.density
    val fractionalSize = when (size) { "small" -> 0.04f; "large" -> 0.06f; else -> 0.05f }
    view.apply {
        gravity = Gravity.CENTER
        maxLines = 3
        includeFontPadding = false
        setTextColor(foregroundColor)
        setTypeface(typeface)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, shortestScreenDp * density * fractionalSize)
        outlineColor = outlineColorInt
        outlineWidthPx = when (outlineThickness) {
            "thin" -> density
            "thick" -> density * 3f
            "none" -> 0f
            else -> density * 2f
        }
        setPadding((10 * density).toInt(), (5 * density).toInt(), (10 * density).toInt(), (5 * density).toInt())
        background = if (backgroundMode == "none") null else GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(backgroundColor)
            cornerRadius = 7 * density
        }
    }
}
