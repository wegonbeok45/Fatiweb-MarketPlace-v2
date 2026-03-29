package isim.ia2y.myapplication

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.FirebaseFirestoreException
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

    private val _orderResult = MutableLiveData<Result<AppOrder>>()
    val orderResult: LiveData<Result<AppOrder>> = _orderResult

    fun setStep(step: Int) {
        _currentStep.value = step
    }

    fun setShippingType(standard: Boolean) {
        _isStandardSelected.value = standard
    }

    fun submitOrder(uid: String, order: AppOrder) {
        _isProcessing.value = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { OrderService.saveOrder(uid, order) }
            }
            _orderResult.value = result
            _isProcessing.value = false
        }
    }

    fun resetOrderResult() {
        _orderResult.value = null
    }
}
