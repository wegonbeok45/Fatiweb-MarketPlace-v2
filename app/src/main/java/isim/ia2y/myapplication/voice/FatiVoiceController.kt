package isim.ia2y.myapplication.voice


import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import isim.ia2y.myapplication.FirebaseAuthManager
import isim.ia2y.myapplication.LocalOrderStore
import isim.ia2y.myapplication.OrderService
import isim.ia2y.myapplication.Product
import isim.ia2y.myapplication.ProductCatalog
import isim.ia2y.myapplication.ProductService
import isim.ia2y.myapplication.R
import org.json.JSONObject
import isim.ia2y.myapplication.CartStore
import isim.ia2y.myapplication.OrderDetailsActivity
import java.text.Normalizer
import java.util.Locale
import isim.ia2y.myapplication.voice.AddressFormFiller
import kotlinx.coroutines.delay
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

class FatiVoiceController(
    private val context: Context,
    private val service: FatiVoiceService,
    private val geminiService: FatiVoiceGeminiService
) {

    private val frenchStopWords = setOf(
        "je", "veux", "chercher", "rechercher", "cherche", "trouver", "voir", "un", "une",
        "le", "la", "les", "de", "des", "du", "pour", "mon", "ma", "mes", "ce", "cet", "cette",
        "et", "ou", "dans", "sur", "avec", "sans", "est", "pas", "merci", "bonjour", "salut"
    )

    companion object {
        private const val TAG = "FatiVoiceController"
        private const val MIN_PRODUCT_MATCH_SCORE = 15
    }

    private var recognizer: SpeechRecognizer? = null
    private val navigator: FatiVoiceNavigator? = (context as? Activity)?.let { FatiVoiceNavigator(it) }
    private var currentState: ConversationState = ConversationState.IDLE
    private var hasGreeted = false
    private var retryCount = 0
    private val maxRetries = 3
    private var isSpeakingFlag = false
    private var lastSpokenText = ""
    private val enableDebugLogs = true
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainScope = MainScope()
    private val sessionManager = FatiVoiceSessionManager(context)
    private var lastUserSpeech = ""
    private var lastSpeechTimestamp = 0L
    private var currentSession: FatiVoiceSessionManager.Session? = null
    private var isListeningActive = false
    private var delayedListenRunnable: Runnable? = null
    private var sessionProduct: String? = null
    // In-memory checkout conversational fields
    private var currentCheckoutName: String? = null
    private var currentCheckoutPhone: String? = null
    private var currentCheckoutAddress: String? = null

    // Backward compatibility callbacks for CheckoutDetailsActivity
    var onRecognitionResult: ((String) -> Unit)? = null
    var onRecognitionError: ((String) -> Unit)? = null
    var onVoiceEnabledChanged: ((Boolean) -> Unit)? = null

    enum class ConversationState {
        IDLE,
        GREETING,
        AWAITING_RESUME_CONFIRMATION,
        LISTENING,
        SEARCHING_PRODUCT,
        PRODUCT_FOUND,
        ASKING_CONFIRM_ORDER,
        WAITING_CART_CONFIRMATION,
        WAITING_CHECKOUT_CONFIRMATION,
        WAITING_NAME,
        WAITING_PHONE,
        WAITING_ADDRESS,
        WAITING_DELIVERY_CHOICE,
        CONFIRMING_ORDER,
        ORDER_COMPLETE,
        // Ajouter ces nouveaux états:
        NAVIGATING_TO_ADDRESSES,
        WAITING_ADDRESS_LABEL,
        WAITING_ADDRESS_RECIPIENT,
        WAITING_ADDRESS_PHONE,
        WAITING_ADDRESS_GOVERNORATE,
        WAITING_ADDRESS_CITY,
        WAITING_ADDRESS_LINE1,
        WAITING_ADDRESS_LINE2,
        WAITING_ADDRESS_POSTAL_CODE,
        WAITING_ADDRESS_NOTES,
        ADDRESS_EDITING_COMPLETE
    }

    // Checkout field ids used by navigator when filling form
    private val nameFieldId = R.id.tvCheckoutAddressName
    private val phoneFieldId = R.id.tvCheckoutAddressPhone
    private val addressFieldId = R.id.tvCheckoutAddressLine1
    private val confirmButtonId = R.id.btnCheckoutContinue
    // Add this as a class variable
    private var addressFormFiller: AddressFormFiller? = null

    private fun performModifyAddress() {
        currentState = ConversationState.NAVIGATING_TO_ADDRESSES
        addressFormFiller = AddressFormFiller()

        service.speak("Je vais vous aider à modifier votre adresse. Préparez-vous à fournir les informations.") {
            mainScope.launch {
                // Wait a moment for user to be ready
                kotlinx.coroutines.delay(2000)

                currentState = ConversationState.WAITING_ADDRESS_LABEL
                service.speak(addressFormFiller!!.getCurrentPrompt()) {
                    listen()
                }
            }
        }
    }

    private fun handleAddressStep(speech: String) {
        val formFiller = addressFormFiller ?: return
        val result = formFiller.advance(speech)

        when (result.step) {
            AddressFormFiller.Step.LABEL -> {
                currentState = ConversationState.WAITING_ADDRESS_RECIPIENT
                service.speak("Merci. ${result.prompt}") {
                    listen()
                }
            }
            AddressFormFiller.Step.RECIPIENT_NAME -> {
                currentState = ConversationState.WAITING_ADDRESS_PHONE
                service.speak("Merci, ${result.value}. ${result.prompt}") {
                    listen()
                }
            }
            AddressFormFiller.Step.PHONE -> {
                currentState = ConversationState.WAITING_ADDRESS_GOVERNORATE
                service.speak("Merci. ${result.prompt}") {
                    listen()
                }
            }
            AddressFormFiller.Step.GOVERNORATE -> {
                currentState = ConversationState.WAITING_ADDRESS_CITY
                service.speak("Merci. ${result.prompt}") {
                    listen()
                }
            }
            AddressFormFiller.Step.CITY -> {
                currentState = ConversationState.WAITING_ADDRESS_LINE1
                service.speak("Merci. ${result.prompt}") {
                    listen()
                }
            }
            AddressFormFiller.Step.ADDRESS_LINE1 -> {
                currentState = ConversationState.WAITING_ADDRESS_LINE2
                service.speak("Merci. ${result.prompt}") {
                    listen()
                }
            }
            AddressFormFiller.Step.ADDRESS_LINE2 -> {
                currentState = ConversationState.WAITING_ADDRESS_POSTAL_CODE
                service.speak("Merci. ${result.prompt}") {
                    listen()
                }
            }
            AddressFormFiller.Step.POSTAL_CODE -> {
                currentState = ConversationState.WAITING_ADDRESS_NOTES
                service.speak("Merci. ${result.prompt}") {
                    listen()
                }
            }
            AddressFormFiller.Step.DELIVERY_NOTES -> {
                completeAddressEditing()
            }
        }
    }

    private fun completeAddressEditing() {
        val formFiller = addressFormFiller ?: return
        currentState = ConversationState.ADDRESS_EDITING_COMPLETE

        service.speak("Je vérifie les informations saisies...") {
            mainScope.launch {
                // Fill all address fields in the dialog
                navigator?.fillAddressFormFields(
                    label = formFiller.getLabel(),
                    recipient = formFiller.getRecipientName(),
                    phone = formFiller.getPhone(),
                    governorate = formFiller.getGovernorate(),
                    city = formFiller.getCity(),
                    line1 = formFiller.getAddressLine1(),
                    line2 = formFiller.getAddressLine2(),
                    postalCode = formFiller.getPostalCode(),
                    notes = formFiller.getDeliveryNotes()
                )

                // Wait for user to see the filled form
                kotlinx.coroutines.delay(1500)

                // Click save button
                navigator?.clickAddressSaveButton()

                // Wait for save to complete
                kotlinx.coroutines.delay(2000)

                // Return to checkout
                navigator?.returnToCheckout()

                // Return to checkout state
                currentState = ConversationState.ASKING_CONFIRM_ORDER
                service.speak("Votre adresse a été mise à jour. Je retourne à la commande.") {
                    listen()
                }

                addressFormFiller = null
            }
        }
    }
    // Called when FatiVoice activates
    fun start() {
        if (!isVoiceEnabled()) {
            Log.d(TAG, "FatiVoice start skipped because assistant is disabled")
            return
        }

        if (isListeningActive) {
            return
        }

        service.initialize {
            if (sessionManager.hasSavedSession() && !hasGreeted) {
                currentSession = sessionManager.loadSession()
                hasGreeted = true
                resumePreviousSession()
                return@initialize
            }

            if (!hasGreeted) {
                hasGreeted = true
                greet()
            } else {
                listen()
            }
        }
    }

    private fun isVoiceEnabled(): Boolean {
        return FatiVoicePreferences.isVoiceEnabled(context)
    }

    private fun setVoiceEnabled(enabled: Boolean) {
        FatiVoicePreferences.setVoiceEnabled(context, enabled)
        onVoiceEnabledChanged?.invoke(enabled)
    }

    private fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (text.isBlank()) {
            onDone?.invoke()
            return
        }
        if (text == lastSpokenText) {
            onDone?.invoke()
            return
        }

        if (enableDebugLogs) Log.d(TAG, "TTS start -> ${text}")
        stopRecognizerSession()
        lastSpokenText = text
        isSpeakingFlag = true
        service.speak(text) {
            isSpeakingFlag = false
            if (enableDebugLogs) Log.d(TAG, "TTS done -> ${text}")
            if (onDone != null) {
                mainHandler.postDelayed({ onDone.invoke() }, 450L)
            }
        }
    }

    private fun speakAndSchedule(text: String) {
        if (text.isBlank()) return
        if (enableDebugLogs) Log.d(TAG, "speakAndSchedule -> $text")
        stopRecognizerSession()
        lastSpokenText = text
        isSpeakingFlag = true
        service.speak(text) {
            isSpeakingFlag = false
            if (enableDebugLogs) Log.d(TAG, "speakAndSchedule done -> $text")
            scheduleListenAfterSpeechDelay()
        }
    }

    private fun scheduleListenAfterSpeechDelay() {
        mainHandler.postDelayed({
            if (FatiVoiceConversationGuard.canOpenMicrophone(service.isSpeaking(), isListeningActive)) {
                listen()
            }
        }, 450L)
    }

    private fun stopRecognizerSession() {
        if (recognizer == null && !isListeningActive) return

        isListeningActive = false
        delayedListenRunnable?.let { mainHandler.removeCallbacks(it) }
        delayedListenRunnable = null
        recognizer?.stopListening()
        recognizer?.destroy()
        recognizer = null
    }

    private fun shouldDelayListen(): Boolean {
        return FatiVoiceConversationGuard.shouldPauseListening(service.isSpeaking()) || isSpeakingFlag
    }

    private fun greet() {
        currentState = ConversationState.GREETING
        val greeting = "Bonjour ! Je suis FatiVoice, votre assistant vocal FatiWeb. Je suis là pour vous aider à trouver vos produits et passer vos commandes. Comment puis-je vous aider ?"

        speak(greeting) {
            currentState = ConversationState.LISTENING
            // Schedule listen with proper delay to ensure TTS is fully done
            delayedListenRunnable?.let { mainHandler.removeCallbacks(it) }
            delayedListenRunnable = Runnable {
                if (!isListeningActive && !service.isSpeaking()) {
                    listen()
                } else {
                    // Retry if still speaking
                    mainHandler.postDelayed(delayedListenRunnable!!, 200L)
                }
            }
            mainHandler.postDelayed(delayedListenRunnable!!, 800L)
        }
    }

    private fun listen() {
        retryCount = 0
        if (isListeningActive) {
            return
        }

        if (!shouldPreserveConversationState(currentState)) {
            currentState = ConversationState.LISTENING
        }

        if (!FatiVoiceConversationGuard.canOpenMicrophone(service.isSpeaking(), isListeningActive)) {
            delayedListenRunnable?.let { mainHandler.removeCallbacks(it) }
            delayedListenRunnable = Runnable {
                if (FatiVoiceConversationGuard.canOpenMicrophone(service.isSpeaking(), isListeningActive)) {
                    listen()
                }
            }
            mainHandler.postDelayed(delayedListenRunnable!!, 180)
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "Speech recognition not available")
            return
        }

        stopRecognizerSession()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        isListeningActive = true

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                3000L
            )
        }

        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                isListeningActive = false
                delayedListenRunnable?.let { mainHandler.removeCallbacks(it) }
                recognizer?.stopListening()
                recognizer?.destroy()
                recognizer = null

                val matches = results?.getStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION
                )
                val text = matches?.firstOrNull()
                if (!text.isNullOrBlank()) {
                    handleSpeech(text)
                } else {
                    handleListenError()
                }
            }

            override fun onError(error: Int) {
                isListeningActive = false
                delayedListenRunnable?.let { mainHandler.removeCallbacks(it) }
                recognizer?.stopListening()
                recognizer?.destroy()
                recognizer = null
                Log.w(TAG, "Recognition error: $error")
                handleListenError()
            }

            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(b: Bundle?) {}
            override fun onEvent(t: Int, b: Bundle?) {}
        })

        try {
            recognizer?.startListening(intent)
        } catch (ex: Exception) {
            Log.w(TAG, "Failed to start listening", ex)
            handleListenError()
        }
    }

    private fun shouldPreserveConversationState(state: ConversationState): Boolean {
        return state == ConversationState.AWAITING_RESUME_CONFIRMATION ||
                state == ConversationState.PRODUCT_FOUND ||
                state == ConversationState.ASKING_CONFIRM_ORDER ||
                state.name.startsWith("WAITING") ||
                state == ConversationState.NAVIGATING_TO_ADDRESSES
    }

    private fun handleSpeech(speech: String) {
        retryCount = 0
        if (enableDebugLogs) Log.d(TAG, "Recognized: $speech | state=$currentState")

        if (onRecognitionResult != null) {
            onRecognitionResult?.invoke(speech)
            return
        }

        var intent = FatiVoiceIntentHandler.detectIntent(speech, VoiceContext(lastIntent = null))
        if (intent == VoiceIntent.UNKNOWN && isLikelyProductQuery(speech)) {
            intent = VoiceIntent.SEARCH_PRODUCT
        }
        if (enableDebugLogs) Log.d(TAG, "Detected intent=$intent")
        if (currentState == ConversationState.AWAITING_RESUME_CONFIRMATION) {
            when (intent) {
                VoiceIntent.CONFIRM_ACTION -> {
                    resumeSavedSession()
                    return
                }
                VoiceIntent.CANCEL_ACTION -> {
                    declineResumeSession()
                    return
                }
                else -> {
                    speak("Je n'ai pas compris. Souhaitez-vous reprendre votre commande précédente ?") {
                        listen()
                    }
                    return
                }
            }
        }

        mainScope.launch {
            when (intent) {
                VoiceIntent.ORDER_PRODUCT -> {
                    if (currentState == ConversationState.ASKING_CONFIRM_ORDER || currentState == ConversationState.WAITING_CART_CONFIRMATION) {
                        performOpenCheckout()
                    } else {
                        val product = extractProductName(speech)
                        val isGenericOrder = product.isBlank() || product.equals("ce produit", ignoreCase = true)

                        if (isGenericOrder) {
                            service.speak("Quel article souhaitez-vous commander ?") {
                                currentState = ConversationState.LISTENING
                                scheduleListenAfterSpeechDelay()
                            }
                        } else {
                            val directProduct = findBestProductMatch(product)
                            if (directProduct != null) {
                                sessionProduct = directProduct.id
                                currentState = ConversationState.WAITING_CART_CONFIRMATION
                                navigator?.openProductDetails(directProduct.id)

                                service.speak("J'ai trouvé le produit ${directProduct.title}. Voulez-vous l'ajouter au panier ?") {
                                    currentState = ConversationState.WAITING_CART_CONFIRMATION
                                    scheduleListenAfterSpeechDelay()
                                }
                            } else if (tryGeminiDiscoveryFlow(speech, intent)) {
                                return@launch
                            } else {
                                currentState = ConversationState.LISTENING
                                service.speak("Désolé, je n'ai pas trouvé ce produit exact. Pouvez-vous préciser le nom, la couleur ou la taille ?") {
                                    scheduleListenAfterSpeechDelay()
                                }
                            }
                        }
                    }
                }
                VoiceIntent.SEARCH_PRODUCT -> {
                    val product = extractProductName(speech)
                    if (product.isBlank() || product.equals("ce produit", ignoreCase = true)) {
                        service.speak("Quel produit cherchez-vous ?") {
                            currentState = ConversationState.LISTENING
                            scheduleListenAfterSpeechDelay()
                        }
                    } else {
                        val directProduct = findBestProductMatch(product)
                        if (directProduct != null) {
                            sessionProduct = directProduct.id
                            currentState = ConversationState.WAITING_CART_CONFIRMATION
                            navigator?.openProductDetails(directProduct.id)

                            service.speak("J'ai trouvé le produit ${directProduct.title}. Voulez-vous l'ajouter au panier ?") {
                                currentState = ConversationState.WAITING_CART_CONFIRMATION
                                scheduleListenAfterSpeechDelay()
                            }
                        } else if (tryGeminiDiscoveryFlow(speech, intent)) {
                            return@launch
                        } else {
                            currentState = ConversationState.LISTENING
                            service.speak("Désolé, je n'ai pas trouvé ce produit exact. Pouvez-vous préciser le nom ou la catégorie ?") {
                                scheduleListenAfterSpeechDelay()
                            }
                        }
                    }
                }
                VoiceIntent.QUERY_ORDER_STATUS -> {
                    performOrderStatusQuery()
                }
                VoiceIntent.RESUME_SESSION -> {
                    if (sessionManager.hasSavedSession()) {
                        currentSession = sessionManager.loadSession()
                        resumePreviousSession()
                    } else {
                        speak("Je n'ai pas de session précédente, comment puis-je vous aider ?") {
                            listen()
                        }
                    }
                }
                VoiceIntent.CONFIRM_ACTION -> {
                    when (currentState) {
                        ConversationState.WAITING_CART_CONFIRMATION,
                        ConversationState.PRODUCT_FOUND -> {
                            val productId = sessionProduct
                            if (!productId.isNullOrBlank()) {
                                if (!ensureProductInCart(productId)) return@launch
                                currentState = ConversationState.WAITING_CHECKOUT_CONFIRMATION
                                service.speak("Produit ajouté au panier. Voulez-vous passer à la caisse ?") {
                                    scheduleListenAfterSpeechDelay()
                                }
                            } else {
                                service.speak("Je n'ai pas de produit à confirmer. Dites-moi ce que vous souhaitez chercher.") {
                                    scheduleListenAfterSpeechDelay()
                                }
                            }
                        }
                        ConversationState.WAITING_CHECKOUT_CONFIRMATION,
                        ConversationState.ASKING_CONFIRM_ORDER -> {
                            performOpenCheckout()
                        }
                        ConversationState.WAITING_DELIVERY_CHOICE -> {
                            performSelectDelivery()
                        }
                        else -> {
                            val productId = sessionProduct
                            if (!productId.isNullOrBlank()) {
                                if (!ensureProductInCart(productId)) return@launch
                                currentState = ConversationState.WAITING_CHECKOUT_CONFIRMATION
                                service.speak("J'ai ajouté le produit précédent au panier. Dites-moi si vous souhaitez passer à la caisse.") {
                                    scheduleListenAfterSpeechDelay()
                                }
                            } else {
                                service.speak("Je n'ai pas encore compris votre demande. Que souhaitez-vous faire ?") {
                                    scheduleListenAfterSpeechDelay()
                                }
                            }
                        }
                    }
                }
                VoiceIntent.GO_TO_CART -> {
                    performGoToCart()
                }
                VoiceIntent.GO_TO_ORDERS -> {
                    performGoToOrders()
                }
                VoiceIntent.GO_HOME -> {
                    performGoHome()
                }
                VoiceIntent.ENABLE_VOICE -> {
                    performEnableVoice()
                }
                VoiceIntent.DISABLE_VOICE -> {
                    performDisableVoice()
                }
                VoiceIntent.SELECT_DELIVERY -> {
                    if (currentState == ConversationState.WAITING_DELIVERY_CHOICE) {
                        performSelectDelivery()
                    } else {
                        processGeminiResponse(speech)
                    }
                }
                VoiceIntent.PROVIDE_NAME -> {
                    if (currentState == ConversationState.WAITING_NAME) {
                        performProvideName(speech)
                    } else {
                        processGeminiResponse(speech)
                    }
                }
                VoiceIntent.PROVIDE_PHONE -> {
                    if (currentState == ConversationState.WAITING_PHONE) {
                        performProvidePhone(speech)
                    } else {
                        processGeminiResponse(speech)
                    }
                }
                VoiceIntent.PROVIDE_ADDRESS -> {
                    if (currentState == ConversationState.WAITING_ADDRESS) {
                        performProvideAddress(speech)
                    } else {
                        processGeminiResponse(speech)
                    }
                }
                else -> {
                    processGeminiResponse(speech)
                }
            }
        }
    }

    private suspend fun tryGeminiDiscoveryFlow(speech: String, intent: VoiceIntent): Boolean {
        if (intent != VoiceIntent.SEARCH_PRODUCT && intent != VoiceIntent.ORDER_PRODUCT) {
            return false
        }

        val rawResponse = runCatching { geminiService.processUserSpeech(speech) }.getOrNull()
        if (rawResponse.isNullOrBlank()) {
            return false
        }

        val parsed = parseGeminiIntentResponse(rawResponse)
        if (parsed == null || parsed.intent.uppercase() !in setOf("SEARCH_PRODUCT", "ORDER_PRODUCT")) {
            return false
        }

        val params = parsed.params
        val queryText = params["product"]
            ?: params["category"]
            ?: params["query"]
            ?: speech

        if (isVagueDiscoveryQuery(queryText, params, speech)) {
            val clarification = parsed.response.takeIf { it.isNotBlank() } ?: "Oui, que cherchez-vous exactement ?"
            currentState = ConversationState.LISTENING
            service.speak(clarification) {
                scheduleListenAfterSpeechDelay()
            }
            return true
        }

        val matchedProduct = findBestProductMatchWithCriteria(queryText, params)
        if (matchedProduct != null) {
            currentState = ConversationState.WAITING_CART_CONFIRMATION
            sessionProduct = matchedProduct.id
            navigator?.openProductDetails(matchedProduct.id)

            val productName = matchedProduct.title.takeIf { it.isNotBlank() }
                ?: matchedProduct.subtitle.takeIf { it.isNotBlank() }
                ?: "cet article"

            service.speak(
                "Oui c'est disponible! J'ai trouvé l'article $productName dans la couleur et l'âge demandés. Voulez-vous l'ajouter au panier ?"
            ) {
                currentState = ConversationState.WAITING_CART_CONFIRMATION
                scheduleListenAfterSpeechDelay()
            }
            return true
        }

        return false
    }

    private fun isVagueDiscoveryQuery(queryText: String, params: Map<String, String>, speech: String): Boolean {
        val genericTerms = setOf(
            "article", "produit", "objet", "chose", "truc", "vêtement", "vetement",
            "choix", "recherche", "cherche", "chercher", "trouver", "voir", "un", "une", "le", "la",
            "de", "des", "du", "pour", "je", "veux", "quel", "quelle", "quels", "quelles"
        )
        val combined = listOf(queryText, speech) + params.values
        val tokens = combined
            .joinToString(" ")
            .let { normalizeVoiceText(it) }
            .split("\\s+".toRegex())
            .filter { it.length >= 2 && it !in genericTerms }
            .distinct()

        val hasSpecificDetails = tokens.isNotEmpty() || params.values.any { value ->
            val normalizedValue = normalizeVoiceText(value)
            Regex("\\d+|bleu|rouge|vert|jaune|noir|blanc|rose|marron|petit|grand|bébé|enfant|femme|homme|garçon|fille|adulte|taille|couleur|age").containsMatchIn(normalizedValue)
        }

        return tokens.isEmpty() && !hasSpecificDetails
    }

    private suspend fun findBestProductMatchWithCriteria(query: String, params: Map<String, String>): Product? {
        val normalizedQuery = normalizeVoiceText(query)
        val criteria = params.values.map(::normalizeVoiceText).filter { it.isNotBlank() }

        val catalog = ProductCatalog.all(includeInactive = false)
        val products = if (catalog.isNotEmpty()) {
            catalog
        } else {
            runCatching { ProductService.fetchProducts(limit = 120) }
                .getOrNull()
                ?.also { ProductCatalog.replaceAll(it) }
                ?: emptyList()
        }

        if (products.isEmpty()) return null

        return products.mapNotNull { product ->
            val score = productMatchScore(product, normalizedQuery) + productCriteriaMatchScore(product, criteria)
            product.takeIf { score >= MIN_PRODUCT_MATCH_SCORE }?.let { it to score }
        }.maxByOrNull { it.second }?.first
    }

    private fun productCriteriaMatchScore(product: Product, criteria: List<String>): Int {
        if (criteria.isEmpty()) return 0
        val candidateText = normalizeVoiceText(buildString {
            append(product.title).append(' ')
            append(product.subtitle).append(' ')
            append(product.category).append(' ')
            append(product.description).append(' ')
            append(product.searchKeywords.joinToString(" ")).append(' ')
            append(product.tags.joinToString(" ")).append(' ')
            append(product.origin).append(' ')
            append(product.sellerName)
        })

        return criteria.fold(0) { acc, criterion ->
            acc + if (candidateText.contains(criterion)) 14 else 0
        }
    }

    private suspend fun processGeminiResponse(speech: String) {
        val response = geminiService.processUserSpeech(speech)
        if (response.isNullOrBlank()) {
            handleListenError()
            return
        }

        val parsed = parseGeminiIntentResponse(response)
        if (parsed == null) {
            service.speak(response) {
                listen()
            }
            return
        }

        // Parler la réponse
        service.speak(parsed.response) {
            // Traiter l'intention après la parole
            handleGeminiIntent(parsed.intent, parsed.params)
        }
    }

    private data class GeminiIntentResponse(
        val response: String,
        val intent: String,
        val params: Map<String, String>
    )

    private fun parseGeminiIntentResponse(raw: String): GeminiIntentResponse? {
        val trimmed = raw.trim()
        val jsonText = try {
            JSONObject(trimmed)
            trimmed
        } catch (ignore: Exception) {
            val start = trimmed.indexOf("{")
            val end = trimmed.lastIndexOf("}")
            if (start >= 0 && end > start) trimmed.substring(start, end + 1) else return null
        }

        return try {
            val json = JSONObject(jsonText)
            val response = json.optString("response", "").trim().takeIf { it.isNotBlank() } ?: return null
            val intent = json.optString("intent", "").trim().takeIf { it.isNotBlank() } ?: return null
            // Validate intent only against the synced VoiceIntent enum set
            val allowedIntents = setOf(
                "SEARCH_PRODUCT",
                "PROVIDE_NAME",
                "PROVIDE_PHONE",
                "PROVIDE_ADDRESS",
                "SELECT_DELIVERY",
                "CONFIRM_ACTION",
                "CANCEL_ACTION",
                "DISABLE_VOICE",
                "UNKNOWN"
            )
            if (!allowedIntents.contains(intent.uppercase())) return null
            val paramsJson = json.optJSONObject("params")
            val params = mutableMapOf<String, String>()
            if (paramsJson != null) {
                val keys = paramsJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    paramsJson.optString(key, "").takeIf { it.isNotBlank() }?.let { params[key] = it }
                }
            }
            GeminiIntentResponse(response, intent, params)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse Gemini JSON response", e)
            null
        }
    }

    private fun handleGeminiIntent(intentName: String, params: Map<String, String>) {
        when (intentName.uppercase()) {
            "SEARCH_PRODUCT" -> {
                val product = params["product"]?.takeIf { it.isNotBlank() } ?: params["query"] ?: "ce produit"
                performSearchProduct(product)
            }
            "CONFIRM_ACTION" -> performOpenCheckout()
            "CANCEL_ACTION" -> {
                service.speak("Très bien, nous continuons sans action.") { listen() }
            }
            "DISABLE_VOICE" -> performDisableVoice()
            "SELECT_DELIVERY" -> performSelectDelivery()
            "PROVIDE_NAME" -> performProvideName(params["name"] ?: "")
            "PROVIDE_PHONE" -> performProvidePhone(params["phone"] ?: "")
            "PROVIDE_ADDRESS" -> performProvideAddress(params["address"] ?: "")
            else -> listen()
        }
    }

    private fun performSearchProduct(product: String) {
        currentState = ConversationState.SEARCHING_PRODUCT

        mainScope.launch {
            val directProduct = findBestProductMatch(product)

            if (directProduct != null) {
                sessionProduct = directProduct.id
                currentState = ConversationState.WAITING_CART_CONFIRMATION

                navigator?.openProductDetails(directProduct.id)

                service.speak("J'ai trouvé le produit ${directProduct.title}. Voulez-vous l'ajouter au panier ?") {
                    currentState = ConversationState.WAITING_CART_CONFIRMATION
                    listen()
                }
                return@launch
            }

                currentState = ConversationState.LISTENING
            service.speak("Désolé, je n'ai pas trouvé ce produit exact. Pouvez-vous préciser le nom ou la couleur ?") {
                scheduleListenAfterSpeechDelay()
            }
        }
    }

    private suspend fun findBestProductMatch(query: String): Product? {
        val normalizedQuery = normalizeVoiceText(query)
        if (normalizedQuery.isBlank()) return null

        val cachedProducts = ProductCatalog.all(includeInactive = false)
        val scored = cachedProducts.mapNotNull { product ->
            val score = productMatchScore(product, normalizedQuery)
            product.takeIf { score >= MIN_PRODUCT_MATCH_SCORE }?.let { it to score }
        }.sortedByDescending { it.second }

        if (scored.firstOrNull()?.second ?: 0 >= MIN_PRODUCT_MATCH_SCORE) {
            return scored.first().first
        }

        return runCatching {
            ProductService.fetchProducts(limit = 60)
        }.getOrNull()
            ?.also { ProductCatalog.replaceAll(it) }
            ?.maxByOrNull { productMatchScore(it, normalizedQuery) }
            ?.takeIf { productMatchScore(it, normalizedQuery) >= MIN_PRODUCT_MATCH_SCORE }
    }

    private fun matchesProductQuery(product: Product, normalizedQuery: String): Boolean {
        val queryTokens = meaningfulTokens(normalizedQuery)
        if (queryTokens.isEmpty()) return false

        val candidateText = buildString {
            append(product.id).append(' ')
            append(product.title).append(' ')
            append(product.subtitle).append(' ')
            append(product.category).append(' ')
            append(product.origin).append(' ')
            append(product.sellerName).append(' ')
            append(product.description).append(' ')
            append(product.searchKeywords.joinToString(" ")).append(' ')
            append(product.tags.joinToString(" "))
        }

        val normalizedCandidate = normalizeVoiceText(candidateText)
        val candidateTokens = meaningfulTokens(normalizedCandidate)
        val titleScore = meaningfulTokens(product.title).count { queryTokens.contains(it) }
        val subtitleScore = meaningfulTokens(product.subtitle).count { queryTokens.contains(it) }
        val keywordScore = product.searchKeywords.map(::normalizeVoiceText).count { queryTokens.contains(it) }
        val tagScore = product.tags.map(::normalizeVoiceText).count { queryTokens.contains(it) }
        val descriptionScore = meaningfulTokens(product.description).count { queryTokens.contains(it) }

        val exactMatch = normalizedCandidate.contains(normalizedQuery)
        val tokenOverlap = queryTokens.count { candidateTokens.contains(it) }

        return exactMatch || tokenOverlap > 0 || titleScore + subtitleScore + keywordScore + tagScore + descriptionScore > 0
    }

    private fun meaningfulTokens(value: String): List<String> {
        return normalizeVoiceText(value)
            .split("\\s+".toRegex())
            .filter { token -> token.length >= 2 && token !in frenchStopWords }
            .distinct()
    }

    private fun productMatchScore(product: Product, normalizedQuery: String): Int {
        if (normalizedQuery.isBlank()) return 0

        val queryTokens = meaningfulTokens(normalizedQuery)
        val titleTokens = meaningfulTokens(product.title)
        val subtitleTokens = meaningfulTokens(product.subtitle)
        val keywords = product.searchKeywords.map(::normalizeVoiceText)
        val tags = product.tags.map(::normalizeVoiceText)
        val descriptionTokens = meaningfulTokens(product.description)
        val categoryTokens = meaningfulTokens(product.category)

        val exactTitle = if (normalizeVoiceText(product.title) == normalizedQuery) 70 else 0
        val exactSubtitle = if (normalizeVoiceText(product.subtitle) == normalizedQuery) 50 else 0
        val exactId = if (normalizeVoiceText(product.id) == normalizedQuery) 80 else 0

        val normalizedCandidate = normalizeVoiceText(buildString {
            append(product.title).append(' ')
            append(product.subtitle).append(' ')
            append(product.category).append(' ')
            append(product.description).append(' ')
            append(product.searchKeywords.joinToString(" ")).append(' ')
            append(product.tags.joinToString(" ")).append(' ')
            append(product.origin).append(' ')
            append(product.sellerName)
        })

        val exactPhraseBonus = if (normalizedCandidate.contains(normalizedQuery) && normalizedQuery.isNotBlank()) 24 else 0

        val tokenMatches = queryTokens.fold(0) { acc, token ->
            acc + when {
                token in titleTokens -> 18
                token in subtitleTokens -> 14
                token in keywords -> 12
                token in tags -> 10
                token in categoryTokens -> 6
                token in descriptionTokens -> 4
                else -> 0
            }
        }

        return exactId + exactTitle + exactSubtitle + exactPhraseBonus + tokenMatches
    }

    private fun normalizeVoiceText(value: String): String {
        val normalized = Normalizer.normalize(value.lowercase(Locale.getDefault()), Normalizer.Form.NFD)
            .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
            .replace("’", "'")
            .replace("\u00a0", " ")
            .replace("[^a-z0-9'\\s]+".toRegex(), " ")
            .trim()
        return normalized.replace("\\s+".toRegex(), " ")
    }

    private fun performAddToCart(productId: String?, productName: String?) {
        currentState = ConversationState.LISTENING
        if (!productId.isNullOrBlank()) {
            CartStore.add(context, productId, 1)
            sessionProduct = productId
            service.speak("Le produit a été ajouté à votre panier.") {
                listen()
            }
            return
        }

        if (!productName.isNullOrBlank()) {
            service.speak("Je cherche $productName pour l'ajouter au panier...") {
                mainScope.launch {
                    val foundProduct = findBestProductMatch(productName)
                    if (foundProduct != null) {
                        sessionProduct = foundProduct.id
                        val added = CartStore.add(context, foundProduct.id, 1)
                        if (added > 0 || CartStore.itemCount(context) > 0) {
                            service.speak("${foundProduct.title} a été ajouté à votre panier. Vous pouvez maintenant confirmer.") {
                                currentState = ConversationState.PRODUCT_FOUND
                                listen()
                            }
                        } else {
                            service.speak("Je n'ai pas pu ajouter ce produit au panier pour le moment.") {
                                listen()
                            }
                        }
                    } else {
                        navigator?.searchProduct(productName)
                        service.speak("Je n'ai pas trouvé ce produit dans le catalogue actif. Je vous ouvre la recherche.") {
                            currentState = ConversationState.PRODUCT_FOUND
                            listen()
                        }
                    }
                }
            }
            return
        }

        service.speak("Quel produit souhaitez-vous ajouter ?") {
            listen()
        }
    }

    private fun ensureProductInCart(productId: String): Boolean {
        val before = CartStore.itemCount(context)
        val added = CartStore.add(context, productId, 1)
        val after = CartStore.itemCount(context)
        return (added > 0 || before > 0 || after > 0).also { isReady ->
            if (!isReady) {
                service.speak("Je dois d'abord ajouter le produit au panier avant de confirmer.") {
                    listen()
                }
            }
        }
    }

    private fun performConfirmOrder() {
        if (CartStore.itemCount(context) <= 0) {
            if (!sessionProduct.isNullOrBlank() && ensureProductInCart(sessionProduct!!)) {
                // continue below
            } else {
                return
            }
        }

        currentState = ConversationState.CONFIRMING_ORDER
        service.speak("Je confirme votre commande.") {
            // Try to click the common checkout confirm button if present
            navigator?.clickButton(confirmButtonId)
            currentState = ConversationState.ORDER_COMPLETE
            service.speak("Votre commande est confirmée. Merci !") {
                AccessibilityHelper.announceOrderConfirmed(context)
                currentState = ConversationState.IDLE
                listen()
            }
        }
    }

    private fun performOpenCheckout() {
        currentState = ConversationState.ASKING_CONFIRM_ORDER
        val checkoutText = if (FirebaseAuthManager.isLoggedIn) {
            "J'ouvre votre panier..."
        } else {
            "J'ouvre votre panier invité..."
        }
        AccessibilityHelper.announceOpenCart(context)
        navigator?.openCheckout()
        service.speak(checkoutText) {
            // Wait for checkout activity to resume and start its own voice form
            mainScope.launch {
                delay(1200)
                currentState = ConversationState.IDLE
                Log.d(TAG, "Checkout opened. Checkout activity will handle voice form.")
            }
        }
    }

    private fun performGoToCart() {
        currentState = ConversationState.LISTENING
        val cartText = if (FirebaseAuthManager.isLoggedIn) {
            "J'ouvre votre panier..."
        } else {
            "J'ouvre votre panier invité..."
        }
        AccessibilityHelper.announceOpenCart(context)
        navigator?.goToCart()
        service.speak(cartText) {
            service.speak("Votre panier est ouvert. Que souhaitez-vous faire ensuite ?") {
                listen()
            }
        }
    }

    private fun performGoToOrders() {
        currentState = ConversationState.LISTENING
        if (!FirebaseAuthManager.isLoggedIn) {
            service.speak("Vous devez être connecté pour accéder à vos commandes. Souhaitez-vous ouvrir votre profil pour vous connecter ?") {
                listen()
            }
            return
        }
        AccessibilityHelper.announceOpenOrders(context)
        navigator?.goToOrders()
        service.speak("J'ouvre vos commandes...") {
            service.speak("Vos commandes s'affichent. Que souhaitez-vous faire ensuite ?") {
                listen()
            }
        }
    }

    private fun performGoHome() {
        currentState = ConversationState.LISTENING
        AccessibilityHelper.announceOpenHome(context)
        navigator?.goToHome()
        service.speak("J'ouvre la page d'accueil...") {
            service.speak("Accueil ouvert. Que puis-je faire pour vous ?") {
                listen()
            }
        }
    }

    private fun performSelectDelivery() {
        currentState = ConversationState.CONFIRMING_ORDER
        service.speak("Je sélectionne livraison à domicile...") {
            AccessibilityHelper.announceSelectHomeDelivery(context)
            navigator?.selectHomeDelivery()
            service.speak("Livraison à domicile sélectionnée. Je confirme votre commande maintenant.") {
                navigator?.clickButton(confirmButtonId)
                currentState = ConversationState.ORDER_COMPLETE
                service.speak("Votre commande est confirmée ! Merci de votre confiance chez FatiWeb.") {
                    AccessibilityHelper.announceOrderConfirmed(context)
                    currentState = ConversationState.IDLE
                    listen()
                }
            }
        }
    }

    private fun performProvideName(value: String) {
        if (value.isBlank()) {
            service.speak("Je n'ai pas entendu votre nom. Pouvez-vous répéter ?") {
                listen()
            }
            return
        }

        // Save in-memory and update UI, then prompt for phone
        currentCheckoutName = value
        currentState = ConversationState.WAITING_PHONE
        AccessibilityHelper.announceFillField(context, "nom")
        navigator?.fillTextField(nameFieldId, value)
        service.speak("Merci $value. Quel est votre numéro de téléphone ?") {
            listen()
        }
    }

    private fun performProvidePhone(value: String) {
        if (value.isBlank()) {
            service.speak("Je n'ai pas entendu votre numéro. Pouvez-vous répéter ?") {
                listen()
            }
            return
        }

        // Save in-memory and update UI, then prompt for address
        currentCheckoutPhone = value
        currentState = ConversationState.WAITING_ADDRESS
        AccessibilityHelper.announceFillField(context, "téléphone")
        navigator?.fillTextField(phoneFieldId, value)
        service.speak("Merci. Quelle est votre adresse de livraison ?") {
            listen()
        }
    }

    private fun performProvideAddress(value: String) {
        if (value.isBlank()) {
            service.speak("Je n'ai pas entendu votre adresse. Pouvez-vous répéter ?") {
                listen()
            }
            return
        }

        // Save in-memory and update UI, then programmatically proceed to confirmation
        currentCheckoutAddress = value
        currentState = ConversationState.WAITING_DELIVERY_CHOICE
        AccessibilityHelper.announceFillField(context, "adresse")
        navigator?.fillTextField(addressFieldId, value)

        // Click continue to advance and then confirm order programmatically
        service.speak("Adresse enregistrée. Je procède à la confirmation de votre commande.") {
            mainScope.launch {
                // Advance to next step
                navigator?.clickButton(confirmButtonId)
                // Small delay to allow UI to process the transition
                kotlinx.coroutines.delay(600)
                // Trigger final confirmation
                performConfirmOrder()
                // Speak final confirmation message explicitly
                service.speak("Félicitations ! Votre commande est confirmée.") {
                    currentState = ConversationState.ORDER_COMPLETE
                    listen()
                }
            }
        }
    }

    private fun extractProductName(speech: String): String {
        var s = normalizeVoiceText(speech)
        // Remove wake phrases and action words in order of specificity
        listOf(
            "hé fativoice", "he fativoice", "hey fati voice", "fati voice", "fativoice",
            "je veux commander", "je veux", "voulez vous", "voulez vous passer",
            "commander", "acheter", "je cherche", "cherche", "trouver", "voir", "ajouter",
            "je veux un", "je veux une", "je veux des", "je voudrais", "je voudrais un", "je voudrais une",
            "montre moi", "montrez moi", "montre-moi", "montrez-moi"
        ).forEach {
            s = s.replace(it, " ", ignoreCase = true)
        }
        return s.trim().split("\\s+".toRegex()).filter { it.length > 2 }.joinToString(" ")
            .trim().takeIf { it.isNotBlank() } ?: "ce produit"
    }

    private fun isLikelyProductQuery(speech: String): Boolean {
        val normalized = normalizeVoiceText(speech)
        if (normalized.isBlank()) return false

        val ignoredWords = setOf("bonjour", "salut", "merci", "oui", "non", "au revoir", "quitter", "stop", "aide", "aidez", "comment", "pourquoi", "quel", "quelle", "ou", "où", "quand")
        if (ignoredWords.any { normalized.contains(it) }) return false

        val tokens = meaningfulTokens(normalized)
        if (tokens.isEmpty()) return false

        val productCatalog = ProductCatalog.all(includeInactive = false)
        if (productCatalog.isEmpty()) return false

        val knownProductTokens = productCatalog.flatMap {
            meaningfulTokens(it.title) + meaningfulTokens(it.subtitle) + meaningfulTokens(it.category) + it.searchKeywords.map(::normalizeVoiceText)
        }.toSet()

        val intersectCount = tokens.count { it in knownProductTokens }
        if (intersectCount >= 1) return true

        return tokens.size in 1..6 && tokens.any { it !in ignoredWords }
    }

    private fun performEnableVoice() {
        if (isVoiceEnabled()) {
            service.speak("FatiVoice est déjà activé.") {
                listen()
            }
            return
        }
        setVoiceEnabled(true)
        currentState = ConversationState.LISTENING
        service.speak("D'accord, FatiVoice est activé. Je suis prêt à vous aider.") {
            if (!hasGreeted) {
                hasGreeted = true
                greet()
            } else {
                listen()
            }
        }
    }

    private fun performDisableVoice() {
        if (!isVoiceEnabled()) {
            service.speak("FatiVoice est déjà désactivé.") {
                reset()
            }
            return
        }
        setVoiceEnabled(false)
        service.speak("D'accord. FatiVoice est désactivé et ne redémarrera pas automatiquement.") {
            reset()
        }
    }

    private fun resumePreviousSession() {
        val session = currentSession ?: sessionManager.loadSession()
        if (session == null) {
            service.speak("Aucune session précédente n'est disponible.") {
                listen()
            }
            return
        }

        currentSession = session
        currentState = session.state
        service.speak("Je reprends votre session précédente.") {
            listen()
        }
    }

    private fun resumeSavedSession() {
        val session = sessionManager.loadSession()
        if (session == null) {
            service.speak("Aucune session enregistrée n'est disponible.") {
                listen()
            }
            return
        }

        currentSession = session
        currentState = session.state
        service.speak("Je reprends la session enregistrée.") {
            listen()
        }
    }

    private fun declineResumeSession() {
        sessionManager.clearSession()
        currentSession = null
        currentState = ConversationState.IDLE
        service.speak("Très bien, nous continuons sans reprendre la session précédente.") {
            listen()
        }
    }

    private fun performOrderStatusQuery() {
        if (!FirebaseAuthManager.isLoggedIn) {
            service.speak("Vous devez être connecté pour consulter l'état de vos commandes.") {
                listen()
            }
            return
        }

        service.speak("Je vérifie l'état de vos commandes.") {
            navigator?.goToOrders()
            listen()
        }
    }

    private fun handleListenError() {
        retryCount++

        if (onRecognitionError != null) {
            onRecognitionError?.invoke("Aucune parole détectée")
        }

        if (enableDebugLogs) Log.d(TAG, "handleListenError retry=$retryCount")

        if (retryCount <= 2) {
            // Use scheduled listen to avoid starting recognition while TTS still releasing audio
            speakAndSchedule("Pouvez-vous reformuler ?")
            return
        }

        retryCount = 0
        speakAndSchedule("Je reste à votre écoute. Dites-moi ce que vous cherchez ou 'Je veux commander'.")
    }

    fun reset() {
        hasGreeted = false
        retryCount = 0
        isListeningActive = false
        delayedListenRunnable?.let { mainHandler.removeCallbacks(it) }
        delayedListenRunnable = null
        service.stopSpeaking()
        recognizer?.stopListening()
        recognizer?.destroy()
        recognizer = null
    }

    fun shutdown() {
        reset()
        recognizer?.destroy()
        recognizer = null
        service.shutdown()
    }
    
    fun startListening(prompt: String) {
        retryCount = 0
        service.speak(prompt) {
            listen()
        }
    }
    
    fun startProactiveGreeting() {
        if (!hasGreeted) {
            hasGreeted = true
            greet()
        }
    }
    
    fun destroy() {
        shutdown()
    }
    fun handleVoiceInput(speech: String) {
        // This allows external activities to inject voice commands
        handleSpeech(speech)
    }
}
