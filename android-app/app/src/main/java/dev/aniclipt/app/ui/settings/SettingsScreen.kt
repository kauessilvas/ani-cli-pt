package dev.aniclipt.app.ui.settings

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
import dev.aniclipt.app.ui.settings.SubtitlePreview

@Composable
internal fun SettingsScreen(
    lightTheme: Boolean, onLightTheme: (Boolean) -> Unit,
    hideAdult: Boolean, onHideAdult: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
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
    subtitleOutlineColor: String,
    onSubtitleOutlineColor: (String) -> Unit,
    subtitleOutlineThickness: String,
    onSubtitleOutlineThickness: (String) -> Unit,
    subtitlePosition: String,
    onSubtitlePosition: (String) -> Unit,
    openSubtitlesApiKey: String,
    apiKeyValidation: ApiKeyValidation,
    onOpenSubtitlesApiKey: (String) -> Unit,
    onValidateOpenSubtitlesApiKey: () -> Unit,
    openSubtitlesEnabled: Boolean,
    onOpenSubtitlesEnabled: (Boolean) -> Unit,
) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp)) {
        Text("Configurações", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
        Text("Personalize sua experiência", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 18.dp))
        Row(Modifier.fillMaxWidth().padding(bottom = 16.dp).clip(RoundedCornerShape(10.dp)).background(Panel).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Modo claro", fontWeight = FontWeight.SemiBold); Text("Altere a aparência do aplicativo", color = Muted, fontSize = 11.sp) }
            Switch(checked = lightTheme, onCheckedChange = onLightTheme)
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 16.dp).clip(RoundedCornerShape(10.dp)).background(Panel).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Ocultar conteúdo adulto", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text("Oculta títulos +18 identificados pelas fontes. Alguns podem não estar classificados.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
            }
            Switch(checked = hideAdult, onCheckedChange = onHideAdult)
        }
        Text("REPRODUÇÃO", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (lightTheme) Panel else Color(0xB8202024)).border(1.dp, Ink.copy(.07f), RoundedCornerShape(10.dp)).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 10.dp)) { Text("Preferir dublagem PT-BR", fontWeight = FontWeight.SemiBold, fontSize = 14.sp); Text("Sem faixa PT-BR identificada: usa áudio original", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp)) }
                Switch(checked = dubbed, onCheckedChange = onDubbed)
            }
        }
        Text("APARÊNCIA DAS LEGENDAS", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp, modifier = Modifier.padding(start = 4.dp, top = 22.dp, bottom = 8.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (lightTheme) Panel else Color(0xB8202024)).border(1.dp, Ink.copy(.07f), RoundedCornerShape(10.dp)).padding(horizontal = 16.dp)) {
            Text("A personalização não está disponível em todos os animes. Legendas incorporadas à imagem do vídeo não podem ser alteradas.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 14.dp))
            SubtitlePreview(subtitleSize, subtitleColor, subtitleFont, subtitleBackground, subtitleOutlineColor, subtitleOutlineThickness, subtitlePosition)
            HorizontalDivider(color = Ink.copy(.10f))
            SubtitlePreferenceRow("Tamanho", subtitleSize, listOf("small" to "Pequeno", "medium" to "Médio", "large" to "Grande"), onSubtitleSize)
            HorizontalDivider(color = Ink.copy(.10f))
            SubtitlePreferenceRow("Cor do texto", subtitleColor, listOf("white" to "Branco", "yellow" to "Amarelo", "cyan" to "Ciano", "green" to "Verde"), onSubtitleColor)
            HorizontalDivider(color = Ink.copy(.10f))
            SubtitlePreferenceRow("Tipo de letra", subtitleFont, listOf("sans" to "Sem serifa", "serif" to "Serifada", "mono" to "Monoespaçada"), onSubtitleFont)
            HorizontalDivider(color = Ink.copy(.10f))
            SubtitlePreferenceRow("Fundo", subtitleBackground, listOf("none" to "Sem fundo", "translucent" to "Translúcido", "dark" to "Escuro"), onSubtitleBackground)
            HorizontalDivider(color = Ink.copy(.10f))
            SubtitlePreferenceRow("Cor da borda", subtitleOutlineColor, listOf("black" to "Preta", "white" to "Branca", "blue" to "Azul", "yellow" to "Amarela", "red" to "Vermelha"), onSubtitleOutlineColor)
            HorizontalDivider(color = Ink.copy(.10f))
            SubtitlePreferenceRow("Espessura da borda", subtitleOutlineThickness, listOf("none" to "Sem borda", "thin" to "Fina", "medium" to "Média", "thick" to "Grossa"), onSubtitleOutlineThickness)
            HorizontalDivider(color = Ink.copy(.10f))
            SubtitlePreferenceRow("Posição na tela", subtitlePosition, listOf("top" to "Superior", "center" to "Centro", "bottom" to "Inferior"), onSubtitlePosition)
        }
        Text("BUSCA DE LEGENDAS PT-BR", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp, modifier = Modifier.padding(start = 4.dp, top = 22.dp, bottom = 8.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (lightTheme) Panel else Color(0xB8202024)).border(1.dp, Ink.copy(.07f), RoundedCornerShape(10.dp)).padding(16.dp)) {
            Text("OpenSubtitles", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text("Ative para buscar legendas PT-BR externas quando a fonte do vídeo não tiver uma. Desativado, o app não consulta o OpenSubtitles.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 10.dp)) {
                    Text("Usar OpenSubtitles", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(if (openSubtitlesEnabled) "Busca externa ativada" else "Busca externa desativada", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
                }
                Switch(checked = openSubtitlesEnabled, onCheckedChange = onOpenSubtitlesEnabled)
            }
            var apiKeyVisible by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = openSubtitlesApiKey,
                onValueChange = onOpenSubtitlesApiKey,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Chave de API") },
                placeholder = { Text("Insira sua chave OpenSubtitles") },
                visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        when (apiKeyValidation) {
                            ApiKeyValidation.CHECKING -> CircularProgressIndicator(Modifier.padding(horizontal = 10.dp).size(18.dp), strokeWidth = 2.dp)
                            ApiKeyValidation.VALID -> Icon(Icons.Default.CheckCircle, "Chave válida", tint = Color(0xFF55D68B), modifier = Modifier.padding(horizontal = 8.dp).size(20.dp))
                            ApiKeyValidation.INVALID -> Icon(Icons.Default.ErrorOutline, "Chave inválida", tint = Color(0xFFFF8A80), modifier = Modifier.padding(horizontal = 8.dp).size(20.dp))
                            else -> Unit
                        }
                        IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                            Icon(if (apiKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (apiKeyVisible) "Ocultar chave" else "Exibir chave", tint = Muted)
                        }
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Ink, unfocusedTextColor = Ink, focusedBorderColor = Accent, unfocusedBorderColor = Ink.copy(.28f), focusedLabelColor = Accent, unfocusedLabelColor = Muted, cursorColor = Accent),
            )
            val validationMessage = when (apiKeyValidation) {
                ApiKeyValidation.VALID -> "Chave validada com sucesso."
                ApiKeyValidation.INVALID -> "OpenSubtitles recusou esta chave."
                ApiKeyValidation.CHECKING -> "Validando chave…"
                ApiKeyValidation.UNAVAILABLE -> "Não foi possível validar agora. Confira a conexão e tente novamente."
                else -> "A chave é verificada com segurança sem baixar legendas."
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(validationMessage, modifier = Modifier.weight(1f).padding(top = 6.dp), color = if (apiKeyValidation == ApiKeyValidation.INVALID) Color(0xFFFF8A80) else if (apiKeyValidation == ApiKeyValidation.VALID) Color(0xFF55D68B) else Muted, fontSize = 10.sp)
                if (apiKeyValidation == ApiKeyValidation.INVALID || apiKeyValidation == ApiKeyValidation.UNAVAILABLE) {
                    TextButton(onClick = onValidateOpenSubtitlesApiKey, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Verificar", fontSize = 11.sp) }
                }
            }
            Text("A chave só é usada quando esta opção está ativada. Sem chave, a busca externa não funciona.", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
        }
        Text("NOTIFICAÇÕES", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp, modifier = Modifier.padding(start = 4.dp, top = 22.dp, bottom = 8.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (lightTheme) Panel else Color(0xB8202024)).border(1.dp, Ink.copy(.07f), RoundedCornerShape(10.dp)).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 10.dp)) { Text("Novos episódios", fontWeight = FontWeight.SemiBold, fontSize = 14.sp); Text("Avisar quando sair episódio de um anime favorito. Verificação periódica, em geral a cada 6 horas.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)) }
                Switch(checked = notificationsEnabled, onCheckedChange = onNotifications)
            }
        }
        Text("ATUALIZAÇÕES", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp, modifier = Modifier.padding(start = 4.dp, top = 22.dp, bottom = 8.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (lightTheme) Panel else Color(0xB8202024)).border(1.dp, Ink.copy(.07f), RoundedCornerShape(10.dp)).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 10.dp)) {
                    Text("Atualizações automáticas", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("O app sempre verifica ao abrir. Ative para também verificar e baixar em segundo plano. Se houver novidade, você pode baixar por aqui.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                    if (availableUpdateVersion != null) {
                        Text("Nova versão $availableUpdateVersion disponível.", color = if (lightTheme) MaterialTheme.colorScheme.primary else Color(0xFF8FC5FF), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    }
                    if (pendingUpdateVersion != null) {
                        Text("Versão $pendingUpdateVersion pronta para instalar.", color = if (lightTheme) MaterialTheme.colorScheme.primary else Color(0xFF8FC5FF), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    } else when (updateDownloadStatus) {
                        ReleaseUpdateWorker.STATUS_DOWNLOADING -> Text("Baixando atualização…", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                        ReleaseUpdateWorker.STATUS_FAILED_NETWORK -> Text("Falha de conexão. Verifique sua internet e tente novamente.", color = Color(0xFFFF9B9B), fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                        ReleaseUpdateWorker.STATUS_FAILED_SERVER -> Text("O GitHub não respondeu como esperado. Tente novamente mais tarde.", color = Color(0xFFFF9B9B), fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                        ReleaseUpdateWorker.STATUS_FAILED_RATE_LIMIT -> Text("O GitHub limitou as consultas. Aguarde e tente novamente.", color = Color(0xFFFF9B9B), fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                        ReleaseUpdateWorker.STATUS_FAILED_ASSET -> Text("O arquivo da atualização não está disponível na release.", color = Color(0xFFFF9B9B), fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                        ReleaseUpdateWorker.STATUS_FAILED_INVALID -> Text("A atualização recebida não passou na verificação. Tente novamente mais tarde.", color = Color(0xFFFF9B9B), fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                        ReleaseUpdateWorker.STATUS_FAILED_STORAGE -> Text("Não foi possível salvar o arquivo. Confira o espaço disponível.", color = Color(0xFFFF9B9B), fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
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
                    Text(if (updateDownloadStatus.startsWith(ReleaseUpdateWorker.STATUS_FAILED)) "Tentar baixar novamente" else "Baixar atualização")
                }
            }
        }
        Text("ani-cli-pt · 0.1.9", color = Muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 24.dp))
    }
}

@Composable
private fun SubtitlePreferenceRow(title: String, selected: String, options: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Box {
            TextButton(onClick = { expanded = true }) {
                Text(options.firstOrNull { it.first == selected }?.second ?: options.first().second, color = if (Canvas == Color(0xFF111113)) Color(0xFFB9D9FF) else MaterialTheme.colorScheme.primary, fontSize = 12.sp, maxLines = 1)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (value, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { onSelect(value); expanded = false })
                }
            }
        }
    }
}
