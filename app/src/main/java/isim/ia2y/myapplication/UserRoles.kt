package isim.ia2y.myapplication

/**
 * Role + status taxonomy for the marketplace rebuild.
 *
 * Existing data uses the legacy string role values in [UserRoles]. The new
 * [VendorStatus] and [ProductApprovalStatus] enums layer onto user/product
 * Firestore docs through the field names in [VendorFields] / [ProductFields].
 *
 * Migration plan: see docs/MIGRATION_VENDOR_STATUS.md.
 * - Existing vendors without `vendorStatus` are treated as [VendorStatus.APPROVED].
 * - Existing products without `approvalStatus` are treated as [ProductApprovalStatus.APPROVED].
 */
object UserRoles {
    const val ADMIN = "admin"
    const val VENDEUR = "vendeur"
    const val CLIENT = "client"
}

/**
 * Vendor onboarding + lifecycle state. Stored on the user document under
 * [VendorFields.STATUS] for any user whose [UserRoles] is `vendeur`.
 */
enum class VendorStatus(val wireValue: String) {
    PENDING("pending"),
    APPROVED("approved"),
    SUSPENDED("suspended"),
    REJECTED("rejected");

    companion object {
        val DEFAULT_FOR_LEGACY: VendorStatus = APPROVED

        fun fromWire(value: String?): VendorStatus =
            entries.firstOrNull { it.wireValue == value } ?: DEFAULT_FOR_LEGACY
    }
}

/**
 * Product moderation + lifecycle state. Stored on the product document under
 * [ProductFields.APPROVAL_STATUS].
 */
enum class ProductApprovalStatus(val wireValue: String) {
    DRAFT("draft"),
    PENDING("pending"),
    APPROVED("approved"),
    REJECTED("rejected"),
    ARCHIVED("archived");

    companion object {
        val DEFAULT_FOR_LEGACY: ProductApprovalStatus = APPROVED

        fun fromWire(value: String?): ProductApprovalStatus =
            entries.firstOrNull { it.wireValue == value } ?: DEFAULT_FOR_LEGACY
    }
}

/**
 * Firestore field names on the user document used by the vendor workflow.
 * Kept here (not in [FirestoreCollections]) so the role/status taxonomy is
 * a single read.
 */
object VendorFields {
    const val STATUS = "vendorStatus"
    const val APPLICATION_SUBMITTED_AT = "vendorApplicationSubmittedAt"
    const val APPROVED_AT = "vendorApprovedAt"
    const val SUSPENDED_REASON = "vendorSuspendedReason"
    const val SHOP_NAME = "shopName"
    const val SHOP_BIO = "shopBio"
    const val SHOP_BANNER_URL = "shopBannerUrl"
    const val SHOP_LOGO_URL = "shopLogoUrl"
    const val SHOP_OPERATING_HOURS = "shopOperatingHours"
    const val SHIPPING_FEE_DT = "shippingFeeDt"
    const val DELIVERY_ZONES = "deliveryZones"
}

/**
 * Firestore field names on the product document used by moderation +
 * lifecycle.
 */
object ProductFields {
    const val APPROVAL_STATUS = "approvalStatus"
    const val APPROVAL_REVIEWED_AT = "approvalReviewedAt"
    const val APPROVAL_REVIEWED_BY = "approvalReviewedBy"
    const val APPROVAL_REJECTION_REASON = "approvalRejectionReason"
    const val FEATURED = "featured"
    const val FEATURED_PRIORITY = "featuredPriority"
    const val REPORTED_COUNT = "reportedCount"
    const val ARCHIVED_AT = "archivedAt"
}
