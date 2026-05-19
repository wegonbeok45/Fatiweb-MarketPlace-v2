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
    private val onClick: (HomeCategoryItem) -> Unit
) : RecyclerView.Adapter<HomeCategoryCarouselAdapter.ViewHolder>() {
    private var items: List<HomeCategoryItem> = initialItems

    init {
        setHasStableIds(true)
    }

    fun submitList(nextItems: List<HomeCategoryItem>) {
        items = nextItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_home_category_carousel, parent, false)
        return ViewHolder(view, onClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item)
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long = items.getOrNull(position)?.categoryKey?.hashCode()?.toLong()
        ?: RecyclerView.NO_ID

    class ViewHolder(
        itemView: View,
        private val onClick: (HomeCategoryItem) -> Unit
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
                        v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(150).start()
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
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
}
