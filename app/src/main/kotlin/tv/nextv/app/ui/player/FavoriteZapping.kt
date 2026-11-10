package tv.nextv.app.ui.player

import tv.nextv.app.data.db.ChannelListRow

/** Zapping (haut/bas, chaîne +/-) dans la liste des favoris, dans l'ordre choisi, en boucle. */
internal object FavoriteZapping {

    /**
     * Favorite suivante ([direction] > 0) ou précédente de la chaîne [itemKey] dans [favorites], en sautant les
     * chaînes illisibles. `null` si la chaîne n'est pas (ou plus) dans les favoris.
     */
    fun next(favorites: List<ChannelListRow>, itemKey: String, direction: Int): ChannelListRow? {
        val start = favorites.indexOfFirst { it.itemKey == itemKey }
        if (start < 0) return null
        val step = if (direction > 0) 1 else -1
        for (offset in 1..favorites.size) {
            val candidate = favorites[Math.floorMod(start + step * offset, favorites.size)]
            if (candidate.streamSupport != UNSUPPORTED) return candidate
        }
        return favorites[start]
    }

    private const val UNSUPPORTED = "UNSUPPORTED"
}
