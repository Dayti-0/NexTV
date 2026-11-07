package tv.nextv.core.epg

import org.junit.Assert.assertEquals
import org.junit.Test
import org.kxml2.io.KXmlParser

/** Mise en forme HTML laissée dans les textes du guide xmltvfr.fr. */
class GuideTextTest {

    @Test
    fun `html tags are removed`() {
        assertEquals("Inédit", GuideText.clean("""<span style="color:#FF2F79">Inédit</span>"""))
        assertEquals("From Lumpinee Boxing Stadium.\n(EN DIRECT)", GuideText.clean("From Lumpinee Boxing Stadium. <p>(EN DIRECT)"))
        assertEquals("Jean, Isabelle and their son", GuideText.clean("<p><span><span><span>Jean, Isabelle and their son</span></span></span></p>"))
        assertEquals("Billy grew up.\nHe wants adventures.", GuideText.clean("Billy grew up.<br>He wants adventures.<br />"))
    }

    @Test
    fun `entities escaped twice are decoded`() {
        assertEquals("L'équipe – le match", GuideText.clean("L&#039;équipe &#150; le match"))
        assertEquals("Tom & Jerry \"en direct\"", GuideText.clean("Tom &amp; Jerry&nbsp;&quot;en direct&quot;"))
        assertEquals("C'est l'heure", GuideText.clean("C&apos;est l&#39;heure"))
        assertEquals("Inédit", GuideText.clean("&lt;span style=&quot;color:#FF2F79&quot;&gt;Inédit&lt;/span&gt;"))
    }

    @Test
    fun `ordinary text is left alone`() {
        assertEquals("Paris-SG / Marseille", GuideText.clean("Paris-SG / Marseille"))
        // Pas une balise : le bruit d'un moustique dans un résumé.
        assertEquals("quand soudain <BZZZZZZ...> ils entendent", GuideText.clean("quand soudain <BZZZZZZ...> ils entendent"))
        assertEquals("x < y & z", GuideText.clean("x < y & z"))
        assertEquals("&unknown;", GuideText.clean("&unknown;"))
    }

    @Test
    fun `the guide is read without html`() {
        val xml = """
            <tv>
              <programme start="20261113201000 +0100" stop="20261113213000 +0100" channel="TF1.fr">
                <title>Le grand concours</title>
                <desc>&lt;p&gt;Des personnalités se prêtent à ce jeu qui teste leur culture générale.&lt;/p&gt; Qui sera l&amp;#039;élu ?</desc>
                <category lang="fr">&lt;span style="color:#FF2F79"&gt;Inédit&lt;/span&gt;</category>
              </programme>
            </tv>
        """.trimIndent()
        val programme = XmltvReader(xml.byteInputStream()) { KXmlParser() }.next() as XmltvProgramme
        assertEquals("Inédit", programme.category)
        assertEquals("Des personnalités se prêtent à ce jeu qui teste leur culture générale.\nQui sera l'élu ?", programme.description)
    }
}
