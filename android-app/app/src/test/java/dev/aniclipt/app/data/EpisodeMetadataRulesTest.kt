package dev.aniclipt.app.data

import org.junit.Assert.*
import org.junit.Test

class EpisodeMetadataRulesTest {
    @Test fun readyDisplayNeedsVerifiedTitlesAndAtLeastOneStill() {
        val anime = Anime(slug = "hi:sample", title = "Sample")
        val ready = (1..8).map { Episode("id-$it", "$it", "Título $it", originalTitle = "English $it", thumbnail = if (it == 1) "still-1" else "") }
        assertTrue(hasUsableEpisodeMetadata(anime, ready))
        assertFalse(hasUsableEpisodeMetadata(anime, ready.map { it.copy(thumbnail = "") }))
        assertFalse(hasUsableEpisodeMetadata(anime, ready.toMutableList().apply { this[1] = this[1].copy(title = "Episode 2") }))
        assertFalse(hasUsableEpisodeMetadata(anime, ready.toMutableList().apply { this[1] = this[1].copy(title = "English 2") }))
    }

    @Test fun matchesNamedEpisodesBeforeAddingPortugueseTitlesAndStills() {
        val source = listOf(Episode("source-1", "1", "The Strongest Man"), Episode("source-2", "2", "The Lone Cyborg"), Episode("source-3", "3", "The Obsessive Scientist"))
        val english = listOf("The Strongest Man", "The Lone Cyborg", "The Obsessive Scientist").mapIndexed { index, title -> EpisodeMetadata(index + 1, title, "") }
        val portuguese = listOf("O Homem Mais Poderoso", "O Ciborgue Solitário", "O Cientista Obsessivo").mapIndexed { index, title -> EpisodeMetadata(index + 1, title, "https://image.tmdb.org/t/p/w780/still-$index.jpg") }
        val merged = mergeVerifiedEpisodeMetadata(source, english, portuguese)
        assertEquals("O Homem Mais Poderoso", merged.first().title)
        assertTrue(merged.first().thumbnail.contains("still-0"))
        assertEquals("source-1", merged.first().id)
    }

    @Test fun genericSourceTitlesRequireAnExactEpisodeCount() {
        val source = (1..3).map { Episode("source-$it", "$it", "Episode $it") }
        val english = (1..4).map { EpisodeMetadata(it, "English $it", "") }
        val portuguese = (1..4).map { EpisodeMetadata(it, "Português $it", "still-$it") }
        assertEquals(source, mergeVerifiedEpisodeMetadata(source, english, portuguese))
        assertEquals("Português 1", mergeVerifiedEpisodeMetadata(source, english.take(3), portuguese.take(3)).first().title)
    }

    @Test fun mismatchedSeasonKeepsSourceData() {
        val source = listOf(Episode("1", "1", "A New Beginning"), Episode("2", "2", "Another Episode"), Episode("3", "3", "Finale"))
        val wrong = listOf("Wrong First", "Wrong Second", "Wrong Third").mapIndexed { index, title -> EpisodeMetadata(index + 1, title, "") }
        assertEquals(source, mergeVerifiedEpisodeMetadata(source, wrong, wrong))
    }

    @Test fun acceptsShortenedEpisodeTitlesWhenTheSeasonStillMatches() {
        val source = listOf("To You Two Thousand Years Later", "That Day", "Shining Dimly in the Midst of Despair", "Night of the Disbanding", "First Battle", "The World She Saw", "The Small Blade", "Hearing the Heartbeat")
            .mapIndexed { index, title -> Episode("source-$index", "${index + 1}", title) }
        val english = listOf("To You, in 2000 Years: The Fall of Shiganshina (1)", "That Day: The Fall of Shiganshina (2)", "A Dim Light Amid Despair: Humanity's Comeback (1)", "The Night of the Closing Ceremony: Humanity's Comeback (2)", "First Battle: The Struggle for Trost (1)", "The World the Girl Saw: The Struggle for Trost (2)", "Small Blade: The Struggle for Trost (3)", "I Can Hear His Heartbeat: The Struggle for Trost (4)")
            .mapIndexed { index, title -> EpisodeMetadata(index + 1, title, "") }
        val portuguese = (1..8).map { EpisodeMetadata(it, "Título em português $it", "still-$it") }
        val merged = mergeVerifiedEpisodeMetadata(source, english, portuguese)
        assertEquals("Título em português 1", merged.first().title)
        assertEquals("still-8", merged.last().thumbnail)
    }

    @Test fun refreshKeepsReadyMetadataOnlyForTheSameEpisodeIdentity() {
        val cached = listOf(Episode("old-1", "1", "Título em português", originalTitle = "Episode 1", thumbnail = "still-1"), Episode("old-2", "2", "Outro título", originalTitle = "Episode 2", thumbnail = "still-2"))
        val fresh = listOf(Episode("old-1", "1", "Episode 1"), Episode("old-2", "3", "Episode 3"))
        val merged = reuseEpisodeMetadata(fresh, cached)
        assertEquals("Título em português", merged.first().title)
        assertEquals("still-1", merged.first().thumbnail)
        assertEquals("Episode 3", merged.last().title)
        assertEquals("", merged.last().thumbnail)
    }
}
