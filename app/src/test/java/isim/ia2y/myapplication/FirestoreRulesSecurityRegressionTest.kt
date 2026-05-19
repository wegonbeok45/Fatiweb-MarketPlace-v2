package isim.ia2y.myapplication

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirestoreRulesSecurityRegressionTest {
    private val rulesText: String by lazy {
        val rulesFile = File("../firestore.rules")
        assertTrue("firestore.rules should exist at the repository root", rulesFile.exists())
        rulesFile.readText()
    }

    @Test
    fun clientOrderCreationIsBlockedEverywhere() {
        assertFalse(
            "Client-side order creation helper must not exist in production rules.",
            rulesText.contains("ownerOrderCreateIsSafe")
        )
        assertTrue(
            "Nested user orders must reject client creates.",
            rulesText.contains("match /orders/{orderId}") &&
                rulesText.contains("allow create: if false;")
        )
        assertFalse(
            "Top-level orders must not allow owner-created pending orders.",
            rulesText.contains("allow create: if signedIn()")
        )
        assertFalse(
            "Nested user orders must not allow owner-created pending orders.",
            rulesText.contains("allow create: if isOwner(userId)")
        )
    }

    @Test
    fun userProfileOwnerCannotChangeRoleOrStatus() {
        assertTrue(rulesText.contains("request.resource.data.role == resource.data.role"))
        assertTrue(rulesText.contains("request.resource.data.status == resource.data.status"))
        assertTrue(rulesText.contains("request.resource.data.createdAt == resource.data.createdAt"))
    }

    @Test
    fun publicProductReadsRequireActivePublishedProducts() {
        assertTrue(rulesText.contains("match /products/{productId}"))
        assertTrue(rulesText.contains("resource.data.isActive == true"))
        assertTrue(rulesText.contains("resource.data.status == 'published'"))
        assertFalse(rulesText.contains("match /products/{productId} {\n      allow read: if true;"))
    }

    @Test
    fun sellerProductWritesValidateCoreFields() {
        assertTrue(rulesText.contains("function sellerProductDocumentIsSafe(productId)"))
        assertTrue(rulesText.contains("request.resource.data.id == productId"))
        assertTrue(rulesText.contains("validProductStatus(request.resource.data.status)"))
        assertTrue(rulesText.contains("httpsImageUrlIsSafe(request.resource.data.imageUrl)"))
        assertTrue(rulesText.contains("allow create: if isAdmin() || (isVendeur() && vendeurProductCreateIsSafe(productId));"))
    }

    @Test
    fun onlyCommerceConfigIsPubliclyReadable() {
        assertTrue(rulesText.contains("match /config/commerce"))
        assertTrue(rulesText.contains("match /config/{configId}"))
        assertFalse(rulesText.contains("match /config/{configId} {\n      allow read: if true;"))
    }

    @Test
    fun usersCanOnlyReadOrMarkTheirOwnInbox() {
        assertTrue(rulesText.contains("match /inbox/{notificationId}"))
        assertTrue(rulesText.contains("allow read: if isOwner(userId) || isAdmin();"))
        assertTrue(rulesText.contains("allow update: if isOwner(userId) && onlyReadStateChanged();"))
    }
}
