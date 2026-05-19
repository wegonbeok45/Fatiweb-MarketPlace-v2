package isim.ia2y.myapplication

import android.content.Intent

const val EXTRA_AUTH_RETURN_TAB = "auth_return_tab"
const val EXTRA_AUTH_RETURN_ROUTE = "auth_return_route"
const val EXTRA_GUEST_CHECKOUT = "guest_checkout"

const val AUTH_RETURN_ROUTE_CHECKOUT = "checkout"
const val AUTH_RETURN_ROUTE_ORDERS = "orders"
const val AUTH_RETURN_ROUTE_CART = "cart"

fun Intent.withAuthReturn(
    tab: MainActivity.Tab? = null,
    route: String? = null
): Intent = apply {
    if (tab != null) putExtra(EXTRA_AUTH_RETURN_TAB, tab.name)
    if (!route.isNullOrBlank()) putExtra(EXTRA_AUTH_RETURN_ROUTE, route)
}

fun Intent.copyAuthReturnFrom(source: Intent): Intent = apply {
    source.getStringExtra(EXTRA_AUTH_RETURN_TAB)?.let { putExtra(EXTRA_AUTH_RETURN_TAB, it) }
    source.getStringExtra(EXTRA_AUTH_RETURN_ROUTE)?.let { putExtra(EXTRA_AUTH_RETURN_ROUTE, it) }
}

fun Intent.authReturnTab(): MainActivity.Tab? =
    getStringExtra(EXTRA_AUTH_RETURN_TAB)?.let { raw ->
        runCatching { MainActivity.Tab.valueOf(raw) }.getOrNull()
    }

fun Intent.authReturnRoute(): String? = getStringExtra(EXTRA_AUTH_RETURN_ROUTE)
