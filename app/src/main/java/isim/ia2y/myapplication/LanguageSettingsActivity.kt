package isim.ia2y.myapplication

import android.os.Bundle
import android.view.View
import com.google.android.material.button.MaterialButton
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.content.res.ColorStateList

class LanguageSettingsActivity : AppCompatActivity() {
    private lateinit var buttonFrench: MaterialButton
    private lateinit var buttonEnglish: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_language_settings)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        findViewById<View>(R.id.ivBack)?.setOnClickListener { finishWithMotion() }

        buttonFrench = findViewById(R.id.btnFrench)
        buttonEnglish = findViewById(R.id.btnEnglish)

        updateLanguageButtons(LanguageManager.getSelectedLanguage(this))

        buttonFrench.setOnClickListener { onLanguageSelected("fr") }
        buttonEnglish.setOnClickListener { onLanguageSelected("en") }

        applyPressFeedback(R.id.ivBack, R.id.btnFrench, R.id.btnEnglish)
        revealViewsInOrder(R.id.layoutTopBar, R.id.cardLanguageOptions)
    }

    private fun onLanguageSelected(languageCode: String) {
        updateLanguageButtons(languageCode)
        if (languageCode != LanguageManager.getSelectedLanguage(this)) {
            LanguageManager.setLanguage(this, languageCode)
            showToast(getString(R.string.language_saved))
        }
    }

    private fun updateLanguageButtons(selectedLanguage: String) {
        val selectedBg = ContextCompat.getColor(this, R.color.profile_language_selected_bg)
        val selectedText = ContextCompat.getColor(this, R.color.profile_language_selected_text)
        val unselectedBg = ContextCompat.getColor(this, R.color.profile_language_unselected_bg)
        val unselectedText = ContextCompat.getColor(this, R.color.profile_language_unselected_text)
        val unselectedStroke = ContextCompat.getColor(this, R.color.colorBorderLight)
        val noStroke = resources.getDimensionPixelSize(R.dimen.space_0)
        val regularStroke = resources.getDimensionPixelSize(R.dimen.stroke_width_default)

        val isFrench = selectedLanguage == "fr"
        styleLanguageButton(
            button = buttonFrench,
            selected = isFrench,
            selectedBg = selectedBg,
            selectedText = selectedText,
            unselectedBg = unselectedBg,
            unselectedText = unselectedText,
            unselectedStroke = unselectedStroke,
            noStroke = noStroke,
            regularStroke = regularStroke
        )
        styleLanguageButton(
            button = buttonEnglish,
            selected = !isFrench,
            selectedBg = selectedBg,
            selectedText = selectedText,
            unselectedBg = unselectedBg,
            unselectedText = unselectedText,
            unselectedStroke = unselectedStroke,
            noStroke = noStroke,
            regularStroke = regularStroke
        )
    }

    private fun styleLanguageButton(
        button: MaterialButton,
        selected: Boolean,
        selectedBg: Int,
        selectedText: Int,
        unselectedBg: Int,
        unselectedText: Int,
        unselectedStroke: Int,
        noStroke: Int,
        regularStroke: Int
    ) {
        val bgColor = if (selected) selectedBg else unselectedBg
        val textColor = if (selected) selectedText else unselectedText
        button.backgroundTintList = ColorStateList.valueOf(bgColor)
        button.setTextColor(textColor)
        button.strokeWidth = if (selected) noStroke else regularStroke
        button.strokeColor = ColorStateList.valueOf(unselectedStroke)
    }
}
