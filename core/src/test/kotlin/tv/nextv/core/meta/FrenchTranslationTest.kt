package tv.nextv.core.meta

import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import tv.nextv.core.net.HttpClientProvider

/** Descriptions du guide dans une autre langue (chaînes flamandes, anglaises…) : traduites en français. */
class FrenchTranslationTest {

    private val server = MockWebServer()
    private var requests = 0
    private var google = true

    /** Description de VTM (Belgique) pour Turquie – Belgique. */
    private val dutch = "Liveverslag van de eerste helft van Turkijë - België, de vijfde wedstrijd van de Rode Duivels in groep 1 " +
        "van Divisie A van de UEFA Nations League, vanuit het Gürsel Akselstadion in Izmir, Turkije. Presentatie: Maarten Breckx."
    private val french = "Reportage en direct de la première mi-temps de Turquie - Belgique, le cinquième match des Diables rouges."

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests++
                val url = request.url
                return when {
                    url.encodedPath.endsWith("/translate_a/single") && google -> {
                        assertEquals("auto", url.queryParameter("sl"))
                        assertEquals("fr", url.queryParameter("tl"))
                        json("""[[["$french","$dutch",null,null,10]],null,"nl"]""")
                    }
                    url.encodedPath.endsWith("/get") -> {
                        assertEquals("nl|fr", url.queryParameter("langpair"))
                        json("""{"responseData":{"translatedText":"Reportage en direct."},"responseStatus":200}""")
                    }
                    else -> MockResponse.Builder().code(429).build()
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun json(body: String) = MockResponse.Builder().code(200).body(body).build()

    private val translator by lazy {
        Translator(HttpClientProvider(), googleUrl = server.url("/translate_a/single"), myMemoryUrl = server.url("/get"))
    }

    @Test
    fun `languages are guessed from their small words`() {
        assertEquals("nl", Languages.guess(dutch))
        assertEquals("fr", Languages.guess("1ère période du match de la Journée 5 du Championnat de France de Ligue 1 opposant AS Monaco et RC Lens"))
        assertEquals("fr", Languages.guess("Les Diables rouges affrontent la Turquie à Izmir, pour le cinquième match de l'année."))
        assertEquals("en", Languages.guess("Live coverage of the Nations League match between Turkey and Belgium from Izmir."))
        assertEquals("de", Languages.guess("Live-Übertragung des Spiels der Nations League zwischen der Türkei und Belgien aus Izmir."))
        // Titre ou noms seuls : rien à deviner.
        assertNull(Languages.guess("Football"))
        assertNull(Languages.guess("Turquie - Belgique"))
    }

    @Test
    fun `foreign descriptions are translated, french ones are left alone`() = runBlocking {
        assertEquals(french, translator.toFrench(dutch))
        // Déjà traduite : gardée, sans nouvelle demande.
        val before = requests
        assertEquals(french, translator.toFrench(dutch))
        assertEquals(before, requests)
        // Française : aucune demande.
        assertNull(translator.toFrench("Les Diables rouges affrontent la Turquie à Izmir, pour le cinquième match de l'année."))
        assertEquals(before, requests)
    }

    @Test
    fun `without google the guessed language goes to mymemory`() = runBlocking {
        google = false
        assertEquals("Reportage en direct.", translator.toFrench(dutch))
    }

    @Test
    fun `google finding french means nothing to translate`() {
        assertEquals("fr", Translator.detectedLanguage("""[[["Bonjour","Bonjour",null,null,10]],null,"fr"]"""))
        assertNull(Translator.detectedLanguage("<html>"))
    }
}
