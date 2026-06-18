package isim.ia2y.myapplication.voice

import java.util.Locale

class AddressFormFiller(private val locale: Locale = Locale.getDefault()) {

    enum class Step {
        LABEL,
        RECIPIENT_NAME,
        PHONE,
        GOVERNORATE,
        CITY,
        ADDRESS_LINE1,
        ADDRESS_LINE2,
        POSTAL_CODE,
        DELIVERY_NOTES
    }

    data class Result(
        val step: Step,
        val value: String,
        val prompt: String
    )

    companion object {
        private val STEPS = listOf(
            Step.LABEL,
            Step.RECIPIENT_NAME,
            Step.PHONE,
            Step.GOVERNORATE,
            Step.CITY,
            Step.ADDRESS_LINE1,
            Step.ADDRESS_LINE2,
            Step.POSTAL_CODE,
            Step.DELIVERY_NOTES
        )
    }

    private val values: MutableMap<Step, String> = mutableMapOf()
    private var currentIndex: Int = 0

    fun getCurrentStep(): Step = STEPS[currentIndex]

    fun getProgressText(): String = "Étape ${currentIndex + 1}/${STEPS.size}"

    fun getCurrentPrompt(): String = promptForStep(getCurrentStep())

    fun advance(resultText: String): Result {
        val step = getCurrentStep()
        val normalized = resultText.trim()
        values[step] = normalized
        val prompt = promptForStep(step)

        if (currentIndex < STEPS.lastIndex) {
            currentIndex += 1
        }

        return Result(step, normalized, prompt)
    }

    fun hasMoreSteps(): Boolean = currentIndex < STEPS.lastIndex

    fun reset() {
        values.clear()
        currentIndex = 0
    }

    fun getLabel(): String = values[Step.LABEL].orEmpty()
    fun getRecipientName(): String = values[Step.RECIPIENT_NAME].orEmpty()
    fun getPhone(): String = values[Step.PHONE].orEmpty()
    fun getGovernorate(): String = values[Step.GOVERNORATE].orEmpty()
    fun getCity(): String = values[Step.CITY].orEmpty()
    fun getAddressLine1(): String = values[Step.ADDRESS_LINE1].orEmpty()
    fun getAddressLine2(): String = values[Step.ADDRESS_LINE2].orEmpty()
    fun getPostalCode(): String = values[Step.POSTAL_CODE].orEmpty()
    fun getDeliveryNotes(): String = values[Step.DELIVERY_NOTES].orEmpty()

    fun getCompletionSummary(): String {
        val label = getLabel().takeIf { it.isNotBlank() } ?: "non défini"
        val recipient = getRecipientName().takeIf { it.isNotBlank() } ?: "non défini"
        val phone = getPhone().takeIf { it.isNotBlank() } ?: "non défini"
        val governorate = getGovernorate().takeIf { it.isNotBlank() } ?: "non défini"
        val city = getCity().takeIf { it.isNotBlank() } ?: "non défini"
        val line1 = getAddressLine1().takeIf { it.isNotBlank() } ?: "non défini"
        val notes = getDeliveryNotes().takeIf { it.isNotBlank() } ?: "aucune"

        return "Libellé: $label; Destinataire: $recipient; Téléphone: $phone; Gouvernorat: $governorate; Ville: $city; Adresse: $line1; Instructions: $notes."
    }

    private fun promptForStep(step: Step): String = when (step) {
        Step.LABEL -> "Quel est le libellé de cette adresse? Par exemple: Maison, Bureau, ou Autre."
        Step.RECIPIENT_NAME -> "Quel est le nom complet du destinataire?"
        Step.PHONE -> "Quel est le numéro de téléphone pour la livraison?"
        Step.GOVERNORATE -> "Quel est le gouvernorat? Par exemple: Tunis, Ariana, Ben Arous."
        Step.CITY -> "Quelle est la ville?"
        Step.ADDRESS_LINE1 -> "Dites l'adresse complète de livraison, rue et numéro."
        Step.ADDRESS_LINE2 -> "Y a-t-il un complément d'adresse? Par exemple: Appartement, Immeuble. Dites non si ce n'est pas nécessaire."
        Step.POSTAL_CODE -> "Quel est le code postal? Dites non si vous ne le connaissez pas."
        Step.DELIVERY_NOTES -> "Avez-vous des instructions spéciales pour la livraison? Dites non si ce n'est pas nécessaire."
    }
}