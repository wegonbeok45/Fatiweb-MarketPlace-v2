package isim.ia2y.myapplication

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.tasks.await

object NotificationService {
    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()
    private val notificationsRef = db.collection(FirestoreCollections.IN_APP_NOTIFICATIONS)
    private fun inboxRef(uid: String) = db.collection(FirestoreCollections.USERS)
        .document(uid)
        .collection(FirestoreCollections.INBOX)

    suspend fun fetchNotifications(): List<FirestoreService.InAppNotification> {
        val snapshot = notificationsRef
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(50)
            .get()
            .await()
        return snapshot.documents.mapNotNull { doc ->
            val data = doc.data ?: return@mapNotNull null
            FirestoreService.InAppNotification(
                id = doc.id,
                title = data["title"] as? String ?: "",
                message = data["message"] as? String ?: "",
                createdAt = data["createdAt"].toEpochMillis(),
                createdBy = data["createdBy"] as? String ?: "",
                audience = data["audience"] as? String ?: "all"
            ).takeIf { isValidNotificationCopy(it.title, it.message) }
        }
    }

    suspend fun fetchUserInboxNotifications(uid: String): List<AppNotification> {
        val snapshot = inboxRef(uid)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(50)
            .get()
            .await()
        return snapshot.documents.mapNotNull { doc ->
            val data = doc.data ?: return@mapNotNull null
            val message = (data["body"] as? String)
                ?.takeIf { it.isNotBlank() }
                ?: (data["message"] as? String).orEmpty()

            AppNotification(
                id = doc.id,
                title = data["title"] as? String ?: "",
                message = message,
                timestamp = data["createdAt"].toEpochMillis(),
                isRead = data["readAt"] != null
            ).takeIf { isValidNotificationCopy(it.title, it.message) }
        }
    }

    suspend fun fetchInAppNotifications(): List<FirestoreService.InAppNotification> = fetchNotifications()

    suspend fun createInAppNotification(
        title: String,
        message: String,
        createdBy: String,
        audience: String = "all"
    ): FirestoreService.InAppNotification {
        check(createdBy.isNotBlank()) { "createdBy is required." }
        validateNotificationCopyOrThrow(title, message)
        return runCatching {
            BackendFunctionsService.sendAnnouncement(title, message, audience)
        }.recoverCatching { error ->
            if (TrustedMutationFallbackPolicy.allowDirectWriteFallback(error) && canWriteAnnouncementsDirectly()) {
                directCreateAnnouncement(title, message, createdBy, audience)
            } else {
                throw error
            }
        }.getOrThrow()
    }

    suspend fun markNotificationsRead(uid: String, notificationIds: Set<String>) {
        if (notificationIds.isEmpty()) return

        val snapshot = inboxRef(uid).get().await()
        val batch = db.batch()
        val now = System.currentTimeMillis()
        var pendingWrites = 0

        snapshot.documents.forEach { doc ->
            if (doc.id !in notificationIds || doc.get("readAt") != null) return@forEach
            batch.update(
                doc.reference,
                mapOf(
                    "readAt" to now,
                    "updatedAt" to now
                )
            )
            pendingWrites += 1
        }

        if (pendingWrites > 0) {
            batch.commit().await()
        }
    }

    suspend fun fetchNotificationReadIds(uid: String): Set<String> {
        val snapshot = inboxRef(uid).get().await()
        return snapshot.documents
            .filter { it.get("readAt") != null }
            .map { it.id }
            .toSet()
    }

    suspend fun replaceNotificationReadIds(uid: String, readIds: Set<String>) {
        val existing = inboxRef(uid).get().await()
        val batch = db.batch()
        val now = System.currentTimeMillis()

        var pendingWrites = 0
        existing.documents.forEach { doc ->
            val shouldBeRead = doc.id in readIds
            val isRead = doc.get("readAt") != null
            if (shouldBeRead == isRead) return@forEach

            batch.update(
                doc.reference,
                mapOf(
                    "readAt" to if (shouldBeRead) now else null,
                    "updatedAt" to now
                )
            )
            pendingWrites += 1
        }

        if (pendingWrites > 0) {
            batch.commit().await()
        }
    }

