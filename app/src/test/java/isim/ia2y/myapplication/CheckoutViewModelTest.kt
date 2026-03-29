package isim.ia2y.myapplication

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class CheckoutViewModelTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private lateinit var viewModel: CheckoutViewModel

    @Before
    fun setup() {
        viewModel = CheckoutViewModel()
    }

    @Test
    fun initialStepIsOne() {
        assertEquals(1, viewModel.currentStep.value)
    }

    @Test
    fun initialShippingIsStandard() {
        assertTrue(viewModel.isStandardSelected.value == true)
    }

    @Test
    fun initialProcessingIsFalse() {
        assertFalse(viewModel.isProcessing.value == true)
    }

    @Test
    fun setStepUpdatesCurrentStep() {
        viewModel.setStep(2)
        assertEquals(2, viewModel.currentStep.value)
        viewModel.setStep(3)
        assertEquals(3, viewModel.currentStep.value)
    }

    @Test
    fun setShippingTypeUpdatesSelection() {
        viewModel.setShippingType(false)
        assertFalse(viewModel.isStandardSelected.value == true)
        viewModel.setShippingType(true)
        assertTrue(viewModel.isStandardSelected.value == true)
    }

    @Test
    fun resetOrderResultClearsValue() {
        viewModel.resetOrderResult()
        assertNull(viewModel.orderResult.value)
    }
}
