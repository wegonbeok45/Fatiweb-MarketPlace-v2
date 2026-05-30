package isim.ia2y.myapplication

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await

/**
 * Admin-side vendor management. Reads / writes the vendor lifecycle fields
 * declared in [VendorFields].
 *
 * Reads use [com.google.firebase.firestore.Query.whereEqualTo] on `role`
 * + optionally `vendorStatus`. Writes call `SetOptions.merge()` and stamp
 * the relevant timestamps.
 */
object AdminVendorService {

    data class VendorRow(
        val uid: String,
        val name: String,
        val shopName: String,
        val email: String,
        val phone: String,
        val avatarUrl: String,
        val role: String,
        val status: VendorStatus,
        val applicationSubmittedAt: Long,
        val approvedAt: Long,
        val suspendedReason: String,
    )

    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()
    private fun users() = db.collection(FirestoreCollections.USERS)
    private fun userRef(uid: String) = users().document(uid)

    /**
     * Fetch vendor accounts plus client accounts that submitted a seller request.
     * Pending/rejected applications keep role=client until an admin approves them.
     */
    suspend fun fetchVendors(
        status: VendorStatus? = null,
        limit: Long = 100L,
        source: Source = Source.SERVER,
    ): List<VendorRow> {
        val rowsById = linkedMapOf<String, VendorRow>()

        suspend fun addRows(query: Query) {
            val snap = query.limit(limit).get(source).await() ?: return
            snap.documents.forEach { doc ->
                rowFromDocument(doc)?.let { rowsById[it.uid] = it }
            }
        }

        if (status == null || status == VendorStatus.APPROVED || status == VendorStatus.SUSPENDED) {
            addRows(users().whereEqualTo("role", UserRoles.VENDEUR))
        }
        if (status == null || status == VendorStatus.PENDING) {
            addRows(users().whereEqualTo(VendorFields.STATUS, VendorStatus.PENDING.wireValue))
        }
        if (status == null || status == VendorStatus.REJECTED) {
            addRows(users().whereEqualTo(VendorFields.STATUS, VendorStatus.REJECTED.wireValue))
        }

        return rowsById.values
            .filter { status == null || it.status == status }
            .sortedByDescending { it.applicationSubmittedAt.coerceAtLeast(it.approvedAt) }
    }

    private fun rowFromDocument(doc: DocumentSnapshot): VendorRow? {
        val data = doc.data ?: return null
        return VendorRow(
            uid = doc.id,
            name = data["name"] as? String ?: data["displayName"] as? String ?: "",
            shopName = data[VendorFields.SHOP_NAME] as? String ?: "",
            email = data["email"] as? String ?: "",
            phone = data["phone"] as? String ?: "",
            avatarUrl = data["avatarUrl"] as? String ?: data["avatar"] as? String ?: "",
            role = data["role"] as? String ?: UserRoles.CLIENT,
            status = VendorStatus.fromWire(data[VendorFields.STATUS] as? String),
            applicationSubmittedAt = (data[VendorFields.APPLICATION_SUBMITTED_AT] as? com.google.firebase.Timestamp)
                ?.toDate()?.time ?: 0L,
            approvedAt = (data[VendorFields.APPROVED_AT] as? com.google.firebase.Timestamp)
                ?.toDate()?.time ?: 0L,
            suspendedReason = data[VendorFields.SUSPENDED_REASON] as? String ?: "",
        )
    }

    /** Count vendors awaiting approval — for admin home pending widget. */
    suspend fun countPendingVendors(): Int {
        return runCatching {
            users()
                .whereEqualTo(VendorFields.STATUS, VendorStatus.PENDING.wireValue)
                .count()
                .get(com.google.firebase.firestore.AggregateSource.SERVER)
                .await()
                .count.toInt()
        }.getOrDefault(0)
    }

    /** Count approved vendors. */
    suspend fun countApprovedVendors(): Int {
        return runCatching {
            users()
                .whereEqualTo("role", UserRoles.VENDEUR)
                .count()
                .get(com.google.firebase.firestore.AggregateSource.SERVER)
                .await()
                .count.toInt()
        }.getOrDefault(0)
    }

    suspend fun approve(uid: String) {
        AdminService.promoteUserToVendeur(uid)
        userRef(uid).set(
            mapOf(
                VendorFields.STATUS to VendorStatus.APPROVED.wireValue,
                VendorFields.APPROVED_AT to FieldValue.serverTimestamp(),
                VendorFields.SUSPENDED_REASON to "",
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
            SetOptions.merge(),
        ).await()
    }

    suspend fun reject(uid: String, reason: String = "") {
        userRef(uid).set(
            mapOf(
                "role" to UserRoles.CLIENT,
                VendorFields.STATUS to VendorStatus.REJECTED.wireValue,
                VendorFields.SUSPENDED_REASON to reason,
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
            SetOptions.merge(),
        ).await()
    }

    suspend fun suspend(uid: String, reason: String = "") {
        userRef(uid).set(
            mapOf(
                VendorFields.STATUS to VendorStatus.SUSPENDED.wireValue,
                VendorFields.SUSPENDED_REASON to reason,
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
            SetOptions.merge(),
        ).await()
    }

    suspend fun restoreToPending(uid: String) {
        userRef(uid).set(
            mapOf(
                VendorFields.STATUS to VendorStatus.PENDING.wireValue,
                VendorFields.SUSPENDED_REASON to "",
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
            SetOptions.merge(),
        ).await()
    }
}
