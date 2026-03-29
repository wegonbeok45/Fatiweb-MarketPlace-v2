package isim.ia2y.myapplication

data class HomeCatalogSections(
    val latest: List<Product>,
    val discover: List<Product>
)

object HomeCatalogSectionsBuilder {
    fun build(products: List<Product>, latestLimit: Int = 10, discoverLimit: Int = 20): HomeCatalogSections {
        val active = products.filter { it.isActive }
        val latest = active
            .sortedByDescending { it.updatedAt }
            .take(latestLimit)

        val discover = active
            .filterNot { candidate -> latest.any { it.id == candidate.id } }
            .sortedWith(
                compareByDescending<Product> { it.rating }
                    .thenByDescending { it.reviewsCount }
                    .thenByDescending { it.updatedAt }
            )
            .take(discoverLimit)

        return HomeCatalogSections(
            latest = latest,
            discover = discover
        )
    }
}
