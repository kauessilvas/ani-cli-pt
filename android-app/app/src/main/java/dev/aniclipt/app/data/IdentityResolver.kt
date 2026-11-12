package dev.aniclipt.app.data

import android.content.SharedPreferences
import androidx.room.withTransaction
import dev.aniclipt.app.data.local.AppDatabase
import dev.aniclipt.app.data.local.IdentityAliasEntity
import dev.aniclipt.app.data.local.IdentityLinkEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.UUID

data class IdentityLink(val workId: String, val releaseId: String)
internal data class CatalogIdentity(val id: Int, val format: String, val romaji: String, val titles: List<String>, val isAdult: Boolean?)

/** Stores source-to-domain links. A source URL can change without changing an existing domain ID. */
class IdentityRegistry(private val prefs: SharedPreferences? = null, private val database: AppDatabase? = null) {
    private val links = mutableMapOf<String, IdentityLink>()
    private val releaseAliases = mutableMapOf<String, String>()
    private var dirty = false
    private var bootstrapped = database == null
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pendingWrite: Deferred<Unit>? = null

    init {
        if (database == null) prefs?.let { store -> runCatching {
            val rows = JSONArray(store.getString(KEY, "[]"))
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                links[row.getString("key")] = IdentityLink(row.getString("work"), row.getString("release"))
            }
            val aliases = JSONArray(store.getString(ALIASES_KEY, "[]"))
            for (index in 0 until aliases.length()) {
                val row = aliases.getJSONObject(index)
                releaseAliases[row.getString("from")] = row.getString("to")
            }
        } }
    }

    /** Load the authoritative Room state before any synchronous identity operations. */
    suspend fun bootstrap() {
        val db = database ?: return
        val dao = db.appDao()
        val storedLinks = dao.identityLinks()
        val storedAliases = dao.identityAliases()
        synchronized(this) {
            if (bootstrapped) return
            links.clear()
            storedLinks.forEach { links[it.key] = IdentityLink(it.workId, it.releaseId) }
            releaseAliases.clear()
            storedAliases.forEach { releaseAliases[it.fromReleaseId] = it.toReleaseId }
            bootstrapped = true
        }
    }

    suspend fun awaitPersisted() {
        val pending = synchronized(this) { pendingWrite }
        pending?.await()
    }

    private fun requireBootstrapped() {
        check(bootstrapped) { "IdentityRegistry.bootstrap() deve ser chamado antes de consultar identidades." }
    }

    @Synchronized fun canonicalReleaseId(id: String): String {
        requireBootstrapped()
        var current = links["provider:$id"]?.releaseId ?: id
        val seen = mutableSetOf<String>()
        while (current in releaseAliases && seen.add(current)) current = releaseAliases.getValue(current)
        return current
    }

    @Synchronized fun linkFor(slug: String): IdentityLink? {
        requireBootstrapped()
        return links["provider:$slug"]?.let { it.copy(releaseId = canonicalReleaseId(it.releaseId)) }
    }

    @Synchronized fun assign(items: List<Anime>): List<Anime> { requireBootstrapped(); return assignInternal(items).also { flush() } }

    @Synchronized fun assignEach(items: List<Anime>): List<Anime> { requireBootstrapped(); return items.map { assignInternal(listOf(it)).single() }.also { flush() } }

    @Synchronized fun assignGroups(groups: List<List<Anime>>): List<List<Anime>> { requireBootstrapped(); return groups.map(::assignInternal).also { flush() } }

    private fun assignInternal(items: List<Anime>): List<Anime> {
        if (items.isEmpty()) return emptyList()
        val known = items.mapNotNull { linkFor(it.slug) }.firstOrNull()
        val external = items.mapNotNull { item -> item.anilistId?.let { links[externalKey(item)] } }.firstOrNull()
        val work = known?.workId ?: external?.workId ?: links[workKey(items.first())]?.workId ?: UUID.randomUUID().toString()
        val release = canonicalReleaseId(known?.releaseId ?: external?.releaseId ?: UUID.randomUUID().toString())
        val link = IdentityLink(work, release)
        var changed = false
        for (item in items) {
            val previous = linkFor(item.slug)
            if (previous != null && previous.releaseId != release) {
                releaseAliases[previous.releaseId] = release
                changed = true
            }
            if (links["provider:${item.slug}"] != link) { links["provider:${item.slug}"] = link; changed = true }
            if (item.anilistId != null && links[externalKey(item)] != link) { links[externalKey(item)] = link; changed = true }
        }
        if (links[workKey(items.first())] == null) { links[workKey(items.first())] = link; changed = true }
        if (changed) dirty = true
        return items.map { it.copy(workId = work, releaseId = release) }
    }

    private fun externalKey(item: Anime): String = "external:anilist:${item.anilistId}:${explicitFormat(item.title, item.metadata) ?: "?"}:${explicitSeason(item.title, item.slug) ?: 1}"

    private fun workKey(item: Anime): String {
        return "work:${seasonFamilyKey(item.title)}"
    }

    private fun flush() {
        if (!dirty) return
        dirty = false
        val db = database
        if (db != null) {
            val linkSnapshot = links.map { (key, link) -> IdentityLinkEntity(key, link.workId, link.releaseId) }
            val aliasSnapshot = releaseAliases.map { (from, to) -> IdentityAliasEntity(from, to) }
            val previous = pendingWrite
            pendingWrite = writeScope.async {
                // The snapshot is complete, so a later write can recover from a transient failure.
                previous?.let { runCatching { it.await() } }
                db.withTransaction {
                    db.appDao().putIdentityLinks(linkSnapshot)
                    db.appDao().putIdentityAliases(aliasSnapshot)
                }
            }
            return
        }
        val store = prefs ?: return
        val rows = JSONArray()
        links.forEach { (key, link) -> rows.put(JSONObject().put("key", key).put("work", link.workId).put("release", link.releaseId)) }
        val aliases = JSONArray()
        releaseAliases.forEach { (from, to) -> aliases.put(JSONObject().put("from", from).put("to", to)) }
        store.edit().putString(KEY, rows.toString()).putString(ALIASES_KEY, aliases.toString()).apply()
    }

    companion object {
        private const val KEY = "identity_links_v1"
        private const val ALIASES_KEY = "identity_aliases_v1"
    }
}

