package isim.ia2y.myapplication

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class HomeCategoryItem(
    val labelResId: Int,
    val iconResId: Int,
    val categoryKey: String
)

class HomeCategoryCarouselAdapter(
    private val items: List<HomeCategoryItem>,
    private val onClick: (HomeCategoryItem) -> Unit
) : RecyclerView.Adapter<HomeCategoryCarouselAdapter.ViewHolder>() {

    init {
        setHasStableIds(true)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_home_category_carousel, parent, false)
        return ViewHolder(view, onClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position % items.size]
        holder.bind(item)
    }

    override fun getItemCount(): Int = if (items.isEmpty()) 0 else Int.MAX_VALUE

    override fun getItemId(position: Int): Long = if (items.isEmpty()) {
        RecyclerView.NO_ID
    } else {
        items[position % items.size].categoryKey.hashCode().toLong()
    }

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
        }

        fun bind(item: HomeCategoryItem) {
            currentItem = item
            icon.setImageResource(item.iconResId)
            label.setText(item.labelResId)
        }
    }
}
