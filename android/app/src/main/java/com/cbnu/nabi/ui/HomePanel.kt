package com.cbnu.nabi.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.cbnu.nabi.R
import com.cbnu.nabi.data.CatAction
import com.cbnu.nabi.data.Labels
import com.cbnu.nabi.data.Mission
import com.cbnu.nabi.data.UserState
import com.cbnu.nabi.pet.PetService
import java.time.LocalDate
import java.util.Calendar

/**
 * Center / 홈 (FR-04, FR-05, FR-06-01), following the Figma "나비의 공간" screen:
 * header (title, coins, missions, shop), the sleep analysis card, and the room with day/night
 * background, the rug and the cat. The cat itself is the existing CatView ([RoomCat]); whether it
 * is in the room or out on the home screen is decided by [CatPresence].
 */
class HomePanel(private val activity: MainActivity) {

    private val root = FrameLayout(activity).apply { clipChildren = false }
    private val dayBg = bgImage(R.drawable.room_day)
    private val nightBg = bgImage(R.drawable.room_night)
    private val rug = roomItem(R.drawable.rug_ivory)
    private val tower = roomItem(R.drawable.shop_wood_cat_tower).apply { visibility = View.GONE }
    private val plant = roomItem(R.drawable.shop_green_plant).apply { visibility = View.GONE }
    private val hint = activity.label("", 10f, Color.argb(190, 74, 48, 39), bold = true).apply {
        setPadding(activity.dp(9), activity.dp(3), activity.dp(9), activity.dp(3))
        background = activity.rounded(Color.argb(153, 255, 249, 243), 999)
    }
    /** Fixed note beside the 부르기 button while Nabi is out on the home screen. */
    private val outHint = activity.label("나비는 지금 밖에서 놀고 있어요. 불러 보세요", 11f, Color.argb(210, 74, 48, 39), bold = true).apply {
        setPadding(activity.dp(11), activity.dp(6), activity.dp(11), activity.dp(6))
        background = activity.cardBg(Color.argb(225, 255, 249, 243), 999, Color.argb(150, 185, 155, 137))
        visibility = View.GONE
    }
    private val floor = FrameLayout(activity).apply { clipChildren = false; clipToPadding = false }
    private val roomCat = RoomCat(activity, floor)

    private val coinText = activity.label("", 14f, bold = true)
    private val missionText = activity.label("미션", 12f, bold = true)

    // analysis card
    private val analysisBody = FrameLayout(activity)
    private var lastState: UserState? = null
    private var analysisError = false
    private var missions: List<Mission> = emptyList()
    private var missionSheet: BottomSheet? = null

    private lateinit var top: LinearLayout
    private lateinit var fab: View
    private var decorator: RoomDecorator? = null
    private val itemViews: Map<ShopStore.Slot, ImageView>
        get() = mapOf(ShopStore.Slot.RUG to rug, ShopStore.Slot.TOWER to tower, ShopStore.Slot.PLANT to plant)

