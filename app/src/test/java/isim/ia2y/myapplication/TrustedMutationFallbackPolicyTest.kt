package isim.ia2y.myapplication

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedMutationFallbackPolicyTest {
    @Test
    fun allowsDebugFallbackForTemporaryBackendUnavailability() {
        if (!BuildConfig.DEBUG) return
        assertTrue(
            TrustedMutationFallbackPolicy.allowDirectWriteFallback(
                IllegalStateException("Function unavailable while emulator boots")
            )
        )
    }

    @Test
    fun allowsDebugFallbackForFirebaseNotFoundCode() {
        if (!BuildConfig.DEBUG) return
        assertTrue(
            TrustedMutationFallbackPolicy.allowDirectWriteFallback(
                IllegalStateException("NOT_FOUND")
            )
        )
    }

    @Test
    fun blocksFallbackForSecurityRelevantFailures() {
        assertFalse(
            TrustedMutationFallbackPolicy.allowDirectWriteFallback(
                IllegalStateException("PERMISSION_DENIED: app check token invalid")
            )
        )
    }

    @Test
    fun blocksFallbackForAuthAndHttpSecurityMessages() {
        listOf(
            "401 unauthenticated",
            "403 forbidden",
            "auth failed",
            "authentication failed",
            "permission-denied"
        ).forEach { message ->
            assertFalse(
                message,
                TrustedMutationFallbackPolicy.allowDirectWriteFallback(IllegalStateException(message))
            )
        }
    }
}
