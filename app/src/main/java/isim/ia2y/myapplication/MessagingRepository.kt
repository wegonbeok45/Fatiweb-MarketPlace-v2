package isim.ia2y.myapplication

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import java.util.UUID

object MessagingRepository {
    private const val CONVERSATIONS = "conversations"
    private const val MESSAGES = "messages"
    private const val PAGE_SIZE = 50L

    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val conversationCacheByUid = mutableMapOf<String, List<Conversation>>()
    private val conversationCacheById = mutableMapOf<String, Conversation>()
    private val messagesCacheByConversationId = mutableMapOf<String, Pair<List<ConversationMessage>, DocumentSnapshot?>>()

    suspend fun openOrCreateProductConversation(productId: String): String =
        BackendFunctionsService.openOrCreateConversation(productId)

    suspend fun openOrCreateProductConversation(product: Product): String {
        val currentUid = FirebaseAuthManager.currentUser?.uid.orEmpty()
        val sellerId = product.sellerId
        if (currentUid.isBlank() || sellerId.isBlank()) {
            return openOrCreateProductConversation(product.id)
        }

        val expectedId = conversationIdFor(currentUid, sellerId, product.id)
        conversationCacheById[expectedId]?.let { return expectedId }

        runCatching { fetchConversation(expectedId, Source.CACHE) }
            .getOrNull()
            ?.let { conversation ->
                rememberConversation(conversation, currentUid)
                return expectedId
            }

        return withTimeoutOrNull(12_000L) {
            openOrCreateProductConversation(product.id)
        } ?: throw IllegalStateException("Messaging took too long to respond.")
    }

