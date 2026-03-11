package isim.ia2y.myapplication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.charset.StandardCharsets

class TextEncodingUtf8Test {
    @Test
    fun frenchAccents_roundTripUtf8_withoutMojibake() {
        val probe = "\u00E9 \u00E0 \u00E7 \u00F9 \u00EA \u0153"
        val roundTrip = String(probe.toByteArray(StandardCharsets.UTF_8), StandardCharsets.UTF_8)

        assertEquals(probe, roundTrip)
        assertFalse(roundTrip.contains("Ã"))
        assertFalse(roundTrip.contains("Â"))
    }
}
