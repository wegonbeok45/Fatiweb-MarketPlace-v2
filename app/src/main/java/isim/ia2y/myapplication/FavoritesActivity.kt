package isim.ia2y.myapplication

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton

// Cette classe organise cette partie de l'app.
class FavoritesActivity : AppCompatActivity() {
    private var shouldAnimateListOnNextRender = true

    // Cette fonction fait une action de cette partie de l'app.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_favoris)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        setupBottomNavigation()
        setupFavorisActions()
        applyPressFeedback(
            R.id.ivBack,
            R.id.flNotifications,
            R.id.navHome,
            R.id.navExplore,
            R.id.navCart,
            R.id.navProfile
        )
        revealViewsInOrder(
            R.id.layoutTopBar,
            R.id.scrollFavorites,
            R.id.layoutBottomNav
        )
    }

    // Cette fonction fait une action de cette partie de l'app.
    override fun onResume() {
        super.onResume()
        updateBottomCartBadge()
        renderFavorites()
    }

    // Cette fonction fait une action de cette partie de l'app.
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (handleNotificationPermissionResult(requestCode, grantResults)) return
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    // Cette fonction fait une action de cette partie de l'app.
    private fun setupBottomNavigation() {
        bindBottomNav(
            homeId = R.id.navHome,
            exploreId = R.id.navExplore,
            favoritesId = null,
            cartId = R.id.navCart,
            profileId = R.id.navProfile
        )
    }

    // Cette fonction fait une action de cette partie de l'app.
    private fun setupFavorisActions() {
        findViewById<View>(R.id.ivBack)?.setOnClickListener { finishWithMotion() }
        bindNotificationEntry(R.id.flNotifications)
    }

    // Cette fonction fait une action de cette partie de l'app.
    private fun renderFavorites() {
        val container = findViewById<LinearLayout>(R.id.layoutFavoritesContainer) ?: return
        val emptyText = findViewById<TextView>(R.id.tvEmptyFavorites) ?: return
        container.removeAllViews()

        val favorites = ProductCatalog.orderedFavorites(FavoritesStore.getFavorites(this))
        emptyText.visibility = if (favorites.isEmpty()) View.VISIBLE else View.GONE

        val inflater = LayoutInflater.from(this)
        favorites.forEachIndexed { index, product ->
            val card = inflater.inflate(R.layout.item_favoris_product_dynamic, container, false)
            val params = (card.layoutParams as? LinearLayout.LayoutParams)
                ?: LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            params.topMargin = if (index == 0) 0 else resources.getDimensionPixelSize(R.dimen.home_products_row_gap)
            card.layoutParams = params

            card.findViewById<ImageView>(R.id.ivFavoriteProductImage)?.loadCatalogImage(product.imageRes)
            card.findViewById<TextView>(R.id.tvFavoriteTag)?.text = product.tag
            card.findViewById<TextView>(R.id.tvFavoriteTitle)?.text = product.title
            card.findViewById<TextView>(R.id.tvFavoritePrice)?.text = formatDt(product.unitPrice)

            card.findViewById<View>(R.id.btnFavoriteToggle)?.setOnClickListener {
                FavoritesStore.setFavorite(this, product.id, false)
                showMotionSnackbar(
                    getString(R.string.product_removed_from_favorites, product.title),
                    R.id.layoutBottomNav
                )
                shouldAnimateListOnNextRender = false
                renderFavorites()
            }

            card.findViewById<MaterialButton>(R.id.btnFavoriteAddCart)?.setOnClickListener {
                CartStore.addOne(this, product.id)
                updateBottomCartBadge()
                showMotionSnackbar(
                    getString(R.string.product_added_to_cart, product.title),
                    R.id.layoutBottomNav
                )
            }

            card.setOnClickListener {
                navigateToProductDetails(product.id)
            }
            container.addView(card)
            if (shouldAnimateListOnNextRender) {
                animateListItemEntry(card, index, startDelayMs = 40L)
            }
        }
        shouldAnimateListOnNextRender = false
        if (favorites.isEmpty()) {
            revealSingleView(R.id.tvEmptyFavorites)
        }
    }
}
