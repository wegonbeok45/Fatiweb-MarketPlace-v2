package isim.ia2y.myapplication

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CheckoutViewModel : ViewModel() {

    private val _currentStep = MutableLiveData(1)
    val currentStep: LiveData<Int> = _currentStep

    private val _isStandardSelected = MutableLiveData(true)
    val isStandardSelected: LiveData<Boolean> = _isStandardSelected

    private val _isProcessing = MutableLiveData(false)
    val isProcessing: LiveData<Boolean> = _isProcessing

    private val _orderResult = MutableLiveData<Result<AppOrder>?>(null)
    val orderResult: LiveData<Result<AppOrder>?> = _orderResult

    private val _userProfile = MutableLiveData<FirestoreService.UserProfile?>(null)
    val userProfile: LiveData<FirestoreService.UserProfile?> = _userProfile

    private var cachedProfileUid: String? = null
    private var loadingProfileUid: String? = null

    fun setStep(step: Int) {
        _currentStep.value = step
    }

    fun setShippingType(standard: Boolean) {
        _isStandardSelected.value = standard
    }

    fun submitOrder(uid: String, order: AppOrder, deliveryType: String) {
        if (_isProcessing.value == true) return
        _isProcessing.value = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { OrderService.saveOrder(uid, order, deliveryType) }
            }
            _orderResult.value = result
            _isProcessing.value = false
        }
    }

    fun loadUserProfile(uid: String) {
        if (cachedProfileUid == uid && _userProfile.value != null) return
        if (loadingProfileUid == uid) return

        loadingProfileUid = uid
        viewModelScope.launch {
            val profile = withContext(Dispatchers.IO) {
                runCatching { FirestoreService.fetchUserProfile(uid) }.getOrNull()
            }
            cachedProfileUid = uid
            loadingProfileUid = null
            _userProfile.value = profile
        }
    }

    fun clearUserProfile() {
        cachedProfileUid = null
        loadingProfileUid = null
        _userProfile.value = null
    }

    fun resetOrderResult() {
        _orderResult.value = null
    }
}
