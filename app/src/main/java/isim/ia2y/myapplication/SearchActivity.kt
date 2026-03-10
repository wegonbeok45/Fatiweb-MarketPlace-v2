package isim.ia2y.myapplication

import android.animation.ValueAnimator
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.slider.RangeSlider
import com.google.android.material.switchmaterial.SwitchMaterial
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class SearchActivity : AppCompatActivity() {
    private lateinit var etSearch: EditText
    private lateinit var tvCancel: TextView
    private lateinit var defaultState: View
    private lateinit var suggestionsState: View
    private lateinit var resultsState: View
    private lateinit var recentList: LinearLayout
    private lateinit var popularGroup: ChipGroup
    private lateinit var suggestionsList: LinearLayout
    private lateinit var resultsCount: TextView
    private lateinit var sortDropdown: AutoCompleteTextView
    private lateinit var gridResults: GridLayout
    private lateinit var emptyState: View
    private lateinit var skeleton: View
    private lateinit var scrollResults: View
    private lateinit var btnBrowseCategories: MaterialButton

    private var currentQuery: String = ""
    private var currentSort: SortOption = SortOption.POPULAR
    private val uiHandler = Handler(Looper.getMainLooper())
    private val searchExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var skeletonAnimator: ValueAnimator? = null
    private var lastResults: List<Product> = emptyList()
    private var searchRequestToken: Int = 0

    private var filterCategory: String = "all"
    private var filterLocation: String = "all"
    private var filterMinPrice: Float = 0f
    private var filterMaxPrice: Float = 400f
    private var filterBioNaturel: Boolean = false

    private val popularSearches = listOf(
        "Harissa",
        "Huile d'olive",
        "Chechia",
        "Bijoux Djerba",
        "Tapis Kairouan"
    )

    private val suggestionPool = listOf(
        "Harissa Arbi",
        "Harissa Bio",
        "Harissa traditionnelle",
        "Huile d'olive extra vierge",
        "Chechia traditionnelle",
        "Bijoux Djerba argent",
        "Tapis Kairouan laine",
        "Savon naturel",
        "Poterie Nabeul",
        "Fouta Hammamet"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_search)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        bindViews()
        bindTopActions()
        bindSearchInput()
        bindSortDropdown()
        bindPopularChips()
        bindBrowseCategories()
        renderRecentSearches()
        showDefaultState(animated = false)
        focusAndOpenKeyboard()
        startTypingHintAnimation(
            hintViewId = R.id.etSearch,
            fullText = getString(R.string.search_hint_products),
            stepDelayMs = 45L, // Faster for dedicated search screen
            R.id.etSearch
        )
        onBackPressedDispatcher.addCallback(this) {
            finishToTop()
        }
    }

    override fun onDestroy() {
        skeletonAnimator?.cancel()
        uiHandler.removeCallbacksAndMessages(null)
        searchExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun bindViews() {
        etSearch = findViewById(R.id.etSearch)
        tvCancel = findViewById(R.id.tvSearchCancel)
        defaultState = findViewById(R.id.layoutDefaultState)
        suggestionsState = findViewById(R.id.layoutSuggestionsState)
        resultsState = findViewById(R.id.layoutResultsState)
        recentList = findViewById(R.id.layoutRecentList)
        popularGroup = findViewById(R.id.chipGroupPopular)
        suggestionsList = findViewById(R.id.layoutSuggestionsList)
        resultsCount = findViewById(R.id.tvResultsCount)
        sortDropdown = findViewById(R.id.dropdownSort)
        gridResults = findViewById(R.id.gridSearchResults)
        emptyState = findViewById(R.id.layoutEmptyState)
        skeleton = findViewById(R.id.layoutSkeleton)
        scrollResults = findViewById(R.id.scrollResults)
        btnBrowseCategories = findViewById(R.id.btnBrowseCategories)
    }

    private fun bindTopActions() {
        findViewById<View>(R.id.ivSearchBack).setOnClickListener { finishToTop() }
        findViewById<View>(R.id.ivSearchFilter).setOnClickListener { showFilterSheet() }
        tvCancel.setOnClickListener {
            etSearch.text?.clear()
            focusAndOpenKeyboard()
            showDefaultState(animated = true)
        }
    }

    private fun bindSearchInput() {
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim().orEmpty()
                toggleCancelButton(query.isNotEmpty())
                if (query.isEmpty()) {
                    showDefaultState(animated = true)
                } else {
                    renderSuggestions(query)
                    showSuggestionsState(animated = true)
                }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        etSearch.setOnEditorActionListener { _, actionId, event ->
            val shouldSearch = actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (!shouldSearch) return@setOnEditorActionListener false
            val query = etSearch.text?.toString()?.trim().orEmpty()
            if (query.isNotEmpty()) {
                performSearch(query)
            }
            true
        }
    }

    private fun bindSortDropdown() {
        val labels = SortOption.values().map { getString(it.labelRes) }
        sortDropdown.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, labels))
        sortDropdown.setText(getString(currentSort.labelRes), false)
        sortDropdown.keyListener = null
        sortDropdown.setOnClickListener { sortDropdown.showDropDown() }
        sortDropdown.setOnItemClickListener { _, _, position, _ ->
            currentSort = SortOption.values()[position]
            if (currentQuery.isNotBlank()) {
                val sorted = applySort(lastResults, currentSort)
                renderSearchResults(currentQuery, sorted)
            }
        }
    }

    private fun bindPopularChips() {
        popularGroup.removeAllViews()
        popularSearches.forEach { term ->
            val chip = Chip(this).apply {
                text = term
                isClickable = true
                isCheckable = false
                chipCornerRadius = 999f
                setTextColor(ContextCompat.getColor(context, R.color.home_chip_text))
                chipBackgroundColor = ContextCompat.getColorStateList(context, R.color.home_chip_bg)
                rippleColor = ContextCompat.getColorStateList(context, R.color.home_nav_selected_bg)
                typeface = resources.getFont(R.font.poppins_regular)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setOnClickListener {
                    etSearch.setText(term)
                    etSearch.setSelection(term.length)
                    performSearch(term)
                }
            }
            popularGroup.addView(chip)
        }
    }

    private fun bindBrowseCategories() {
        btnBrowseCategories.setOnClickListener {
            navigateToMainTab(MainActivity.Tab.EXPLORE)
        }
    }

    private fun renderRecentSearches() {
        recentList.removeAllViews()
        val items = getRecentSearches().take(5)
        if (items.isEmpty()) {
            val empty = TextView(this).apply {
                text = getString(R.string.search_recent_empty)
                setTextColor(ContextCompat.getColor(context, R.color.home_text_secondary))
                textSize = 13f
                typeface = resources.getFont(R.font.poppins_regular)
            }
            recentList.addView(empty)
            return
        }

        items.forEachIndexed { index, term ->
            recentList.addView(buildRecentRow(term))
            if (index < items.lastIndex) {
                recentList.addView(buildDivider())
            }
        }
    }

    private fun buildRecentRow(term: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 6.dp }
            setPadding(0, 8.dp, 0, 8.dp)
        }

        val clock = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_recent_history)
            setColorFilter(ContextCompat.getColor(context, R.color.home_text_secondary))
            layoutParams = LinearLayout.LayoutParams(18.dp, 18.dp)
        }

        val text = TextView(this).apply {
            this.text = term
            setTextColor(ContextCompat.getColor(context, R.color.home_text_primary))
            textSize = 14f
            typeface = resources.getFont(R.font.poppins_regular)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = 10.dp }
            setOnClickListener {
                etSearch.setText(term)
                etSearch.setSelection(term.length)
                performSearch(term)
            }
        }

        val remove = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(ContextCompat.getColor(context, R.color.home_text_secondary))
            layoutParams = LinearLayout.LayoutParams(18.dp, 18.dp)
            setOnClickListener {
                removeRecentSearch(term)
                renderRecentSearches()
            }
        }

        row.addView(clock)
        row.addView(text)
        row.addView(remove)
        return row
    }

    private fun renderSuggestions(query: String) {
        suggestionsList.removeAllViews()
        val normalized = query.lowercase(Locale.getDefault())
        val hits = (suggestionPool + ProductCatalog.all().map { it.title })
            .distinct()
            .filter { it.lowercase(Locale.getDefault()).contains(normalized) }
            .take(8)
            .ifEmpty { listOf(query) }

        hits.forEachIndexed { index, hit ->
            suggestionsList.addView(buildSuggestionRow(query, hit))
            if (index < hits.lastIndex) {
                suggestionsList.addView(buildDivider())
            }
        }
    }

    private fun buildSuggestionRow(query: String, suggestion: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, 10.dp, 0, 10.dp)
            setOnClickListener {
                etSearch.setText(suggestion)
                etSearch.setSelection(suggestion.length)
                performSearch(suggestion)
            }
        }

        val icon = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_search)
            setColorFilter(ContextCompat.getColor(context, R.color.home_text_secondary))
            layoutParams = LinearLayout.LayoutParams(18.dp, 18.dp)
        }

        val text = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = 10.dp }
            textSize = 15f
            typeface = resources.getFont(R.font.poppins_regular)
            this.text = createSuggestionSpan(query, suggestion)
        }

        row.addView(icon)
        row.addView(text)
        return row
    }

    private fun createSuggestionSpan(query: String, suggestion: String): SpannableString {
        val span = SpannableString(suggestion)
        val start = suggestion.lowercase(Locale.getDefault())
            .indexOf(query.lowercase(Locale.getDefault()))
        if (start < 0) {
            span.setSpan(
                ForegroundColorSpan(ContextCompat.getColor(this, R.color.home_text_primary)),
                0,
                suggestion.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            return span
        }
        val end = (start + query.length).coerceAtMost(suggestion.length)
        span.setSpan(
            StyleSpan(android.graphics.Typeface.BOLD),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        span.setSpan(
            ForegroundColorSpan(ContextCompat.getColor(this, R.color.home_text_primary)),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        if (start > 0) {
            span.setSpan(
                ForegroundColorSpan(ContextCompat.getColor(this, R.color.home_text_secondary)),
                0,
                start,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        if (end < suggestion.length) {
            span.setSpan(
                ForegroundColorSpan(ContextCompat.getColor(this, R.color.home_text_secondary)),
                end,
                suggestion.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return span
    }

    private fun performSearch(query: String) {
        val requestToken = ++searchRequestToken
        currentQuery = query
        if (etSearch.text?.toString() != query) {
            etSearch.setText(query)
            etSearch.setSelection(query.length)
        }
        hideKeyboard()
        saveRecentSearch(query)
        renderRecentSearches()
        showResultsState(animated = true)
        startSkeleton()

        val sortAtRequestTime = currentSort
        val catalogSnapshot = ProductCatalog.all()
        val categoryAtRequestTime = filterCategory
        val locationAtRequestTime = filterLocation
        val minPriceAtRequestTime = filterMinPrice
        val maxPriceAtRequestTime = filterMaxPrice
        val bioAtRequestTime = filterBioNaturel

        searchExecutor.execute {
            val filtered = applyFilters(
                query = query,
                source = catalogSnapshot,
                category = categoryAtRequestTime,
                location = locationAtRequestTime,
                minPrice = minPriceAtRequestTime,
                maxPrice = maxPriceAtRequestTime,
                bioNaturel = bioAtRequestTime
            )
            val sorted = applySort(filtered, sortAtRequestTime)

            uiHandler.post {
                if (isFinishing || isDestroyed) return@post
                if (requestToken != searchRequestToken) return@post
                if (query != currentQuery) return@post

                lastResults = sorted
                renderSearchResults(query, sorted)
                stopSkeleton()
            }
        }
    }

    private fun renderSearchResults(query: String, items: List<Product>) {
        resultsCount.text = getString(R.string.search_results_count, items.size, query)
        gridResults.removeAllViews()
        if (items.isEmpty()) {
            emptyState.visibility = View.VISIBLE
            return
        }
        emptyState.visibility = View.GONE

        items.forEachIndexed { index, product ->
            val card = layoutInflater.inflate(R.layout.item_search_product_card, gridResults, false)
            bindResultCard(card, product)
            val col = index % 2
            val row = index / 2
            val params = GridLayout.LayoutParams(
                GridLayout.spec(row),
                GridLayout.spec(col, 1f)
            ).apply {
                width = 0
                setMargins(
                    if (col == 0) 0 else 8.dp,
                    0,
                    if (col == 0) 8.dp else 0,
                    18.dp
                )
            }
            card.layoutParams = params
            gridResults.addView(card)
        }
    }

    private fun bindResultCard(view: View, product: Product) {
        view.findViewById<ImageView>(R.id.ivSearchProductImage).setImageResource(product.imageRes)
        view.findViewById<TextView>(R.id.tvSearchProductTitle).text = product.title
        view.findViewById<TextView>(R.id.tvSearchProductSubtitle).text = product.subtitle
        view.findViewById<TextView>(R.id.tvSearchPriceAmount).text = String.format(Locale.US, "%.3f", product.price)

        val favoriteIcon = view.findViewById<ImageView>(R.id.ivSearchFavoriteIcon)
        fun refreshFavoriteTint() {
            val isFavorite = FavoritesStore.isFavorite(this, product.id)
            val tint = if (isFavorite) R.color.home_heart_active else R.color.home_text_primary
            favoriteIcon.setColorFilter(ContextCompat.getColor(this, tint))
        }
        refreshFavoriteTint()

        view.findViewById<MaterialCardView>(R.id.btnSearchFavorite).setOnClickListener {
            FavoritesStore.toggleFavorite(this, product.id)
            refreshFavoriteTint()
        }
        view.findViewById<MaterialCardView>(R.id.btnSearchAddCart).setOnClickListener {
            CartStore.addOne(this, product.id)
            showToast(getString(R.string.product_added_to_cart, product.title))
        }
        view.setOnClickListener { navigateToProductDetails(product.id) }
    }

    private fun applyFilters(
        query: String,
        source: List<Product>,
        category: String = filterCategory,
        location: String = filterLocation,
        minPrice: Float = filterMinPrice,
        maxPrice: Float = filterMaxPrice,
        bioNaturel: Boolean = filterBioNaturel
    ): List<Product> {
        val locationKeyword = mapLocationToKeyword(location)
        val normalizedQuery = query.lowercase(Locale.getDefault())
        return source.filter { product ->
            val searchable = product.searchableText

            val queryMatch = searchable.contains(normalizedQuery)
            val categoryMatch = when (category) {
                "craft" -> searchable.contains("artisan") || searchable.contains("tapis") || searchable.contains("poterie")
                "food" -> searchable.contains("huile") || searchable.contains("harissa") || searchable.contains("safran")
                "fashion" -> searchable.contains("chechia") || searchable.contains("balgha") || searchable.contains("bijoux")
                else -> true
            }
            val priceMatch = product.price in minPrice.toDouble()..maxPrice.toDouble()
            val locationMatch = locationKeyword == null || searchable.contains(locationKeyword)
            val bioMatch = !bioNaturel || searchable.contains("bio") || searchable.contains("naturel")
            queryMatch && categoryMatch && priceMatch && locationMatch && bioMatch
        }
    }

    private fun applySort(items: List<Product>, sort: SortOption): List<Product> = when (sort) {
        SortOption.PRICE_LOW -> items.sortedBy { it.price }
        SortOption.PRICE_HIGH -> items.sortedByDescending { it.price }
        SortOption.POPULAR -> items.sortedByDescending { it.reviewsCount }
        SortOption.NEWEST -> items.sortedByDescending { it.id.hashCode() }
    }

    private fun mapLocationToKeyword(value: String): String? = when (value) {
        "medina" -> "medina"
        "djerba" -> "djerba"
        "kairouan" -> "kairouan"
        else -> null
    }

    private fun showFilterSheet() {
        val dialog = BottomSheetDialog(this)
        val sheet = layoutInflater.inflate(R.layout.bottom_sheet_search_filters, null)
        dialog.setContentView(sheet)

        val categoryGroup = sheet.findViewById<ChipGroup>(R.id.chipGroupFilterCategory)
        val locationGroup = sheet.findViewById<ChipGroup>(R.id.chipGroupLocation)
        val slider = sheet.findViewById<RangeSlider>(R.id.sliderPriceRange)
        val tvRange = sheet.findViewById<TextView>(R.id.tvPriceRangeValue)
        val swBio = sheet.findViewById<SwitchMaterial>(R.id.switchBioNaturel)
        val btnReset = sheet.findViewById<MaterialButton>(R.id.btnFilterReset)
        val btnApply = sheet.findViewById<MaterialButton>(R.id.btnFilterApply)

        fun syncRangeLabel(values: List<Float>) {
            tvRange.text = getString(
                R.string.search_filter_price_value,
                values[0].roundToInt(),
                values[1].roundToInt()
            )
        }

        slider.values = listOf(filterMinPrice, filterMaxPrice)
        syncRangeLabel(slider.values)
        slider.addOnChangeListener { _, _, _ -> syncRangeLabel(slider.values) }
        swBio.isChecked = filterBioNaturel

        when (filterCategory) {
            "craft" -> categoryGroup.check(R.id.chipCategoryCraft)
            "food" -> categoryGroup.check(R.id.chipCategoryFood)
            "fashion" -> categoryGroup.check(R.id.chipCategoryFashion)
            else -> categoryGroup.check(R.id.chipCategoryAll)
        }
        when (filterLocation) {
            "medina" -> locationGroup.check(R.id.chipLocationMedina)
            "djerba" -> locationGroup.check(R.id.chipLocationDjerba)
            "kairouan" -> locationGroup.check(R.id.chipLocationKairouan)
            else -> locationGroup.check(R.id.chipLocationAny)
        }

        btnReset.setOnClickListener {
            filterCategory = "all"
            filterLocation = "all"
            filterMinPrice = 0f
            filterMaxPrice = 400f
            filterBioNaturel = false
            dialog.dismiss()
            if (currentQuery.isNotBlank()) performSearch(currentQuery)
        }

        btnApply.setOnClickListener {
            filterCategory = when (categoryGroup.checkedChipId) {
                R.id.chipCategoryCraft -> "craft"
                R.id.chipCategoryFood -> "food"
                R.id.chipCategoryFashion -> "fashion"
                else -> "all"
            }
            filterLocation = when (locationGroup.checkedChipId) {
                R.id.chipLocationMedina -> "medina"
                R.id.chipLocationDjerba -> "djerba"
                R.id.chipLocationKairouan -> "kairouan"
                else -> "all"
            }
            filterMinPrice = slider.values[0]
            filterMaxPrice = slider.values[1]
            filterBioNaturel = swBio.isChecked
            dialog.dismiss()
            if (currentQuery.isNotBlank()) performSearch(currentQuery)
        }
        dialog.show()
    }

    private fun showDefaultState(animated: Boolean) = fadeTo(defaultState, animated)
    private fun showSuggestionsState(animated: Boolean) = fadeTo(suggestionsState, animated)
    private fun showResultsState(animated: Boolean) = fadeTo(resultsState, animated)

    private fun fadeTo(target: View, animated: Boolean) {
        val states = listOf(defaultState, suggestionsState, resultsState)
        states.forEach { view ->
            if (view == target) return@forEach
            if (!view.isShown) return@forEach
            if (!animated) {
                view.visibility = View.GONE
                return@forEach
            }
            view.animate().alpha(0f).setDuration(120L).withEndAction {
                view.visibility = View.GONE
                view.alpha = 1f
            }.start()
        }
        if (target.isShown) return
        if (!animated) {
            target.visibility = View.VISIBLE
            target.alpha = 1f
            return
        }
        target.alpha = 0f
        target.visibility = View.VISIBLE
        target.animate().alpha(1f).setDuration(170L)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    private fun toggleCancelButton(show: Boolean) {
        if (show && tvCancel.visibility != View.VISIBLE) {
            tvCancel.visibility = View.VISIBLE
            tvCancel.alpha = 0f
            tvCancel.animate().alpha(1f).setDuration(150L).start()
        } else if (!show && tvCancel.visibility == View.VISIBLE) {
            tvCancel.animate().alpha(0f).setDuration(120L).withEndAction {
                tvCancel.visibility = View.GONE
                tvCancel.alpha = 1f
            }.start()
        }
    }

    private fun buildDivider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            1.dp
        )
        setBackgroundColor(ContextCompat.getColor(context, R.color.home_divider))
    }

    private fun startSkeleton() {
        scrollResults.visibility = View.GONE
        skeleton.visibility = View.VISIBLE
        skeletonAnimator?.cancel()
        skeletonAnimator = ValueAnimator.ofFloat(0.45f, 1f).apply {
            duration = 720L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { skeleton.alpha = it.animatedValue as Float }
            start()
        }
    }

    private fun stopSkeleton() {
        skeletonAnimator?.cancel()
        skeleton.alpha = 1f
        skeleton.visibility = View.GONE
        scrollResults.visibility = View.VISIBLE
        scrollResults.alpha = 0f
        scrollResults.animate().alpha(1f).setDuration(180L).start()
    }

    private fun focusAndOpenKeyboard() {
        etSearch.requestFocus()
        etSearch.post {
            val imm = getSystemService(InputMethodManager::class.java)
            imm?.showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(etSearch.windowToken, 0)
    }

    private fun getRecentSearches(): MutableList<String> {
        val raw = getSharedPreferences(PREFS_SEARCH, Context.MODE_PRIVATE)
            .getString(KEY_RECENT_SEARCHES_CSV, "")
            .orEmpty()
        if (raw.isBlank()) return mutableListOf()
        
        return try {
            val array = org.json.JSONArray(raw)
            val list = mutableListOf<String>()
            for (i in 0 until array.length()) {
                list.add(array.getString(i))
            }
            list
        } catch (e: Exception) {
            raw.split(RECENT_SEPARATOR).map { it.trim() }
                .filter { it.isNotBlank() }
                .toMutableList()
        }
    }

    private fun saveRecentListToPrefs(list: List<String>) {
        val array = org.json.JSONArray()
        list.forEach { array.put(it) }
        getSharedPreferences(PREFS_SEARCH, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RECENT_SEARCHES_CSV, array.toString())
            .apply()
    }

    private fun saveRecentSearch(query: String) {
        val normalized = query.trim()
        if (normalized.isEmpty()) return
        val recent = getRecentSearches()
        recent.removeAll { it.equals(normalized, ignoreCase = true) }
        recent.add(0, normalized)
        saveRecentListToPrefs(recent.take(5))
    }

    private fun removeRecentSearch(query: String) {
        val filtered = getRecentSearches().filterNot { it.equals(query, ignoreCase = true) }
        saveRecentListToPrefs(filtered)
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).roundToInt()

    private enum class SortOption(val labelRes: Int) {
        PRICE_LOW(R.string.search_sort_price_low),
        PRICE_HIGH(R.string.search_sort_price_high),
        POPULAR(R.string.search_sort_popular),
        NEWEST(R.string.search_sort_newest)
    }

    companion object {
        private const val PREFS_SEARCH = "search_screen_prefs"
        private const val KEY_RECENT_SEARCHES_CSV = "recent_searches_csv"
        private const val RECENT_SEPARATOR = "|||"
    }
}
