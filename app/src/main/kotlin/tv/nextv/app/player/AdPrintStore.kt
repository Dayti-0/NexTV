package tv.nextv.app.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import tv.nextv.core.audio.AdPrintLibrary
import java.io.File
import java.io.IOException

/**
 * Pubs déjà entendues ([AdPrintLibrary]), communes à toutes les chaînes et gardées sur l'appareil (un seul fichier,
 * quelques mégaoctets au plus). Lues à la première lecture qui en a besoin ; l'apprentissage, l'oubli et l'écriture se
 * font l'un après l'autre sur un fil d'arrière-plan (jamais sur celui du son).
 */
class AdPrintStore(
    private val file: File,
    private val scope: CoroutineScope,
    maxPrints: Int,
    /** Pubs fournies avec l'appli ([SEED_VERSION]), ajoutées une fois à celles de l'appareil ; `null` : aucune. */
    private val seed: (() -> java.io.InputStream?)? = null,
) {

    val library = AdPrintLibrary(maxPrints)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val worker = Dispatchers.IO.limitedParallelism(1)

    @Volatile
    private var loaded = false

    /** Relit la sauvegarde (une seule fois). */
    fun load() {
        if (loaded) return
        loaded = true
        scope.launch(worker) {
            try {
                if (file.isFile && file.length() in 1..MAX_BYTES) file.inputStream().buffered().use { library.restore(it) }
            } catch (e: IOException) {
                // Fichier illisible : les pubs seront réapprises.
            }
            addSeed()
        }
    }

    /** Son d'une page de pubs à apprendre. Appelé depuis le fil du son : rend la main tout de suite. */
    fun learn(prints: IntArray, loud: BooleanArray, confirmed: Boolean) {
        scope.launch(worker) {
            if (library.learn(prints, loud, confirmed, now = System.currentTimeMillis() / 60_000) > 0) save()
        }
    }

    /** Morceau à l'essai reconnu sous le logo de la chaîne : c'était le programme. */
    fun forget(chunk: AdPrintLibrary.Chunk) {
        scope.launch(worker) {
            library.forget(chunk)
            save()
        }
    }

    /**
     * Pubs relevées sur les enregistrements d'analyse (toutes chaînes), fournies avec l'appli : reconnues dès leur
     * premier passage sur cet appareil, en 2 à 3 s (rejeu du 26/11/2026 sur deux nuits jamais entendues : pubs baissées
     * 82 → 85 %, délai moyen 21 → 16 s). Ajoutées une fois par version de la bibliothèque, à l'essai (oubliées si elles
     * repassent sous le logo d'une chaîne), et datées d'avant tout : celles apprises ici restent prioritaires.
     */
    private fun addSeed() {
        val open = seed ?: return
        val marker = File(file.parentFile, "seed.version")
        val done = runCatching { marker.readText().trim().toInt() }.getOrDefault(0)
        if (done >= SEED_VERSION) return
        try {
            val stream = open() ?: return
            val pack = AdPrintLibrary(Int.MAX_VALUE)
            val read = stream.buffered().use { pack.restore(it) }
            if (!read) return
            if (library.merge(pack) > 0) save()
            file.parentFile?.mkdirs()
            marker.writeText(SEED_VERSION.toString())
        } catch (e: IOException) {
            // Bibliothèque illisible : l'appareil apprend seul, comme avant.
        }
    }

    private fun save() {
        try {
            file.parentFile?.mkdirs()
            val temporary = File(file.path + ".tmp")
            temporary.writeBytes(library.encode())
            if (!temporary.renameTo(file)) temporary.delete()
        } catch (e: IOException) {
            // Disque plein : la mémoire des pubs reste en place jusqu'à la fermeture.
        }
    }

    companion object {
        private const val MAX_BYTES = 16L * 1024 * 1024

        /** Version de la bibliothèque fournie (`assets/ad_prints_seed.bin`) : à augmenter à chaque nouvelle bibliothèque. */
        const val SEED_VERSION = 1

        /** Fichier de la bibliothèque fournie, dans les assets de l'appli. */
        const val SEED_ASSET = "ad_prints_seed.bin"
    }
}
