package isim.ia2y.myapplication.voice

import android.util.Patterns
import java.text.Normalizer
import java.util.Locale

data class VoiceContext(
    val lastIntent: VoiceIntent? = null,
    val lastParams: Map<String, String> = emptyMap()
)

enum class VoiceIntent {
    SEARCH_PRODUCT,
    ORDER_PRODUCT,
    QUERY_ORDER_STATUS,
    RESUME_SESSION,
    CONFIRM_ACTION,
    CANCEL_ACTION,
    ENABLE_VOICE,
    DISABLE_VOICE,
    GO_TO_CART,
    GO_TO_ORDERS,
    GO_HOME,
    PROVIDE_NAME,
    PROVIDE_PHONE,
    PROVIDE_ADDRESS,
    SELECT_DELIVERY,
    MODIFY_ADDRESS,        // ✅ NEW
    UNKNOWN
}

object FatiVoiceIntentHandler {

    private fun normalize(text: String): String {
        val normalized = Normalizer.normalize(text.trim(), Normalizer.Form.NFD)
        return normalized.lowercase(Locale.ROOT)
            .replace("\\p{Mn}+".toRegex(), "")
            .replace("’", "'")
            .replace("\u00a0", " ")            .replace("[^\\p{L}\\p{Nd}'\\s]+".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")            .trim()
    }

    private fun containsAny(text: String, vararg phrases: String): Boolean {
        return phrases.any { phrase -> text.contains(phrase) }
    }

    fun detectIntent(speech: String, context: VoiceContext): VoiceIntent {
        val s = normalize(speech)
        if (s.isEmpty()) return VoiceIntent.UNKNOWN

        // Voice activation / deactivation
        if (containsAny(s,
                "activer fati",
                "activer l'assistant",
                "allumer fati",
                "allumer l'assistant"
            )) {
            return VoiceIntent.ENABLE_VOICE
        }
        if (containsAny(s,
                "desactiver fativoice",
                "désactiver fativoice",
                "arreter la voix",
                "arrêter la voix",
                "desactiver",
                "arreter",
                "arrêter",
                "stop",
                "fermer l'assistant",
                "quitter",
                "bye bye",
                "bye",
                "au revoir"
            )) {
            return VoiceIntent.DISABLE_VOICE
        }

        // ✅ NEW: Address modification intent
        if (containsAny(s,
                "modifier",
                "changer",
                "éditer",
                "editer",
                "nouvelle adresse",
                "change adresse",
                "changer adresse",
                "modifier adresse",
                "autre adresse"
            )) {
            return VoiceIntent.MODIFY_ADDRESS
        }

        // Resume previous session
        if (containsAny(s, "reprendre", "continuer", "poursuivre", "revenir")) {
            return VoiceIntent.RESUME_SESSION
        }

        // Order status query
        if (containsAny(s,
                "statut",
                "ou est ma commande",
                "ou en est ma commande",
                "etat de ma commande",
                "état de ma commande"
            )) {
            return VoiceIntent.QUERY_ORDER_STATUS
        }

        // Confirmation / agreement
        if (containsAny(s, "confirmer", "oui", "d'accord", "ok", "valider", "confirme")) {
            return VoiceIntent.CONFIRM_ACTION
        }

        // Add-to-cart / order intent
        if (containsAny(s,
                "ajouter au panier",
                "ajoute au panier",
                "ajouter",
                "ajoute",
                "mettre au panier",
                "commander",
                "acheter",
                "passer commande",
                "je veux acheter",
                "je veux commander",
                "je veux un",
                "je veux une",
                "je veux des",
                "je voudrais",
                "je voudrais un",
                "je voudrais une",
                "j'aimerais",
                "j aimerais",
                "donne moi",
                "donnez moi",
                "montre moi",
                "montre-moi",
                "montrez moi",
                "montrez-moi",
                "affiche moi",
                "affiche-moi"
            )) {
            return VoiceIntent.ORDER_PRODUCT
        }

        // Search intent
        if (containsAny(s,
                "rechercher",
                "recherche",
                "cherche",
                "chercher",
                "trouver",
                "voir",
                "cherchez",
                "afficher",
                "quels articles",
                "quels produits",
                "que conseillez",
                "que recommandez",
                "articles vendez",
                "produits vendez",
                "pour un bébé",
                "pour bebe",
                "vêtement",
                "vetement",
                "bébé",
                "bebe",
                "bleu",
                "robe",
                "t-shirt",
                "tshirt",
                "je cherche un",
                "je cherche une",
                "je veux un",
                "je veux une",
                "je voudrais un",
                "je voudrais une",
                "j'aimerais un",
                "j aimerais un",
                "montre moi",
                "montre-moi",
                "montrez moi",
                "montrez-moi",
                "donne moi",
                "donnez moi"
            )) {
            return VoiceIntent.SEARCH_PRODUCT
        }

        // Fallback heuristics for product requests without explicit search words.
        if (containsAny(s,
                "je veux",
                "je voudrais",
                "j'aimerais",
                "donne moi",
                "donnez moi",
                "montre moi",
                "montre-moi",
                "montrez moi",
                "montrez-moi"
            ) && s.contains(Regex("\\b(un|une|des|le|la|les|ce|cette|mon|ma|mes)\\b"))) {
            return VoiceIntent.SEARCH_PRODUCT
        }

        // Cart / orders / home
        if (containsAny(s, "panier", "mon panier", "ouvrir le panier")) return VoiceIntent.GO_TO_CART
        if (containsAny(s, "commandes", "mes commandes", "suivi commande")) return VoiceIntent.GO_TO_ORDERS
        if (containsAny(s, "accueil", "retour", "page d'accueil")) return VoiceIntent.GO_HOME

        // Phone number heuristic
        val digits = s.filter { it.isDigit() }
        if (digits.length >= 8) return VoiceIntent.PROVIDE_PHONE

        // Name heuristic
        if (s.matches(Regex("""[a-zàâçéèêëîïôûùüÿñæœ'\-]+ [a-zàâçéèêëîïôûùüÿñæœ'\-]+"""))) {
            return VoiceIntent.PROVIDE_NAME
        }

        // Address heuristic
        val addressKeywords = listOf("rue", "avenue", "boulevard", "lot", "zone", "imm", "appartement", "cite")
        if (addressKeywords.any { s.contains(it) }) return VoiceIntent.PROVIDE_ADDRESS

        return VoiceIntent.UNKNOWN
    }
}
