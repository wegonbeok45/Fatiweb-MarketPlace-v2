package isim.ia2y.myapplication

// Cette classe organise cette partie de l'app.
object AdminSession {
    private var verifiedAdminUid: String? = null

    // Cette fonction fait une action de cette partie de l'app.
    fun isVerified(uid: String): Boolean = verifiedAdminUid == uid

    // Cette fonction fait une action de cette partie de l'app.
    fun markVerified(uid: String) {
        verifiedAdminUid = uid
    }

    // Cette fonction fait une action de cette partie de l'app.
    fun clearIfDifferent(uid: String?) {
        if (uid == null || verifiedAdminUid != uid) {
            verifiedAdminUid = null
        }
    }
}
