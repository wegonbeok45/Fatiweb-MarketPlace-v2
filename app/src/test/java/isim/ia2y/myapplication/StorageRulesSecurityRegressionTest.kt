package isim.ia2y.myapplication

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageRulesSecurityRegressionTest {
    private val rulesText: String by lazy {
        val rulesFile = File("../storage.rules")
        assertTrue("storage.rules should exist at the repository root", rulesFile.exists())
        rulesFile.readText()
    }

    @Test
    fun productImageUploadsAreCappedAndThumbnailsAreReadOnly() {
        assertTrue(rulesText.contains("request.resource.size < maxBytes"))
        assertTrue(rulesText.contains("isImageUpload(5 * 1024 * 1024)"))
        assertTrue(rulesText.contains("match /product_images/{ownerId}/{productId}/{fileName}"))
        assertTrue(rulesText.contains("canWriteOwnedProductImage(ownerId)"))
        assertTrue(rulesText.contains("match /product_images/{productId}/thumbnails/{fileName}"))
        assertTrue(rulesText.contains("match /user_avatars/{userId}/thumbnails/{fileName}"))
        assertTrue(rulesText.contains("allow write: if false;"))
    }
}
