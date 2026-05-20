package isim.ia2y.myapplication

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists the last N messages of each conversation to a local JSON file so
 * the chat screen shows recent history instantly on reopen, before the
 * Firestore listener delivers its first snapshot.
 *
 * Files are stored at: <filesDir>/chat_cache/<conversationId>.json
 * Each file holds at most [MAX_CACHED_MESSAGES] confirmed (non-pending) messages.
 */
object ConversationCache {
    private const val TAG = "ConversationCache"
    private const val MAX_CACHED_MESSAGES = 200
    private const val DIR_NAME = "chat_cache"

    // ── Public API ────────────────────────────────────────────────────────────

    /** Synchronously read cached messages. Returns empty list on any error. */
    fun load(context: Context, conversationId: String): List<ConversationMessage> {
        if (conversationId.isBlank()) return emptyList()
        return runCatching {
            val file = cacheFile(context, conversationId)
            if (!file.exists()) return emptyList()
            val json = file.readText()
            val arr = JSONArray(json)
            val result = mutableListOf<ConversationMessage>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                result.add(fromJson(obj))
            }
            result
        }.onFailure { Log.w(TAG, "load($conversationId) failed", it) }
            .getOrDefault(emptyList())
    }

    /**
     * Persist [messages] for [conversationId]. Skips local-pending messages
     * (those are transient). Capped at [MAX_CACHED_MESSAGES] most-recent.
     * Call on a background thread (IO dispatcher or similar).
     */
    fun save(context: Context, conversationId: String, messages: List<ConversationMessage>) {
        if (conversationId.isBlank()) return
        runCatching {
            val confirmed = messages
                .filter { !it.isLocalPending }
                .takeLast(MAX_CACHED_MESSAGES)
            val arr = JSONArray()
            confirmed.forEach { arr.put(toJson(it)) }
            val file = cacheFile(context, conversationId)
            file.parentFile?.mkdirs()
            file.writeText(arr.toString())
        }.onFailure { Log.w(TAG, "save($conversationId) failed", it) }
    }

    /** Delete the cache for a specific conversation (e.g. on account sign-out). */
    fun clear(context: Context, conversationId: String) {
        runCatching { cacheFile(context, conversationId).delete() }
    }

    /** Delete all cached conversations (e.g. on sign-out). */
    fun clearAll(context: Context) {
        runCatching { cacheDir(context).deleteRecursively() }
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun cacheDir(context: Context) =
        File(context.applicationContext.filesDir, DIR_NAME)

    private fun cacheFile(context: Context, conversationId: String): File {
        // Sanitize: only keep alphanumeric + safe chars to avoid path traversal
        val safe = conversationId.replace(Regex("[^A-Za-z0-9_\\-]"), "_")
        return File(cacheDir(context), "$safe.json")
    }

    private fun toJson(msg: ConversationMessage): JSONObject = JSONObject().apply {
        put("id", msg.id)
        put("conversationId", msg.conversationId)
        put("senderId", msg.senderId)
        put("receiverId", msg.receiverId)
        put("type", msg.type)
        put("text", msg.text)
        put("imageUrl", msg.imageUrl)
        put("thumbnailUrl", msg.thumbnailUrl)
        put("storagePath", msg.storagePath)
        put("createdAt", msg.createdAt)
        put("deliveryStatus", msg.deliveryStatus)
        put("clientMessageId", msg.clientMessageId)
        // readBy: Map<String,Long>
        val readByObj = JSONObject()
        msg.readBy.forEach { (k, v) -> readByObj.put(k, v) }
        put("readBy", readByObj)
        // reactions: Map<String,String>
        val reactionsObj = JSONObject()
        msg.reactions.forEach { (k, v) -> reactionsObj.put(k, v) }
        put("reactions", reactionsObj)
    }

    private fun fromJson(obj: JSONObject): ConversationMessage {
        val readBy = mutableMapOf<String, Long>()
        obj.optJSONObject("readBy")?.let { rb ->
            rb.keys().forEach { k -> readBy[k] = rb.getLong(k) }
        }
        val reactions = mutableMapOf<String, String>()
        obj.optJSONObject("reactions")?.let { re ->
            re.keys().forEach { k -> reactions[k] = re.getString(k) }
        }
        return ConversationMessage(
            id = obj.optString("id"),
            conversationId = obj.optString("conversationId"),
            senderId = obj.optString("senderId"),
            receiverId = obj.optString("receiverId"),
            type = obj.optString("type", "text"),
            text = obj.optString("text"),
            imageUrl = obj.optString("imageUrl"),
            thumbnailUrl = obj.optString("thumbnailUrl"),
            storagePath = obj.optString("storagePath"),
            createdAt = obj.optLong("createdAt"),
            readBy = readBy,
            reactions = reactions,
            deliveryStatus = obj.optString("deliveryStatus", "sent"),
            clientMessageId = obj.optString("clientMessageId"),
            isLocalPending = false,
            localProgress = 0,
            localError = null
        )
    }
}
