package isim.ia2y.myapplication

import android.content.Context
import android.content.Intent

/**
 * Vendor-facing product editor.
 *
 * Thin subclass of [AdminProductEditorActivity] that routes all vendor
 * product create/edit flows to a dedicated Activity class.
 *
 * All form logic (image picker, AI autofill, validation, Firestore save)
 * lives in the parent.  This class exists solely to:
 *  - Give vendor navigation its own Android component name.
 *  - Remove the [AdminProduitsActivity.EXTRA_SELLER_MODE] coupling from
 *    every vendor call-site — callers no longer need to know about an
 *    admin flag.
 *  - Make the back-stack legible: the task history shows
 *    VendorProductEditorActivity instead of AdminProductEditorActivity
 *    when a vendor edits their product.
 *
 * The parent's `isSellerMode` getter reads [AdminProduitsActivity.EXTRA_SELLER_MODE]
 * from the intent; [createIntent] always sets it to `true` so vendor-mode
 * behaviour is guaranteed without any override.
 */
class VendorProductEditorActivity : AdminProductEditorActivity() {

    companion object {
        /**
         * Build an intent for the vendor product editor.
         *
         * @param context   Calling context.
         * @param productId Firestore product ID to edit, or `null` to create a new product.
         */
        fun createIntent(context: Context, productId: String? = null): Intent =
            AdminProductEditorActivity
                .createIntent(context = context, productId = productId, sellerMode = true)
                .setClass(context, VendorProductEditorActivity::class.java)
    }
}
