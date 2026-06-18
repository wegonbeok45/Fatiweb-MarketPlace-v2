package isim.ia2y.myapplication.voice

import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager

object AccessibilityHelper {

    private fun announce(context: Context, message: String) {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        if (manager?.isEnabled == true) {
            val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT)
            event.text.add(message)
            event.className = AccessibilityHelper::class.java.name
            event.packageName = context.packageName
            manager.sendAccessibilityEvent(event)
        }
    }

    fun announceOpenHome(context: Context) {
        announce(context, "J'ouvre la page d'accueil")
    }

    fun announceOpenCart(context: Context) {
        announce(context, "J'ouvre votre panier")
    }

    fun announceOpenOrders(context: Context) {
        announce(context, "J'ouvre vos commandes")
    }

    fun announceSelectHomeDelivery(context: Context) {
        announce(context, "Je sélectionne livraison à domicile")
    }

    fun announceFillField(context: Context, fieldName: String) {
        announce(context, "Je remplis le champ $fieldName")
    }

    fun announceOrderConfirmed(context: Context) {
        announce(context, "Votre commande est confirmée")
    }
}
