package dev.aniclipt.app.data

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimesOnlineFallbackTest {
    @Test fun matchesOnlyTheSameTitleDespiteAudioAndYearLabels() {
        assertTrue(fallbackTitleMatches("Black Clover", "Black Clover Dublado"))
        assertTrue(fallbackTitleMatches("Black Clover", "Black Clover 2017"))
        assertFalse(fallbackTitleMatches("Black Clover", "Black Clover 2nd Season"))
        assertFalse(fallbackTitleMatches("High School DxD", "High School of the Dead"))
    }

    @Test fun mapsAnimeFireSeasonEpisodeToContinuousRelease() {
        val anime = Anime("one-piece#s7", "One Piece · Temporada 7")
        assertEquals("One Piece", fallbackSearchTitle(anime))
        val html = """<div id="episodes">
            <li><div class="numerando">6 - 195</div><a href="/episodio/one-piece-195">Episódio 195</a></li>
            <li><div class="numerando">7 - 197</div><a href="/episodio/one-piece-197">Episódio 197</a></li>
            <li><div class="numerando">7 - 196</div><a href="/episodio/one-piece-196">Episódio 196</a></li>
            <li><div class="numerando">8 - 229</div><a href="/episodio/one-piece-229">Episódio 229</a></li>
        </div>"""
        val doc = Jsoup.parse(html, "https://animesonline.cloud/")
        assertEquals("https://animesonline.cloud/episodio/one-piece-196", fallbackEpisodeUrl(doc, Episode("fire-1", "1", ""), 7))
        assertEquals("https://animesonline.cloud/episodio/one-piece-197", fallbackEpisodeUrl(doc, Episode("fire-2", "2", ""), 7))
        assertEquals(null, fallbackEpisodeUrl(doc, Episode("fire-3", "3", ""), 7))
        assertEquals("https://animesonline.cloud/episodio/one-piece-196", fallbackEpisodeUrl(doc, Episode("other", "196", ""), null))
    }

    @Test fun doesNotShiftEpisodesAcrossAGapInFallbackCatalog() {
        val doc = Jsoup.parse("""<div id="episodes">
            <li><div class="numerando">7 - 196</div><a href="/episodio/196">196</a></li>
            <li><div class="numerando">7 - 198</div><a href="/episodio/198">198</a></li>
        </div>""", "https://animesonline.cloud/")
        assertEquals(null, fallbackEpisodeUrl(doc, Episode("fire-2", "2", ""), 7))
    }

    @Test fun keepsIndependentSeasonTitlesIntact() {
        assertEquals("One-Punch Man 2nd Season", fallbackSearchTitle(Anime("hi:one-punch-man-2", "One-Punch Man 2nd Season")))
        assertEquals("One-Punch Man - Temporada 2", fallbackSearchTitle(Anime("one-punch-man#s1", "One-Punch Man - Temporada 2")))
        assertEquals("One Piece", fallbackSearchTitle(Anime("one-piece#s1", "One Piece · Temporada 1")))
    }
}
