package isim.ia2y.myapplication

import android.content.Context
import android.net.Uri

object UserAvatarStorage {
    suspend fun uploadAvatar(context: Context, uid: String, uri: Uri): String {
        return ProductImageStorage.uploadUserAvatar(
            context = context,
            uid = uid,
            uri = uri
        )
    }
}
