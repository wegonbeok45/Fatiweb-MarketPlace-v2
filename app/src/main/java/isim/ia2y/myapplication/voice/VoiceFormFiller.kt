package isim.ia2y.myapplication.voice

import java.util.Locale

class VoiceFormFiller(private val locale: Locale = Locale.getDefault()) {

    enum class Step {
        NAME,
        PHONE,
        ADDRESS,
        PAYMENT
    }

    data class Result(
        val step: Step,
        val value: String,
        val prompt: String
    )

    companion object {
        private val STEPS = listOf(
            Step.NAME,
            Step.PHONE,
            Step.ADDRESS
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

    fun getRecipientName(): String = values[Step.NAME].orEmpty()

    fun getPhone(): String = values[Step.PHONE].orEmpty()

    fun getAddressLine(): String = values[Step.ADDRESS].orEmpty()

    fun getPaymentMethod(): String = values[Step.PAYMENT].orEmpty()

    fun getCompletionSummary(): String {
        val name = getRecipientName().takeIf { it.isNotBlank() } ?: "non défini"
        val phone = getPhone().takeIf { it.isNotBlank() } ?: "non défini"
        val address = getAddressLine().takeIf { it.isNotBlank() } ?: "non défini"
        val payment = getPaymentMethod().takeIf { it.isNotBlank() } ?: "espèces"
        return "Nom: $name; Téléphone: $phone; Adresse: $address; Paiement: $payment."
    }

    private fun promptForStep(step: Step): String = when (step) {
        Step.NAME -> "Pouvez-vous indiquer le nom du destinataire ?"
        Step.PHONE -> "Quel est le numéro de téléphone pour la livraison ?"
        Step.ADDRESS -> "Dites l'adresse complète de livraison, rue, ville et gouvernorat."
        Step.PAYMENT -> "Comment souhaitez-vous payer ? Dites espèces ou cash."
    }
}
