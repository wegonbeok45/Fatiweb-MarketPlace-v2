package isim.ia2y.myapplication

import java.io.IOException

/**
 * AI chat service.
 *
 * Vendor-agnostic wrapper around the marketplace assistant. Requests are
 * routed through Firebase Functions ([BackendFunctionsService.assistantSendMessage])
 * so the AI provider key and private context stay off-device.
 *
 * Currently backed by xAI Grok (migrated from Google Gemini). Swapping the
 * provider is a backend-only change — this file does not need to know.
 */
object AssistantChatService {

    private var lastRequestTime = 0L
    private const val COOLDOWN_MS = 3000L

    suspend fun sendMessage(
        history: List<ChatMessage>,
        userId: String? = null,
    ): String {
        val now = System.currentTimeMillis()
        if (now - lastRequestTime < COOLDOWN_MS) {
            throw IOException("RATE_LIMIT")
        }
        lastRequestTime = now
        return runCatching {
            BackendFunctionsService.assistantSendMessage(history, userId)
        }.recoverCatching { error ->
            localFallbackReply(history)?.let { return@recoverCatching it }
            throw error
        }.getOrThrow()
    }

    private fun localFallbackReply(history: List<ChatMessage>): String? {
        val lastUserText = history
            .lastOrNull { it.role == ChatMessage.Role.USER && !it.isLoading }
            ?.text
            ?.trim()
            .orEmpty()
        if (lastUserText.isBlank()) return null

        val normalized = lastUserText.lowercase()
        val wantsFrench = listOf("livraison", "commande", "produit", "paiement", "delai", "délai")
            .any { normalized.contains(it) }

        return when {
            normalized.contains("delivery") ||
                normalized.contains("shipping") ||
                normalized.contains("livraison") ||
                normalized.contains("delai") ||
                normalized.contains("délai") -> {
                if (wantsFrench) {
                    "La livraison standard coute 7 DT et la livraison express coute 12.5 DT. Le delai indique dans l'application est generalement de 3 a 5 jours apres confirmation. Pour un colis precis, ouvrez Profil > Commandes."
                } else {
                    "Standard shipping is 7 DT and express shipping is 12.5 DT. The in-app estimate is usually 3 to 5 days after confirmation. For a specific package, open Profile > Orders."
                }
            }
            normalized.contains("order") ||
                normalized.contains("track") ||
                normalized.contains("commande") ||
                normalized.contains("suivre") -> {
                if (wantsFrench) {
                    "Pour suivre une commande, ouvrez Profil > Commandes. Si le statut semble incorrect, contactez support@fatiweb.tn."
                } else {
                    "To track an order, open Profile > Orders. If the status looks wrong, contact support@fatiweb.tn."
                }
            }
            normalized.contains("payment") ||
                normalized.contains("pay") ||
                normalized.contains("paiement") -> {
                if (wantsFrench) {
                    "Le paiement disponible est le paiement a la livraison."
                } else {
                    "The available payment method is cash on delivery."
                }
            }
            normalized.contains("product") ||
                normalized.contains("popular") ||
                normalized.contains("produit") -> {
                val products = ProductCatalog.all(includeInactive = false).take(3)
                if (products.isEmpty()) {
                    if (wantsFrench) {
                        "Le catalogue est en cours de chargement. Reessayez dans un instant pour voir les produits disponibles."
                    } else {
                        "The catalog is still loading. Try again in a moment to see available products."
                    }
                } else {
                    val summary = products.joinToString("; ") { "${it.title} - ${it.price} DT" }
                    if (wantsFrench) {
                        "Voici quelques produits disponibles : $summary."
                    } else {
                        "Here are a few available products: $summary."
                    }
                }
            }
            normalized.contains("support") ||
                normalized.contains("help") ||
                normalized.contains("aide") -> {
                if (wantsFrench) {
                    "Pour le support client, envoyez un email a support@fatiweb.tn."
                } else {
                    "For customer support, email support@fatiweb.tn."
                }
            }
            else -> null
        }
    }
}
