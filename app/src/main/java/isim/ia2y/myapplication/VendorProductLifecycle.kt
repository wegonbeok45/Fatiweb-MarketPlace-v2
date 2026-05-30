package isim.ia2y.myapplication

import java.util.UUID

/**
 * Vendor-side lifecycle actions for a product. Wraps [ProductService.saveProduct]
 * by flipping `status` / `isActive` / `approvalStatus` and reusing the same
 * validation + Firestore path. Image and permission checks still apply.
 *
 * Caller is responsible for refreshing the list after a successful action.
 */
object VendorProductLifecycle {

    sealed class Result {
        data class Success(val product: Product) : Result()
        data class Failure(val cause: Throwable) : Result()
    }

    /** Move a draft / archived product back to a live, approved listing. */
    suspend fun publish(product: Product): Result = save(
        product.copy(
            status = "published",
            isActive = true,
            // If admin moderation isn't yet wired in Phase 5, the field stays "approved".
            // Once Phase 5 ships, a vendor publish should set this to "pending" instead.
            approvalStatus = product.approvalStatus.ifBlank { "approved" },
        )
    )

    /** Take a live product off the catalog without deleting it. */
    suspend fun unpublish(product: Product): Result = save(
        product.copy(
            status = "draft",
            isActive = false,
        )
    )

    /** Archive a product. Hidden from clients; vendor can restore via publish. */
    suspend fun archive(product: Product): Result = save(
        product.copy(
            status = "archived",
            isActive = false,
        )
    )

    /**
     * Duplicate: write a new product doc with the same content but a fresh id,
     * "(copie)" suffix on the title, and starting in draft state.
     */
    suspend fun duplicate(product: Product): Result = save(
        product.copy(
            id = UUID.randomUUID().toString(),
            title = appendCopySuffix(product.title),
            status = "draft",
            isActive = false,
            approvalStatus = "draft",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
    )

    /**
     * Set the discount percentage on a product. [percent] is clamped to 0..90.
     * Pass 0 to remove a discount.
     */
    suspend fun setDiscount(product: Product, percent: Int): Result = save(
        product.copy(
            discountPercent = percent.coerceIn(0, 90),
            updatedAt = System.currentTimeMillis(),
        )
    )

    /** Hard delete. Wraps [ProductService.deleteProduct]. */
    suspend fun delete(productId: String): Result {
        return runCatching { ProductService.deleteProduct(productId) }
            .fold(
                onSuccess = { Result.Success(product = sentinel(productId)) },
                onFailure = { Result.Failure(it) },
            )
    }

    private suspend fun save(product: Product): Result {
        return runCatching { ProductService.saveProduct(product) }
            .fold(
                onSuccess = { Result.Success(it) },
                onFailure = { Result.Failure(it) },
            )
    }

    private fun appendCopySuffix(title: String): String {
        val trimmed = title.trim()
        return if (trimmed.endsWith("(copie)", ignoreCase = true)) trimmed
        else if (trimmed.isBlank()) "(copie)"
        else "$trimmed (copie)"
    }

    private fun sentinel(productId: String): Product = Product(
        id = productId,
        title = "",
        subtitle = "",
        price = 0.0,
        rating = 0.0,
        reviewsCount = 0,
        tags = emptyList(),
        description = "",
        bullets = emptyList(),
        imageRes = 0,
    )
}
