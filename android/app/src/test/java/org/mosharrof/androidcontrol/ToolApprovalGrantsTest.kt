package org.mosharrof.androidcontrol

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ToolApprovalGrantsTest {

    @After
    fun cleanup() {
        ToolApprovalGrants.clearForTests()
    }

    private val args = mapOf<String, Any?>(
        "text" to "hello",
        "options" to mapOf("count" to 2, "enabled" to true)
    )

    @Test
    fun grantAllowsExactToolAndArgumentsOnce() {
        val token = ToolApprovalGrants.issue("connector.a", "tool.write", args, 1000)

        assertTrue(
            ToolApprovalGrants.consume(
                token, "connector.a", "tool.write", args, 1001
            )
        )
        assertFalse(
            ToolApprovalGrants.consume(
                token, "connector.a", "tool.write", args, 1002
            )
        )
    }

    @Test
    fun grantRejectsDifferentArguments() {
        val token = ToolApprovalGrants.issue("connector.a", "tool.write", args, 1000)
        val changed = args + ("text" to "different")

        assertFalse(
            ToolApprovalGrants.consume(
                token, "connector.a", "tool.write", changed, 1001
            )
        )
        assertFalse(
            ToolApprovalGrants.consume(
                token, "connector.a", "tool.write", args, 1002
            )
        )
    }

    @Test
    fun grantRejectsDifferentTool() {
        val token = ToolApprovalGrants.issue("connector.a", "tool.write", args, 1000)

        assertFalse(
            ToolApprovalGrants.consume(
                token, "connector.a", "tool.other", args, 1001
            )
        )
    }

    @Test
    fun grantRejectsDifferentConnector() {
        val token = ToolApprovalGrants.issue("connector.a", "tool.write", args, 1000)

        assertFalse(
            ToolApprovalGrants.consume(
                token, "connector.b", "tool.write", args, 1001
            )
        )
    }

    @Test
    fun grantExpiresAfterSixtySeconds() {
        val token = ToolApprovalGrants.issue("connector.a", "tool.write", args, 1000)

        assertFalse(
            ToolApprovalGrants.consume(
                token, "connector.a", "tool.write", args, 61_000
            )
        )
    }

    @Test
    fun connectorRevocationInvalidatesItsGrantsOnly() {
        val first = ToolApprovalGrants.issue("connector.a", "tool.write", args, 1000)
        val second = ToolApprovalGrants.issue("connector.b", "tool.write", args, 1000)

        ToolApprovalGrants.revokeConnector("connector.a")

        assertFalse(
            ToolApprovalGrants.consume(
                first, "connector.a", "tool.write", args, 1001
            )
        )
        assertTrue(
            ToolApprovalGrants.consume(
                second, "connector.b", "tool.write", args, 1001
            )
        )
    }

    @Test
    fun mapKeyOrderDoesNotChangeFingerprint() {
        val firstArgs = linkedMapOf<String, Any?>(
            "alpha" to 1,
            "beta" to mapOf("x" to true, "y" to "value")
        )
        val reorderedArgs = linkedMapOf<String, Any?>(
            "beta" to mapOf("y" to "value", "x" to true),
            "alpha" to 1
        )

        val token = ToolApprovalGrants.issue(
            "connector.a", "tool.write", firstArgs, 1000
        )

        assertTrue(
            ToolApprovalGrants.consume(
                token, "connector.a", "tool.write", reorderedArgs, 1001
            )
        )
    }


    @Test
    fun expiredGrantsArePurgedBeforeNewGrantIsIssued() {
        val args = mapOf<String, Any?>("value" to "test")
        val oldTokens = (1..1_000).map {
            ToolApprovalGrants.issue("connector.a", "tool.write", args, 1000)
        }

        val fresh = ToolApprovalGrants.issue(
            "connector.a", "tool.write", args, 61_000
        )

        assertFalse(
            ToolApprovalGrants.consume(
                oldTokens.first(), "connector.a", "tool.write", args, 61_001
            )
        )
        assertTrue(
            ToolApprovalGrants.consume(
                fresh, "connector.a", "tool.write", args, 61_001
            )
        )
    }

    @Test
    fun grantCapacityIsBounded() {
        val args = mapOf<String, Any?>("value" to 1)
        repeat(1_000) {
            ToolApprovalGrants.issue(
                "connector.a", "tool.write", args, 1000
            )
        }

        val overflow = runCatching {
            ToolApprovalGrants.issue(
                "connector.a", "tool.write", args, 1000
            )
        }

        assertFalse(overflow.isSuccess)
    }

    @Test
    fun grantRejectsTimestampOverflow() {
        val result = runCatching {
            ToolApprovalGrants.issue(
                "connector.a",
                "tool.write",
                args,
                Long.MAX_VALUE
            )
        }
        assertFalse("Expiry timestamp overflow must be rejected", result.isSuccess)
    }

    @Test
    fun invalidGenericNumbersAreRejected() {
        val invalidValues = listOf(
            Double.NaN,
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY
        )

        invalidValues.forEach { invalid ->
            val result = runCatching {
                ToolApprovalGrants.issue(
                    "connector.a",
                    "tool.write",
                    mapOf("value" to invalid),
                    1000
                )
            }
            assertFalse(result.isSuccess)
        }
    }


    @Test
    fun rejectsApprovalFingerprintWithExcessiveNesting() {
        var nested: Any? = "leaf"
        repeat(40) {
            nested = mapOf("child" to nested)
        }
        val result = runCatching {
            ToolApprovalGrants.issue(
                "connector.a",
                "tool.write",
                mapOf("root" to nested),
                1000
            )
        }
        assertFalse("Deeply nested input must fail closed", result.isSuccess)
    }

}
