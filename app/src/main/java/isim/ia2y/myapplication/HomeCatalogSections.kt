package isim.ia2y.myapplication

data class HomeCatalogSections(
    val featured: List<Product>,
    val latest: List<Product>,
    val discover: List<Product>
)

object HomeCatalogSectionsBuilder {
    fun build(
        products: List<Product>,
        featuredLimit: Int = 6,
        latestLimit: Int = 10,
        discoverLimit: Int = 20
    ): HomeCatalogSections {
        val active = products.filter { it.isActive }

        val featured = active
            .sortedWith(
                compareByDescending<Product> { it.rating }
                    .thenByDescending { it.reviewsCount }
                    .thenByDescending { it.updatedAtMillis }
            )
            .take(featuredLimit)

        val latest = active
            .filterNot { candidate -> featured.any { it.id == candidate.id } }
            .sortedByDescending { it.updatedAtMillis }
            .take(latestLimit)

        val discover = active
            .filterNot { candidate ->
                featured.any { it.id == candidate.id } || latest.any { it.id == candidate.id }
            }
            .sortedWith(
                compareByDescending<Product> { it.rating }
                    .thenByDescending { it.reviewsCount }
                    .thenByDescending { it.updatedAtMillis }
            )
            .take(discoverLimit)

        return HomeCatalogSections(
            featured = featured,
            latest = latest,
            discover = discover
        )
    }
}
