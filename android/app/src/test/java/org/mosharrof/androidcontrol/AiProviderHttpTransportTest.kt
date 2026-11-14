package org.mosharrof.androidcontrol

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

class AiProviderHttpTransportTest {
    @Before fun setup() {
        AiProviderRegistry.clearForTests()
        AiProviderRegistry.registerBuiltIns()
    }

    @After fun cleanup() {
        AiProviderRegistry.clearForTests()
    }

    private fun request() = AiProviderRequestFactory.createInitialRequest(
        AiProviderConfig(AiProviderId.OPENAI, "test-model", credentialReference = "keystore:test"),
        "fake-key",
        "hello",
        emptyList()
    )

    private class FakeConnection(
        url: URL,
        private val status: Int,
        private val responseBody: ByteArray,
        private val onWrite: (() -> Unit)? = null
    ) : HttpURLConnection(url) {
        val written = object : ByteArrayOutputStream() {
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                super.write(buffer, offset, length)
                onWrite?.invoke()
            }
        }
        val sentHeaders = linkedMapOf<String, String>()
        var disconnected = false

        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode(): Int = status
        override fun getInputStream(): InputStream = ByteArrayInputStream(responseBody)
        override fun getErrorStream(): InputStream = ByteArrayInputStream(responseBody)
        override fun getOutputStream(): OutputStream = written
        override fun setRequestProperty(key: String, value: String) {
            sentHeaders[key] = value
        }
        override fun getRequestProperty(key: String): String? = sentHeaders[key]
    }

    @Test fun postsJsonWithBoundedTimeoutsAndRedirectsDisabled() {
        val fake = FakeConnection(
            URL("https://api.openai.com/v1/responses"),
            200,
            """{"id":"resp_1","output":[]}""".toByteArray()
        )
        val transport = AiProviderHttpTransport { fake }
        val response = transport.post(request())

        assertEquals(200, response.statusCode)
        assertEquals("resp_1", response.payload.getString("id"))
        assertFalse(fake.instanceFollowRedirects)
        assertEquals(AiProviderHttpTransport.CONNECT_TIMEOUT_MS, fake.connectTimeout)
        assertEquals(AiProviderHttpTransport.READ_TIMEOUT_MS, fake.readTimeout)
        assertEquals("Bearer fake-key", fake.sentHeaders["Authorization"])
        assertTrue(fake.written.toString("UTF-8").contains("\"model\":\"test-model\""))
        assertFalse(fake.disconnected.not())
    }

    @Test fun httpErrorsExposeOnlyStatusAndRetryClassification() {
        val errorBody = """{"error":{"message":"private provider detail"}}"""
        val fake = FakeConnection(
            URL("https://api.openai.com/v1/responses"),
            429,
            errorBody.toByteArray()
        )
        val failure = try {
            AiProviderHttpTransport { fake }.post(request())
            fail("Expected rate limit error")
            null
        } catch (error: AiProviderHttpException) {
            error
        }

        assertNotNull(failure)
        assertEquals(429, failure!!.statusCode)
        assertEquals(AiProviderRetryClass.RATE_LIMITED, failure.retryClass)
        assertFalse(failure.message!!.contains("private provider detail"))
        assertTrue(fake.disconnected)
    }

    @Test fun serverErrorsAreClassifiedTransientButNotRetriedAutomatically() {
        val fake = FakeConnection(
            URL("https://api.openai.com/v1/responses"),
            503,
            "{}".toByteArray()
        )
        val failure = try {
            AiProviderHttpTransport { fake }.post(request())
            fail("Expected server error")
            null
        } catch (error: AiProviderHttpException) {
            error
        }
        assertEquals(AiProviderRetryClass.TRANSIENT, failure!!.retryClass)
    }

    @Test fun rejectsNonHttpsAndQueryCredentialUrlsBeforeOpeningConnection() {
        var opened = false
        val transport = AiProviderHttpTransport {
            opened = true
            FakeConnection(it, 200, "{}".toByteArray())
        }
        val invalid = AiProviderHttpRequest(
            "http://api.openai.com/v1/responses",
            mapOf("Content-Type" to "application/json"),
            mapOf("model" to "x")
        )
        assertThrows(IllegalArgumentException::class.java) { transport.post(invalid) }
        assertFalse(opened)
    }

    @Test fun rejectsUnsupportedOrInjectedHeaders() {
        val transport = AiProviderHttpTransport {
            FakeConnection(it, 200, "{}".toByteArray())
        }
        val invalid = AiProviderHttpRequest(
            "https://api.openai.com/v1/responses",
            mapOf(
                "Content-Type" to "application/json",
                "X-Injected" to "bad"
            ),
            mapOf("model" to "x")
        )
        assertThrows(IllegalArgumentException::class.java) { transport.post(invalid) }
    }

    @Test fun cancellationBeforeDispatchDoesNotOpenConnectionOrCreateUnknownOutcome() {
        val cancellation = AiProviderCancellation()
        cancellation.cancel()
        var opened = false
        val transport = AiProviderHttpTransport {
            opened = true
            FakeConnection(it, 200, "{}".toByteArray())
        }
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            transport.post(request(), cancellation)
        }
        assertFalse(opened)
        assertFalse(cancellation.hasUncertainOutcome())
    }

    @Test fun cancellationAfterRequestDispatchMarksOutcomeUncertainAndClosesConnection() {
        lateinit var cancellation: AiProviderCancellation
        val fake = FakeConnection(
            URL("https://api.openai.com/v1/responses"),
            200,
            """{"id":"resp_1"}""".toByteArray(),
            onWrite = { cancellation.cancel() }
        )
        cancellation = AiProviderCancellation()
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            AiProviderHttpTransport { fake }.post(request(), cancellation)
        }
        assertTrue(cancellation.hasUncertainOutcome())
        assertTrue(fake.disconnected)
    }

    @Test fun receivedHttpResponseClearsUncertainOutcomeEvenIfLaterCancelled() {
        val fake = FakeConnection(
            URL("https://api.openai.com/v1/responses"),
            200,
            """{"id":"resp_1"}""".toByteArray()
        )
        val cancellation = AiProviderCancellation()
        val response = AiProviderHttpTransport { fake }.post(request(), cancellation)
        assertEquals(200, response.statusCode)
        assertFalse(cancellation.hasUncertainOutcome())
        cancellation.cancel()
        assertFalse(cancellation.hasUncertainOutcome())
    }

    @Test fun responseBodyIsBoundedAndConnectionAlwaysCloses() {
        val fake = FakeConnection(
            URL("https://api.openai.com/v1/responses"),
            200,
            ByteArray(AiProviderHttpTransport.MAX_RESPONSE_BYTES + 1) { 'x'.code.toByte() }
        )
        assertThrows(IllegalArgumentException::class.java) {
            AiProviderHttpTransport { fake }.post(request())
        }
        assertTrue(fake.disconnected)
    }
}
