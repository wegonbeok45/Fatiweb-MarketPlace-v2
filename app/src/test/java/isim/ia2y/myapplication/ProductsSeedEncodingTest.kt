package isim.ia2y.myapplication

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProductsSeedEncodingTest {
    @Test
    fun productsSeedDoesNotContainCommonMojibakeMarkers() {
        val seedFile = File("src/main/assets/products_seed.json")
        assertTrue("products_seed.json should exist for seed verification", seedFile.exists())

        val text = seedFile.readText(Charsets.UTF_8)
        val mojibakeMarkers = listOf("Ã©", "Ã¨", "Ãª", "Ã ", "Ã§", "Â", "dÃ©licat", "RÃ©alisÃ©")

        mojibakeMarkers.forEach { marker ->
            assertFalse("Seed file still contains mojibake marker '$marker'", text.contains(marker))
        }
    }
}
