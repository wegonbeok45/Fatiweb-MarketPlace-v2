package isim.ia2y.myapplication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeliveryAddressValidatorTest {

    @Test
    fun validate_returnsNull_forCompleteAddress() {
        val input = DeliveryAddressInput(
            label = "Maison",
            recipientName = "Ahmed Ben Salem",
            phone = "+216 12 345 678",
            governorate = "Tunis",
            city = "La Marsa",
            addressLine1 = "10 Rue du Lac",
            addressLine2 = "Appartement 4",
            postalCode = "2070",
            deliveryNotes = "Sonner deux fois",
            isDefault = true
        )

        assertNull(DeliveryAddressValidator.validate(input))
    }

    @Test
    fun validate_rejectsShortPhone() {
        val input = DeliveryAddressInput(
            label = "Maison",
            recipientName = "Ahmed Ben Salem",
            phone = "1234",
            governorate = "Tunis",
            city = "La Marsa",
            addressLine1 = "10 Rue du Lac",
            addressLine2 = "",
            postalCode = "2070",
            deliveryNotes = "",
            isDefault = false
        )

        assertEquals(
            "Entrez un numero de telephone valide.",
            DeliveryAddressValidator.validate(input)
        )
    }

    @Test
    fun normalizedPhone_keepsLeadingPlusAndDigitsOnly() {
        assertEquals("+21612345678", DeliveryAddressValidator.normalizedPhone("+216 12-345-678"))
    }
}
