package org.mosharrof.androidcontrol

/**
 * Safe recovery categories for OAuth refresh. No category contains token data
 * or raw authorization-server error text.
 */
internal enum class McpOAuthRefreshFailure(val userMessage: String) {
    REFRESH_ALREADY_RUNNING(
        "এই Connector-এর refresh চলছে; শেষ হলে আবার চেষ্টা করুন।"
    ),
    ENCRYPTED_STATE_UNAVAILABLE(
        "Encrypted OAuth state পড়া যায়নি; স্থানীয় credentials মুছবেন না।"
    ),
    AUTHORIZATION_STATE_MISSING(
        "OAuth session পাওয়া যায়নি; ব্যবহারকারীর সম্মতিতে আবার Login প্রয়োজন।"
    ),
    RESOURCE_BINDING_INVALID(
        "OAuth resource binding যাচাই হয়নি; নিরাপত্তার জন্য MCP request বন্ধ।"
    ),
    REFRESH_REJECTED(
        "Authorization Server refresh প্রত্যাখ্যান করেছে; স্বয়ংক্রিয় retry বন্ধ। নেটওয়ার্ক যাচাই করে একবার manual retry করুন; পুনরায় ব্যর্থ হলে আবার Login করুন।"
    ),
    BEARER_TOKEN_REQUIRED(
        "Server Bearer token দেয়নি; MCP request বন্ধ রাখা হয়েছে।"
    ),
    ENCRYPTED_STATE_SAVE_FAILED(
        "নতুন OAuth state encrypted storage-এ সংরক্ষণ হয়নি; পুরোনো state অক্ষত থাকতে পারে।"
    ),
    REFRESH_START_FAILED(
        "OAuth refresh শুরু করা যায়নি; credentials অক্ষত আছে। পরে manual retry করুন।"
    )
}

internal class McpOAuthRefreshException(
    val reason: McpOAuthRefreshFailure,
    cause: Throwable? = null
) : IllegalStateException(reason.userMessage, cause)

/**
 * Pure commit gate for refresh results. A Bearer token is released to callers
 * only after the updated AppAuth state has been durably encrypted.
 */
internal object McpOAuthRefreshCommit {
    fun persistBeforeRelease(
        accessToken: String?,
        tokenType: String?,
        authorizationError: Throwable?,
        persistState: () -> Result<Unit>
    ): Result<String> = runCatching {
        if (authorizationError != null || accessToken.isNullOrBlank()) {
            throw McpOAuthRefreshException(
                McpOAuthRefreshFailure.REFRESH_REJECTED,
                authorizationError
            )
        }
        if (!tokenType.equals("Bearer", ignoreCase = true)) {
            throw McpOAuthRefreshException(
                McpOAuthRefreshFailure.BEARER_TOKEN_REQUIRED
            )
        }
        val persistence = runCatching { persistState() }.getOrElse { Result.failure(it) }
        persistence.getOrElse {
            throw McpOAuthRefreshException(
                McpOAuthRefreshFailure.ENCRYPTED_STATE_SAVE_FAILED,
                it
            )
        }
        accessToken
    }
}

