package isim.ia2y.myapplication

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class CategoryCardAdapter(
    private val mode: Mode,
    private val onClick: (MarketplaceCategory) -> Unit
) : RecyclerView.Adapter<CategoryCardAdapter.ViewHolder>() {
    enum class Mode { GRID, COMPACT }

    private var items: List<MarketplaceCategory> = emptyList()

    init {
        setHasStableIds(true)
    }

    fun submitList(nextItems: List<MarketplaceCategory>) {
        items = nextItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_marketplace_category_card, parent, false)
        return ViewHolder(view, mode, onClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long =
        items.getOrNull(position)?.id?.hashCode()?.toLong() ?: RecyclerView.NO_ID

    class ViewHolder(
        itemView: View,
        private val mode: Mode,
        private val onClick: (MarketplaceCategory) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val image = itemView.findViewById<ImageView>(R.id.ivMarketplaceCategoryImage)
        private val title = itemView.findViewById<TextView>(R.id.tvMarketplaceCategoryName)
        private val meta = itemView.findViewById<TextView>(R.id.tvMarketplaceCategoryMeta)
        private var current: MarketplaceCategory? = null

        init {
            itemView.setOnClickListener {
                current?.let(onClick)
            }
            // Match Home page's press animation (0.97f scale)
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

        fun bind(item: MarketplaceCategory) {
            current = item
            image.loadCatalogImage(
                item.imageUrl,
                MarketplaceCategories.imageResFor(item.id),
                requestedSizePx = 480,
                crossfadeMillis = 180
            )
            title.text = item.name
            meta.text = itemView.context.getString(R.string.category_card_browse)
            itemView.layoutParams = itemView.layoutParams.apply {
                height = if (mode == Mode.COMPACT) {
                    itemView.resources.getDimensionPixelSize(R.dimen.marketplace_category_compact_height)
                } else {
                    itemView.resources.getDimensionPixelSize(R.dimen.marketplace_category_grid_height)
                }
                if (mode == Mode.COMPACT) {
                    width = itemView.resources.getDimensionPixelSize(R.dimen.marketplace_category_compact_width)
                }
            }
        }
    }
}
