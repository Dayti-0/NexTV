package tv.nextv.core.sync

/** Valeur d'une clé du document partagé, ou son effacement ([value] nul), datée de son dernier changement. */
data class SyncEntry(val value: String?, val time: Long)

/**
 * État d'une télé à un moment donné : chaque élément synchronisé sous sa clé ([SyncKeys]), et les portées (une par
 * fournisseur de playlist) présentes sur la télé.
 */
data class SyncSnapshot(val values: Map<String, String> = emptyMap(), val scopes: Set<String> = emptySet())

/**
 * Document partagé par les télés reliées : chaque clé (favori, réglage, « Déjà vu »…) garde la dernière valeur
 * écrite par l'une d'elles, ou son effacement. Chaque télé garde le document complet et le dépose tel quel : fusionner
 * deux documents donne le même résultat dans n'importe quel ordre, et une télé éteinte rattrape tout en se rallumant.
 */
class SyncDocument(val entries: Map<String, SyncEntry> = emptyMap()) {

    /** Valeurs en vigueur (sans les effacements). */
    fun values(): Map<String, String> = buildMap { this@SyncDocument.entries.forEach { (key, entry) -> entry.value?.let { put(key, it) } } }

    /** Pour chaque clé, le changement le plus récent des deux documents. */
    fun merge(other: SyncDocument): SyncDocument {
        if (other.entries.isEmpty()) return this
        val merged = HashMap(entries)
        for ((key, theirs) in other.entries) {
            val mine = merged[key]
            if (mine == null || wins(theirs, mine)) merged[key] = theirs
        }
        return SyncDocument(merged)
    }

    /**
     * Ajoute au document les changements faits sur cette télé depuis le dernier passage ([base] : état laissé par le
     * passage précédent, [current] : état actuel), datés de [now] (ou juste après le changement connu le plus récent de
     * la clé, si l'horloge d'une autre télé avance).
     *
     * Une définition de playlist n'est jamais effacée (une télé qui retire la sienne ne la retire pas aux autres). Une
     * clé rattachée à une playlist ([SyncKeys.scopeOf]) n'est suivie que si la playlist est sur la télé ; son
     * effacement seulement si elle y était déjà au passage précédent (une playlist retirée n'efface pas ses favoris
     * des autres télés).
     *
     * @param joining première synchronisation d'une télé qui vient de rejoindre les autres : ce qu'elle a déjà est
     * ajouté sans rien remplacer (les réglages des télés déjà reliées l'emportent sur les siens).
     */
    fun withLocalChanges(base: SyncSnapshot, current: SyncSnapshot, now: Long, joining: Boolean = false): SyncDocument {
        val updated = HashMap(entries)
        var changed = false
        for (key in base.values.keys + current.values.keys) {
            val scope = SyncKeys.scopeOf(key)
            if (scope != null && scope !in current.scopes) continue
            val before = base.values[key]
            val after = current.values[key]
            val known = updated[key]
            when {
                after != null -> {
                    if (known?.value == after) continue
                    // Inchangé depuis le passage précédent : seulement si le document n'en sait rien (document perdu).
                    if (before == after && known != null) continue
                    val time = if (joining || (before == after && known == null)) JOIN_TIME else maxOf(now, (known?.time ?: 0) + 1)
                    if (known != null && time <= known.time) continue
                    updated[key] = SyncEntry(after, time)
                    changed = true
                }
                before != null && !joining && !SyncKeys.isPlaylist(key) -> {
                    if (scope != null && scope !in base.scopes) continue
                    if (known != null && known.value == null) continue
                    updated[key] = SyncEntry(null, maxOf(now, (known?.time ?: 0) + 1))
                    changed = true
                }
            }
        }
        return if (changed) SyncDocument(updated) else this
    }

