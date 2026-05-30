package isim.ia2y.myapplication.ui.state

import android.view.View
import android.widget.TextView
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import com.google.android.material.button.MaterialButton
import isim.ia2y.myapplication.R

/**
 * Binds a [UiState] to a set of containers (loading skeleton, empty state, error state, data list).
 * Each slot's visibility is toggled based on the current state. Caller is responsible for
 * populating the data slot's contents (adapter submit, etc.).
 */
class StateRenderer(
    private val loadingView: View?,
    private val emptyView: View?,
    private val errorView: View?,
    private val dataView: View?,
) {
    fun render(state: UiState<*>) {
        loadingView?.visibility = if (state is UiState.Loading) View.VISIBLE else View.GONE
        emptyView?.visibility = if (state is UiState.Empty) View.VISIBLE else View.GONE
        errorView?.visibility = if (state is UiState.Error) View.VISIBLE else View.GONE
        dataView?.visibility = if (state is UiState.Data<*>) View.VISIBLE else View.GONE
    }

    fun bindEmpty(
        titleRes: Int? = null,
        subtitleRes: Int? = null,
        ctaRes: Int? = null,
        onCta: (() -> Unit)? = null,
    ) {
        val v = emptyView ?: return
        titleRes?.let { v.findViewById<TextView?>(R.id.msEmptyTitle)?.setText(it) }
        subtitleRes?.let { v.findViewById<TextView?>(R.id.msEmptySubtitle)?.setText(it) }
        val cta = v.findViewById<MaterialButton?>(R.id.msEmptyCta)
        if (ctaRes != null && onCta != null) {
            cta?.setText(ctaRes)
            cta?.visibility = View.VISIBLE
            cta?.setOnClickListener { onCta() }
        } else {
            cta?.visibility = View.GONE
            cta?.setOnClickListener(null)
        }
    }

    fun bindError(
        @StringRes titleRes: Int? = null,
        @StringRes subtitleRes: Int? = null,
        onRetry: (() -> Unit)? = null,
    ) {
        val v = errorView ?: return
        titleRes?.let { v.findViewById<TextView?>(R.id.msErrorTitle)?.setText(it) }
        subtitleRes?.let { v.findViewById<TextView?>(R.id.msErrorSubtitle)?.setText(it) }
        val retry = v.findViewById<MaterialButton?>(R.id.msErrorRetry)
        retry?.setOnClickListener { onRetry?.invoke() }
    }

    companion object {
        fun of(
            root: View,
            @IdRes loadingId: Int,
            @IdRes emptyId: Int,
            @IdRes errorId: Int,
            @IdRes dataId: Int,
        ): StateRenderer = StateRenderer(
            loadingView = root.findViewById(loadingId),
            emptyView = root.findViewById(emptyId),
            errorView = root.findViewById(errorId),
            dataView = root.findViewById(dataId),
        )
    }
}
