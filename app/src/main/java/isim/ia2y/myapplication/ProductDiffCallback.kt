package isim.ia2y.myapplication

import androidx.recyclerview.widget.DiffUtil

class ProductDiffCallback : DiffUtil.ItemCallback<Product>() {
    override fun areItemsTheSame(oldItem: Product, newItem: Product): Boolean =
        oldItem.id == newItem.id

    override fun areContentsTheSame(oldItem: Product, newItem: Product): Boolean =
        oldItem == newItem
}
