package isim.ia2y.myapplication.voice

import java.util.LinkedList

class FatiVoiceConversationManager {

    private val history = LinkedList<String>()

    fun rememberTurn(text: String) {
        if (text.isBlank()) return
        history.addLast(text.trim())
        while (history.size > 8) {
            history.removeFirst()
        }
    }

    fun recentContext(): String {
        return if (history.isEmpty()) {
            "Aucune conversation précédente."
        } else {
            history.joinToString(" | ")
        }
    }

    fun clear() {
        history.clear()
    }
}
