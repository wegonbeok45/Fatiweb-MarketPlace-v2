package isim.ia2y.myapplication

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isGone
import androidx.fragment.app.Fragment

class ExploreTabFragment : Fragment(R.layout.fragment_explore_tab) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.applyStatusBarInset()
        view.findViewById<View?>(R.id.layoutBottomNav)?.isGone = true
        view.findViewById<View?>(R.id.viewBottomDivider)?.isGone = true
        setupHeaderAndExploreActions(view)
        polishExploreUi()
        setStaticSearchHint(view)
        CatalogSyncManager.refreshAsync(force = false)
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.updateHostCartBadge()
        updateNotificationBadge()
    }

    private fun updateNotificationBadge() {
        val badge = view?.findViewById<View>(R.id.notificationBadge) ?: return
        badge.visibility = if (NotificationStore.hasUnread(requireContext())) View.VISIBLE else View.GONE
    }

    private fun setupHeaderAndExploreActions(root: View) {
        root.findViewById<View>(R.id.ivHomeLogo)?.setOnClickListener {
            (activity as? MainActivity)?.selectTab(MainActivity.Tab.HOME)
        }
        root.findViewById<View>(R.id.tvBrand)?.setOnClickListener {
            (activity as? MainActivity)?.selectTab(MainActivity.Tab.HOME)
        }
        root.findViewById<View>(R.id.ivTopCart)?.setOnClickListener {
            (activity as? MainActivity)?.selectTab(MainActivity.Tab.CART)
        }

        (activity as? AppCompatActivity)?.bindNotificationEntry(R.id.ivTopNotifications)
        bindCategorySearch(root, R.id.cardCategoryArtisanat, "craft")
        bindCategorySearch(root, R.id.cardCategoryCosmetiques, "beauty")
        bindCategorySearch(root, R.id.cardCategoryEpices, "food")
        bindCategorySearch(root, R.id.cardCategoryVetements, "fashion")
        bindCategorySearch(root, R.id.cardCategoryDecoration, "decor")
        bindCategorySearch(root, R.id.cardCategoryHuiles, "food")
    }

    private fun polishExploreUi() {
        (activity as? AppCompatActivity)?.applyPressFeedback(
            R.id.ivHomeLogo,
            R.id.tvBrand,
            R.id.ivTopCart,
            R.id.ivTopNotifications,
            R.id.cardCategoryArtisanat,
            R.id.cardCategoryCosmetiques,
            R.id.cardCategoryEpices,
            R.id.cardCategoryVetements,
            R.id.cardCategoryDecoration,
            R.id.cardCategoryHuiles
        )

        (activity as? AppCompatActivity)?.window?.decorView?.post {
            (activity as? AppCompatActivity)?.animateExploreEntrance(
                topSectionId = R.id.layoutTopSection,
                scrollId = R.id.scrollExploreContent,
                bottomNavId = 0,
                cardIds = intArrayOf(
                    R.id.cardCategoryArtisanat,
                    R.id.cardCategoryCosmetiques,
                    R.id.cardCategoryEpices,
                    R.id.cardCategoryVetements,
                    R.id.cardCategoryDecoration,
                    R.id.cardCategoryHuiles
                )
            )
        }
    }

    private fun setStaticSearchHint(root: View) {
        val searchHint = root.findViewById<TextView>(R.id.tvSearchPlaceholder) ?: return
        searchHint.text = getString(R.string.auto_search_products_makers_or_3406)
    }

    private fun bindCategorySearch(root: View, viewId: Int, category: String) {
        root.findViewById<View?>(viewId)?.setOnClickListener {
            val host = activity as? AppCompatActivity ?: return@setOnClickListener
            startActivity(Intent(requireContext(), SearchActivity::class.java).apply {
                putExtra(SearchActivity.EXTRA_INITIAL_CATEGORY, category)
            })
            if (host.isReducedMotionEnabled()) {
                host.overridePendingTransition(0, 0)
            } else {
                host.overridePendingTransition(
                    R.anim.motion_activity_enter_from_top,
                    R.anim.motion_activity_exit_stay
                )
            }
        }
    }
}
