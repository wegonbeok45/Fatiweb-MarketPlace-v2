package isim.ia2y.myapplication.ui.base

import android.view.View
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import isim.ia2y.myapplication.R

/**
 * Single-call binder for the Ms.Chip.Status pill family.
 * Use to keep status colour + label aligned with the design system rather than
 * hand-mapping in each adapter.
 */
object MsStatusPill {
    enum class Kind { Pending, Approved, Rejected, Draft, Archived, Info }

    fun bind(target: View?, kind: Kind, @StringRes labelRes: Int) {
        val tv = target as? TextView ?: return
        tv.text = tv.context.getString(labelRes)
        apply(tv, kind)
    }

    fun bind(target: View?, kind: Kind, label: CharSequence) {
        val tv = target as? TextView ?: return
        tv.text = label
        apply(tv, kind)
    }

    private fun apply(tv: TextView, kind: Kind) {
        val ctx = tv.context
        val (fg, bg) = when (kind) {
            Kind.Pending -> R.color.ms_status_pending_fg to R.drawable.ms_bg_status_pending
            Kind.Approved -> R.color.ms_status_approved_fg to R.drawable.ms_bg_status_approved
            Kind.Rejected -> R.color.ms_status_rejected_fg to R.drawable.ms_bg_status_rejected
            Kind.Draft -> R.color.ms_status_draft_fg to R.drawable.ms_bg_status_draft
            Kind.Archived -> R.color.ms_status_archived_fg to R.drawable.ms_bg_status_archived
            Kind.Info -> R.color.ms_status_info_fg to R.drawable.ms_bg_status_info
        }
        tv.setTextColor(ContextCompat.getColor(ctx, fg))
        tv.background = ContextCompat.getDrawable(ctx, bg)
    }
}