    /** Sans les effacements de plus de [keepMillis] (une télé restée éteinte plus longtemps peut faire revenir un élément). */
    fun pruned(now: Long, keepMillis: Long = TOMBSTONE_MILLIS): SyncDocument {
        val limit = now - keepMillis
        if (entries.values.none { it.value == null && it.time < limit }) return this
        return SyncDocument(entries.filterValues { it.value != null || it.time >= limit })
    }

    override fun equals(other: Any?): Boolean = other is SyncDocument && other.entries == entries

    override fun hashCode(): Int = entries.hashCode()

    companion object {
        /** Date des éléments qu'une télé avait avant de rejoindre les autres : n'importe quel changement l'emporte. */
        const val JOIN_TIME = 1L

        /** Effacements gardés 90 jours. */
        const val TOMBSTONE_MILLIS = 90L * 24 * 60 * 60_000L

        /** Le plus récent l'emporte ; à égalité, toujours le même des deux, quel que soit l'ordre de la fusion. */
        internal fun wins(candidate: SyncEntry, current: SyncEntry): Boolean = when {
            candidate.time != current.time -> candidate.time > current.time
            candidate.value == current.value -> false
            current.value == null -> true
            candidate.value == null -> false
            else -> candidate.value > current.value
        }
    }
}

/**
 * Clés du document partagé. Les éléments d'une playlist (favoris, « Déjà vu », reprises, catégories masquées) sont
 * rangés sous la portée de leur fournisseur ([scope], son adresse sans identifiants) : deux télés qui ont chacune leur
 * compte chez le même fournisseur partagent leurs favoris.
 */
object SyncKeys {
    private const val SEP = '\u001F'

    const val SETTING = "s"
    const val SERVICE_KEY = "k"
    const val PLAYLIST = "p"
    const val FAVORITE = "f"
    const val HIDDEN = "h"
    const val WATCHED = "w"
    const val RESUME = "r"

    private val SCOPED = setOf(PLAYLIST, FAVORITE, HIDDEN, WATCHED, RESUME)

    fun setting(name: String) = "$SETTING$SEP$name"
    fun serviceKey(name: String) = "$SERVICE_KEY$SEP$name"
    fun playlist(scope: String) = "$PLAYLIST$SEP$scope"
    fun favorite(scope: String, kind: String, itemKey: String) = "$FAVORITE$SEP$scope$SEP$kind$SEP$itemKey"
    fun hidden(scope: String, kind: String, title: String) = "$HIDDEN$SEP$scope$SEP$kind$SEP$title"
    fun watched(scope: String, itemKey: String) = "$WATCHED$SEP$scope$SEP$itemKey"
    fun resume(scope: String, itemKey: String) = "$RESUME$SEP$scope$SEP$itemKey"

    /** Parties d'une clé : type, puis le reste (portée, type de contenu, clé de l'élément…). */
    fun parts(key: String): List<String> = key.split(SEP)

    fun isPlaylist(key: String): Boolean = key.startsWith("$PLAYLIST$SEP")

    /**
     * Portée d'une clé d'élément de playlist ; `null` pour un réglage. Les définitions de playlist ([PLAYLIST]) n'ont
     * pas de portée suivie : elles ne sont jamais effacées (une télé qui retire sa playlist ne la retire pas aux autres).
     */
    fun scopeOf(key: String): String? {
        val parts = parts(key)
        return if (parts.size >= 3 && parts[0] in SCOPED) parts[1] else null
    }

    /**
     * Portée d'une adresse de playlist : son serveur, en minuscules, sans « www. », sans identifiants ni chemin.
     * `null` si l'adresse n'a pas de serveur.
     */
    fun scopeOfUrl(url: String): String? {
        val afterScheme = url.trim().substringAfter("://", "")
        if (afterScheme.isEmpty()) return null
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
        // Port retiré : les serveurs de secours d'un fournisseur ne diffèrent souvent que par lui.
        val host = authority.substringBefore(':').lowercase().removePrefix("www.").trimEnd('.')
        return host.takeIf { it.isNotEmpty() }
    }
}
