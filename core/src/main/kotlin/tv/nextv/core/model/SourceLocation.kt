package tv.nextv.core.model

/**
 * Emplacement d'un contenu (playlist, guide EPG…).
 *
 * Chaque variante est ouverte par un [tv.nextv.core.io.ContentOpener] dédié, ce qui
 * permet d'ajouter d'autres origines (FTP, SMB, stockage cloud…) sans toucher aux
 * chargeurs ni aux analyseurs.
 */
sealed interface SourceLocation {

    /** Ressource distante HTTP ou HTTPS. */
    data class Remote(val url: String) : SourceLocation

    /**
     * Document local choisi via le sélecteur de fichiers du système.
     *
     * @property uri URI opaque fourni par le système (ex. `content://…`).
     * @property displayName nom lisible du fichier, s'il est connu.
     */
    data class LocalDocument(val uri: String, val displayName: String? = null) : SourceLocation
}
