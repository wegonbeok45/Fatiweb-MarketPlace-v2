package isim.ia2y.myapplication
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Rect
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.lottie.LottieAnimationView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.slider.RangeSlider
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import isim.ia2y.myapplication.databinding.ActivitySearchBinding
import kotlin.math.roundToInt
import com.google.firebase.firestore.DocumentSnapshot

class SearchActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySearchBinding
    private val etSearch get() = binding.etSearch
    private val tvCancel get() = binding.tvSearchCancel
    private val defaultState get() = binding.layoutDefaultState
    private val suggestionsState get() = binding.layoutSuggestionsState
    private val resultsState get() = binding.layoutResultsState
    private val recentList get() = binding.layoutRecentList
    private val popularGroup get() = binding.chipGroupPopular
    private val suggestionsList get() = binding.layoutSuggestionsList
    private val resultsCount get() = binding.tvResultsCount
    private val sortDropdown get() = binding.dropdownSort
    private val resultsRecycler get() = binding.rvSearchResults
    private val emptyState get() = binding.layoutEmptyState
    private val emptyAnimation get() = binding.ivSearchEmptyAnimation
    private val skeleton get() = binding.layoutSkeleton
    private val scrollResults get() = binding.scrollResults
    private val btnBrowseCategories get() = binding.btnBrowseCategories
    private lateinit var resultsAdapter: SearchResultsAdapter

    private var currentQuery: String = ""
    private var currentSort: SortOption = SortOption.POPULAR
    private val uiHandler = Handler(Looper.getMainLooper())
    private var skeletonAnimator: ValueAnimator? = null
    private var lastResults: List<Product> = emptyList()
    private var searchRequestToken: Int = 0
    private var searchLastDoc: DocumentSnapshot? = null
    private var isSearching: Boolean = false
    private var hasMoreResults: Boolean = true

    private var filterCategory: String = "all"
    private var filterLocation: String = "all"
    private var filterMinPrice: Float = 0f
    private var filterMaxPrice: Float = MAX_FILTER_PRICE
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
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
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

        if (savedInstanceState != null) {
            currentQuery = savedInstanceState.getString(KEY_SEARCH_QUERY, "")
            currentSort = SortOption.values().getOrElse(savedInstanceState.getInt(KEY_SORT_ORDINAL, SortOption.POPULAR.ordinal)) { SortOption.POPULAR }
            filterCategory = savedInstanceState.getString(KEY_FILTER_CATEGORY, "all") ?: "all"
            filterLocation = savedInstanceState.getString(KEY_FILTER_LOCATION, "all") ?: "all"
            filterMinPrice = savedInstanceState.getFloat(KEY_FILTER_MIN_PRICE, 0f)
            filterMaxPrice = savedInstanceState.getFloat(KEY_FILTER_MAX_PRICE, MAX_FILTER_PRICE)
            filterBioNaturel = savedInstanceState.getBoolean(KEY_FILTER_BIO, false)
            sortDropdown.setText(getString(currentSort.labelRes), false)
            if (currentQuery.isNotBlank()) {
                etSearch.setText(currentQuery)
                etSearch.setSelection(currentQuery.length)
            }
        }

        applyInitialSearchState()
        lifecycleScope.launch {
            CatalogSyncManager.ensureSynced(force = false)
            if (isFinishing || isDestroyed) return@launch
            if (currentQuery.isNotBlank() || filterCategory != "all") {
                performSearch(currentQuery)
            }
        }
        etSearch.hint = getString(R.string.search_hint_products)
        onBackPressedDispatcher.addCallback(this) {
            finishSearchScreen()
        }
    }

    private fun applyInitialSearchState() {
        filterCategory = intent.getStringExtra(EXTRA_INITIAL_CATEGORY)?.ifBlank { "all" } ?: filterCategory
        val initialQuery = intent.getStringExtra(EXTRA_INITIAL_QUERY).orEmpty()
        if (initialQuery.isNotBlank()) {
            etSearch.setText(initialQuery)
            etSearch.setSelection(initialQuery.length)
            performSearch(initialQuery)
        } else if (filterCategory != "all") {
            toggleCancelButton(true)
            performSearch("")
        }
    }

    override fun onDestroy() {
        skeletonAnimator?.cancel()
        uiHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun bindViews() {
        resultsAdapter = SearchResultsAdapter(
            onToggleFavorite = { product -> FavoritesStore.toggleFavorite(this, product.id) },
            onAddToCart = { product -> handleAddToCart(product) },
            onOpenProduct = { product -> openProduct(product.id) }
        )
        resultsRecycler.layoutManager = GridLayoutManager(this, marketplaceGridSpanCount(maxSpanCount = 4))
        resultsRecycler.adapter = resultsAdapter
        if (resultsRecycler.itemDecorationCount == 0) {
            resultsRecycler.addItemDecoration(object : RecyclerView.ItemDecoration() {
                override fun getItemOffsets(
                    outRect: Rect,
                    view: View,
                    parent: RecyclerView,
                    state: RecyclerView.State
                ) {
                    val spanCount = (parent.layoutManager as? GridLayoutManager)?.spanCount ?: 1
                    val spacing = 12.dp
                    val halfSpacing = spacing / 2
                    val position = parent.getChildAdapterPosition(view)
                    if (position == RecyclerView.NO_POSITION) return

                    val column = position % spanCount
                    outRect.top = if (position < spanCount) 0 else spacing
                    outRect.left = if (column == 0) 0 else halfSpacing
                    outRect.right = if (column == spanCount - 1) 0 else halfSpacing
                }
            })
        }
        resultsRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0) {
                    val lm = recyclerView.layoutManager as? GridLayoutManager ?: return
                    val visibleItemCount = lm.childCount
                    val totalItemCount = lm.itemCount
                    val pastVisibleItems = lm.findFirstVisibleItemPosition()

                    if (!isSearching && hasMoreResults) {
                        if ((visibleItemCount + pastVisibleItems) >= totalItemCount - 4) {
                            performSearch(currentQuery, isLoadMore = true)
                        }
                    }
                }
            }
        })
    }

    private fun bindTopActions() {
        binding.ivSearchBack.setOnClickListener { finishSearchScreen() }
        binding.ivSearchFilter.setOnClickListener { showFilterSheet() }
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
                    if (hasActiveFilters()) {
                        performSearch("")
                    } else {
                        showDefaultState(animated = true)
                    }
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
            if (resultsState.visibility == View.VISIBLE) {
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
                typeface = resources.getFont(R.font.plus_jakarta_sans_regular)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
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
            openMainTab(MainActivity.Tab.EXPLORE)
        }
    }

    private fun renderRecentSearches() {
        recentList.removeAllViews()
        val items = getRecentSearches().take(5)
        if (items.isEmpty()) {
            val empty = TextView(this).apply {
                text = getString(R.string.search_recent_empty)
                setTextColor(ContextCompat.getColor(context, R.color.home_text_secondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = resources.getFont(R.font.plus_jakarta_sans_regular)
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = resources.getFont(R.font.plus_jakarta_sans_regular)
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = resources.getFont(R.font.plus_jakarta_sans_regular)
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

    private fun performSearch(query: String, isLoadMore: Boolean = false) {
        if (!isLoadMore) {
            searchRequestToken++
            searchLastDoc = null
            hasMoreResults = true
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
            lastResults = emptyList()
            resultsAdapter.submitList(emptyList())
        }

        if (isSearching || !hasMoreResults) return
        val requestToken = searchRequestToken
        isSearching = true

        val sortAtRequestTime = currentSort
        val categoryAtRequestTime = filterCategory
        val locationAtRequestTime = filterLocation
        val minPriceAtRequestTime = filterMinPrice
        val maxPriceAtRequestTime = filterMaxPrice
        val bioAtRequestTime = filterBioNaturel
        val lastDocAtRequestTime = searchLastDoc

        lifecycleScope.launch {
            val (fetchedProducts, nextDoc) = try {
                ProductService.fetchProductsPaginated(
                    pageSize = 50,
                    lastDoc = lastDocAtRequestTime,
                    categoryFilter = if (categoryAtRequestTime != "all") categoryAtRequestTime else null
                )
            } catch (e: Exception) {
                Pair(emptyList<Product>(), null)
            }

            val sorted = withContext(Dispatchers.Default) {
                val filtered = applyFilters(
                    query = query,
                    source = fetchedProducts,
                    category = categoryAtRequestTime,
                    location = locationAtRequestTime,
                    minPrice = minPriceAtRequestTime,
                    maxPrice = maxPriceAtRequestTime,
                    bioNaturel = bioAtRequestTime
                )
                applySort(filtered, sortAtRequestTime)
            }

            if (isFinishing || isDestroyed || requestToken != searchRequestToken) {
                isSearching = false
                return@launch
            }

            searchLastDoc = nextDoc
            hasMoreResults = nextDoc != null
            val combined = if (isLoadMore) lastResults + sorted else sorted
            lastResults = combined
            
            renderSearchResults(query, combined)
            if (!isLoadMore) stopSkeleton()
            isSearching = false
            
            if (combined.size < 10 && hasMoreResults) {
                performSearch(query, isLoadMore = true)
            }
        }
    }

    private fun renderSearchResults(query: String, items: List<Product>) {
        (resultsRecycler.layoutManager as? GridLayoutManager)?.spanCount =
            marketplaceGridSpanCount(maxSpanCount = 4)
        val label = if (query.isBlank()) currentFilterDescriptor() else query
        resultsCount.text = getString(R.string.search_results_count, items.size, label)
        resultsAdapter.submitList(items)
        if (items.isEmpty()) {
            emptyState.visibility = View.VISIBLE
            resultsRecycler.visibility = View.GONE
            emptyAnimation.playAnimation()
            return
        }
        emptyState.visibility = View.GONE
        resultsRecycler.visibility = View.VISIBLE
        emptyAnimation.pauseAnimation()
    }

    private fun handleAddToCart(product: Product) {
        if (product.stock <= 0) {
            showSearchToast(getString(R.string.product_state_out_of_stock))
            return
        }
        val beforeCount = CartStore.itemCount(this)
        CartStore.addOne(this, product.id)
        val afterCount = CartStore.itemCount(this)
        if (afterCount == beforeCount) {
            showSearchToast(getString(R.string.product_stock_limit_reached))
        } else {
            showSearchToast(getString(R.string.product_added_to_cart, product.title))
        }
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
                "craft" -> product.category == "craft"
                "decor" -> product.category == "decor"
                "food" -> product.category == "food"
                "fashion" -> product.category == "fashion"
                "beauty" -> product.category == "beauty"
                else -> true
            }
            val priceMatch = product.price in minPrice.toDouble()..maxPrice.toDouble()
            val locationMatch = locationKeyword == null || product.origin.contains(locationKeyword)
            val bioMatch = !bioNaturel || product.isBio
            val visibilityMatch = product.isActive
            queryMatch && categoryMatch && priceMatch && locationMatch && bioMatch && visibilityMatch
        }
    }

    private fun applySort(items: List<Product>, sort: SortOption): List<Product> = when (sort) {
        SortOption.PRICE_LOW -> items.sortedBy { it.price }
        SortOption.PRICE_HIGH -> items.sortedByDescending { it.price }
        SortOption.POPULAR -> items.sortedByDescending { it.reviewsCount }
        SortOption.NEWEST -> items.sortedByDescending { it.updatedAt }
    }

    private fun mapLocationToKeyword(value: String): String? = when (value) {
        "medina" -> "medina"
        "djerba" -> "djerba"
        "kairouan" -> "kairouan"
        else -> null
    }

    private fun currentFilterDescriptor(): String = when (filterCategory) {
        "craft" -> getString(R.string.search_category_craft)
        "decor" -> getString(R.string.search_category_decor)
        "food" -> getString(R.string.search_category_food)
        "fashion" -> getString(R.string.search_category_fashion)
        "beauty" -> getString(R.string.search_category_beauty)
        else -> getString(R.string.search_filter_all)
    }

    private fun showFilterSheet() {
        val dialog = BottomSheetDialog(this)
        val sheet = layoutInflater.inflate(R.layout.bottom_sheet_search_filters, findViewById(android.R.id.content), false)
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
            "decor" -> categoryGroup.check(R.id.chipCategoryDecor)
            "food" -> categoryGroup.check(R.id.chipCategoryFood)
            "fashion" -> categoryGroup.check(R.id.chipCategoryFashion)
            "beauty" -> categoryGroup.check(R.id.chipCategoryBeauty)
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
            filterMaxPrice = MAX_FILTER_PRICE
            filterBioNaturel = false
            dialog.dismiss()
            performSearch(currentQuery)
        }

        btnApply.setOnClickListener {
            filterCategory = when (categoryGroup.checkedChipId) {
                R.id.chipCategoryCraft -> "craft"
                R.id.chipCategoryDecor -> "decor"
                R.id.chipCategoryFood -> "food"
                R.id.chipCategoryFashion -> "fashion"
                R.id.chipCategoryBeauty -> "beauty"
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
            performSearch(currentQuery)
        }
        dialog.show()
    }

    private fun hasActiveFilters(): Boolean {
        return filterCategory != "all" ||
            filterLocation != "all" ||
            filterMinPrice > 0f ||
            filterMaxPrice < MAX_FILTER_PRICE ||
            filterBioNaturel
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
            view.animate().alpha(0f).setDuration(MotionTokens.QUICK).withEndAction {
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
        target.animate().alpha(1f).setDuration(MotionTokens.STANDARD)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    private fun toggleCancelButton(show: Boolean) {
        if (show && tvCancel.visibility != View.VISIBLE) {
            tvCancel.visibility = View.VISIBLE
            tvCancel.alpha = 0f
            tvCancel.animate().alpha(1f).setDuration(MotionTokens.QUICK).start()
        } else if (!show && tvCancel.visibility == View.VISIBLE) {
            tvCancel.animate().alpha(0f).setDuration(MotionTokens.QUICK).withEndAction {
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
        scrollResults.animate().alpha(1f).setDuration(MotionTokens.STANDARD).start()
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

    private fun finishSearchScreen() {
        finish()
        overridePendingTransition(R.anim.motion_activity_enter_stay, R.anim.motion_activity_exit_to_top)
    }

    private fun openMainTab(tab: MainActivity.Tab) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_OPEN_TAB, tab.name)
        }
        startActivity(intent)
        overridePendingTransition(R.anim.motion_activity_enter_forward, R.anim.motion_activity_exit_forward)
    }

    private fun openProduct(productId: String) {
        startActivity(ProductDetailsScreen.createIntent(this, productId))
        overridePendingTransition(R.anim.motion_activity_enter_forward, R.anim.motion_activity_exit_forward)
    }

    private fun showSearchToast(message: String) {
        showMotionSnackbar(message)
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
        saveRecentListToPrefs(recent.take(MAX_RECENT_SEARCHES))
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SEARCH_QUERY, currentQuery)
        outState.putInt(KEY_SORT_ORDINAL, currentSort.ordinal)
        outState.putString(KEY_FILTER_CATEGORY, filterCategory)
        outState.putString(KEY_FILTER_LOCATION, filterLocation)
        outState.putFloat(KEY_FILTER_MIN_PRICE, filterMinPrice)
        outState.putFloat(KEY_FILTER_MAX_PRICE, filterMaxPrice)
        outState.putBoolean(KEY_FILTER_BIO, filterBioNaturel)
    }

    companion object {
        private const val PREFS_SEARCH = "search_screen_prefs"
        private const val KEY_RECENT_SEARCHES_CSV = "recent_searches_csv"
        private const val RECENT_SEPARATOR = "|||"
        private const val MAX_FILTER_PRICE = 400f
        private const val MAX_RECENT_SEARCHES = 5
        const val EXTRA_INITIAL_QUERY = "extra_initial_query"
        const val EXTRA_INITIAL_CATEGORY = "extra_initial_category"

        private const val KEY_SEARCH_QUERY = "search_query"
        private const val KEY_SORT_ORDINAL = "sort_ordinal"
        private const val KEY_FILTER_CATEGORY = "filter_category"
        private const val KEY_FILTER_LOCATION = "filter_location"
        private const val KEY_FILTER_MIN_PRICE = "filter_min_price"
        private const val KEY_FILTER_MAX_PRICE = "filter_max_price"
        private const val KEY_FILTER_BIO = "filter_bio"

        fun createIntent(
            context: Context,
            initialQuery: String = "",
            initialCategory: String = "all"
        ): Intent {
            return Intent(context, SearchActivity::class.java).apply {
                putExtra(EXTRA_INITIAL_QUERY, initialQuery)
                putExtra(EXTRA_INITIAL_CATEGORY, initialCategory)
            }
        }
    }
}
