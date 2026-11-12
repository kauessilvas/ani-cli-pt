package dev.aniclipt.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlaybackPolicyTest {
    private fun source(label: String, height: Int = 720, audio: String = "Áudio original", provider: String = "hi:anime") =
        PlaybackSource("https://example.test/video", null, "https://example.test/", "Anime", "1", audio, label, listOf(height), provider)

    @Test fun primaryFastPathRequiresConfirmedAudioOrPortugueseSubtitles() {
        assertTrue(canPlayPrimaryImmediately(source("Legenda da fonte", provider = "anime#s1"), dubbed = false))
        assertTrue(canPlayPrimaryImmediately(source("Legenda PT-BR · fonte do vídeo"), dubbed = false))
        assertFalse(canPlayPrimaryImmediately(source("Legenda em inglês · fallback"), dubbed = false))
        assertFalse(canPlayPrimaryImmediately(source("Sem PT-BR · configure OpenSubtitles"), dubbed = false))
        assertFalse(canPlayPrimaryImmediately(source("Legenda da fonte", provider = "hi:anime"), dubbed = false))
        assertTrue(canPlayPrimaryImmediately(source("Áudio dublado", audio = "Dublado PT-BR"), dubbed = true))
        assertFalse(canPlayPrimaryImmediately(source("Áudio original", audio = "Áudio original · dublagem PT-BR indisponível"), dubbed = true))
    }

    @Test fun portugueseSourceWinsAndExternalIsNotCalled() = runBlocking {
        var externalCalls = 0
        val chosen = choosePlaybackSource(false, true,
            { listOf(source("PT-BR", 480), source("PT-BR", 1080), source("inglês", 2160)) },
            { externalCalls++; listOf(source("OpenSubtitles", 2160)) },
            { error("Não deve consultar dublagem") })
        assertEquals(1080, chosen.availableVideoHeights.single())
        assertEquals(0, externalCalls)
    }

    @Test fun externalSubtitlePrecedesEnglishAndDubbed() = runBlocking {
        val chosen = choosePlaybackSource(false, true, { listOf(source("inglês", 1080)) },
            { listOf(source("OpenSubtitles PT-BR", 480)) }, { error("Não deve consultar dublagem") })
        assertTrue(chosen.subtitleLabel.contains("OpenSubtitles"))
    }

    @Test fun disabledExternalIsNeverQueriedAndDubbedIsSuggestedBeforeEnglish() = runBlocking {
        val english = source("inglês", 1080)
        val dubbed = source("Áudio dublado", audio = "Dublado PT-BR")
        try {
            choosePlaybackSource(false, false, { listOf(english) }, { error("Busca externa desativada") }, { listOf(dubbed) })
            fail("Deveria sugerir dublagem")
        } catch (suggestion: DubSuggestion) {
            assertEquals(dubbed, suggestion.dubbed)
            assertEquals(english, suggestion.english)
        }
    }

    @Test fun englishIsUsedWhenNothingElseExists() = runBlocking {
        val english = source("inglês", 720)
        assertEquals(english, choosePlaybackSource(false, true, { listOf(english) }, { emptyList() }, { emptyList() }))
    }

    @Test fun explicitlyDubbedPicksBestAndSkipsOriginal() = runBlocking {
        val result = choosePlaybackSource(true, true, { error("Original não deve ser consultado") }, { error("Não deve buscar legendas externas") },
            { listOf(source("Áudio dublado", 480, "Dublado PT-BR"), source("Áudio dublado", 1080, "Dublado PT-BR")) })
        assertEquals(1080, result.availableVideoHeights.single())
    }

    @Test fun missingSourcesFailInsteadOfInventingSubtitles() = runBlocking {
        try {
            choosePlaybackSource(false, true, { emptyList() }, { emptyList() }, { emptyList() })
            fail("Deveria informar indisponibilidade")
        } catch (expected: IllegalStateException) { assertTrue(expected.message.orEmpty().contains("Nenhuma fonte compatível")) }
    }
}
