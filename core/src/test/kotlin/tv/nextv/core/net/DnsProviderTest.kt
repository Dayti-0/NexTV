package tv.nextv.core.net

import okhttp3.Dns
import okhttp3.dnsoverhttps.DnsOverHttps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class DnsProviderTest {

    private fun HttpClientProvider.resolvers(purpose: RequestPurpose = RequestPurpose.STREAM) =
        (client(purpose).dns as FallbackDns).resolvers

    @Test
    fun `system resolver first by default, then unfiltered public resolvers`() {
        val provider = HttpClientProvider()
        RequestPurpose.entries.forEach { purpose ->
            val resolvers = provider.resolvers(purpose)
            assertEquals(listOf(DnsProvider.SYSTEM, DnsProvider.CLOUDFLARE, DnsProvider.GOOGLE), resolvers.map { it.first })
            assertSame(Dns.SYSTEM, resolvers.first().second)
        }
    }

    @Test
    fun `public resolver over https first for every request type`() {
        val provider = HttpClientProvider(networkSettings = NetworkSettings(dns = DnsProvider.QUAD9))
        RequestPurpose.entries.forEach { purpose ->
            val dns = provider.resolvers(purpose).first().second as DnsOverHttps
            assertEquals("dns.quad9.net", dns.url.host)
        }
    }

    @Test
    fun `quad9 falls back to the device and then to cloudflare`() {
        assertEquals(listOf(DnsProvider.QUAD9, DnsProvider.SYSTEM, DnsProvider.CLOUDFLARE), DnsProvider.QUAD9.withFallbacks())
        assertEquals(listOf(DnsProvider.CLOUDFLARE, DnsProvider.SYSTEM, DnsProvider.GOOGLE), DnsProvider.CLOUDFLARE.withFallbacks())
        assertEquals(listOf(DnsProvider.GOOGLE, DnsProvider.SYSTEM, DnsProvider.CLOUDFLARE), DnsProvider.GOOGLE.withFallbacks())
    }

    @Test
    fun `changing the setting rebuilds the clients`() {
        val provider = HttpClientProvider(networkSettings = NetworkSettings(dns = DnsProvider.QUAD9))
        assertTrue(provider.resolvers().first().second is DnsOverHttps)
        provider.update(networkSettings = NetworkSettings(dns = DnsProvider.SYSTEM))
        assertSame(Dns.SYSTEM, provider.resolvers().first().second)
        provider.update(networkSettings = NetworkSettings(dns = DnsProvider.CLOUDFLARE))
        assertEquals("cloudflare-dns.com", (provider.resolvers().first().second as DnsOverHttps).url.host)
    }

    @Test
    fun `quad9 is reached by its fixed addresses`() {
        assertEquals(listOf("9.9.9.9", "149.112.112.112"), DnsProvider.QUAD9.addresses.take(2))
    }

    // --- Secours ---

    private val server = InetAddress.getByAddress("playlist.example", byteArrayOf(203.toByte(), 0, 113, 7))

    private class Counting(val answer: (String) -> List<InetAddress>) : Dns {
        var calls = 0
        override fun lookup(hostname: String): List<InetAddress> {
            calls++
            return answer(hostname)
        }
    }

    private val blocking = Counting { throw UnknownHostException(it) }

    @Test
    fun `a server blocked by the chosen resolver is found by the next one`() {
        val next = Counting { listOf(server) }
        val dns = FallbackDns(listOf(DnsProvider.QUAD9 to blocking, DnsProvider.SYSTEM to next))

        assertEquals(listOf(server), dns.lookup("playlist.example"))
        assertEquals(listOf(server), dns.lookup("playlist.example"))
        // Le résolveur qui a trouvé est retenu : le premier n'est pas réinterrogé.
        assertEquals(1, blocking.calls)
        assertEquals(2, next.calls)
    }

    @Test
    fun `a working resolver is the only one asked`() {
        val first = Counting { listOf(server) }
        val second = Counting { listOf(server) }
        FallbackDns(listOf(DnsProvider.SYSTEM to first, DnsProvider.CLOUDFLARE to second)).lookup("playlist.example")
        assertEquals(1, first.calls)
        assertEquals(0, second.calls)
    }

    @Test
    fun `a blocking answer such as 0_0_0_0 counts as not found`() {
        val sinkhole = Counting { listOf(InetAddress.getByAddress(it, ByteArray(4))) }
        val dns = FallbackDns(listOf(DnsProvider.SYSTEM to sinkhole, DnsProvider.CLOUDFLARE to Counting { listOf(server) }))
        assertEquals(listOf(server), dns.lookup("playlist.example"))
    }

    @Test
    fun `the blocking answer is kept when nothing better is found`() {
        val loopback = listOf(InetAddress.getByAddress("playlist.example", byteArrayOf(127, 0, 0, 1)))
        val dns = FallbackDns(listOf(DnsProvider.SYSTEM to Counting { loopback }, DnsProvider.CLOUDFLARE to blocking))
        assertEquals(loopback, dns.lookup("playlist.example"))
    }

    @Test
    fun `localhost is never taken for a blocking answer`() {
        val loopback = listOf(InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1)))
        val other = Counting { listOf(server) }
        assertEquals(loopback, FallbackDns(listOf(DnsProvider.SYSTEM to Counting { loopback }, DnsProvider.CLOUDFLARE to other)).lookup("localhost"))
        assertEquals(0, other.calls)
    }

    @Test(expected = UnknownHostException::class)
    fun `unknown everywhere is still an unknown host`() {
        FallbackDns(listOf(DnsProvider.SYSTEM to blocking, DnsProvider.CLOUDFLARE to Counting { throw UnknownHostException(it) }))
            .lookup("nowhere.example")
    }
}
