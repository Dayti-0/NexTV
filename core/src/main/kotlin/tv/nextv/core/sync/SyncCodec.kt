package tv.nextv.core.sync

import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Télé qui a déposé un document : identifiant tiré au hasard, nom affiché, version de l'appli, date du dépôt. */
data class SyncDevice(val id: String, val name: String, val app: String = "", val at: Long = 0)

/** Document déposé par une télé, avec la télé qui l'a déposé. */
data class SyncFile(val device: SyncDevice, val document: SyncDocument)

/**
 * Groupe de télés reliées : page filebin commune ([bin], tirée au hasard) et clé de chiffrement des documents
 * ([key], 32 octets). Sans la clé, la page ne montre que des octets illisibles.
 */
class SyncGroup(val bin: String, val key: ByteArray) {
    fun encode(): String = "$bin~${key.toByteString().hex()}"

    override fun equals(other: Any?): Boolean = other is SyncGroup && other.bin == bin && other.key.contentEquals(key)

    override fun hashCode(): Int = bin.hashCode()

    companion object {
        fun decode(text: String): SyncGroup? {
            val bin = text.substringBefore('~', "").takeIf { it.startsWith(BIN_PREFIX) } ?: return null
            val key = runCatching { text.substringAfter('~').decodeHex().toByteArray() }.getOrNull() ?: return null
            return if (key.size == KEY_BYTES) SyncGroup(bin, key) else null
        }

        fun create(random: SecureRandom = SecureRandom()): SyncGroup =
            SyncGroup(BIN_PREFIX + randomText(random, 24), ByteArray(KEY_BYTES).also(random::nextBytes))

        const val BIN_PREFIX = "nixtv-sync-"
        const val KEY_BYTES = 32
    }
}

/** Texte de [length] lettres et chiffres sans ambiguïté (ni « l », ni « o », ni « 0 », ni « 1 »). */
internal fun randomText(random: SecureRandom, length: Int): String {
    val letters = "abcdefghijkmnpqrstuvwxyz23456789"
    return (1..length).map { letters[random.nextInt(letters.length)] }.joinToString("")
}

class SyncFormatException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Fichiers déposés sur la page commune : JSON compressé puis chiffré (AES-GCM, qui refuse aussi un fichier modifié).
 * Format : « NXS1 », 12 octets d'IV, puis le chiffré.
 */
object SyncCodec {
    private val MAGIC = "NXS1".toByteArray(Charsets.US_ASCII)
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    fun encodeFile(file: SyncFile, key: ByteArray): ByteArray = encrypt(documentJson(file).toString().toByteArray(Charsets.UTF_8), key)

    /** @throws SyncFormatException fichier illisible (autre clé, abîmé, pas un document de l'appli). */
    fun decodeFile(bytes: ByteArray, key: ByteArray): SyncFile = parseFile(String(decrypt(bytes, key), Charsets.UTF_8))

    /** Document seul, en clair (état gardé sur l'appareil, lui-même chiffré par l'appareil). */
    fun documentJson(file: SyncFile): JSONObject {
        val entries = JSONObject()
        for ((key, entry) in file.document.entries) {
            entries.put(key, JSONArray().put(entry.time).put(entry.value ?: JSONObject.NULL))
        }
        return JSONObject()
            .put("v", 1)
            .put("device", JSONObject().put("id", file.device.id).put("name", file.device.name).put("app", file.device.app).put("at", file.device.at))
            .put("e", entries)
    }

    /** @throws SyncFormatException */
    fun parseFile(text: String): SyncFile = try {
        val root = JSONObject(text)
        val device = root.getJSONObject("device")
        SyncFile(
            SyncDevice(device.getString("id"), device.optString("name"), device.optString("app"), device.optLong("at")),
            parseEntries(root.optJSONObject("e") ?: JSONObject()),
        )
    } catch (e: JSONException) {
        throw SyncFormatException("Document illisible", e)
    }

    private fun parseEntries(json: JSONObject): SyncDocument {
        val entries = HashMap<String, SyncEntry>(json.length() * 2)
        for (key in json.keys()) {
            val pair = json.optJSONArray(key) ?: continue
            val time = pair.optLong(0, -1)
            if (time < 0) continue
            val value = if (pair.isNull(1)) null else pair.optString(1)
            entries[key] = SyncEntry(value, time)
        }
        return SyncDocument(entries)
    }

