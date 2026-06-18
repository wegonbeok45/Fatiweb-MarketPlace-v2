package isim.ia2y.myapplication

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class HomeCategoryItem(
    val label: String,
    val imageUrl: String,
    val imageResId: Int,
    val categoryKey: String,
    val badgeIconResId: Int
)

class HomeCategoryCarouselAdapter(
    initialItems: List<HomeCategoryItem>,
    private val onClick: (HomeCategoryItem) -> Unit,
    private val onInteractionChanged: (Boolean) -> Unit = {}
) : RecyclerView.Adapter<HomeCategoryCarouselAdapter.ViewHolder>() {
    private var items: List<HomeCategoryItem> = initialItems

    fun submitList(nextItems: List<HomeCategoryItem>) {
        items = nextItems
        notifyDataSetChanged()
    }

    fun centeredStartPosition(): Int {
        if (items.isEmpty()) return 0
        val middle = itemCount / 2
        return middle - (middle % items.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_home_category_carousel, parent, false)
        return ViewHolder(view, onClick, onInteractionChanged)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position % items.size]
        holder.bind(item)
    }

    override fun getItemCount(): Int = if (items.isEmpty()) 0 else items.size * LOOP_MULTIPLIER

    class ViewHolder(
        itemView: View,
        private val onClick: (HomeCategoryItem) -> Unit,
        private val onInteractionChanged: (Boolean) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val icon = itemView.findViewById<ImageView>(R.id.ivCategoryIcon)
        private val label = itemView.findViewById<TextView>(R.id.tvCategoryLabel)
        private var currentItem: HomeCategoryItem? = null

        init {
            itemView.setOnClickListener {
                currentItem?.let(onClick)
            }
            itemView.setOnTouchListener { v, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        onInteractionChanged(true)
                        v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(150).start()
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        onInteractionChanged(false)
                        v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                    }
                }
                false
            }
        }

        fun bind(item: HomeCategoryItem) {
            currentItem = item
            icon.visibility = View.VISIBLE
            icon.imageTintList = null
            icon.clearColorFilter()
            icon.loadCatalogImage(
                item.imageUrl,
                item.imageResId,
                requestedSizePx = 480,
                crossfadeMillis = 180
            )
            label.text = item.label
        }
    }

    private companion object {
        const val LOOP_MULTIPLIER = 400
    }
}