    suspend fun markNotificationRead(uid: String, notificationId: String) {
        markNotificationsRead(uid, setOf(notificationId))
    }

    suspend fun isNotificationRead(uid: String, notificationId: String): Boolean {
        val snapshot = inboxRef(uid).document(notificationId).get().await()
        return snapshot.get("readAt") != null
    }

    private suspend fun canWriteAnnouncementsDirectly(): Boolean {
        val uid = FirebaseAuthManager.currentUser?.uid ?: return false
        return AdminSession.isVerified(uid) || UserService.fetchUserRole(uid) == "admin"
    }

    private suspend fun directCreateAnnouncement(
        title: String,
        message: String,
        createdBy: String,
        audience: String
    ): FirestoreService.InAppNotification {
        val notificationDoc = notificationsRef.document()
        val notificationId = notificationDoc.id
        val createdAt = System.currentTimeMillis()
        val recipients = resolveAudienceUserIds(audience)

        notificationDoc.set(
            mapOf(
                "title" to title,
                "message" to message,
                "body" to message,
                "createdBy" to createdBy,
                "audience" to audience,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()

        recipients.chunked(350).forEach { chunk ->
            val batch = db.batch()
            chunk.forEach { userId ->
                batch.set(
                    inboxRef(userId).document(notificationId),
                    mapOf(
                        "title" to title,
                        "message" to message,
                        "body" to message,
                        "createdBy" to createdBy,
                        "audience" to audience,
                        "notificationId" to notificationId,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
            batch.commit().await()
        }

        return FirestoreService.InAppNotification(
            id = notificationId,
            title = title,
            message = message,
            createdAt = createdAt,
            createdBy = createdBy,
            audience = audience
        )
    }

    private suspend fun resolveAudienceUserIds(audience: String): List<String> {
        val snapshot = db.collection(FirestoreCollections.USERS).get().await()
        return snapshot.documents.mapNotNull { doc ->
            val uid = (doc.getString("uid") ?: doc.id).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val role = doc.getString("role") ?: "client"
            when (audience) {
                "admins" -> uid.takeIf { role == "admin" }
                "clients" -> uid.takeIf { role != "admin" }
                else -> uid
            }
        }.distinct()
    }
}

private fun validateNotificationCopyOrThrow(title: String, message: String) {
    require(isValidNotificationCopy(title, message)) {
        "Announcement content looks malformed. Please use a clear title and message."
    }
}

private fun Any?.toEpochMillis(): Long = when (this) {
    is Number -> this.toLong()
    is com.google.firebase.Timestamp -> this.toDate().time
    is Map<*, *> -> (this["seconds"] as? Number)?.toLong()?.times(1000L) ?: 0L
    else -> 0L
}

private fun isValidNotificationCopy(title: String, message: String): Boolean {
    val normalizedTitle = title.trim().lowercase()
    val normalizedMessage = message.trim().lowercase()
    if (normalizedTitle.isBlank() || normalizedMessage.length < 8) return false

    val blockedSamples = listOf("juuui", "rrrtzfh", "bienvznuz", "hello")
    if (blockedSamples.any { it in normalizedTitle || it in normalizedMessage }) return false

    return !looksLikeKeyboardMashing(normalizedTitle) && !looksLikeKeyboardMashing(normalizedMessage)
}

private fun looksLikeKeyboardMashing(text: String): Boolean {
    val letters = text.count { it.isLetter() }
    if (letters < 4) return false

    val consecutiveConsonants = Regex("[bcdfghjklmnpqrstvwxz]{5,}")
    if (consecutiveConsonants.containsMatchIn(text)) return true

    val vowelCount = text.count { it in "aeiouy" }
    return vowelCount == 0
}
