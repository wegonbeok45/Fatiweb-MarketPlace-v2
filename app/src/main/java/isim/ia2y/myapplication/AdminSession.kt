package isim.ia2y.myapplication

object AdminSession {
    private var verifiedAdminUid: String? = null

    fun isVerified(uid: String): Boolean = verifiedAdminUid == uid

    fun markVerified(uid: String) {
        verifiedAdminUid = uid
    }

    fun clearIfDifferent(uid: String?) {
        if (uid == null || verifiedAdminUid != uid) {
            verifiedAdminUid = null
        }
    }
}
