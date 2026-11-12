package dev.aniclipt.app.data

import org.junit.Assert.*
import org.junit.Test

class IdentityResolverTest {
    @Test fun equivalentProviderEntriesShareReleaseAndEpisodes() {
        val resolver = IdentityResolver(IdentityRegistry())
        val group = resolver.group(listOf(
            Anime("fire#s1", "Example Story", anilistId = 42),
            Anime("hi:example", "Example Story", anilistId = 42),
        )).single()
        val alternative = group.alternatives.single()
        assertEquals(group.workId, alternative.workId)
        assertEquals(group.releaseId, alternative.releaseId)
        assertEquals(
            resolver.identifyEpisodes(group, listOf(Episode("fire-1", "01", "Episódio 1"))).single().episodeId,
            resolver.identifyEpisodes(alternative, listOf(Episode("hi-1", "1.0", "Episode 1"))).single().episodeId,
        )
    }

    @Test fun sequelHasSameWorkButDistinctRelease() {
        val registry = IdentityRegistry()
        val first = registry.assign(listOf(Anime("fire#s1", "Example Story", anilistId = 42))).single()
        val second = registry.assign(listOf(Anime("fire#s2", "Example Story Season 2", anilistId = 42))).single()
        assertEquals(first.workId, second.workId)
        assertNotEquals(first.releaseId, second.releaseId)
    }

    @Test fun conflictingExternalIdsStaySeparateAndExistingLinkIsStable() {
        val registry = IdentityRegistry()
        val resolver = IdentityResolver(registry)
        val first = resolver.identify(Anime("fire#s1", "Example Story", anilistId = 42))
        val groups = resolver.group(listOf(first, Anime("hi:other", "Example Story", anilistId = 43)))
        assertEquals(2, groups.size)
        assertEquals(first.releaseId, resolver.identify(first.copy(title = "Updated title")).releaseId)
    }

    @Test fun previouslySeparateProviderIdsBecomeAliasesAfterConfirmedMatch() {
        val registry = IdentityRegistry()
        val resolver = IdentityResolver(registry)
        val fire = resolver.identify(Anime("fire#s1", "Example Story", anilistId = 42))
        val hi = resolver.identify(Anime("hi:example", "Example Story"))
        val grouped = resolver.group(listOf(fire, hi.copy(anilistId = 42))).single()
        assertEquals(grouped.releaseId, registry.canonicalReleaseId(fire.releaseId))
        assertEquals(grouped.releaseId, registry.canonicalReleaseId(hi.releaseId))
    }

    @Test fun ambiguousExternalMatchDoesNotAssignAnId() {
        val resolver = IdentityResolver(IdentityRegistry())
        val result = resolver.matchExternalIds(listOf(Anime("source", "Example Story")), listOf(
            CatalogIdentity(1, "TV", "Example Story", listOf("Example Story"), false),
            CatalogIdentity(2, "TV", "Example Story", listOf("Example Story"), false),
        )).single()
        assertNull(result.anilistId)
    }
}
