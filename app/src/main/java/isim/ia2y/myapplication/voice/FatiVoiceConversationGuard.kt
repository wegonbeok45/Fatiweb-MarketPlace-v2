package isim.ia2y.myapplication.voice

object FatiVoiceConversationGuard {

    fun canOpenMicrophone(isSpeaking: Boolean, isListeningActive: Boolean): Boolean {
        return !isSpeaking && !isListeningActive
    }

    fun shouldPauseListening(isSpeaking: Boolean): Boolean {
        return isSpeaking
    }
}
