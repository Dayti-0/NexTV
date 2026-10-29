package tv.nextv.core.model

/**
 * Identifiants d'accès à une source protégée.
 *
 * [toString] ne révèle jamais les valeurs : une instance peut finir dans un message
 * de journal ou une trace sans exposer le compte de l'utilisateur.
 */
class Credentials(val username: String, val password: String) {

    override fun equals(other: Any?): Boolean =
        other is Credentials && other.username == username && other.password == password

    override fun hashCode(): Int = 31 * username.hashCode() + password.hashCode()

    override fun toString(): String = "Credentials(***)"
}
