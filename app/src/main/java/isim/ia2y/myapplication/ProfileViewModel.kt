package isim.ia2y.myapplication

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ProfileUserInfo(
    val profile: FirestoreService.UserProfile?,
    val role: String
)

class ProfileViewModel : ViewModel() {
    private val _userInfo = MutableLiveData<ProfileUserInfo?>()
    val userInfo: LiveData<ProfileUserInfo?> = _userInfo

    private val _loadError = MutableLiveData<Throwable?>()
    val loadError: LiveData<Throwable?> = _loadError

    private var cachedUid: String? = null
    private var loadingUid: String? = null

    fun loadUserInfo(uid: String, forceRefresh: Boolean = false) {
        if (!forceRefresh && cachedUid == uid && _userInfo.value != null) return
        if (!forceRefresh && loadingUid == uid) return

        UserService.cachedRole(uid)?.let { cachedRole ->
            if (_userInfo.value?.role != cachedRole) {
                _userInfo.value = ProfileUserInfo(_userInfo.value?.profile, cachedRole)
            }
        }

        loadingUid = uid
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val profileDeferred = async {
                        runCatching { FirestoreService.fetchUserProfile(uid) }.getOrNull()
                    }
                    val roleDeferred = async {
                        runCatching { FirestoreService.fetchUserRole(uid, forceRefresh = forceRefresh) }
                            .getOrNull()
                    }
                    val profile = profileDeferred.await()
                    val role = roleDeferred.await()?.ifBlank { profile?.role ?: "client" }
                        ?: profile?.role
                        ?: "client"
                    ProfileUserInfo(profile, role)
                }
            }
            loadingUid = null
            result.fold(
                onSuccess = { info ->
                    cachedUid = uid
                    _userInfo.value = info
                    _loadError.value = null
                },
                onFailure = { error ->
                    _loadError.value = error
                }
            )
        }
    }

    fun clear() {
        cachedUid = null
        loadingUid = null
        _userInfo.value = null
        _loadError.value = null
    }
}
