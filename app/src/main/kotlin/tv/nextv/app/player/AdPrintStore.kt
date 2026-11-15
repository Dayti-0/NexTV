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
class AdPrintStore(private val file: File, private val scope: CoroutineScope, maxPrints: Int) {

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

    private companion object {
        const val MAX_BYTES = 16L * 1024 * 1024
    }
}