    private val clock = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { updateScene(); clock.postDelayed(this, 60_000) }
    }

    val view: View get() = root

    init {
        root.addView(dayBg, matchParent())
        root.addView(nightBg, matchParent())
        root.addView(rug, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(plant, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(tower, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(hint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { gravity = Gravity.TOP or Gravity.START })
        hint.alpha = 0f
        root.addView(floor, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0))

        top = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        top.addView(buildHeader())
        top.addView(buildAnalysisCard(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(activity.dp(16), activity.dp(4), activity.dp(16), 0) })
        root.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(outHint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            setMargins(activity.dp(16), 0, activity.dp(12 + 54 + 8), activity.dp(92 + 15))
        })
        fab = buildCallFab()
        root.addView(fab, FrameLayout.LayoutParams(activity.dp(54), activity.dp(54)).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            setMargins(0, 0, activity.dp(12), activity.dp(92))
        })

        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) layoutRoom(r - l, b - t)
        }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { clock.removeCallbacks(tick); tick.run() }
            override fun onViewDetachedFromWindow(v: View) { clock.removeCallbacks(tick); clock.removeCallbacks(touchHintTask) }
        })
        CatPresence.onChanged = { updateCat() }
        CatPresence.roomReact = { action, line -> roomCat.react(action, line) }

        coinText.text = ShopStore.coins(activity).toString()
        showAnalysis(null, null)
        updateScene()
    }

    // ---------------- header ----------------
    private fun buildHeader(): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(activity.dp(16), activity.dp(24), activity.dp(16), activity.dp(8))
        }
        val titles = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val titleRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(activity.label("나비의 공간", 24f, title = true))
        titleRow.addView(activity.icon(R.drawable.ic_paw, Color.parseColor("#9B604D"), 19).apply {
            (layoutParams as LinearLayout.LayoutParams).leftMargin = activity.dp(6)
        })
        titles.addView(titleRow)
        titles.addView(activity.label("오늘도 나비와\n편안한 하루를 보내요.", 11f, Palette.ink, bold = true).apply {
            setPadding(0, activity.dp(6), 0, 0)
            setLineSpacing(activity.dp(2).toFloat(), 1f)
        })
        row.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val actions = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        // coins (temporary local data — see ShopStore)
        val coin = pill().apply {
            addView(coinBadge())
            addView(coinText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { leftMargin = activity.dp(6) })
            contentDescription = "보유 코인"
        }
        actions.addView(coin)
        // missions (FR-06-01)
        val missionBtn = pill().apply {
            addView(activity.icon(R.drawable.ic_check, Palette.lavenderDeep, 14))
            addView(missionText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { leftMargin = activity.dp(4) })
            contentDescription = "오늘의 미션"
            setOnClickListener { openMissions() }
        }
        actions.addView(missionBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, activity.dp(32))
            .apply { leftMargin = activity.dp(6) })
        // shop
        val store = FrameLayout(activity).apply {
            background = glass(12)
            elevation = activity.dp(2).toFloat()
            addView(activity.icon(R.drawable.ic_store, Color.parseColor("#9B604D"), 19),
                FrameLayout.LayoutParams(activity.dp(19), activity.dp(19), Gravity.CENTER))
            contentDescription = "상점 열기"
            setOnClickListener { activity.startActivity(Intent(activity, ShopActivity::class.java)) }
        }
        actions.addView(store, LinearLayout.LayoutParams(activity.dp(32), activity.dp(32)).apply { leftMargin = activity.dp(6) })
        row.addView(actions)
        return row
    }

    private fun pill() = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(activity.dp(5), 0, activity.dp(10), 0)
        minimumHeight = activity.dp(32)
        background = glass(999)
        elevation = activity.dp(2).toFloat()
    }

    private fun coinBadge() = FrameLayout(activity).apply {
        background = activity.rounded(Color.parseColor("#F6C85F"), 999).apply { setStroke(activity.dp(1), Color.parseColor("#E4A93A")) }
        addView(activity.icon(R.drawable.ic_paw, Color.parseColor("#B9782A"), 12),
            FrameLayout.LayoutParams(activity.dp(12), activity.dp(12), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(activity.dp(22), activity.dp(22))
    }

    private fun glass(radiusDp: Int) =
        activity.cardBg(Color.argb(230, 255, 249, 243), radiusDp, Color.argb(191, 185, 155, 137))

    // ---------------- analysis card (FR-04) ----------------
    private fun buildAnalysisCard(): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = activity.cardBg(Color.argb(240, 255, 249, 243), 20, Color.argb(178, 185, 155, 137))
            elevation = activity.dp(4).toFloat()
            setOnClickListener { onAnalysisTapped() }
            contentDescription = "나의 수면 분석. 눌러서 수면 일지 보기"
        }
        val title = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(14), 0, activity.dp(11), 0)
        }
        title.addView(activity.icon(R.drawable.ic_chart, Palette.lavender, 19))
        title.addView(activity.label("나의 수면 분석", 16f, title = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = activity.dp(7) })
        val record = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(10), 0, activity.dp(10), 0)
            background = activity.rounded(Palette.lavenderSoft, 11)
            addView(activity.icon(R.drawable.ic_edit, Color.parseColor("#71568D"), 13))
            addView(activity.label("기록하기", 11f, Color.parseColor("#71568D"), bold = true),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { leftMargin = activity.dp(4) })
            setOnClickListener { activity.openSleepRecord() }
        }
        title.addView(record, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, activity.dp(29)))
        card.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(41)))
        card.addView(View(activity).apply { setBackgroundColor(Color.argb(51, 185, 155, 137)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(1)))
        card.addView(analysisBody, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(92)))
        return card
    }

    /**
     * FR-04-02/04: loading, error, collecting (baseline not ready) and ready are shown differently.
     * New users never get a made-up result (NFR-04).
     */
    private fun showAnalysis(state: UserState?, error: String?) {
        analysisBody.removeAllViews()
        val today = todayEntry()
        if (today != null) { showSummary(today, state); analysisError = false; return }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(14), 0, activity.dp(14), 0)
        }
        val iconBox = FrameLayout(activity).apply {
            background = activity.rounded(Palette.lavenderSoft, 14)
            addView(activity.icon(R.drawable.ic_moon, Color.parseColor("#8F78B5"), 25),
                FrameLayout.LayoutParams(activity.dp(25), activity.dp(25), Gravity.CENTER))
        }
        row.addView(iconBox, LinearLayout.LayoutParams(activity.dp(40), activity.dp(40)))
        val texts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val (headline, detail) = when {
            error != null -> "분석 정보를 불러오지 못했어요" to "$error · 눌러서 다시 시도"
            state == null -> "불러오는 중…" to "잠시만 기다려 주세요."
            state.baselineStatus != "ready" ->
                "데이터 수집 중" to "기록을 남기면 수면 요약을 확인할 수 있어요.\n유효 기록 ${state.baselineValidDays}일"
            else -> "수면 상태: ${Labels.sleep(state.sleepState)}" to
                "위험도 ${Labels.risk(state.riskLevel)} · 생활 습관 ${Labels.behavior(state.behaviorState)}"
        }
        texts.addView(activity.label(headline, 15f, bold = true))
        texts.addView(activity.label(detail, 11f, Palette.muted).apply {
            setPadding(0, activity.dp(5), 0, 0)
            setLineSpacing(activity.dp(2).toFloat(), 1f)
            maxLines = 3; ellipsize = TextUtils.TruncateAt.END
        })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = activity.dp(10) })
        row.addView(activity.icon(R.drawable.ic_chevron_right, Palette.muted, 18))
        analysisBody.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        analysisError = error != null
    }

    private fun todayEntry(): DiaryEntry? =
        SleepDiaryStore(activity, activity.prefs.email).get(LocalDate.now())

    /** Figma summary once today's diary exists: total sleep on the left, efficiency + satisfaction on the right. */
    private fun showSummary(e: DiaryEntry, state: UserState?) {
        // Both columns start on the same top line, so "총 수면시간" sits level with "수면 효율".
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, activity.dp(14), 0, 0)
        }
        val left = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(14), 0, activity.dp(8), 0)
        }
        val head = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(activity.icon(R.drawable.ic_moon, Color.parseColor("#8F78B5"), 20))
        head.addView(activity.label("총 수면시간", 11f, Palette.muted, bold = true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = activity.dp(5) })
        left.addView(head)
        left.addView(activity.label(formatDuration(e.totalSleepMin), 21f, title = true).apply { setPadding(0, activity.dp(5), 0, 0) })
        // FR-04-02: the personal baseline is still being built — say so next to the numbers.
        if (state != null && state.baselineStatus != "ready") {
            left.addView(activity.label("기준선 수집 중 · 유효 ${state.baselineValidDays}일", 9.5f, Palette.muted).apply {
                setPadding(0, activity.dp(5), 0, 0)
            })
        }
        row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.48f))
        row.addView(View(activity).apply { setBackgroundColor(Color.argb(51, 185, 155, 137)) },
            LinearLayout.LayoutParams(activity.dp(1), activity.dp(58)))
        val right = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(14), 0, activity.dp(14), 0)
        }
        right.addView(metric("수면 효율", "${e.efficiencyPct}%"))
        right.addView(metric("수면 만족도", SleepRecordSheet.SATISFACTIONS[e.satisfaction - 1]),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dp(10) })
        row.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.52f))
        analysisBody.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun metric(name: String, value: String) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(activity.label(name, 11f, Palette.muted, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(activity.label(value, 14f, bold = true))
    }

    /** Tapping the analysis card opens the 수면 일지 tab (user decision); on an error it retries instead. */
    private fun onAnalysisTapped() {
        if (analysisError) refresh() else activity.showDiary()
    }

    // ---------------- missions (FR-06-01, existing feature) ----------------
    private fun openMissions() {
        val sheet = BottomSheet(activity, "오늘의 미션", "미션을 해내면 나비가 함께 기뻐해요.")
        missionSheet = sheet
        renderMissions(sheet.body)
        sheet.show()
    }

    private fun renderMissions(body: Column) {
        body.root.removeAllViews()
        if (missions.isEmpty()) {
            body.text("지금은 진행 중인 미션이 없어요", 14f, 0.7f)
            return
        }
        for (m in missions) {
            val card = Column(activity, 6)
            card.root.setPadding(activity.dp(16), activity.dp(8), activity.dp(16), activity.dp(14))
            card.root.background = activity.cardBg(radiusDp = 18)
            card.add(activity.label(m.title, 16f, title = true))
            card.text(m.description, 14f)
            card.text("보상 EXP ${m.rewardExp} · 기한 ${m.deadline.take(16).replace('T', ' ')}", 12f, 0.7f)
            val btn = card.add(Button(activity).apply { text = "완료했어요" }, matchWidth = false)
            btn.setOnClickListener {
                btn.isEnabled = false
                activity.bg({ activity.api.completeMission(m.missionId) }) { r ->
                    btn.isEnabled = true
                    r.onSuccess { res ->
                        Toast.makeText(activity, res.message, Toast.LENGTH_LONG).show()
                        catReact(res.action, res.message)
                        refresh()
                    }.onFailure {
                        Toast.makeText(activity, it.friendly(), Toast.LENGTH_LONG).show()
                        catReact(CatAction.COMFORT, "괜찮아, 기록하고 다시 해보자냥")
                    }
                }
            }
            body.add(card.root)
        }
    }

    /** Let whichever cat is visible react (room cat if it is here, otherwise the overlay). */
    private fun catReact(action: CatAction, line: String?) {
        CatPresence.react(activity, action, line)
    }

    // ---------------- room & cat (FR-05) ----------------
    private fun roomItem(res: Int) = ImageView(activity).apply {
        setImageResource(res)
        scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun bgImage(res: Int) = ImageView(activity).apply {
        setImageResource(res)
        scaleType = ImageView.ScaleType.CENTER_CROP
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun matchParent() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    /** Day 06:00–20:00, otherwise night (Figma). */
    private fun updateScene() {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val day = h in 6..19
        dayBg.animate().alpha(if (day) 1f else 0f).setDuration(500).start()
        nightBg.animate().alpha(if (day) 0f else 1f).setDuration(500).start()
    }

    /**
     * Positions from the Figma CSS: rug 78dp above the bottom, at most 80% / 344dp wide;
     * cat tower bottom-right (34vw, 116–145dp), plant bottom-left (23vw, 72–98dp).
     */
    private fun layoutRoom(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        // Furniture lives on the drawn floor of the background image, so it keeps the same spot and
        // proportions on any screen shape. Saved arrangement (방 꾸미기) first, else the Figma default.
        for ((slot, v) in itemViews) {
            val p = ShopStore.placement(activity, slot) ?: DEFAULT_PLACEMENTS.getValue(slot)
            placeItem(v, imageToScreen(p, w, h), w, h)
        }
        // The cat's "screen": a band of floor around the DEFAULT rug spot (not the user's rug, so the
        // cat's area doesn't jump around). CatView keeps its feet between 28% of this height from the
        // top and 28dp above the bottom.
        val rugSpot = imageToScreen(DEFAULT_PLACEMENTS.getValue(ShopStore.Slot.RUG), w, h)
        val rugW = (rugSpot.width * w).toInt()
        val rugBottom = (rugSpot.bottom * h).toInt()
        floor.layoutParams = (floor.layoutParams as FrameLayout.LayoutParams).apply {
            height = rugW / 2 + activity.dp(70)
            gravity = Gravity.BOTTOM
            bottomMargin = (h - rugBottom - activity.dp(8)).coerceAtLeast(0)
        }
        root.requestLayout()
        floor.post { roomCat.onFloorResized() }
    }

    /** Where the room background is drawn (CENTER_CROP of the 900×1600 room image) in this view. */
    private fun imageFrame(w: Int, h: Int): android.graphics.RectF {
        val d = dayBg.drawable
        val iw = (d?.intrinsicWidth ?: 900).toFloat(); val ih = (d?.intrinsicHeight ?: 1600).toFloat()
        val scale = maxOf(w / iw, h / ih)
        val dw = iw * scale; val dh = ih * scale
        val left = (w - dw) / 2f; val top = (h - dh) / 2f
        return android.graphics.RectF(left, top, left + dw, top + dh)
    }

    /** Background-image fractions → view fractions (what [placeItem] and the decorator use). */
    private fun imageToScreen(p: ShopStore.Placement, w: Int, h: Int): ShopStore.Placement {
        val f = imageFrame(w, h)
        return ShopStore.Placement(
            (f.left + p.cx * f.width()) / w,
            (f.top + p.bottom * f.height()) / h,
            p.width * f.width() / w,
        )
    }

    private fun screenToImage(p: ShopStore.Placement, w: Int, h: Int): ShopStore.Placement {
        val f = imageFrame(w, h)
        return ShopStore.Placement(
            (p.cx * w - f.left) / f.width(),
            (p.bottom * h - f.top) / f.height(),
            p.width * w / f.width(),
        )
    }

    /** Lay an item out from a room-fraction [ShopStore.Placement] (bottom-centre anchored). */
    private fun placeItem(v: ImageView, p: ShopStore.Placement, w: Int, h: Int) {
        val itemW = (p.width * w).toInt()
        val itemH = (itemW * aspect(v)).toInt()
        v.layoutParams = (v.layoutParams as FrameLayout.LayoutParams).apply {
            width = itemW; height = itemH
            gravity = Gravity.TOP or Gravity.START
            leftMargin = (p.cx * w).toInt() - itemW / 2
            topMargin = (p.bottom * h).toInt() - itemH
            rightMargin = 0; bottomMargin = 0
        }
    }

    /**
     * Phone: the floating tab bar covers the bottom ~80dp, so the 부르기 button sits above it (Figma).
     * Tablet (3 panes, no tab bar): bring the button and its note down near the bottom edge.
     */
    fun setTabBarShown(shown: Boolean) {
        val fabBottom = activity.dp(if (shown) 92 else 24)
        (fab.layoutParams as FrameLayout.LayoutParams).bottomMargin = fabBottom
        (outHint.layoutParams as FrameLayout.LayoutParams).bottomMargin = fabBottom + activity.dp(15)
        fab.requestLayout(); outHint.requestLayout()
    }

    // ---------------- 방 꾸미기 mode ----------------
    /** Opened from 상점 › 내 아이템 › 방 꾸미기. Hides everything but the room while arranging. */
    fun enterDecorate() {
        if (decorator != null) return
        if (root.width == 0) { root.post { enterDecorate() }; return }
        top.visibility = View.GONE
        fab.visibility = View.GONE
        hint.visibility = View.GONE
        outHint.visibility = View.GONE
        clock.removeCallbacks(touchHintTask)
        roomCat.hide()
        activity.setTabBarVisible(false)
        // Show what was just equipped in the shop first; start arranging once that is laid out.
        updateFurniture()
        root.post { startDecorator() }
    }

    private fun startDecorator() {
        if (decorator != null) return
        val d = RoomDecorator(activity, root, itemViews.filterValues { it.visibility == View.VISIBLE }, ::aspect,
            apply = { slot, p -> placeItem(itemViews.getValue(slot), p, root.width, root.height) },
            onFinish = { saved, stored ->
                if (saved != null) {
                    val inImage = saved.mapValues { (_, p) -> screenToImage(p, root.width, root.height) }
                    val ok = ShopStore.savePlacements(activity, inImage) &&
                        stored.all { ShopStore.unequip(activity, it) }
                    if (!ok) Toast.makeText(activity, "배치를 저장하지 못했어요", Toast.LENGTH_SHORT).show()
                }
                exitDecorate()
            })
        decorator = d
        root.addView(d, matchParent())
    }

    private fun exitDecorate() {
        decorator?.let { root.removeView(it) }
        decorator = null
        itemViews.values.forEach { it.scaleX = 1f; it.scaleY = 1f; it.translationY = 0f }
        top.visibility = View.VISIBLE
        fab.visibility = View.VISIBLE
        hint.visibility = View.VISIBLE
        activity.setTabBarVisible(true)
        updateFurniture() // saved state: equipped items + placements (a cancel drops unsaved changes)
        updateCat()
    }

    /** System back while decorating: leave without saving. */
    fun handleBack(): Boolean {
        val d = decorator ?: return false
        d.cancel() // nothing saved
        // back to where 방 꾸미기 was opened from: 상점 › 내 아이템
        activity.startActivity(android.content.Intent(activity, ShopActivity::class.java)
            .putExtra(ShopActivity.EXTRA_OPEN_OWNED, true))
        return true
    }

    /** height / width of the image currently shown (falls back to 1:1). */
    private fun aspect(v: ImageView): Float {
        val d = v.drawable ?: return 1f
        return if (d.intrinsicWidth > 0) d.intrinsicHeight.toFloat() / d.intrinsicWidth else 1f
    }

    /** FR-06-05: show the rug / cat tower / plant equipped in the shop ([ShopStore], temporary data). */
    private fun updateFurniture() {
        fun show(v: ImageView, slot: ShopStore.Slot) {
            val item = ShopStore.equipped(activity, slot)?.let(ShopStore::find)
            if (item == null) { v.visibility = View.GONE; return }
            v.setImageResource(item.image)
            v.visibility = View.VISIBLE
        }
        show(rug, ShopStore.Slot.RUG)
        show(tower, ShopStore.Slot.TOWER)
        show(plant, ShopStore.Slot.PLANT)
        if (root.width > 0) layoutRoom(root.width, root.height)
    }

    /** Show or remove the room cat depending on [CatPresence]. It only comes in when called. */
    private fun updateCat() {
        if (!root.isAttachedToWindow || decorator != null) return
        val wasShown = roomCat.isShown
        if (CatPresence.showInRoom) roomCat.show() else roomCat.hide()
        outHint.visibility = if (CatPresence.showInRoom) View.GONE else View.VISIBLE
        if (!CatPresence.showInRoom) { clock.removeCallbacks(touchHintTask); hint.animate().cancel(); hint.alpha = 0f }
        else if (!wasShown) scheduleTouchHint(1_200) // the cat just came in
    }

    // ---------------- "나비를 터치해 보세요" (now and then, above the cat) ----------------
    private var touchHintsLeft = 0
    private val touchHintTask = Runnable { showTouchHint() }

    /** At most twice per visit to home: shortly after arriving, and once more a little later. */
    private fun startTouchHints() {
        touchHintsLeft = 2
        scheduleTouchHint(1_800)
    }

    private fun scheduleTouchHint(delayMs: Long) {
        if (touchHintsLeft <= 0) return
        clock.removeCallbacks(touchHintTask)
        clock.postDelayed(touchHintTask, delayMs)
    }

    private fun showTouchHint() {
        if (decorator != null || !CatPresence.showInRoom || !root.isAttachedToWindow || touchHintsLeft <= 0) return
        val (ax, ay) = roomCat.anchor() ?: return
        touchHintsLeft--
        hint.text = "나비를 터치해 보세요"
        hint.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val x = (floor.left + ax - hint.measuredWidth / 2f).toInt().coerceIn(activity.dp(8), root.width - hint.measuredWidth - activity.dp(8))
        val y = (floor.top + ay - hint.measuredHeight - activity.dp(10)).toInt().coerceAtLeast(activity.dp(8))
        hint.layoutParams = (hint.layoutParams as FrameLayout.LayoutParams).apply { leftMargin = x; topMargin = y }
        hint.translationY = activity.dp(4).toFloat()
        hint.animate().alpha(1f).translationY(0f).setDuration(250).withEndAction {
            hint.animate().alpha(0f).setStartDelay(2_600).setDuration(400).withEndAction { hint.animate().startDelay = 0 }.start()
        }.start()
        if (touchHintsLeft > 0) scheduleTouchHint((25_000..45_000).random().toLong())
    }

    /** Bottom-right "나비 부르기" button (user decision): calls the overlay cat in, or the room cat over. */
    private fun buildCallFab(): View = FrameLayout(activity).apply {
        background = activity.rounded(Color.parseColor("#A78CCA"), 999).apply { setStroke(activity.dp(1), Color.argb(178, 255, 255, 255)) }
        elevation = activity.dp(5).toFloat()
        addView(activity.icon(R.drawable.ic_cat_silhouette, Color.WHITE, 29),
            FrameLayout.LayoutParams(activity.dp(29), activity.dp(29), Gravity.CENTER))
        contentDescription = "나비 부르기"
        setOnClickListener {
            if (!CatPresence.showInRoom) CatPresence.enterRoom() // overlay cat walks into the room
            floor.post { roomCat.callOver() }
        }
    }

    // ---------------- data ----------------
    /** Called on resume: reload state + missions, refresh coins and decide where the cat is. */
    fun refresh() {
        coinText.text = ShopStore.coins(activity).toString()
        if (decorator == null) updateFurniture()
        startTouchHints()
        root.post { updateCat() }
        if (lastState == null) showAnalysis(null, null)
        activity.bg({ activity.api.userState() to activity.api.activeMissions() }) { r ->
            r.onSuccess { (state, list) ->
                lastState = state
                activity.prefs.catName = state.catName
                showAnalysis(state, null)
                missions = list
                missionText.text = if (list.isEmpty()) "미션" else "미션 ${list.size}"
                missionSheet?.let { renderMissions(it.body) }
                activity.onUserStateLoaded(state)
            }.onFailure {
                showAnalysis(lastState, it.friendly())
                if (!activity.prefs.loggedIn) activity.showLogin() // token expired (401)
            }
        }
    }

    /** The overlay switch changed in 마이페이지. */
    fun onOverlayChanged() = root.post { updateCat() }

    companion object {
        /**
         * Figma default spots, converted from the Figma CSS on a 390×844 phone into fractions of
         * the room image: rug centred 78dp above the bottom at 80% width (max 344), cat tower
         * bottom-right (34vw), plant bottom-left (23vw).
         */
        private val DEFAULT_PLACEMENTS = mapOf(
            ShopStore.Slot.RUG to ShopStore.Placement(0.5f, 0.9076f, 0.657f),
            ShopStore.Slot.TOWER to ShopStore.Placement(0.748f, 0.9088f, 0.279f),
            ShopStore.Slot.PLANT to ShopStore.Placement(0.2154f, 0.9076f, 0.189f),
        )
    }
}