    fun listenConversations(
        uid: String,
        onChange: (List<Conversation>) -> Unit,
        onError: (Throwable) -> Unit
    ): ListenerRegistration {
        conversationCacheByUid[uid]?.takeIf { it.isNotEmpty() }?.let(onChange)
        return db.collection(CONVERSATIONS)
            .whereArrayContains("participantIds", uid)
            .orderBy("lastMessageAt", Query.Direction.DESCENDING)
            .limit(80)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    if (conversationCacheByUid[uid].isNullOrEmpty()) {
                        onError(error)
                    } else {
                        onChange(conversationCacheByUid[uid].orEmpty())
                    }
                    return@addSnapshotListener
                }
                val conversations = snapshot?.documents.orEmpty()
                    .mapNotNull { document -> document.data?.let { Conversation.fromMap(document.id, it) } }
                    .filter { it.status == "active" }
                    .filterNot { uid in it.hiddenFor }
                conversationCacheByUid[uid] = conversations
                conversations.forEach { conversationCacheById[it.id] = it }
                onChange(conversations)
            }
    }

    fun listenUnreadTotal(
        uid: String,
        onChange: (Int) -> Unit,
        onError: (Throwable) -> Unit = {}
    ): ListenerRegistration {
        return listenConversations(
            uid = uid,
            onChange = { conversations -> onChange(conversations.sumOf { it.unreadCounts[uid] ?: 0 }) },
            onError = onError
        )
    }

    fun listenMessages(
        conversationId: String,
        onChange: (List<ConversationMessage>, DocumentSnapshot?) -> Unit,
        onError: (Throwable) -> Unit
    ): ListenerRegistration {
        messagesCacheByConversationId[conversationId]?.let { (messages, cursor) ->
            if (messages.isNotEmpty()) onChange(messages, cursor)
        }
        return db.collection(CONVERSATIONS)
            .document(conversationId)
            .collection(MESSAGES)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(PAGE_SIZE)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    onError(error)
                    return@addSnapshotListener
                }
                val messages = snapshot?.documents.orEmpty()
                    .mapNotNull { document -> document.data?.let { ConversationMessage.fromMap(document.id, it) } }
                    .asReversed()
                val cursor = snapshot?.documents?.lastOrNull()
                messagesCacheByConversationId[conversationId] = messages to cursor
                onChange(messages, cursor)
            }
    }

    suspend fun loadOlderMessages(
        conversationId: String,
        cursor: DocumentSnapshot?
    ): Pair<List<ConversationMessage>, DocumentSnapshot?> {
        if (cursor == null) return emptyList<ConversationMessage>() to null
        val snapshot = db.collection(CONVERSATIONS)
            .document(conversationId)
            .collection(MESSAGES)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .startAfter(cursor)
            .limit(PAGE_SIZE)
            .get()
            .await()
        val messages = snapshot.documents
            .mapNotNull { document -> document.data?.let { ConversationMessage.fromMap(document.id, it) } }
            .asReversed()
        return messages to snapshot.documents.lastOrNull()
    }

    suspend fun getConversation(conversationId: String): Conversation {
        conversationCacheById[conversationId]?.let { return it }
        val cached = runCatching { fetchConversation(conversationId, Source.CACHE) }.getOrNull()
        if (cached != null) {
            rememberConversation(cached)
        }
        val fresh = withTimeoutOrNull(5_000L) {
            runCatching { fetchConversation(conversationId, Source.DEFAULT) }.getOrNull()
        }
        return (fresh ?: cached)?.also { rememberConversation(it) }
            ?: throw IllegalStateException("Conversation not found.")
    }

    private suspend fun fetchConversation(conversationId: String, source: Source): Conversation {
        val snapshot = db.collection(CONVERSATIONS).document(conversationId).get(source).await()
        val data = snapshot.data ?: throw IllegalStateException("Conversation not found.")
        return Conversation.fromMap(snapshot.id, data)
    }

    private fun rememberConversation(conversation: Conversation, uidHint: String? = null) {
        conversationCacheById[conversation.id] = conversation
        val uids = (listOfNotNull(uidHint) + conversation.participantIds).filter { it.isNotBlank() }.distinct()
        uids.forEach { uid ->
            val next = (listOf(conversation) + conversationCacheByUid[uid].orEmpty())
                .distinctBy { it.id }
                .filter { it.status == "active" && uid !in it.hiddenFor }
                .sortedByDescending { it.lastMessageAt }
            conversationCacheByUid[uid] = next
        }
    }

    suspend fun sendText(conversationId: String, text: String): String {
        val id = newClientMessageId()
        BackendFunctionsService.sendConversationMessage(
            conversationId = conversationId,
            clientMessageId = id,
            type = "text",
            text = text
        )
        return id
    }

    suspend fun sendImage(
        conversationId: String,
        clientMessageId: String,
        imageUrl: String,
        thumbnailUrl: String,
        storagePath: String
    ): String {
        BackendFunctionsService.sendConversationMessage(
            conversationId = conversationId,
            clientMessageId = clientMessageId,
            type = "image",
            imageUrl = imageUrl,
            thumbnailUrl = thumbnailUrl,
            storagePath = storagePath
        )
        return clientMessageId
    }

    suspend fun markRead(conversationId: String) {
        BackendFunctionsService.markConversationRead(conversationId)
    }

    suspend fun toggleHeart(conversationId: String, messageId: String) {
        BackendFunctionsService.toggleConversationMessageReaction(conversationId, messageId)
    }

    suspend fun hide(conversationId: String) {
        BackendFunctionsService.hideConversation(conversationId)
    }

    suspend fun block(userId: String) {
        BackendFunctionsService.blockConversationUser(userId)
    }

    suspend fun report(conversationId: String, messageId: String, reason: String) {
        BackendFunctionsService.reportConversationMessage(conversationId, messageId, reason)
    }

    fun newClientMessageId(): String = "android_${UUID.randomUUID().toString().replace("-", "")}"

    private fun conversationIdFor(buyerId: String, sellerId: String, productId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$buyerId|$sellerId|$productId".toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }.take(40)
    }
}
