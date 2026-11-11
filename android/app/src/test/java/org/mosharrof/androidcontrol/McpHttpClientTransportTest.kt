package org.mosharrof.androidcontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

class McpHttpClientTransportTest {

    private class FakeConnection(
        url: URL,
        private val status: Int,
        private val errorBody: String = "",
        private val responseBody: String = "",
        private val responseType: String = "application/json"
    ) : HttpURLConnection(url) {

        val requestBytes = ByteArrayOutputStream()
        var wasDisconnected = false
            private set

        override fun connect() {}

        override fun disconnect() {
            wasDisconnected = true
        }

        override fun usingProxy() = false

        override fun getOutputStream(): OutputStream = requestBytes

        override fun getResponseCode(): Int = status

        override fun getErrorStream(): InputStream =
            ByteArrayInputStream(errorBody.toByteArray(Charsets.UTF_8))

        override fun getInputStream(): InputStream =
            ByteArrayInputStream(responseBody.toByteArray(Charsets.UTF_8))

        override fun getContentType(): String = responseType
    }

    private fun request() = McpProtocol.modernDiscoveryRequest(
        id = "transport-test-1",
        clientName = "MOSHARROF",
        clientVersion = "1.0"
    )

    @Test
    fun http404WithMethodNotFoundPreservesStatusAndRpcCode() {
        val fake = FakeConnection(
            URL("https://example.com/mcp"),
            404,
            """{"jsonrpc":"2.0","id":"transport-test-1","error":{"code":-32601,"message":"Method not found"}}"""
        )

        val client = McpHttpClient(
            endpoint = "https://example.com/mcp",
            connectionFactory = { fake }
        )

        val failure = try {
            client.post(request())
            throw AssertionError("Expected MCP HTTP failure")
        } catch (error: McpHttpException) {
            error
        }

        assertEquals(404, failure.statusCode)
        assertEquals(-32601, failure.rpcErrorCode)
        assertTrue(fake.wasDisconnected)
    }

    @Test
    fun http500WithoutRpcErrorDoesNotInventRpcCode() {
        val fake = FakeConnection(
            URL("https://example.com/mcp"),
            500,
            """{"message":"Internal server error"}"""
        )

        val client = McpHttpClient(
            endpoint = "https://example.com/mcp",
            connectionFactory = { fake }
        )

        val failure = try {
            client.post(request())
            throw AssertionError("Expected MCP HTTP failure")
        } catch (error: McpHttpException) {
            error
        }

        assertEquals(500, failure.statusCode)
        assertNull(failure.rpcErrorCode)
        assertTrue(fake.wasDisconnected)
    }

    @Test
    fun httpErrorDoesNotExposeEchoedBearerTokenInExceptionMessage() {
        val secret = "sensitive-bearer-token-value"
        val fake = FakeConnection(
            URL("https://example.com/mcp"),
            401,
            """{"error":{"code":-32001,"message":"Rejected $secret"}}"""
        )
        val client = McpHttpClient(
            endpoint = "https://example.com/mcp",
            bearerToken = secret,
            connectionFactory = { fake }
        )

        val failure = try {
            client.post(request())
            throw AssertionError("Expected MCP HTTP failure")
        } catch (error: McpHttpException) {
            error
        }

        assertFalse(failure.message.orEmpty().contains(secret))
        assertFalse(failure.message.orEmpty().contains("Rejected"))
        assertNull(failure.rpcErrorData)
        assertTrue(fake.wasDisconnected)
    }

    @Test
    fun unsupportedVersionErrorRetainsOnlySupportedVersionHint() {
        val fake = FakeConnection(
            URL("https://example.com/mcp"),
            400,
            """{"error":{"code":-32022,"message":"bad","data":{"supported":["2025-11-25"],"echo":"secret-value"}}}"""
        )
        val client = McpHttpClient(
            endpoint = "https://example.com/mcp",
            connectionFactory = { fake }
        )
        val failure = try {
            client.post(request())
            throw AssertionError("Expected MCP HTTP failure")
        } catch (error: McpHttpException) {
            error
        }

        assertEquals(-32022, failure.rpcErrorCode)
        assertEquals(
            "2025-11-25",
            failure.rpcErrorData?.optJSONArray("supported")?.optString(0)
        )
        assertFalse(failure.rpcErrorData.toString().contains("secret-value"))
    }

