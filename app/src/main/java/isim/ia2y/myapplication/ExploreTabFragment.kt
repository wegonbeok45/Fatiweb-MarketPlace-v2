package isim.ia2y.myapplication

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isGone
import androidx.fragment.app.Fragment

// Cette classe organise cette partie de l'app.
class ExploreTabFragment : Fragment(R.layout.fragment_explore_tab) {
    // Cette fonction fait une action de cette partie de l'app.
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View?>(R.id.layoutBottomNav)?.isGone = true
        view.findViewById<View?>(R.id.viewBottomDivider)?.isGone = true
        view.findViewById<View?>(R.id.layoutTopSection)?.isGone = true
        setupHeaderAndExploreActions(view)
        polishExploreUi()
    }

    // Cette fonction fait une action de cette partie de l'app.
    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.updateHostCartBadge()
        updateNotificationBadge()
    }

    // Cette fonction fait une action de cette partie de l'app.
    private fun updateNotificationBadge() {
        val badge = view?.findViewById<View>(R.id.notificationBadge) ?: return
        badge.visibility = if (NotificationStore.hasUnread(requireContext())) View.VISIBLE else View.GONE
    }

    // Cette fonction fait une action de cette partie de l'app.
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
        (activity as? AppCompatActivity)?.bindComingSoon(
            R.id.cardCategoryArtisanat,
            R.id.cardCategoryCosmetiques,
            R.id.cardCategoryEpices,
            R.id.cardCategoryVetements,
            R.id.cardCategoryDecoration,
            R.id.cardCategoryHuiles
        )
    }

    // Cette fonction fait une action de cette partie de l'app.
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
                bottomNavId = 0, // Disable bottom nav animation
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
}
