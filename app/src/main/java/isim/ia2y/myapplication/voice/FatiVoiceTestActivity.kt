package isim.ia2y.myapplication.voice

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.setPadding
import isim.ia2y.myapplication.R
import android.util.Log

class FatiVoiceTestActivity : Activity() {
    private val TAG = "FatiVoiceTest"
    private lateinit var fatiVoiceService: FatiVoiceService
    private lateinit var fatiVoiceController: FatiVoiceController
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16)
        }

        val input = EditText(this).apply {
            hint = "Type simulated phrase here"
        }

        val btnSend = Button(this).apply {
            text = "Send phrase"
            setOnClickListener {
                val text = input.text.toString().takeIf { it.isNotBlank() } ?: ""
                if (text.isNotBlank()) {
                    appendLog("Simulate input: $text")
                    fatiVoiceController.handleVoiceInput(text)
                }
            }
        }

        val btnScenarioA = Button(this).apply {
            text = "Scenario A: order -> yes -> yes"
            setOnClickListener {
                runScenarioA()
            }
        }

        val btnScenarioB = Button(this).apply {
            text = "Scenario B: greeting"
            setOnClickListener {
                runScenarioB()
            }
        }

        val btnScenarioC = Button(this).apply {
            text = "Scenario C: order misunderstood"
            setOnClickListener {
                runScenarioC()
            }
        }

        val logView = TextView(this).apply {
            movementMethod = ScrollingMovementMethod()
            textSize = 12f
            setPadding(8)
        }

        root.addView(input)
        root.addView(btnSend)
        root.addView(btnScenarioA)
        root.addView(btnScenarioB)
        root.addView(btnScenarioC)

        val scroll = ScrollView(this).apply {
            addView(logView)
        }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)

        // Init voice controller/service
        fatiVoiceService = FatiVoiceService(this)
        fatiVoiceController = FatiVoiceController(this, fatiVoiceService, FatiVoiceGeminiService)

        fatiVoiceController.onRecognitionResult = { result ->
            mainHandler.post {
                appendLog("onRecognitionResult -> $result")
            }
        }
        fatiVoiceController.onRecognitionError = { err ->
            mainHandler.post {
                appendLog("onRecognitionError -> $err")
            }
        }

        appendLog("FatiVoiceTestActivity ready")
    }

    private fun appendLog(line: String) {
        Log.d(TAG, line)
        val tv = (findViewById<ScrollView>(android.R.id.content).getChildAt(0) as LinearLayout).getChildAt(6) as ScrollView
        val inner = tv.getChildAt(0) as TextView
        inner.append(line + "\n")
        mainHandler.post {
            tv.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun runScenarioA() {
        // Simulate: "Hé FatiVoice, je veux commander un t-shirt rouge taille M"
        appendLog("[Scenario A] start")
        fatiVoiceController.handleVoiceInput("Hé FatiVoice, je veux commander un t-shirt rouge taille M")
        mainHandler.postDelayed({
            appendLog("[Scenario A] reply: oui (add to cart)")
            fatiVoiceController.handleVoiceInput("oui")
        }, 1800)
        mainHandler.postDelayed({
            appendLog("[Scenario A] reply: oui (checkout)")
            fatiVoiceController.handleVoiceInput("oui")
        }, 3800)
    }

    private fun runScenarioB() {
        appendLog("[Scenario B] Greeting")
        fatiVoiceController.start()
        // No further input; observe logs
    }

    private fun runScenarioC() {
        appendLog("[Scenario C] start - ambiguous order")
        fatiVoiceController.handleVoiceInput("Hé FatiVoice, je veux commander")
    }
}