/** One place for match decisions; uncertain titles remain independent. */
class IdentityResolver(private val registry: IdentityRegistry) {
    internal fun matchExternalIds(items: List<Anime>, identities: List<CatalogIdentity>): List<Anime> = items.map { item ->
        val matches = identities.mapNotNull { identity ->
            identityScore(item.title, identity.format, identity.titles, explicitFormat(item.title, item.metadata))?.let { identity to it }
        }.sortedByDescending { it.second }
        val best = matches.firstOrNull()?.takeIf { matches.size == 1 || matches[0].second - matches[1].second >= .12 }?.first
        item.copy(anilistId = best?.id ?: item.anilistId, isAdult = best?.isAdult ?: item.isAdult)
    }

    fun canUseAsAlternative(first: Anime, second: Anime): Boolean =
        dev.aniclipt.app.data.canUseAsAlternative(first, second, first.anilistId, second.anilistId)

    fun group(items: List<Anime>): List<Anime> {
        val groups = mutableListOf<MutableList<Anime>>()
        items.forEach { item ->
            val group = groups.firstOrNull { entries -> entries.all { other ->
                canUseAsAlternative(item, other, item.anilistId, other.anilistId)
            } }
            if (group == null) groups.add(mutableListOf(item)) else group.add(item)
        }
        return registry.assignGroups(groups).map { identified ->
            val preferred = identified.firstOrNull { !it.title.contains("dublado", true) } ?: identified.first()
            preferred.copy(alternatives = identified.filterNot { it.slug == preferred.slug }, isAdult = aggregateAdultFlags(identified))
        }
    }

    fun identify(anime: Anime): Anime = registry.assign(listOf(anime)).first()

    fun identifyEach(items: List<Anime>): List<Anime> = registry.assignEach(items)

    fun identifyEpisodes(anime: Anime, episodes: List<Episode>): List<Episode> {
        val releaseId = registry.canonicalReleaseId(anime.releaseId.ifBlank { identify(anime).releaseId })
        val duplicates = episodes.groupingBy { it.number.trim().toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() ?: it.number }.eachCount()
        return episodes.map { episode ->
            val number = episode.number.trim().toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() ?: episode.number
            val key = "$releaseId:$number" + if ((duplicates[number] ?: 0) > 1) ":${episode.id}" else ""
            episode.copy(episodeId = UUID.nameUUIDFromBytes(key.toByteArray(StandardCharsets.UTF_8)).toString())
        }
    }
}
