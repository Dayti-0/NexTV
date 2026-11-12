package tv.nextv.core.net

import org.junit.Test
import org.junit.Assert.assertEquals
import tv.nextv.core.model.SourceLocation
import tv.nextv.core.repository.SourceInput
import tv.nextv.core.repository.SourceInputValidator
import tv.nextv.core.source.Mirrors
import tv.nextv.core.source.XtreamAccount

class ExplicitPortTest {

    @Test
    fun `le port 80 ecrit reste dans le lien de la playlist`() {
        val result = SourceInputValidator.validate(
            SourceInput(name = "", location = SourceLocation.Remote("http://exemple.test:80/get.php?a=1"), mirrors = listOf("http://secours.test:80", "secours2.test:443")),
        ) as SourceInputValidator.Result.Valid
        assertEquals("http://exemple.test:80/get.php?a=1", (result.input.location as SourceLocation.Remote).url)
        assertEquals(listOf("http://secours.test:80", "http://secours2.test:443"), result.input.mirrors)
    }

    @Test
    fun `sans port ecrit rien n est ajoute`() {
        assertEquals("http://exemple.test", Mirrors.normalize("exemple.test"))
        assertEquals("http://exemple.test:8080", Mirrors.normalize("http://exemple.test:8080/"))
        assertEquals("https://exemple.test:443", Mirrors.normalize("https://u:p@exemple.test:443/x"))
        assertEquals("http://[::1]:80", Mirrors.normalize("http://[::1]:80"))
    }

    @Test
    fun `compte Xtream avec le port 80`() {
        val link = XtreamAccount("http://exemple.test:80", "moi", "secret").playlistUrl()!!
        assertEquals("http://exemple.test:80/get.php?username=moi&password=secret&type=m3u_plus&output=ts", link)
        assertEquals("http://exemple.test:80", XtreamAccount.parse(link)!!.server)
    }
}
