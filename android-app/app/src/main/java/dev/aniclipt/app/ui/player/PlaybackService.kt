package dev.aniclipt.app.ui.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import dev.aniclipt.app.data.Anime
import dev.aniclipt.app.data.Episode
import dev.aniclipt.app.data.PlaybackSource

/** Owns the one player and media session while video can continue outside the Activity. */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private lateinit var trackSelector: DefaultTrackSelector
    private lateinit var dataSource: DefaultHttpDataSource.Factory
    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private var currentKey: String? = null

    override fun onCreate() {
        super.onCreate()
        trackSelector = DefaultTrackSelector(this)
        dataSource = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        player = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .build().apply {
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                setHandleAudioBecomingNoisy(true)
            }
        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> playSource(intent)
            ACTION_QUALITY -> applyQuality(intent.getStringExtra(EXTRA_QUALITY).orEmpty())
            ACTION_STOP -> {
                currentKey = null
                player.stop()
                player.clearMediaItems()
                stopSelf()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun playSource(intent: Intent) {
        val url = intent.getStringExtra(EXTRA_URL)?.takeIf(String::isNotBlank) ?: return
        val subtitle = intent.getStringExtra(EXTRA_SUBTITLE)
        val referer = intent.getStringExtra(EXTRA_REFERER).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val episode = intent.getStringExtra(EXTRA_EPISODE).orEmpty()
        val subtitleLabel = intent.getStringExtra(EXTRA_SUBTITLE_LABEL).orEmpty()
        val key = "$url|$subtitle|$referer|$episode"
        if (key == currentKey) return
        currentKey = key
        val origin = runCatching { Uri.parse(referer).let { "${it.scheme}://${it.host}" } }.getOrDefault(referer)
        dataSource.setDefaultRequestProperties(mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36",
            "Referer" to referer, "Origin" to origin, "Accept" to "*/*",
            "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.7",
        ))
        val metadata = MediaMetadata.Builder()
            .setTitle(intent.getStringExtra(EXTRA_ANIME_TITLE).orEmpty().ifBlank { title })
            .setDisplayTitle("$title · Episódio $episode")
            .setArtist("Episódio $episode")
            .apply { intent.getStringExtra(EXTRA_POSTER)?.takeIf(String::isNotBlank)?.let { setArtworkUri(Uri.parse(it)) } }
            .build()
        val item = MediaItem.Builder().setUri(url).setMediaMetadata(metadata).apply {
            if (!url.contains(".mp4", true)) setMimeType(MimeTypes.APPLICATION_M3U8)
            subtitle?.let { subtitleUrl ->
                val path = Uri.parse(subtitleUrl).path.orEmpty()
                val isSubRip = path.endsWith(".srt", true) || subtitleLabel.contains("OpenSubtitles", true)
                val mime = if (isSubRip) MimeTypes.APPLICATION_SUBRIP else MimeTypes.TEXT_VTT
                val language = if (subtitleLabel.contains("inglês", true)) "en" else "pt-BR"
                setSubtitleConfigurations(listOf(MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitleUrl))
                    .setMimeType(mime).setLanguage(language).setLabel(subtitleLabel)
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()))
            }
        }.build()
        player.setMediaItem(item, intent.getLongExtra(EXTRA_POSITION, 0L).coerceAtLeast(0L))
        player.prepare()
        player.play()
    }

    private fun applyQuality(quality: String) {
        val height = quality.removeSuffix("p").toIntOrNull()
        val parameters = trackSelector.buildUponParameters().clearVideoSizeConstraints()
        if (height != null) parameters.setMinVideoSize(1, height).setMaxVideoSize(Int.MAX_VALUE, height)
        trackSelector.setParameters(parameters)
    }

    override fun onDestroy() {
        session?.release()
        session = null
        player.release()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_PLAY = "dev.aniclipt.app.PLAY"
        private const val ACTION_QUALITY = "dev.aniclipt.app.QUALITY"
        private const val ACTION_STOP = "dev.aniclipt.app.STOP"
        private const val EXTRA_URL = "url"
        private const val EXTRA_SUBTITLE = "subtitle"
        private const val EXTRA_REFERER = "referer"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_EPISODE = "episode"
        private const val EXTRA_SUBTITLE_LABEL = "subtitle_label"
        private const val EXTRA_ANIME_TITLE = "anime_title"
        private const val EXTRA_POSTER = "poster"
        private const val EXTRA_POSITION = "position"
        private const val EXTRA_QUALITY = "quality"
        @Volatile private var playbackRequested = false

        fun start(context: Context, source: PlaybackSource, anime: Anime?, episode: Episode?, positionMs: Long) {
            playbackRequested = true
            context.startService(Intent(context, PlaybackService::class.java).apply {
                action = ACTION_PLAY
                putExtra(EXTRA_URL, source.videoUrl)
                putExtra(EXTRA_SUBTITLE, source.subtitleUrl)
                putExtra(EXTRA_REFERER, source.referer)
                putExtra(EXTRA_TITLE, source.title)
                putExtra(EXTRA_EPISODE, source.episode)
                putExtra(EXTRA_SUBTITLE_LABEL, source.subtitleLabel)
                putExtra(EXTRA_ANIME_TITLE, anime?.title)
                putExtra(EXTRA_POSTER, anime?.poster)
                putExtra(EXTRA_POSITION, positionMs)
            })
        }

        fun connect(context: Context): ListenableFuture<MediaController> = MediaController.Builder(
            context, SessionToken(context, ComponentName(context, PlaybackService::class.java)),
        ).buildAsync()

        fun setQuality(context: Context, quality: String) {
            context.startService(Intent(context, PlaybackService::class.java).apply {
                action = ACTION_QUALITY
                putExtra(EXTRA_QUALITY, quality)
            })
        }

        fun stop(context: Context) {
            if (!playbackRequested) return
            playbackRequested = false
            context.startService(Intent(context, PlaybackService::class.java).apply { action = ACTION_STOP })
        }
    }
}
