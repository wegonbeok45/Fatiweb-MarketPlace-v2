package isim.ia2y.myapplication

/**
 * Direct Firestore writes are kept as a debug-only escape hatch for local development when the
 * callable backend is temporarily unavailable or not deployed yet.
 *
 * Security-significant failures such as App Check, auth, or permission denials must not silently
 * degrade into client-side privileged writes because that weakens the intended trust boundary.
 */
object TrustedMutationFallbackPolicy {
    fun allowDirectWriteFallback(error: Throwable): Boolean {
        if (!BuildConfig.DEBUG) return false

        val message = error.message.orEmpty()
        val normalized = message.lowercase()

        if (
            normalized.contains("permission denied") ||
            normalized.contains("permission_denied") ||
            normalized.contains("unauthenticated") ||
            normalized.contains("403") ||
            normalized.contains("app check") ||
            normalized.contains("attestation")
        ) {
            return false
        }

        return normalized.contains("not found") ||
            normalized.contains("not_found") ||
            normalized.contains("404") ||
            normalized.contains("unavailable") ||
            normalized.contains("deadline") ||
            normalized.contains("timed out")
    }
}
