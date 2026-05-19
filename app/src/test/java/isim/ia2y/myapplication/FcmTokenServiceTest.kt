package isim.ia2y.myapplication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FcmTokenServiceTest {
    @Test
    fun stableFcmTokenDocumentId_doesNotExposeRawToken() {
        val token = "sample-fcm-token"
        val tokenId = UserService.stableFcmTokenDocumentId(token)

        assertEquals(64, tokenId.length)
        assertFalse(tokenId.contains(token))
    }

    @Test
    fun stableFcmTokenDocumentId_sameTokenUsesSameDocument() {
        val token = "same-token"

        assertEquals(
            UserService.stableFcmTokenDocumentId(token),
            UserService.stableFcmTokenDocumentId(token)
        )
    }
}
