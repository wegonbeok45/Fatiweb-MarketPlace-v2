package isim.ia2y.myapplication

import android.content.Context
import org.json.JSONObject

data class UserPersonalDetails(
    val firstName: String = "",
    val lastName: String = "",
    val email: String = "",
    val phone: String = "",
    val birthday: String = "",
    val gender: String = "",
    val bio: String = ""
) {
    val fullName: String
        get() = listOf(firstName.trim(), lastName.trim())
            .filter { it.isNotBlank() }
            .joinToString(" ")
}

object PersonalDetailsStore {
    private const val PREFS_NAME = "personal_details_store"
    private const val KEY_DETAILS = "details"
    private const val GUEST_KEY = "guest"

    private fun prefs(context: Context) =
        context.getSharedPreferences("${accountKey()}_$PREFS_NAME", Context.MODE_PRIVATE)

    private fun accountKey(): String = FirebaseAuthManager.currentUser?.uid ?: GUEST_KEY

    fun load(context: Context): UserPersonalDetails {
        val saved = parse(prefs(context).getString(KEY_DETAILS, null))
        val user = FirebaseAuthManager.currentUser
        val address = AddressBookStore.getCurrent(context)
        val (defaultFirst, defaultLast) = splitDisplayName(user?.displayName)

        return UserPersonalDetails(
            firstName = saved?.firstName?.ifBlank { defaultFirst } ?: defaultFirst,
            lastName = saved?.lastName?.ifBlank { defaultLast } ?: defaultLast,
            email = user?.email?.takeIf { it.isNotBlank() } ?: saved?.email.orEmpty(),
            phone = saved?.phone?.ifBlank { address?.phone.orEmpty() } ?: address?.phone.orEmpty(),
            birthday = saved?.birthday.orEmpty(),
            gender = saved?.gender.orEmpty(),
            bio = saved?.bio.orEmpty()
        )
    }

    fun save(context: Context, details: UserPersonalDetails) {
        val payload = JSONObject().apply {
            put("firstName", details.firstName.trim())
            put("lastName", details.lastName.trim())
            put("email", details.email.trim())
            put("phone", details.phone.trim())
            put("birthday", details.birthday.trim())
            put("gender", details.gender.trim())
            put("bio", details.bio.trim())
        }
        prefs(context).edit().putString(KEY_DETAILS, payload.toString()).apply()
    }

    private fun parse(raw: String?): UserPersonalDetails? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val json = JSONObject(raw)
            UserPersonalDetails(
                firstName = json.optString("firstName"),
                lastName = json.optString("lastName"),
                email = json.optString("email"),
                phone = json.optString("phone"),
                birthday = json.optString("birthday"),
                gender = json.optString("gender"),
                bio = json.optString("bio")
            )
        }.getOrNull()
    }

    private fun splitDisplayName(displayName: String?): Pair<String, String> {
        val parts = displayName.orEmpty()
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        if (parts.isEmpty()) return "" to ""
        if (parts.size == 1) return parts.first() to ""
        return parts.first() to parts.drop(1).joinToString(" ")
    }
}
