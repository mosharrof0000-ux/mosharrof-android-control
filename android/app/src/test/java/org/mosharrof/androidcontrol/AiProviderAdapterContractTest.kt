package org.mosharrof.androidcontrol

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class AiProviderAdapterContractTest {
    @After fun cleanup() {
        AiProviderRegistry.clearForTests()
        ConnectorRegistry.clearForTests()
    }

    private class FakeAdapter : AiProviderAdapter {
        override val provider = AiProviderId.CUSTOM
        override val adapterVersion = "test-1"
        override fun validateConfiguration(config: AiProviderConfig) {
            require(config.provider == provider)
        }
        override fun capabilityProfile(model: String, apiVersion: String) =
            AiCapabilityProfile(
                provider, model, apiVersion, adapterVersion,
                mapOf(AiCapability.TOOL_CALLING to CapabilitySupport.SUPPORTED)
            )
        override fun encodeToolDefinitions(
            config: AiProviderConfig,
            tools: List<CanonicalPluginDefinition>
        ) = mapOf("tools" to tools.map { it.name })
        override fun decodeResponse(payload: Map<String, Any?>): CanonicalAiResult =
            CanonicalAiResult.Text(payload["text"] as? String ?: error("missing text"))
        override fun encodeToolResults(
            config: AiProviderConfig,
            results: List<CanonicalToolResult>
        ) = mapOf("results" to results.map { it.content })
    }

    @Test fun registryRejectsDuplicateProviderAdapters() {
        AiProviderRegistry.register(FakeAdapter())
        assertSame(FakeAdapter::class.java, AiProviderRegistry.get(AiProviderId.CUSTOM)!!::class.java)
        assertThrows(IllegalArgumentException::class.java) {
            AiProviderRegistry.register(FakeAdapter())
        }
    }


    @Test fun builtInAdaptersRegisterOnceAndExposeDistinctProviders() {
        AiProviderRegistry.registerBuiltIns()
        AiProviderRegistry.registerBuiltIns()
        assertNotNull(AiProviderRegistry.get(AiProviderId.GEMINI))
        assertNotNull(AiProviderRegistry.get(AiProviderId.OPENAI))
        assertNotNull(AiProviderRegistry.get(AiProviderId.XAI))
        assertNotNull(AiProviderRegistry.get(AiProviderId.ANTHROPIC))
        assertNotNull(AiProviderRegistry.get(AiProviderId.CUSTOM))
        assertEquals(
            setOf(AiProviderId.GEMINI, AiProviderId.OPENAI, AiProviderId.XAI, AiProviderId.ANTHROPIC, AiProviderId.CUSTOM),
            AiProviderRegistry.providers()
        )
    }

    @Test fun capabilityUnknownIsNotTreatedAsSupported() {
        val profile = AiCapabilityProfile(
            AiProviderId.CUSTOM, "model-x", "v1", "1",
            mapOf(AiCapability.STREAMING to CapabilitySupport.UNKNOWN)
        )
        assertFalse(profile.supports(AiCapability.STREAMING))
        assertFalse(profile.supports(AiCapability.TOOL_CALLING))
    }

    @Test fun canonicalToolCallsRejectDuplicateProviderIds() {
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalAiResult.ToolCalls(
                listOf(
                    CanonicalToolCall("call-1", "read", emptyMap()),
                    CanonicalToolCall("call-1", "write", emptyMap())
                )
            )
        }
    }

    @Test fun canonicalToolResultHasBoundedContent() {
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalToolResult("call-1", "read", "x".repeat(256 * 1024 + 1))
        }
    }

    @Test fun adapterCapabilitiesAreModelAndVersionScoped() {
        val adapter = FakeAdapter()
        val profile = adapter.capabilityProfile("model-a", "v2")
        assertEquals("model-a", profile.model)
        assertEquals("v2", profile.apiVersion)
        assertTrue(profile.supports(AiCapability.TOOL_CALLING))
    }

    @Test fun canonicalToolCallPreservesProviderCallIdAndArguments() {
        val args = linkedMapOf<String, Any?>("query" to "hello", "limit" to 3)
        val call = CanonicalToolCall("vendor-call-7", "search", args)
        assertEquals("vendor-call-7", call.providerCallId)
        assertEquals(args, call.arguments)
    }

    @Test fun providerCatalogSeparatesTranslationFromInboundBridge() {
        AiProviderRegistry.registerBuiltIns()
        val catalog = AiProviderCatalog.snapshot().associateBy { it.provider }
        assertEquals(
            AiProviderImplementationStatus.TRANSLATION_ONLY,
            catalog[AiProviderId.GEMINI]?.status
        )
        assertTrue(catalog[AiProviderId.GEMINI]?.adapterRegistered == true)
        assertEquals(
            AiProviderIntegrationDirection.INBOUND_MCP_CLIENT,
            catalog[AiProviderId.MICROSOFT]?.direction
        )
        assertEquals(
            AiProviderImplementationStatus.INBOUND_BRIDGE_PENDING,
            catalog[AiProviderId.MICROSOFT]?.status
        )
        assertFalse(catalog[AiProviderId.MICROSOFT]?.adapterRegistered == true)
    }


    @Test fun builtInProviderCapabilitiesFailClosedUntilVendorVerified() {
        AiProviderRegistry.registerBuiltIns()
        val versions = mapOf(
            AiProviderId.GEMINI to "v1beta",
            AiProviderId.OPENAI to "v1",
            AiProviderId.XAI to "v1",
            AiProviderId.ANTHROPIC to "2026-10-21",
            AiProviderId.CUSTOM to "v1"
        )

        versions.forEach { (provider, apiVersion) ->
            val adapter = requireNotNull(AiProviderRegistry.get(provider))
            val profile = adapter.capabilityProfile("model-under-test", apiVersion)

            assertEquals(provider, profile.provider)
            assertEquals("model-under-test", profile.model)
            assertEquals(apiVersion, profile.apiVersion)
            assertEquals(CapabilitySupport.UNSUPPORTED, profile.capabilities[AiCapability.MODEL_DISCOVERY])
            listOf(
                AiCapability.TOOL_CALLING,
                AiCapability.PARALLEL_TOOL_CALLS,
                AiCapability.STREAMING,
                AiCapability.CANCELLATION,
                AiCapability.JSON_SCHEMA,
                AiCapability.STRUCTURED_OUTPUTS
            ).forEach { capability ->
                assertEquals(
                    "$provider must not claim unverified $capability support",
                    CapabilitySupport.UNKNOWN,
                    profile.capabilities[capability]
                )
                assertFalse(profile.supports(capability))
            }
        }
    }

}
