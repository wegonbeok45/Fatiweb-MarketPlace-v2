package isim.ia2y.myapplication.ui.base

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/**
 * Lightweight base for the marketplace rebuild. Wraps a [ListAdapter] so every list
 * gets DiffUtil for free and a single bind callback.
 *
 * Usage:
 *
 * ```
 * class OrdersAdapter(onClick: (Order) -> Unit) : BaseListAdapter<Order>(
 *     layoutRes = R.layout.ms_component_list_row,
 *     diff = idDiff { it.id },
 *     bind = { view, item -> /* bind row */ },
 *     onClick = onClick,
 * )
 * ```
 */
open class BaseListAdapter<T : Any>(
    @LayoutRes private val layoutRes: Int,
    diff: DiffUtil.ItemCallback<T>,
    private val bind: (view: View, item: T) -> Unit,
    private val onClick: ((T) -> Unit)? = null,
) : ListAdapter<T, BaseListAdapter.VH>(diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        bind(holder.itemView, item)
        holder.itemView.setOnClickListener(
            onClick?.let { handler -> View.OnClickListener { handler(item) } }
        )
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView)
}

/**
 * Convenience DiffUtil builder for items with a stable id.
 */
inline fun <T : Any> idDiff(crossinline id: (T) -> Any): DiffUtil.ItemCallback<T> =
    object : DiffUtil.ItemCallback<T>() {
        override fun areItemsTheSame(oldItem: T, newItem: T): Boolean = id(oldItem) == id(newItem)
        override fun areContentsTheSame(oldItem: T, newItem: T): Boolean = oldItem == newItem
    }