    fun encrypt(plain: ByteArray, key: ByteArray): ByteArray {
        val packed = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(plain) } }.toByteArray()
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        return MAGIC + iv + cipher.doFinal(packed)
    }

    /** @throws SyncFormatException */
    fun decrypt(bytes: ByteArray, key: ByteArray): ByteArray {
        if (bytes.size <= MAGIC.size + IV_BYTES || !bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw SyncFormatException("Pas un document de l'appli")
        }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, bytes, MAGIC.size, IV_BYTES))
            val packed = cipher.doFinal(bytes, MAGIC.size + IV_BYTES, bytes.size - MAGIC.size - IV_BYTES)
            GZIPInputStream(ByteArrayInputStream(packed)).use { it.readBytes() }
        } catch (e: GeneralSecurityException) {
            throw SyncFormatException("Autre clé ou fichier abîmé", e)
        } catch (e: IOException) {
            throw SyncFormatException("Fichier abîmé", e)
        }
    }
}

/**
 * Liaison des télés par un code. Code du groupe ([codeOf], [LINK_DIGITS] chiffres) : tiré de la clé du groupe, donc
 * le même sur toutes ses télés, et permanent. L'invitation (le groupe, chiffré avec une clé tirée du code) est sur une
 * page filebin dont le nom vient aussi du code ([inviteBin]) ; chaque télé du groupe la redépose au moins tous les
 * 3 jours (filebin garde une page 7 jours) : une télé qui saisit le code rejoint le groupe même si les autres sont
 * éteintes. Les codes à [CODE_DIGITS] chiffres servent à la configuration à distance ([RemoteSetupLink]).
 */
object SyncPairing {
    const val CODE_DIGITS = 6
    const val LINK_DIGITS = 8
    const val INVITE_FILE = "invitation.bin"

    fun newCode(random: SecureRandom = SecureRandom()): String =
        (1..CODE_DIGITS).map { '0' + random.nextInt(10) }.joinToString("")

    /** Code permanent du groupe, le même sur toutes ses télés. */
    fun codeOf(group: SyncGroup): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(group.key, "HmacSHA256"))
        val bytes = mac.doFinal("nixtv-link-code".toByteArray(Charsets.UTF_8))
        val number = bytes.take(8).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) } and Long.MAX_VALUE
        return (number % 100_000_000L).toString().padStart(LINK_DIGITS, '0')
    }

    /**
     * Code saisi (espaces et tirets permis) ramené à ses chiffres ; `null` s'il n'a pas [digits] chiffres.
     */
    fun normalize(input: String, digits: Int = CODE_DIGITS): String? =
        input.filter { it.isDigit() }.takeIf { it.length == digits && input.all { c -> c.isDigit() || c == ' ' || c == '-' } }

    /** « 482 913 », « 4829 1306 » */
    fun display(code: String): String = when (code.length) {
        CODE_DIGITS -> code.substring(0, 3) + " " + code.substring(3)
        LINK_DIGITS -> code.substring(0, 4) + " " + code.substring(4)
        else -> code
    }

    /** Page de l'invitation ; [generation] > 0 quand filebin a fermé les précédentes (7 jours sans dépôt). */
    fun inviteBin(code: String, generation: Int = 0): String {
        val base = "nixtv-link-" + "nixtv-link|$code".encodeUtf8().sha256().hex().take(20)
        return if (generation == 0) base else "$base-$generation"
    }

    fun inviteKey(code: String): ByteArray = "nixtv-link-key|$code".encodeUtf8().sha256().toByteArray()

    /** Invitation lue : le groupe, sa page en usage, et le nom de la télé qui invite. */
    data class Invite(val group: SyncGroup, val generation: Int, val from: String)

    fun encodeInvite(group: SyncGroup, from: String, code: String, generation: Int = 0): ByteArray = SyncCodec.encrypt(
        JSONObject().put("group", group.encode()).put("generation", generation).put("from", from).toString().toByteArray(Charsets.UTF_8),
        inviteKey(code),
    )

    /** `null` si l'invitation est illisible avec ce code. */
    fun decodeInvite(bytes: ByteArray, code: String): Invite? = try {
        val json = JSONObject(String(SyncCodec.decrypt(bytes, inviteKey(code)), Charsets.UTF_8))
        SyncGroup.decode(json.getString("group"))?.let { Invite(it, json.optInt("generation"), json.optString("from")) }
    } catch (e: SyncFormatException) {
        null
    } catch (e: JSONException) {
        null
    }
}
