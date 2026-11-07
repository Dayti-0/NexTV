package tv.nextv.core.net

import okhttp3.Dns
import okhttp3.dnsoverhttps.DnsOverHttps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsProviderTest {

    @Test
    fun `system resolver by default`() {
        val provider = HttpClientProvider()
        RequestPurpose.entries.forEach { assertSame(Dns.SYSTEM, provider.client(it).dns) }
    }

    @Test
    fun `public resolver over https for every request type`() {
        val provider = HttpClientProvider(networkSettings = NetworkSettings(dns = DnsProvider.QUAD9))
        RequestPurpose.entries.forEach { purpose ->
            val dns = provider.client(purpose).dns as DnsOverHttps
            assertEquals("dns.quad9.net", dns.url.host)
        }
    }

    @Test
    fun `changing the setting rebuilds the clients`() {
        val provider = HttpClientProvider(networkSettings = NetworkSettings(dns = DnsProvider.QUAD9))
        assertTrue(provider.client(RequestPurpose.STREAM).dns is DnsOverHttps)
        provider.update(networkSettings = NetworkSettings(dns = DnsProvider.SYSTEM))
        assertSame(Dns.SYSTEM, provider.client(RequestPurpose.STREAM).dns)
        provider.update(networkSettings = NetworkSettings(dns = DnsProvider.CLOUDFLARE))
        assertEquals("cloudflare-dns.com", (provider.client(RequestPurpose.STREAM).dns as DnsOverHttps).url.host)
    }

    @Test
    fun `quad9 is reached by its fixed addresses`() {
        assertEquals(listOf("9.9.9.9", "149.112.112.112"), DnsProvider.QUAD9.addresses.take(2))
    }
}
