package org.mosharrof.androidcontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class McpOAuthMetadataFetcherTest {
    private class FakeConnection(
        url: URL,
        private val status: Int,
        private val body: ByteArray,
        private val mime: String = "application/json"
    ) : HttpURLConnection(url) {
        var disconnected = false
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = status
        override fun getContentType(): String = mime
        override fun getContentLengthLong(): Long = body.size.toLong()
        override fun getInputStream(): InputStream = ByteArrayInputStream(body)
    }

    private fun json(value: String) = value.toByteArray(Charsets.UTF_8)

    @Test
    fun discoversAndValidatesResourceAndAuthorizationServerMetadata() {
        val resource = "https://mcp.example.com/api"
        val issuer = "https://auth.example.com/tenant"
        val resourceMetadataUrl =
            "https://mcp.example.com/.well-known/oauth-protected-resource/api"
        val issuerMetadataUrl =
            "https://auth.example.com/.well-known/oauth-authorization-server/tenant"
        val resourceMetadata = """{"resource":"$resource","authorization_servers":["$issuer"]}"""
        val serverMetadata = """{
            "issuer":"$issuer",
            "authorization_endpoint":"$issuer/authorize",
            "token_endpoint":"$issuer/token",
            "response_types_supported":["code"],
            "grant_types_supported":["authorization_code"],
            "token_endpoint_auth_methods_supported":["none"],
            "code_challenge_methods_supported":["S256"]
        }"""
        val seen = mutableListOf<FakeConnection>()
        val responses = mapOf(
            resourceMetadataUrl to json(resourceMetadata),
            issuerMetadataUrl to json(serverMetadata)
        )
        val fetcher = McpOAuthMetadataFetcher { url ->
            val connection = FakeConnection(
                url,
                HttpURLConnection.HTTP_OK,
                responses[url.toString()] ?: error("unexpected URL")
            )
            seen += connection
            connection
        }

        val result = fetcher.discover(resource)

        assertNotNull(result)
        assertEquals(issuer, result?.authorizationServerIssuer)
        assertEquals(2, seen.size)
        assertEquals(true, seen.all { it.disconnected })
    }

    @Test
    fun rejectsRedirectWithoutFollowingIt() {
        val connection = FakeConnection(
            URL("https://mcp.example.com/.well-known/oauth-protected-resource"),
            HttpURLConnection.HTTP_MOVED_TEMP,
            json("""{"resource":"https://mcp.example.com","authorization_servers":[]}""")
        )
        val fetcher = McpOAuthMetadataFetcher { connection }

        assertNull(fetcher.discover("https://mcp.example.com"))
        assertEquals(false, connection.instanceFollowRedirects)
        assertEquals(true, connection.disconnected)
    }

    @Test
    fun rejectsOversizedMetadataBody() {
        val oversized = ByteArray(McpOAuthMetadataFetcher.MAX_METADATA_BYTES + 1) { 'a'.code.toByte() }
        val connection = FakeConnection(
            URL("https://client.example.com/.well-known/oauth-client"),
            HttpURLConnection.HTTP_OK,
            oversized
        )
        val fetcher = McpOAuthMetadataFetcher { connection }

        assertNull(
            fetcher.fetchClientMetadata(
                "https://client.example.com/.well-known/oauth-client",
                "org.mosharrof.androidcontrol:/oauth2redirect"
            )
        )
        assertEquals(true, connection.disconnected)
    }

    @Test
    fun validatesCimdMetadataBeforeReturningIt() {
        val metadataUrl = "https://client.example.com/.well-known/oauth-client"
        val redirect = "org.mosharrof.androidcontrol:/oauth2redirect"
        val metadata = """{
            "client_id":"$metadataUrl",
            "client_name":"MOSHARROF Android",
            "application_type":"native",
            "token_endpoint_auth_method":"none",
            "redirect_uris":["$redirect"],
            "grant_types":["authorization_code"],
            "response_types":["code"]
        }"""
        val connection = FakeConnection(URL(metadataUrl), HttpURLConnection.HTTP_OK, json(metadata))
        val fetcher = McpOAuthMetadataFetcher { connection }

        assertNotNull(fetcher.fetchClientMetadata(metadataUrl, redirect))
        assertEquals("GET", connection.requestMethod)
        assertEquals(false, connection.instanceFollowRedirects)
        assertEquals(true, connection.disconnected)
    }

    @Test
    fun rejectsNonJsonContentType() {
        val connection = FakeConnection(
            URL("https://client.example.com/.well-known/oauth-client"),
            HttpURLConnection.HTTP_OK,
            json("{}"),
            "text/html"
        )
        val fetcher = McpOAuthMetadataFetcher { connection }

        assertNull(
            fetcher.fetchClientMetadata(
                "https://client.example.com/.well-known/oauth-client",
                "org.mosharrof.androidcontrol:/oauth2redirect"
            )
        )
    }
}
