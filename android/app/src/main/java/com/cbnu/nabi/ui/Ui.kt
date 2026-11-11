package com.cbnu.nabi.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.cbnu.nabi.data.ApiException
import java.util.concurrent.Executors

/* Small helpers so screens can be built in code without extra libraries (same approach as Mochi). */

private val pool = Executors.newCachedThreadPool()

/** Run blocking [work] (network) off the main thread, then deliver the result on the main thread. */
fun <T> Activity.bg(work: () -> T, done: (Result<T>) -> Unit) {
    pool.execute {
        val r = runCatching(work)
        runOnUiThread { if (!isFinishing && !isDestroyed) done(r) }
    }
}

fun Throwable.friendly(): String =
    if (this is ApiException) message ?: code else "문제가 생겼어요 (${javaClass.simpleName})"

fun Activity.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

object Palette {
    val cat = Color.parseColor("#E8964A")
    val cream = Color.parseColor("#F8E6C8")
    val ink = Color.parseColor("#2B2840")
    val danger = Color.parseColor("#D9362B")
    val card = Color.argb(28, 232, 150, 74)
    val divider = Color.argb(48, 128, 128, 128)
}

/** Remove a view from its current parent so it can be placed in a new layout (FR-01-04). */
fun View.detach() {
    (parent as? ViewGroup)?.removeView(this)
}

/** Standard page column with the screen padding used by every panel. */
fun Activity.page(): Column = Column(this, 12).apply {
    root.setPadding(dp(24), dp(24), dp(24), dp(32))
}

fun Activity.scrollPage(page: Column): ScrollView = ScrollView(this).apply { addView(page.root) }

fun Activity.rounded(color: Int, radiusDp: Int = 14): GradientDrawable =
    GradientDrawable().apply { setColor(color); cornerRadius = dp(radiusDp).toFloat() }

/** Vertical container with a `+view` style add that applies a top margin. */
class Column(private val activity: Activity, gapDp: Int = 10) {
    val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val gap = activity.dp(gapDp)

    fun <V : View> add(v: V, matchWidth: Boolean = true): V {
        root.addView(v, LinearLayout.LayoutParams(
            if (matchWidth) LinearLayout.LayoutParams.MATCH_PARENT else LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = gap })
        return v
    }

    fun heading(text: String) = add(TextView(activity).apply {
        this.text = text; textSize = 18f; setTypeface(typeface, Typeface.BOLD)
        setPadding(0, activity.dp(12), 0, 0)
    })

    fun text(text: String = "", size: Float = 14f, alpha: Float = 1f) = add(TextView(activity).apply {
        this.text = text; textSize = size; this.alpha = alpha
    })

    /** A row of equal-width buttons. */
    fun buttonRow(vararg buttons: Pair<String, () -> Unit>): LinearLayout {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, onClick) in buttons) {
            row.addView(Button(activity).apply {
                text = label
                setOnClickListener { onClick() }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        return add(row)
    }
}
