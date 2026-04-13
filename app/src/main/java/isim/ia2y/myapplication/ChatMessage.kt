package isim.ia2y.myapplication

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: Role = Role.USER,
    val text: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val isLoading: Boolean = false
) {
    enum class Role { USER, BOT }
}