    @Test
    fun successfulJsonResponseUsesExpectedTransportSettingsAndBearerToken() {
        val fake = FakeConnection(
            URL("https://example.com/mcp"),
            200,
            responseBody = """{"jsonrpc":"2.0","id":"transport-test-1","result":{"resultType":"complete"}}"""
        )

        val client = McpHttpClient(
            endpoint = "https://example.com/mcp",
            bearerToken = "transport-test-token",
            connectionFactory = { fake }
        )

        val response = client.post(request())

        assertEquals("2.0", response.optString("jsonrpc"))
        assertEquals("transport-test-1", response.optString("id"))
        assertNotNull(fake.requestBytes.toByteArray().takeIf { it.isNotEmpty() })
        assertEquals("POST", fake.requestMethod)
        assertEquals("Bearer transport-test-token", fake.getRequestProperty("Authorization"))
        assertEquals("application/json", fake.getRequestProperty("Content-Type"))
        assertEquals("application/json, text/event-stream", fake.getRequestProperty("Accept"))
        assertEquals(8_000, fake.connectTimeout)
        assertEquals(15_000, fake.readTimeout)
        assertFalse(fake.instanceFollowRedirects)
        assertTrue(fake.wasDisconnected)
    }

    @Test
    fun modernJsonResponseRejectsMissingResultType() {
        val fake = FakeConnection(
            URL("https://example.com/mcp"),
            200,
            responseBody = """{"jsonrpc":"2.0","id":"transport-test-1","result":{}}"""
        )
        val client = McpHttpClient(
            endpoint = "https://example.com/mcp",
            connectionFactory = { fake }
        )
        try {
            client.post(request())
            throw AssertionError("Missing modern resultType should be rejected")
        } catch (error: IOException) {
            assertTrue(error.message.orEmpty().contains("resultType"))
        }
        assertTrue(fake.wasDisconnected)
    }

    @Test
    fun modernJsonResponseAcceptsInputRequiredResultType() {
        val fake = FakeConnection(
            URL("https://example.com/mcp"),
            200,
            responseBody = """{"jsonrpc":"2.0","id":"transport-test-1","result":{"resultType":"input_required","inputRequests":{}}}"""
        )
        val client = McpHttpClient(
            endpoint = "https://example.com/mcp",
            connectionFactory = { fake }
        )
        val response = client.post(request())
        assertEquals(
            "input_required",
            response.getJSONObject("result").getString("resultType")
        )
        assertTrue(fake.wasDisconnected)
    }

    @Test
    fun modernSseResponseAcceptsCompleteResultType() {
        val fake = FakeConnection(
            URL("https://example.com/mcp"),
            200,
            responseBody = "data: {\"jsonrpc\":\"2.0\",\"id\":\"transport-test-1\",\"result\":{\"resultType\":\"complete\"}}\n\n",
            responseType = "text/event-stream"
        )
        val client = McpHttpClient(
            endpoint = "https://example.com/mcp",
            connectionFactory = { fake }
        )

        val response = client.post(request())
        assertEquals(
            "complete",
            response.getJSONObject("result").getString("resultType")
        )
        assertTrue(fake.wasDisconnected)
    }

    @Test
    fun oversizedSuccessfulResponseIsRejectedAndConnectionDisconnected() {
        val oversizedBody = "x".repeat(256 * 1024 + 1)

        val fake = FakeConnection(
            URL("https://example.com/mcp"),
            200,
            responseBody = oversizedBody
        )

        val client = McpHttpClient(
            endpoint = "https://example.com/mcp",
            connectionFactory = { fake }
        )

        try {
            client.post(request())
            throw AssertionError("Expected oversized response to be rejected")
        } catch (error: IOException) {
            assertTrue(error.message.orEmpty().contains("অতিক্রম"))
        }

        assertTrue(fake.wasDisconnected)
    }

    @Test
    fun oversizedRequestBodyIsRejectedBeforeWriting() {
        val baseRequest = request()
        val oversizedRequest = baseRequest.copy(
            params = baseRequest.params + (
                "padding" to "x".repeat(256 * 1024)
            )
        )

        val fake = FakeConnection(
            URL("https://example.com/mcp"),
            200,
            responseBody = """{"jsonrpc":"2.0","id":"transport-test-1","result":{}}"""
        )

        val client = McpHttpClient(
            endpoint = "https://example.com/mcp",
            connectionFactory = { fake }
        )

        try {
            client.post(oversizedRequest)
            throw AssertionError("Expected oversized request to be rejected")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("অতিক্রম"))
        }

        assertEquals(0, fake.requestBytes.size())
    }

    @Test
    fun perRequestBearerTokenValidationRejectsHeaderInjection() {
        assertTrue(McpHttpClient.isValidBearerToken("valid-token_123"))
        assertFalse(McpHttpClient.isValidBearerToken(""))
        assertFalse(McpHttpClient.isValidBearerToken("token\\rInjected: yes"))
        assertFalse(McpHttpClient.isValidBearerToken("token\\nInjected: yes"))
    }

}
