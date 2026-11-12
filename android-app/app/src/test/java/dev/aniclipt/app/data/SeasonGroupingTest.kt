package dev.aniclipt.app.data

import org.junit.Assert.*
import org.junit.Test

class SeasonGroupingTest {
    @Test fun finalSeasonPartsBelongToTheSameNavigationFamily() {
        val titles = listOf(
            "Attack on Titan",
            "Attack on Titan Season 2",
            "Attack on Titan Season 3",
            "Attack on Titan: Final Season, Part 1",
            "Attack on Titan: Final Season, Part 2",
            "Attack on Titan: The Final Season Part 3",
        )
        assertEquals(1, titles.map(::seasonFamilyKey).distinct().size)
        val seasons = titles.mapIndexed { index, title -> Anime("hi:release-$index", title) }
            .sortedWith(compareBy<Anime> { seasonOrder(it).first }.thenBy { seasonOrder(it).second }.thenBy { seasonOrder(it).third })
        assertEquals(listOf(
            "Temporada 1", "Temporada 2", "Temporada 3",
            "Temporada final · Parte 1", "Temporada final · Parte 2", "Temporada final · Parte 3",
        ), seasons.map { seasonDisplayLabel(it, seasons) })
        assertEquals("Assistir Final P1 EP.1", seasonWatchLabel(seasons[3], seasons, "1"))
    }

    @Test fun unknownNumberUsesSourceTitleInsteadOfInventingASeason() {
        val seasons = listOf(Anime("a", "Example Show"), Anime("b", "Example Show Continuation"))
        assertEquals("Example Show Continuation", seasonDisplayLabel(seasons[1], seasons))
    }

    @Test fun numberedPartsKeepTheirSeasonNumber() {
        val release = Anime("example#s1", "Example Show Season 3 Part 2")
        assertEquals("Temporada 3 · Parte 2", seasonDisplayLabel(release, listOf(release)))
        val unnumbered = Anime("hi:example", "Example Show Part 2")
        assertEquals("Parte 2", seasonDisplayLabel(unnumbered, listOf(unnumbered)))
    }
}
