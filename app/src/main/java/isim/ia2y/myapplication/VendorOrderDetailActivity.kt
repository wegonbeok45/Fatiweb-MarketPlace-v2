package isim.ia2y.myapplication

import android.content.Context
import android.content.Intent

/**
 * Vendor-facing order detail screen.
 *
 * Thin subclass of [AdminOrderDetailsActivity] that permanently sets
 * [isSellerMode] to `true`, which:
 *  - hides the vendor identity card (vendors must not see each other's data)
 *  - hides the status-change button (only admins may change order status)
 *  - skips the client profile fetch (unnecessary for vendor view)
 *
 * Layout and all logic are inherited from the parent class — zero duplication.
 */
class VendorOrderDetailActivity : AdminOrderDetailsActivity() {

    /** Always seller-mode — override is compile-time constant. */
    override val isSellerMode: Boolean
        get() = true

    companion object {

        /**
         * Create an intent targeting [VendorOrderDetailActivity].
         *
         * @param uid      Buyer's Firebase UID (stored on the [AppOrder]).
         * @param orderId  Firestore order document ID.
         */
        fun createIntent(
            context: Context,
            uid: String,
            orderId: String,
        ): Intent =
            AdminOrderDetailsActivity
                .createIntent(context = context, uid = uid, orderId = orderId, sellerMode = true)
                .setClass(context, VendorOrderDetailActivity::class.java)
    }
}
