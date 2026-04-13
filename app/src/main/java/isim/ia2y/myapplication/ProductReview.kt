package isim.ia2y.myapplication

data class ProductReview(
    val reviewId: String = "",
    val userId: String = "",
    val userName: String = "",
    val productId: String = "",
    val rating: Int = 0,
    val comment: String = "",
    val createdAt: Any? = null
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "reviewId" to reviewId,
        "userId" to userId,
        "userName" to userName,
        "productId" to productId,
        "rating" to rating,
        "comment" to comment,
        "createdAt" to (createdAt ?: com.google.firebase.Timestamp.now())
    )

    companion object {
        fun fromMap(id: String, map: Map<String, Any>): ProductReview = ProductReview(
            reviewId = id,
            userId = map["userId"] as? String ?: "",
            userName = map["userName"] as? String ?: "",
            productId = map["productId"] as? String ?: "",
            rating = (map["rating"] as? Number)?.toInt() ?: 0,
            comment = map["comment"] as? String ?: "",
            createdAt = map["createdAt"]
        )
    }
}
