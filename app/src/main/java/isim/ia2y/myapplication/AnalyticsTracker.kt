package isim.ia2y.myapplication

import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.ktx.Firebase

object AnalyticsTracker {
    fun appOpen(source: String = "direct") {
        logEvent(FirebaseAnalytics.Event.APP_OPEN) {
            putString("source", source)
        }
    }

    fun viewItem(product: Product) {
        logEvent(FirebaseAnalytics.Event.VIEW_ITEM) {
            putString(FirebaseAnalytics.Param.ITEM_ID, product.id)
            putString(FirebaseAnalytics.Param.ITEM_NAME, product.title)
            putString(FirebaseAnalytics.Param.ITEM_CATEGORY, product.category)
            putDouble(FirebaseAnalytics.Param.VALUE, product.price)
            putString(FirebaseAnalytics.Param.CURRENCY, "TND")
        }
    }

    fun removeFromCart(productId: String, quantity: Int) {
        logEvent(FirebaseAnalytics.Event.REMOVE_FROM_CART) {
            putString(FirebaseAnalytics.Param.ITEM_ID, productId)
            putLong(FirebaseAnalytics.Param.QUANTITY, quantity.toLong())
        }
    }

    fun signUp(method: String) {
        logEvent(FirebaseAnalytics.Event.SIGN_UP) {
            putString(FirebaseAnalytics.Param.METHOD, method)
        }
    }

    fun login(method: String) {
        logEvent(FirebaseAnalytics.Event.LOGIN) {
            putString(FirebaseAnalytics.Param.METHOD, method)
        }
    }

    fun search(query: String, resultCount: Int, source: String) {
        logEvent(FirebaseAnalytics.Event.SEARCH) {
            putString(FirebaseAnalytics.Param.SEARCH_TERM, query.take(100))
            putLong("result_count", resultCount.toLong())
            putString("source", source)
        }
    }

    fun addToCart(product: Product, quantity: Int) {
        logEvent(FirebaseAnalytics.Event.ADD_TO_CART) {
            putString(FirebaseAnalytics.Param.ITEM_ID, product.id)
            putString(FirebaseAnalytics.Param.ITEM_NAME, product.title)
            putLong(FirebaseAnalytics.Param.QUANTITY, quantity.toLong())
            putDouble(FirebaseAnalytics.Param.VALUE, product.price * quantity)
            putString(FirebaseAnalytics.Param.CURRENCY, "TND")
        }
    }

    fun beginCheckout(itemCount: Int, value: Double) {
        logEvent(FirebaseAnalytics.Event.BEGIN_CHECKOUT) {
            putLong("item_count", itemCount.toLong())
            putDouble(FirebaseAnalytics.Param.VALUE, value)
            putString(FirebaseAnalytics.Param.CURRENCY, "TND")
        }
    }

    fun checkoutStepCompleted(step: Int, name: String) {
        logEvent("checkout_step_completed") {
            putLong("step", step.toLong())
            putString("step_name", name)
        }
    }

    fun viewCart(itemCount: Int, value: Double) {
        logEvent(FirebaseAnalytics.Event.VIEW_CART) {
            putLong("item_count", itemCount.toLong())
            putDouble(FirebaseAnalytics.Param.VALUE, value)
            putString(FirebaseAnalytics.Param.CURRENCY, "TND")
        }
    }

    fun purchase(order: AppOrder) {
        logEvent(FirebaseAnalytics.Event.PURCHASE) {
            putString(FirebaseAnalytics.Param.TRANSACTION_ID, order.id)
            putDouble(FirebaseAnalytics.Param.VALUE, order.total)
            putString(FirebaseAnalytics.Param.CURRENCY, "TND")
            putLong("item_count", order.items.sumOf { it.quantity }.toLong())
        }
    }

    private fun logEvent(name: String, buildParams: Bundle.() -> Unit) {
        runCatching { Firebase.analytics.logEvent(name, Bundle().apply(buildParams)) }
    }
}
