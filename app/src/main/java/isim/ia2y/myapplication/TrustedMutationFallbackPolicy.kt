package isim.ia2y.myapplication

/**
 * Direct Firestore writes are kept as a debug-only escape hatch for local development when the
 * callable backend is temporarily unavailable or not deployed yet.
 *
 * Security and auth failures never fall back, even in DEBUG. In RELEASE, no fallback is ever
 * allowed; all writes must go through the Cloud Function.
 */
object TrustedMutationFallbackPolicy {
    fun allowDirectWriteFallback(error: Throwable): Boolean {
        if (!BuildConfig.DEBUG) return false

        val message = error.messageChain().lowercase()
        if (message.containsAny(SECURITY_BLOCKLIST)) return false

        val functionError = error as? BackendFunctionException
        if (functionError != null) {
            return functionError.code.name in TRANSIENT_FUNCTION_CODE_NAMES
        }

        return message.containsAny(TRANSIENT_MESSAGE_ALLOWLIST)
    }

    private val SECURITY_BLOCKLIST = listOf(
        "permission denied",
        "permission_denied",
        "permission-denied",
        "unauthenticated",
        "authentication",
        "auth failed",
        "app check",
        "app attestation",
        "401",
        "403"
    )

    private val TRANSIENT_MESSAGE_ALLOWLIST = listOf(
        "not found",
        "not_found",
        "404",
        "unavailable",
        "deadline",
        "timed out",
        "timeout",
        "temporary",
        "emulator boots",
        "function unavailable",
        "internal"
    )

    private val TRANSIENT_FUNCTION_CODE_NAMES = setOf(
        "NOT_FOUND",
        "UNAVAILABLE",
        "DEADLINE_EXCEEDED",
        "INTERNAL"
    )

    private fun Throwable.messageChain(): String {
        return buildString {
            var current: Throwable? = this@messageChain
            var depth = 0
            while (current != null && depth < 4) {
                append(current.message.orEmpty())
                append(' ')
                current = current.cause
                depth++
            }
        }
    }

    private fun String.containsAny(values: List<String>): Boolean {
        return values.any(::contains)
    }
}
