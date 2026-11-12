package com.cbnu.nabi.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.cbnu.nabi.R

/**
 * "나비의 상점" (FR-06-02~05) following the Figma shop: banner, 상점 / 내 아이템 tabs, category
 * filters, 2-column product grid, purchase confirmation and equipping room items.
 * Coins and products are TEMPORARY local data ([ShopStore]) until the backend has a shop API.
 */
class ShopActivity : Activity() {

    private enum class Tab { SHOP, OWNED }
    private enum class OwnedFilter(val label: String) { ALL("전체"), EQUIPPED("장착 중"), STORED("보관 중") }

    private var tab = Tab.SHOP
    private var filter: ShopStore.Category? = null
    private var ownedFilter = OwnedFilter.ALL
    private lateinit var coinText: TextView
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Palette.page) }

        // header: back · title · coins
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(8))
        }
        header.addView(FrameLayout(this).apply {
            background = cardBg(Palette.card, 12, Color.parseColor("#E6D4C8"))
            addView(icon(R.drawable.ic_chevron_left, Palette.ink, 22), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            contentDescription = "홈으로 돌아가기"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(36), dp(36)))
        header.addView(label("나비의 상점", 22f, title = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10) })
        coinText = label("", 14f, bold = true)
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(10), 0)
            background = cardBg(Palette.card, 999, Color.parseColor("#DECCBE"))
            addView(coin(20))
            addView(coinText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) })
            contentDescription = "보유 코인"
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(33)))
        root.addView(header)

        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(24)) }
        root.addView(ScrollView(this).apply { addView(content) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        render()
    }

    // ---------------- page ----------------
    private fun render() {
        coinText.text = ShopStore.coins(this).toString()
        content.removeAllViews()
        content.addView(banner())
        // spec §8: make clear these are not real purchases
        content.addView(label("※ 코인과 상품은 서버 연동 전 체험용 임시 데이터예요.", 10.5f, Palette.muted).apply {
            setPadding(dp(4), dp(8), 0, 0)
        })
        content.addView(tabs(), lp(top = 12, height = 45))
        if (tab == Tab.SHOP) {
            content.addView(chips(listOf<Pair<String, ShopStore.Category?>>("전체" to null) +
                ShopStore.Category.values().map { it.label to it }, filter) { filter = it; render() }, lp(top = 10))
            val items = ShopStore.products.filter { filter == null || it.category == filter }
            content.addView(grid(items), lp(top = 10))
        } else {
            content.addView(decorateButton(), lp(top = 12, height = 48))
            content.addView(chips(OwnedFilter.values().map { it.label to it }, ownedFilter) { ownedFilter = it; render() }, lp(top = 10))
            content.addView(ownedList(), lp(top = 10))
        }
    }

    private fun lp(top: Int = 0, height: Int? = null) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, height?.let { dp(it) } ?: ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun coin(sizeDp: Int) = View(this).apply {
        background = rounded(Color.parseColor("#FFD66C"), 999).apply { setStroke(dp(1), Color.parseColor("#C98A28")) }
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun banner() = FrameLayout(this).apply {
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#EEE4F6"), Color.parseColor("#E5D6F1"))).apply {
            cornerRadius = dp(22).toFloat(); setStroke(dp(1), Color.parseColor("#D9C9E7"))
        }
        val texts = LinearLayout(this@ShopActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(20)) }
        texts.addView(label("나비의 방을", 13f, Palette.muted))
        texts.addView(label("더 포근하게 꾸며보세요!", 19f).apply { setPadding(0, dp(6), 0, 0) })
        addView(texts)
        addView(icon(R.drawable.ic_sparkle, Color.parseColor("#B9A2DA"), 22), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.END or Gravity.TOP).apply { setMargins(0, dp(18), dp(26), 0) })
        addView(icon(R.drawable.ic_sparkle, Color.parseColor("#C7B4E2"), 14), FrameLayout.LayoutParams(dp(14), dp(14), Gravity.END or Gravity.BOTTOM).apply { setMargins(0, 0, dp(60), dp(18)) })
        minimumHeight = dp(104)
    }

    private fun tabs() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(4), dp(4), dp(4), dp(4))
        background = cardBg(Color.parseColor("#FFFAF6"), 16, Color.parseColor("#E4D4CA"))
        for ((t, name) in listOf(Tab.SHOP to "상점", Tab.OWNED to "내 아이템")) {
            addView(label(name, 12.5f, if (t == tab) Palette.ink else Palette.muted, bold = true).apply {
                gravity = Gravity.CENTER
                if (t == tab) background = rounded(Color.parseColor("#EAE0F4"), 12)
                setOnClickListener { tab = t; render() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (t == Tab.OWNED) leftMargin = dp(4) })
        }
    }

    private fun <T> chips(items: List<Pair<String, T>>, selected: T, onPick: (T) -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        items.forEachIndexed { i, (name, value) ->
            val on = value == selected
            addView(label(name, 11.5f, if (on) Color.WHITE else Palette.ink, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(13), 0, dp(13), 0)
                background = if (on) rounded(Color.parseColor("#A78CCA"), 999) else cardBg(Color.parseColor("#FFFAF6"), 999, Color.parseColor("#E3D2C6"))
                setOnClickListener { onPick(value) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)).apply { if (i > 0) leftMargin = dp(6) })
        }
    }

    // ---------------- 상점 ----------------
    private fun grid(items: List<ShopStore.Product>): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        items.chunked(2).forEachIndexed { r, pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { i, p ->
                row.addView(productCard(p), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i == 1) leftMargin = dp(10) })
            }
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = dp(10) })
            col.addView(row, lp(top = if (r > 0) 10 else 0))
        }
        if (items.isEmpty()) col.addView(label("이 분류에는 상품이 없어요.", 12f, Palette.muted))
        return col
    }

    private fun productCard(p: ShopStore.Product): View {
        val owned = ShopStore.isOwned(this, p.id)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(9), dp(9), dp(9), dp(11))
            background = cardBg(Color.parseColor("#FFFAF6"), 19, Color.parseColor("#E3D2C6"))
            elevation = dp(1).toFloat()
            addView(image(p.image), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(115)))
            addView(label(p.name, 13f).apply { setPadding(dp(2), dp(8), 0, 0) })
            if (owned) {
                addView(label("보유 중", 10f, Color.parseColor("#73578E"), bold = true).apply {
                    setPadding(dp(7), dp(3), dp(7), dp(3)); background = rounded(Color.parseColor("#EEE4F4"), 8)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            } else {
                addView(LinearLayout(this@ShopActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(coin(18))
                    addView(label(p.price.toString(), 12f, bold = true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(5) })
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            }
            contentDescription = p.name + if (owned) ", 보유 중" else ", ${p.price}코인"
            setOnClickListener { if (owned) { tab = Tab.OWNED; render() } else openPurchase(p) }
        }
    }

    private fun image(res: Int) = FrameLayout(this).apply {
        background = rounded(Color.parseColor("#F8F1EC"), 14)
        addView(ImageView(this@ShopActivity).apply {
            setImageResource(res)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    // ---------------- 구매 (FR-06-03/04) ----------------
    private fun openPurchase(p: ShopStore.Product) {
        val dialog = Dialog(this)
        val box = Modals.box(this).apply { gravity = Gravity.CENTER_HORIZONTAL }

        fun fill(result: ShopStore.Result?) {
            box.removeAllViews()
            box.addView(FrameLayout(this).apply {
                background = rounded(Color.parseColor("#EDE3F4"), 19)
                addView(icon(R.drawable.ic_shopping_bag, Color.parseColor("#8264A1"), 27), FrameLayout.LayoutParams(dp(27), dp(27), Gravity.CENTER))
            }, LinearLayout.LayoutParams(dp(55), dp(55)))
            val title = when (result) {
                null -> "${p.name}을"
                ShopStore.Result.SUCCESS -> "구매를 완료했어요"
                ShopStore.Result.INSUFFICIENT -> "코인이 부족해요"
                ShopStore.Result.OWNED -> "이미 보유한 상품이에요"
                ShopStore.Result.ERROR -> "구매하지 못했어요"
            }
            box.addView(label(title, 19f, title = true).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0) })
            val msg = when (result) {
                null -> "${p.price}코인으로 구매하시겠어요?"
                ShopStore.Result.SUCCESS -> "${p.name}이(가) 내 아이템에 추가되었어요."
                ShopStore.Result.INSUFFICIENT -> "수면 기록과 활동을 통해 코인을 더 모아보세요."
                ShopStore.Result.OWNED -> "비소모성 상품은 한 번만 구매할 수 있어요."
                ShopStore.Result.ERROR -> "잠시 후 다시 시도해 주세요."
            }
            box.addView(label(msg, 12f, Palette.muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) })

            if (result == null) {
                val coins = ShopStore.coins(this)
                val after = coins - p.price
                box.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    background = rounded(Color.parseColor("#F7F0F7"), 15)
                    addView(label("보유 코인", 10.5f, Palette.muted))
                    addView(label(coins.toString(), 15f, Palette.lavenderDeep, bold = true).apply { setPadding(dp(9), 0, dp(6), 0) })
                    addView(icon(R.drawable.ic_chevron_right, Palette.muted, 15))
                    addView(label(maxOf(0, after).toString(), 15f, if (after < 0) Color.parseColor("#B45656") else Palette.lavenderDeep, bold = true)
                        .apply { setPadding(dp(6), 0, 0, 0) })
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(14) })
                box.addView(Modals.buttons(this, "취소", { dialog.dismiss() }, "구매하기") {
                    val r = ShopStore.purchase(this, p)
                    if (r == ShopStore.Result.SUCCESS) render() // FR-06-04: balance + owned state
                    fill(r)
                }, lp(top = 16))
            } else if (result == ShopStore.Result.SUCCESS) {
                box.addView(Modals.buttons(this, "닫기", { dialog.dismiss() }, "내 아이템 보기") {
                    dialog.dismiss(); tab = Tab.OWNED; ownedFilter = OwnedFilter.ALL; render()
                }, lp(top = 16))
            } else {
                box.addView(Modals.buttons(this, null, {}, "닫기") { dialog.dismiss() }, lp(top = 16))
            }
        }
        fill(null)
        Modals.show(this, dialog, box)
    }

    // ---------------- 내 아이템 (FR-06-05) ----------------
    /** "방 꾸미기": back to the home room in arrange mode (move / resize the equipped furniture). */
    private fun decorateButton() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = cardBg(Palette.lavenderSoft, 16, Color.parseColor("#DCCDEB"))
        addView(icon(R.drawable.ic_sparkle, Color.parseColor("#71568D"), 18))
        addView(label("방 꾸미기", 14f, Color.parseColor("#71568D"), bold = true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(7) })
        contentDescription = "방 꾸미기. 장착한 가구의 위치와 크기를 바꿔요"
        setOnClickListener {
            startActivity(android.content.Intent(this@ShopActivity, MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_DECORATE, true))
            finish()
        }
    }

    private fun ownedList(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val items = listOf(ShopStore.defaultRug) + ShopStore.products.filter { ShopStore.isOwned(this, it.id) }
        val shown = items.filter {
            val equipped = it.slot != null && ShopStore.equipped(this, it.slot) == it.id
            when (ownedFilter) {
                OwnedFilter.ALL -> true
                OwnedFilter.EQUIPPED -> equipped
                OwnedFilter.STORED -> it.slot != null && !equipped
            }
        }
        if (shown.isEmpty()) col.addView(label("해당하는 아이템이 없어요.", 12f, Palette.muted).apply { setPadding(dp(4), dp(8), 0, 0) })
        shown.forEachIndexed { i, p -> col.addView(ownedCard(p), lp(top = if (i > 0) 9 else 0)) }
        return col
    }

    private fun ownedCard(p: ShopStore.Product): View {
        val equipped = p.slot != null && ShopStore.equipped(this, p.slot) == p.id
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = cardBg(Color.parseColor("#FFFAF6"), 18, Color.parseColor("#E2D1C5"))
            elevation = dp(1).toFloat()
            addView(image(p.image).apply { background = rounded(Color.parseColor("#F7F0EB"), 13) }, LinearLayout.LayoutParams(dp(68), dp(65)))
            val texts = LinearLayout(this@ShopActivity).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(label(p.name, 13.5f, bold = true))
            texts.addView(label(when {
                p.id == ShopStore.DEFAULT_RUG -> "기본 제공"
                p.slot == null -> "보유 중"
                equipped -> "장착 중"
                else -> "보관 중"
            }, 10.5f, Palette.muted).apply { setPadding(0, dp(4), 0, 0) })
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10) })
            when {
                p.slot != null && equipped -> addView(label("장착 중", 10f, Color.parseColor("#765895"), bold = true).apply {
                    setPadding(dp(8), dp(5), dp(8), dp(5)); background = rounded(Color.parseColor("#EEE4F5"), 9)
                })
                p.slot != null -> addView(label("장착하기", 11f, Color.WHITE, bold = true).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(12), dp(7), dp(12), dp(7))
                    background = rounded(Color.parseColor("#A78CCA"), 11)
                    setOnClickListener { ShopStore.equip(this@ShopActivity, p); render() }
                })
            }
        }
    }
}
