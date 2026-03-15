package isim.ia2y.myapplication

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
// Cette classe organise cette partie de l'app.
class ExampleInstrumentedTest {
    @Test
    // Cette fonction fait une action de cette partie de l'app.
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("isim.ia2y.myapplication", appContext.packageName)
    }
}
