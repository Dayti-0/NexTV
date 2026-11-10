package tv.nextv.app

import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Dernier incident de l'application, montré dans les réglages pour pouvoir le signaler (adresses et
 * identifiants masqués) :
 * - plantage : enregistré au moment où il se produit, avec sa trace ;
 * - erreur d'une tâche de fond, qui n'a pas fermé l'application ;
 * - fermeture par le système (mémoire insuffisante, application qui ne répond plus, bibliothèque native),
 *   que l'application ne peut pas voir elle-même : constatée au lancement suivant ([recordSystemExit]).
 *
 * Pour ces fermetures, l'écran affiché et la mémoire sont notés au fil de l'utilisation dans un fichier
 * « session » ([foreground], [screen], [memoryPressure]), retiré dès que l'application n'est plus à l'écran :
 * s'il est encore là au lancement suivant, l'application a été fermée pendant qu'on s'en servait.
 *
 * @param memory relevé de la mémoire (« 120 / 384 Mo »), ajouté au fichier de session à chaque écriture.
 */
class CrashLog(
    private val file: File,
    private val version: String = BuildConfig.VERSION_NAME,
    private val clock: () -> Long = System::currentTimeMillis,
    private val memory: () -> String? = { null },
    private val io: ExecutorService = Executors.newSingleThreadExecutor { task -> Thread(task, "nextv-session").apply { isDaemon = true } },
    private val redact: (String) -> String,
) {
    constructor(file: File, redact: (String) -> String) : this(file = file, version = BuildConfig.VERSION_NAME, redact = redact)

    enum class Kind {
        /** L'application s'est fermée sur une erreur. */
        CRASH,

        /** Erreur d'une tâche de fond : l'application est restée ouverte. */
        BACKGROUND,

        /** Fermée par le système (mémoire, blocage, bibliothèque native). */
        SYSTEM,
    }

    /** @property details trace de la pile (début, causes et fin), limitée à [MAX_LINES] lignes. */
    data class Entry(val atMillis: Long, val version: String, val summary: String, val details: String, val kind: Kind = Kind.CRASH)

    /** Session en cours au moment d'une fermeture : où l'application en était, d'après son fichier de session. */
    data class Session(
        val atMillis: Long,
        val version: String,
        /** Démarrages de l'appareil : différent au lancement suivant, l'appareil a été éteint (pas un incident). */
        val boot: Int,
        val screen: String?,
        val memory: String?,
        /** Dernier signal de mémoire basse du système (« critique, il y a 4 s »), s'il y en a eu. */
        val pressure: String?,
    )

    private val sessionFile = File(file.parentFile, SESSION_FILE)

    @Volatile
    private var route: String? = null

    @Volatile
    private var section: String? = null

    @Volatile
    private var boot = 0

    @Volatile
    private var visible = false

    @Volatile
    private var pressure: Pair<String, Long>? = null

    /** Enregistre les plantages, puis laisse le système fermer l'application comme d'habitude. */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            record(error)
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * @param fatal faux : erreur d'une tâche de fond, l'application reste ouverte. Elle ne remplace pas un
     * plantage déjà enregistré (c'est lui qu'il faut signaler d'abord).
     */
    fun record(error: Throwable, fatal: Boolean = true) {
        runCatching {
            if (!fatal && last()?.kind == Kind.CRASH) return
            val trace = runCatching { redact(error.stackTraceToString()) }.getOrElse { error.javaClass.name }
            write(clock(), if (fatal) Kind.CRASH else Kind.BACKGROUND, compact(trace, MAX_LINES))
        }
    }

    /**
     * Fermeture par le système constatée au lancement suivant. [summary] dit pourquoi (une ligne), [details]
     * ce qu'on en sait (mémoire, écran affiché, pile du fil principal pour un blocage).
     */
    fun recordSystemExit(atMillis: Long, summary: String, details: List<String>) {
        runCatching {
            val text = (listOf(summary) + details).joinToString("\n") { redact(it) }
            write(atMillis, Kind.SYSTEM, text.lineSequence().take(MAX_LINES).joinToString("\n"))
        }
    }

    private fun write(atMillis: Long, kind: Kind, details: String) {
        file.writeText("$atMillis\n$version\t${kind.name}\n$details")
    }

    fun last(): Entry? = runCatching {
        if (!file.exists()) return null
        val lines = file.readLines()
        val at = lines.getOrNull(0)?.toLongOrNull() ?: return null
        // « version », ou « version<tab>type » depuis que les fermetures par le système sont notées.
        val header = lines.getOrNull(1).orEmpty().split('\t')
        val kind = Kind.entries.firstOrNull { it.name == header.getOrNull(1) } ?: Kind.CRASH
        val details = lines.drop(2)
        Entry(at, header.first(), summary(details), details.joinToString("\n"), kind)
    }.getOrNull()

    fun clear() {
        file.delete()
    }

    // --- Session en cours ---------------------------------------------------------------------------

    /** Application affichée ([boot] : nombre de démarrages de l'appareil) : la session est notée à partir de maintenant. */
    fun foreground(boot: Int) {
        this.boot = boot
        visible = true
        saveSession()
    }

    /** Application quittée ou en veille : une fermeture par le système est alors normale, la session est oubliée. */
    fun background() {
        visible = false
        io.execute { runCatching { sessionFile.delete() } }
    }

    /** Écran affiché (modèle de la route de navigation, sans numéro de chaîne ni titre). */
    fun screen(route: String?) {
        if (route == this.route) return
        this.route = route
        saveSession()
    }

    /** Section de l'accueil affichée (chaînes, films, séries, guide, foot). */
    fun section(name: String?) {
        if (name == section) return
        section = name
        saveSession()
    }

    /** Le système manque de mémoire ([level] : « basse », « critique »…). */
    fun memoryPressure(level: String) {
        pressure = level to clock()
        saveSession()
    }

    private fun saveSession() {
        if (!visible) return
        val now = clock()
        val place = route?.let { r -> if (r.startsWith(HOME_ROUTE) && section != null) "$r ($section)" else r }
        val text = buildString {
            append("at=").append(now).append('\n')
            append("version=").append(version).append('\n')
            append("boot=").append(boot).append('\n')
            place?.let { append("screen=").append(it).append('\n') }
            runCatching { memory() }.getOrNull()?.let { append("memory=").append(it).append('\n') }
            pressure?.let { (level, at) -> append("pressure=").append(level).append(", ").append((now - at) / 1_000).append(" s avant").append('\n') }
        }
        io.execute {
            // Écran quitté entre-temps : le fichier ne doit pas réapparaître.
            if (visible) runCatching { sessionFile.writeText(text) }
        }
    }

    /**
     * Session restée ouverte au lancement précédent (`null` : il s'était terminé normalement), puis oubliée.
     * À appeler une fois, au lancement, avant [foreground].
     */
    fun takePreviousSession(): Session? = runCatching {
        if (!sessionFile.isFile) return null
        val values = sessionFile.readLines().mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
        }.toMap()
        sessionFile.delete()
        Session(
            atMillis = values["at"]?.toLongOrNull() ?: return null,
            version = values["version"].orEmpty(),
            boot = values["boot"]?.toIntOrNull() ?: 0,
            screen = values["screen"],
            memory = values["memory"],
            pressure = values["pressure"],
        )
    }.getOrNull()

    companion object {
        const val MAX_LINES = 40

        private const val SESSION_FILE = "session.txt"
        private const val HOME_ROUTE = "home"

        /** Lignes gardées en tête de la trace, et pour la cause première (la dernière « Caused by »). */
        private const val HEAD_LINES = 12
        private const val ROOT_LINES = 14

        /** Lignes gardées pour chaque cause intermédiaire. */
        private const val MIDDLE_LINES = 4

        /** Erreur et premier endroit du code de l'application concerné. */
        fun summary(lines: List<String>): String {
            val error = lines.firstOrNull().orEmpty()
            val cause = lines.lastOrNull { it.startsWith("Caused by:") }?.removePrefix("Caused by: ")
            val place = lines.firstOrNull { it.trim().startsWith("at tv.nextv.") }?.trim()?.removePrefix("at ")
            return listOfNotNull(cause ?: error, place).joinToString("\n")
        }

        /**
         * Trace réduite à [maxLines] lignes sans perdre l'essentiel : son début, puis chaque cause (« Caused by »)
         * avec ses premières lignes, la cause première — tout en bas d'une trace, là où est la vraie raison — étant
         * la mieux servie. Dans chaque partie, la première ligne du code de l'application est gardée même si elle
         * vient plus loin. Les lignes sautées sont signalées par « … ».
         */
        fun compact(trace: String, maxLines: Int = MAX_LINES): String {
            val lines = trace.lines().filter { it.isNotBlank() }
            if (lines.size <= maxLines) return lines.joinToString("\n")
            // Parties de la trace : l'erreur, puis chacune de ses causes.
            val sections = ArrayList<List<String>>()
            var current = ArrayList<String>()
            for (line in lines) {
                if (line.startsWith("Caused by:") && current.isNotEmpty()) {
                    sections += current
                    current = ArrayList()
                }
                current += line
            }
            sections += current
            val budgets = sections.indices.map { index ->
                when {
                    // La ligne du code de l'application et « … » peuvent s'y ajouter.
                    sections.size == 1 -> maxLines - 2
                    index == sections.lastIndex -> ROOT_LINES
                    index == 0 -> HEAD_LINES
                    else -> MIDDLE_LINES
                }
            }
            val out = ArrayList<String>()
            for ((index, section) in sections.withIndex()) {
                val kept = section.take(budgets[index]).toMutableList()
                if (kept.size < section.size) {
                    // Première ligne du code de l'application, si elle n'est pas déjà dans ce qui est gardé.
                    val own = section.drop(kept.size).firstOrNull { it.trim().startsWith("at tv.nextv.") }
                    if (kept.none { it.trim().startsWith("at tv.nextv.") } && own != null) kept += own
                    kept += "\t…"
                }
                out += kept
            }
            if (out.size <= maxLines) return out.joinToString("\n")
            // Trop de causes : le début et la fin (la cause première) passent avant celles du milieu.
            val tail = out.takeLast(ROOT_LINES + 2)
            return (out.take(maxLines - tail.size - 1) + "\t…" + tail).joinToString("\n")
        }
    }
}
