package isim.ia2y.myapplication

import java.util.UUID

data class DeliveryAddress(
    val id: String = UUID.randomUUID().toString(),
    val label: String = "",
    val recipientName: String = "",
    val phone: String = "",
    val governorate: String = "",
    val city: String = "",
    val addressLine1: String = "",
    val addressLine2: String? = null,
    val postalCode: String? = null,
    val deliveryNotes: String? = null,
    val isDefault: Boolean = false
) {
    val titleLine: String
        get() = listOf(recipientName, label.takeIf { it.isNotBlank() }).joinToString(" - ")

    val summaryLine: String
        get() = listOf(addressLine1, city, governorate)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(", ")

    val detailsLine: String
        get() = listOf(
            postalCode?.trim().orEmpty(),
            phone.trim(),
            addressLine2?.trim().orEmpty()
        ).filter { it.isNotBlank() }.joinToString(" - ")

    fun toMap(): Map<String, Any> = buildMap {
        put("id", id)
        put("label", label.trim())
        put("recipientName", recipientName.trim())
        put("phone", phone.trim())
        put("governorate", governorate.trim())
        put("city", city.trim())
        put("addressLine1", addressLine1.trim())
        put("addressLine2", addressLine2?.trim().orEmpty())
        put("postalCode", postalCode?.trim().orEmpty())
        put("deliveryNotes", deliveryNotes?.trim().orEmpty())
        put("isDefault", isDefault)
    }

    fun toSnapshot(): DeliveryAddressSnapshot = DeliveryAddressSnapshot(
        label = label.trim(),
        recipientName = recipientName.trim(),
        phone = phone.trim(),
        governorate = governorate.trim(),
        city = city.trim(),
        addressLine1 = addressLine1.trim(),
        addressLine2 = addressLine2?.trim().orEmpty(),
        postalCode = postalCode?.trim().orEmpty(),
        deliveryNotes = deliveryNotes?.trim().orEmpty()
    )

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromAny(value: Any?): DeliveryAddress? {
            val map = value as? Map<String, Any?> ?: return null
            return DeliveryAddress(
                id = map["id"] as? String ?: UUID.randomUUID().toString(),
                label = map["label"] as? String ?: "",
                recipientName = map["recipientName"] as? String ?: "",
                phone = map["phone"] as? String ?: "",
                governorate = map["governorate"] as? String ?: "",
                city = map["city"] as? String ?: "",
                addressLine1 = map["addressLine1"] as? String ?: "",
                addressLine2 = map["addressLine2"] as? String,
                postalCode = map["postalCode"] as? String,
                deliveryNotes = map["deliveryNotes"] as? String,
                isDefault = map["isDefault"] as? Boolean ?: false
            )
        }
    }
}

data class DeliveryAddressSnapshot(
    val label: String = "",
    val recipientName: String = "",
    val phone: String = "",
    val governorate: String = "",
    val city: String = "",
    val addressLine1: String = "",
    val addressLine2: String = "",
    val postalCode: String = "",
    val deliveryNotes: String = ""
) {
    val summaryLine: String
        get() = listOf(addressLine1, city, governorate)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(", ")

    val detailsLine: String
        get() = listOf(postalCode.trim(), phone.trim(), addressLine2.trim())
            .filter { it.isNotBlank() }
            .joinToString(" - ")

    fun toMap(): Map<String, Any> = mapOf(
        "label" to label.trim(),
        "recipientName" to recipientName.trim(),
        "phone" to phone.trim(),
        "governorate" to governorate.trim(),
        "city" to city.trim(),
        "addressLine1" to addressLine1.trim(),
        "addressLine2" to addressLine2.trim(),
        "postalCode" to postalCode.trim(),
        "deliveryNotes" to deliveryNotes.trim()
    )

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromAny(value: Any?): DeliveryAddressSnapshot? {
            val map = value as? Map<String, Any?> ?: return null
            return DeliveryAddressSnapshot(
                label = map["label"] as? String ?: "",
                recipientName = map["recipientName"] as? String ?: "",
                phone = map["phone"] as? String ?: "",
                governorate = map["governorate"] as? String ?: "",
                city = map["city"] as? String ?: "",
                addressLine1 = map["addressLine1"] as? String ?: "",
                addressLine2 = map["addressLine2"] as? String ?: "",
                postalCode = map["postalCode"] as? String ?: "",
                deliveryNotes = map["deliveryNotes"] as? String ?: ""
            )
        }
    }
}

data class DeliveryAddressInput(
    val label: String,
    val recipientName: String,
    val phone: String,
    val governorate: String,
    val city: String,
    val addressLine1: String,
    val addressLine2: String,
    val postalCode: String,
    val deliveryNotes: String,
    val isDefault: Boolean
)

object DeliveryAddressValidator {
    fun validate(input: DeliveryAddressInput): String? = when {
        input.label.trim().length < 2 -> "Ajoutez un libelle clair pour cette adresse."
        input.recipientName.trim().length < 3 -> "Entrez le nom complet du destinataire."
        input.phone.normalizedPhoneValue().length < 8 -> "Entrez un numero de telephone valide."
        input.governorate.trim().length < 2 -> "Precisez le gouvernorat."
        input.city.trim().length < 2 -> "Precisez la ville."
        input.addressLine1.trim().length < 5 -> "Ajoutez une adresse de livraison complete."
        input.postalCode.isNotBlank() && input.postalCode.trim().length !in 4..8 ->
            "Le code postal doit contenir entre 4 et 8 caracteres."

        else -> null
    }

    fun normalizedPhone(phone: String): String = phone.normalizedPhoneValue()

    private fun String.normalizedPhoneValue(): String = trim().filter { it.isDigit() || it == '+' }
}

data class OrderStatusEntry(
    val status: String,
    val changedAt: Long
) {
    fun toMap(): Map<String, Any> = mapOf(
        "status" to OrderStatuses.normalize(status),
        "changedAt" to changedAt
    )

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromAny(value: Any?): OrderStatusEntry? {
            val map = value as? Map<String, Any?> ?: return null
            return OrderStatusEntry(
                status = OrderStatuses.normalize(map["status"] as? String ?: return null),
                changedAt = (map["changedAt"] as? Number)?.toLong() ?: 0L
            )
        }
    }
}
