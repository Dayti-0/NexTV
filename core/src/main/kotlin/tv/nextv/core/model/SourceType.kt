package tv.nextv.core.model

/**
 * Type de source de chaînes.
 *
 * L'identifiant [id] est celui qui est persisté : il ne doit jamais changer.
 * Ajouter un type (Xtream Codes, Stalker…) revient à déclarer une valeur ici et à
 * enregistrer le [tv.nextv.core.source.PlaylistLoader] correspondant.
 */
enum class SourceType(val id: String) {
    M3U("m3u");

    companion object {
        fun fromId(id: String): SourceType? = entries.firstOrNull { it.id == id }
    }
}
