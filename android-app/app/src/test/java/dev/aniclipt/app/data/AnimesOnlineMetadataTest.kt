package dev.aniclipt.app.data

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class AnimesOnlineMetadataTest {
    @Test fun matchesOnlyTheSameRelease() {
        assertEquals(onlineTitleKey("One-Punch Man · Temporada 2"), onlineTitleKey("One-Punch Man 2 Dublado"))
        assertEquals(onlineTitleKey("Kimetsu no Yaiba"), onlineTitleKey("Kimetsu no Yaiba – Todos os Episódios"))
        assertNotEquals(onlineTitleKey("One-Punch Man"), onlineTitleKey("One-Punch Man 3"))
        assertNotEquals(onlineTitleKey("Kaiju No. 8"), onlineTitleKey("Kaiju No. 8: Narumi no Heijitsu"))
    }

    @Test fun readsPortugueseTitlesAndOriginalImages() {
        val html = """<div class="sheader"><div class="poster"><img src="https://animesonline.cloud/wp-content/uploads/2024/04/poster-207x300.jpg"></div><div class="data"><h1>Kaiju No. 8</h1></div></div><div class="wp-content"><p>Sinopse: Uma história.</p></div><div id="episodes"><li><div class="imagen"><img src="https://animesonline.cloud/wp-content/uploads/2024/04/still-300x170.jpg"></div><div class="numerando">1 - 1</div><div class="episodiotitle"><a href="https://animesonline.cloud/episodio/kaiju-01">Episódio 01 - O homem que virou kaiju</a></div></li></div>"""
        val data = parseOnlineMetadata(Jsoup.parse(html, "https://animesonline.cloud/"))!!
        assertEquals("Kaiju No. 8", data.title)
        assertEquals("Uma história.", data.synopsis)
        assertEquals("https://animesonline.cloud/wp-content/uploads/2024/04/poster.jpg", data.poster)
        assertEquals("O homem que virou kaiju", data.episodes.single().title)
        assertEquals("https://animesonline.cloud/wp-content/uploads/2024/04/still.jpg", data.episodes.single().still)
    }

    @Test fun ignoresTrailersAndPrefersARealTitleOverAnEpisodePlaceholder() {
        val html = """<div class="sheader"><div class="data"><h1>Kaijuu 8-gou 2nd Season</h1></div></div><div id="episodes"><li><div class="numerando">2 - 0</div><div class="episodiotitle"><a href="/episodio/trailer">Trailer</a></div></li><li><div class="numerando">2 - 1</div><div class="episodiotitle"><a href="/episodio/placeholder">Episódio X</a></div></li><li><div class="imagen"><img src="https://animesonline.cloud/wp-content/uploads/2025/07/real-300x170.jpg"></div><div class="numerando">2 - 1</div><div class="episodiotitle"><a href="/episodio/real">Episódio 01 - A Arma Kaiju</a></div></li></div>"""
        val episodes = parseOnlineMetadata(Jsoup.parse(html, "https://animesonline.cloud/"))!!.episodes
        assertEquals(1, episodes.size)
        assertEquals("A Arma Kaiju", episodes.single().title)
    }

    @Test fun mergesOnlyAlignedEpisodesAndKeepsExistingHighQualityStills() {
        val source = listOf(Episode("one", "1", "Episode 1", thumbnail = ""), Episode("two", "2", "Episode 2", thumbnail = "tmdb-still"))
        val online = listOf(OnlineEpisodeMetadata(1, "Primeiro", "online-1"), OnlineEpisodeMetadata(2, "Segundo", "online-2"))
        val merged = mergeOnlineEpisodeMetadata(source, online, true)
        assertEquals("Primeiro", merged.first().title)
        assertEquals("online-1", merged.first().thumbnail)
        assertEquals("tmdb-still", merged.last().thumbnail)
        assertEquals(source, mergeOnlineEpisodeMetadata(source, online.take(1), true))
        assertEquals(source, mergeOnlineEpisodeMetadata(source, listOf(online.first(), online.last().copy(number = 3)), true))
    }
}
