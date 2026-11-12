package com.cbnu.nabi.ui

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.cbnu.nabi.R
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

/** Colors. The first group are the Figma design tokens (index.css :root) — use these for new UI (NFR-05). */
object Palette {
    val page = Color.parseColor("#F8EEE6")
    val sand = Color.parseColor("#F7EBDD")
    val card = Color.parseColor("#FFFAF5")
    val ink = Color.parseColor("#4A3027")
    val muted = Color.parseColor("#75645B")
    val coral = Color.parseColor("#E99680")
    val lavender = Color.parseColor("#A991CF")
    val lavenderSoft = Color.parseColor("#EEE5F5")
    val lavenderDeep = Color.parseColor("#654C76")
    val line = Color.parseColor("#D9C3B4")

    val cat = Color.parseColor("#E8964A")
    val cream = Color.parseColor("#F8E6C8")
    val danger = Color.parseColor("#D9362B")
    val divider = line
}

/** Figma fonts: Jua for titles, Gowun Dodum for body text (also the theme default). */
object Fonts {
    private var titleFace: Typeface? = null
    private var bodyFace: Typeface? = null

    fun title(c: Context): Typeface = titleFace ?: c.resources.getFont(R.font.jua).also { titleFace = it }
    fun body(c: Context): Typeface = bodyFace ?: c.resources.getFont(R.font.gowun_dodum).also { bodyFace = it }
}

/** Rounded card background with the Figma hairline border. */
fun Activity.cardBg(
    fill: Int = Palette.card,
    radiusDp: Int = 18,
    stroke: Int? = Palette.line,
): GradientDrawable = rounded(fill, radiusDp).apply {
    if (stroke != null) setStroke(dp(1), stroke)
}

/** A tinted vector icon from res/drawable/ic_*.xml (generated from the Figma icons). */
fun Activity.icon(res: Int, color: Int, sizeDp: Int = 24): ImageView = ImageView(this).apply {
    setImageResource(res)
    imageTintList = ColorStateList.valueOf(color)
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
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
        this.text = text; textSize = 20f
        typeface = Fonts.title(activity)
        setTextColor(Palette.ink)
        setPadding(0, activity.dp(12), 0, 0)
    })

    fun text(text: String = "", size: Float = 14f, alpha: Float = 1f) = add(TextView(activity).apply {
        this.text = text; textSize = size; this.alpha = alpha
        typeface = Fonts.body(activity)
        setTextColor(Palette.ink)
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
