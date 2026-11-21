package com.cbnu.nabi.walk

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.cbnu.nabi.BuildConfig
import com.cbnu.nabi.R
import com.cbnu.nabi.data.ApiClient
import com.cbnu.nabi.data.CatAction
import com.cbnu.nabi.data.Prefs
import com.cbnu.nabi.data.WalkHistory
import com.cbnu.nabi.pet.PetService
import com.cbnu.nabi.ui.MissionProposalStore
import com.cbnu.nabi.ui.Modals
import com.cbnu.nabi.ui.Palette
import com.cbnu.nabi.ui.bg
import com.cbnu.nabi.ui.cardBg
import com.cbnu.nabi.ui.dp
import com.cbnu.nabi.ui.friendly
import com.cbnu.nabi.ui.icon
import com.cbnu.nabi.ui.label
import com.cbnu.nabi.ui.lockPortraitOnPhones
import com.cbnu.nabi.ui.rounded
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.random.Random

/**
 * 산책 page. Opened from 나비's walk suggestion (overlay card or in-app proposal) or directly.
 *
 * 1. Finds the user's position and recommends a loop on the map ([WalkRoutePlanner]).
 * 2. The user tunes it: walk length (15–60분), "다른 경로" (another direction), and map taps that add
 *    places to pass (up to [MAX_VIA]); "초기화" goes back to the automatic loop.
 * 3. 산책 시작 → [WalkTrackingService] records the path; 산책 끝내기 → POST /walks (산책 내역, PostgreSQL).
 */
class WalkActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var api: ApiClient
    private lateinit var map: MapView
    private lateinit var lm: LocationManager

    private var start: LatLon? = null
    private var usingFallbackStart = false
    private var targetMinutes = 30
    private var heading = Random.nextDouble(0.0, 360.0)
    private val via = mutableListOf<LatLon>()
    private var route: WalkRoute? = null
    private var planSeq = 0
    private var proposalId: String? = null

    private val routeLine by lazy { Polyline(map).apply { outlinePaint.color = Color.parseColor("#9D7BC4"); outlinePaint.strokeWidth = dp(6).toFloat() } }
    private val walkedLine by lazy { Polyline(map).apply { outlinePaint.color = Color.parseColor("#E99680"); outlinePaint.strokeWidth = dp(5).toFloat() } }
    private val markers = mutableListOf<Marker>()
    private var myLocation: MyLocationNewOverlay? = null

    private lateinit var routeInfo: TextView
    private lateinit var hint: TextView
    private lateinit var statsText: TextView
    private lateinit var actionBtn: TextView
    private lateinit var historyBox: LinearLayout
    private val chips = mutableMapOf<Int, TextView>()
    private lateinit var planControls: LinearLayout

    private val main = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { renderStats(); main.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lockPortraitOnPhones()
        prefs = Prefs(this)
        api = ApiClient(prefs)
        lm = getSystemService(LocationManager::class.java)
        proposalId = intent.getStringExtra(EXTRA_PROPOSAL_ID) ?: WalkSession.proposalId

        Configuration.getInstance().apply {
            userAgentValue = BuildConfig.APPLICATION_ID // OSM tile usage policy: identify the app
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }
        setContentView(buildUi())
        if (hasLocationPermission()) onLocationReady() else requestPermissions(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOCATION)
        loadHistory()
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        WalkSession.listener = { drawWalked(); renderStats() }
        if (WalkSession.active) { drawWalked(); main.post(ticker) }
        renderMode()
    }

    override fun onPause() {
        map.onPause()
        WalkSession.listener = null
        main.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        stopLocate()
        map.onDetach()
        super.onDestroy()
    }

    // ---------------- UI ----------------
    private fun buildUi(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Palette.page) }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(8))
        }
        header.addView(FrameLayout(this).apply {
            background = cardBg(Palette.card, 12, Color.parseColor("#E6D4C8"))
            addView(icon(R.drawable.ic_chevron_left, Palette.ink, 22), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            contentDescription = "돌아가기"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(36), dp(36)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(label("나비와 산책", 22f, title = true))
        titles.addView(label("가볍게 걸으면 밤에 더 잘 잘 수 있어요", 11f, Palette.muted).apply { setPadding(0, dp(3), 0, 0) })
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10) })
        root.addView(header)

        map = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(16.0)
            controller.setCenter(GeoPoint(DEFAULT_START[0], DEFAULT_START[1]))
            isTilesScaledToDpi = true
        }
        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean { addVia(p); return true }
            override fun longPressHelper(p: GeoPoint): Boolean { addVia(p); return true }
        }))
        map.overlays.add(routeLine)
        map.overlays.add(walkedLine)
        val mapFrame = FrameLayout(this).apply {
            addView(map)
            // OSM attribution (required by the tile licence).
            addView(label("© OpenStreetMap contributors", 9f, Palette.muted).apply {
                setPadding(dp(6), dp(2), dp(6), dp(2)); setBackgroundColor(Color.argb(200, 255, 255, 255))
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END))
        }
        val mapH = (resources.displayMetrics.heightPixels * 0.46f).toInt()
        root.addView(mapFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, mapH))

        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(24)) }
        routeInfo = label("현재 위치를 찾고 있어요…", 15f, title = true)
        panel.addView(routeInfo)
        hint = label("", 11f, Palette.muted).apply { setPadding(0, dp(4), 0, 0); setLineSpacing(dp(2).toFloat(), 1f) }
        panel.addView(hint)

        planControls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        planControls.addView(label("산책 시간", 12.5f, bold = true).apply { setPadding(0, dp(12), 0, dp(6)) })
        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (m in listOf(15, 30, 45, 60)) {
            val c = label("${m}분", 12f, bold = true).apply {
                gravity = Gravity.CENTER
                setOnClickListener { if (targetMinutes != m) { targetMinutes = m; styleChips(); replan() } }
            }
            chips[m] = c
            chipRow.addView(c, LinearLayout.LayoutParams(0, dp(36), 1f).apply { if (m != 15) leftMargin = dp(6) })
        }
        planControls.addView(chipRow)
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        btnRow.addView(Modals.button(this, "다른 경로", false) { via.clear(); heading = (heading + 72 + Random.nextDouble(-15.0, 15.0)) % 360; replan() },
            LinearLayout.LayoutParams(0, dp(42), 1f))
        btnRow.addView(Modals.button(this, "초기화", false) { via.clear(); replan() },
            LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(8) })
        planControls.addView(btnRow)
        panel.addView(planControls)
        styleChips()

        statsText = label("", 13f, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0); visibility = View.GONE }
        panel.addView(statsText)
        actionBtn = Modals.button(this, "산책 시작", true) { onAction() }
        panel.addView(actionBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(14) })

        panel.addView(label("산책 내역", 17f, title = true).apply { setPadding(0, dp(22), 0, dp(4)) })
        historyBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(historyBox)
        root.addView(ScrollView(this).apply { addView(panel) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun styleChips() = chips.forEach { (m, v) ->
        val on = m == targetMinutes
        v.background = if (on) rounded(Color.parseColor("#9D7BC4"), 12) else cardBg(Color.WHITE, 12, Color.parseColor("#E3D3C8"))
        v.setTextColor(if (on) Color.WHITE else Palette.ink)
    }

    private fun renderMode() {
        val walking = WalkSession.active
        planControls.visibility = if (walking) View.GONE else View.VISIBLE
        statsText.visibility = if (walking) View.VISIBLE else View.GONE
        actionBtn.text = if (walking) "산책 끝내기" else "산책 시작"
        actionBtn.background = rounded(if (walking) Color.parseColor("#E99680") else Color.parseColor("#9D7BC4"), 14)
        if (walking) renderStats() else renderHint()
    }

    private fun renderHint() {
        hint.text = buildString {
            append("지도를 누르면 지나갈 곳을 추가할 수 있어요 (최대 ${MAX_VIA}곳).")
            if (via.isNotEmpty()) append(" 지금 ${via.size}곳을 지나요.")
            if (route?.snapped == false) append("\n길 안내 서버에 연결하지 못해 직선 경로로 보여줘요.")
            if (usingFallbackStart) append("\n현재 위치를 몰라 기본 위치(충북대) 기준으로 보여줘요.")
        }
    }

    @SuppressLint("SetTextI18n")
    private fun renderStats() {
        if (!WalkSession.active) return
        val sec = WalkSession.elapsedSec
        statsText.text = "%d:%02d 경과 · %.2fkm 걸었어요".format(sec / 60, sec % 60, WalkSession.distanceM / 1000) +
            (WalkSession.targetMinutes?.let { " · 목표 ${it}분" } ?: "")
        hint.text = "화면을 꺼도 기록돼요. 알림에서 다시 이 화면으로 올 수 있어요."
    }

    // ---------------- location ----------------
    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) {
            if (hasLocationPermission()) onLocationReady() else useFallbackStart()
        }
    }

    private var locateListener: LocationListener? = null

    @SuppressLint("MissingPermission")
    private fun onLocationReady() {
        myLocation = MyLocationNewOverlay(GpsMyLocationProvider(this), map).also { it.enableMyLocation(); map.overlays.add(it) }
        val last = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < 10 * 60_000 }
            .maxByOrNull { it.time }
        if (last != null) setStart(last)
        // Ask for a fresh fix as well; the first answer (or a better one) moves the start.
        val l = LocationListener { loc ->
            // A fresh fix replaces a stale cached one (or the fallback) when it is noticeably elsewhere.
            val s = start
            val moved = s == null || usingFallbackStart ||
                WalkRoutePlanner.haversine(s, doubleArrayOf(loc.latitude, loc.longitude)) > START_MOVE_M
            if (moved && !WalkSession.active) { via.clear(); setStart(loc) }
            stopLocate()
        }
        locateListener = l
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (runCatching { lm.isProviderEnabled(p) }.getOrDefault(false))
                runCatching { lm.requestLocationUpdates(p, 1_000L, 0f, l, Looper.getMainLooper()) }
        }
        main.postDelayed({ if (start == null) useFallbackStart() }, 8_000)
    }

    private fun stopLocate() {
        locateListener?.let { runCatching { lm.removeUpdates(it) } }
        locateListener = null
    }

    private fun setStart(loc: Location) {
        usingFallbackStart = false
        start = doubleArrayOf(loc.latitude, loc.longitude)
        map.controller.setCenter(GeoPoint(loc.latitude, loc.longitude))
        replan()
    }

    private fun useFallbackStart() {
        if (start != null) return
        usingFallbackStart = true
        start = DEFAULT_START
        replan()
    }

    // ---------------- route ----------------
    private fun addVia(p: GeoPoint) {
        if (WalkSession.active || start == null) return
        if (via.size >= MAX_VIA) {
            Toast.makeText(this, "지나갈 곳은 ${MAX_VIA}곳까지 추가할 수 있어요", Toast.LENGTH_SHORT).show(); return
        }
        via.add(doubleArrayOf(p.latitude, p.longitude))
        replan()
    }

    private fun replan() {
        val s = start ?: return
        if (WalkSession.active) return
        val seq = ++planSeq
        routeInfo.text = "경로를 찾고 있어요…"
        val viaCopy = via.toList()
        bg({ WalkRoutePlanner.plan(s, targetMinutes, heading, viaCopy) }) { r ->
            if (seq != planSeq) return@bg // a newer request is on its way
            val planned = r.getOrNull() ?: return@bg
            route = planned
            routeInfo.text = "추천 경로 · %.1fkm · 약 %d분".format(planned.distanceM / 1000, planned.minutes)
            drawRoute(planned, s, fit = true)
            renderHint()
        }
    }

    private fun drawRoute(r: WalkRoute, s: LatLon, fit: Boolean) {
        routeLine.setPoints(r.points.map { GeoPoint(it[0], it[1]) })
        markers.forEach { map.overlays.remove(it) }
        markers.clear()
        markers += marker(s, R.drawable.ic_home, "출발·도착")
        via.forEachIndexed { i, p ->
            markers += marker(p, R.drawable.ic_paw, "지나갈 곳 ${i + 1} (눌러서 삭제)").apply {
                setOnMarkerClickListener { _, _ -> if (!WalkSession.active) { via.removeAt(i); replan() }; true }
            }
        }
        markers.forEach { map.overlays.add(it) }
        if (fit && r.points.size >= 2) {
            val box = BoundingBox.fromGeoPointsSafe(r.points.map { GeoPoint(it[0], it[1]) })
            map.post { runCatching { map.zoomToBoundingBox(box.increaseByScale(1.25f), true) } }
        }
        map.invalidate()
    }

    private fun marker(p: LatLon, iconRes: Int, title: String) = Marker(map).apply {
        position = GeoPoint(p[0], p[1])
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        icon = getDrawable(iconRes)?.mutate()?.apply { setTint(Color.parseColor("#71568D")) }
        this.title = title
    }

    private fun drawWalked() {
        walkedLine.setPoints(WalkSession.path.map { GeoPoint(it[0], it[1]) })
        map.invalidate()
    }

    // ---------------- start / finish ----------------
    private fun onAction() = if (WalkSession.active) finishWalk() else startWalk()

    private fun startWalk() {
        if (!hasLocationPermission()) {
            Modals.info(this, R.drawable.ic_paw, "위치 권한이 필요해요",
                "산책 경로를 기록하려면 위치 권한을 허용해 주세요.", "허용하기" to {
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOCATION)
                })
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF) // the walk still records without it
        }
        WalkSession.start()
        WalkSession.targetMinutes = targetMinutes
        WalkSession.plannedRoute = route?.points ?: emptyList()
        WalkSession.plannedDistanceM = route?.distanceM
        WalkSession.proposalId = proposalId
        start?.let { WalkSession.add(Location("start").apply { latitude = it[0]; longitude = it[1]; time = System.currentTimeMillis(); accuracy = 10f }) }
        WalkTrackingService.start(this)
        PetService.show(this, CatAction.WALK, "같이 걷자냥!")
        main.post(ticker)
        renderMode()
    }

    private fun finishWalk() {
        val elapsed = WalkSession.elapsedSec
        if (elapsed < MIN_WALK_SEC) {
            Modals.info(this, R.drawable.ic_paw, "아직 1분도 안 걸었어요",
                "지금 끝내면 산책 내역에 저장하지 않아요. 끝낼까요?", "끝내기" to { endSession(); renderMode() })
            return
        }
        val startedAt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(WalkSession.startedAtMs), ZoneId.systemDefault())
        val endedAt = ZonedDateTime.now()
        val distance = WalkSession.distanceM
        val path = WalkRoutePlanner.thin(WalkSession.path.toList())
        val planned = WalkRoutePlanner.thin(WalkSession.plannedRoute)
        val target = WalkSession.targetMinutes
        val plannedDist = WalkSession.plannedDistanceM
        val pid = WalkSession.proposalId
        endSession()
        renderMode()
        save(startedAt, endedAt, distance, target, plannedDist, planned, path, pid)
    }

    private fun endSession() {
        WalkSession.stop()
        WalkTrackingService.stop(this)
        main.removeCallbacks(ticker)
        WalkSession.proposalId = null
    }

    private fun save(
        startedAt: ZonedDateTime, endedAt: ZonedDateTime, distance: Double, target: Int?, plannedDist: Double?,
        planned: List<LatLon>, path: List<LatLon>, pid: String?,
    ) {
        // The walk is done either way: complete the accepted 산책 mission and keep the body on the device
        // until the server has it, so a failed upload is never lost ([loadHistory] sends it again).
        pid?.let { MissionProposalStore(this, prefs.email).completeById(it) }
        pending.add(api.walkBody(startedAt, endedAt, distance, target, plannedDist, planned, path, pid))
        PetService.show(this, CatAction.HAPPY, "산책 최고였다냥!")
        actionBtn.isEnabled = false
        actionBtn.text = "산책 내역 저장 중…"
        bg({ pending.flush(api) }) { r ->
            actionBtn.isEnabled = true
            renderMode()
            val min = java.time.Duration.between(startedAt, endedAt).toMinutes()
            if ((r.getOrNull() ?: 0) > 0) {
                Toast.makeText(this, "산책을 기록했어요 · ${min}분 · %.2fkm".format(distance / 1000), Toast.LENGTH_LONG).show()
                loadHistory()
            } else {
                Modals.info(this, R.drawable.ic_paw, "산책 내역을 아직 저장하지 못했어요",
                    "이 기기에 보관해 두었다가 서버에 연결되면 자동으로 올릴게요.\n(${r.exceptionOrNull()?.friendly() ?: "연결 실패"})",
                    "지금 다시 시도" to { loadHistory() })
                loadHistory()
            }
        }
    }

    // ---------------- history (GET /walks) ----------------
    private val pending by lazy { PendingWalks(this, prefs.email) }

    /** Uploads walks kept on the device first, then reads the history. */
    private fun loadHistory() {
        bg({ runCatching { pending.flush(api) }; api.walks(20) }) { r ->
            historyBox.removeAllViews()
            r.onSuccess { renderHistory(it) }.onFailure {
                historyBox.addView(label("산책 내역을 불러오지 못했어요 (${it.friendly()})", 11.5f, Palette.muted))
            }
            pending.count().takeIf { it > 0 }?.let {
                historyBox.addView(label("서버에 아직 올리지 못한 산책 ${it}건이 이 기기에 있어요. 연결되면 자동으로 올려요.",
                    11f, Palette.lavenderDeep, bold = true).apply { setPadding(0, dp(8), 0, 0) })
            }
        }
    }

    private fun renderHistory(h: WalkHistory) {
        if (h.items.isEmpty()) {
            historyBox.addView(label("아직 산책 기록이 없어요. 나비와 첫 산책을 해 볼까요?", 11.5f, Palette.muted)); return
        }
        historyBox.addView(label("지금까지 ${h.walks}번 · 총 %.1fkm · %d분".format(h.totalDistanceM / 1000, h.totalDurationSec / 60),
            12f, Palette.lavenderDeep, bold = true).apply { setPadding(0, dp(2), 0, dp(8)) })
        val fmt = DateTimeFormatter.ofPattern("M월 d일 (E) a h:mm", Locale.KOREAN)
        for (w in h.items) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = cardBg(Palette.card, 14, Color.parseColor("#EADDD3"))
            }
            row.addView(icon(R.drawable.ic_paw, Palette.lavender, 18))
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(label(w.startedAt.atZoneSameInstant(ZoneId.systemDefault()).format(fmt), 12f, bold = true))
            texts.addView(label("${w.durationSec / 60}분 · %.2fkm".format(w.distanceM / 1000) +
                (w.targetMinutes?.let { " · 목표 ${it}분" } ?: ""), 11f, Palette.muted).apply { setPadding(0, dp(3), 0, 0) })
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10) })
            historyBox.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(6) })
        }
    }

    companion object {
        const val EXTRA_PROPOSAL_ID = "walk_proposal_id"
        private const val REQ_LOCATION = 51
        private const val REQ_NOTIF = 52
        private const val MAX_VIA = 3
        private const val MIN_WALK_SEC = 60L
        private const val START_MOVE_M = 50.0
        /** 충북대학교 (used only when the position is unknown). */
        private val DEFAULT_START = doubleArrayOf(36.6285, 127.4563)

        fun open(context: Context, proposalId: String?) {
            context.startActivity(Intent(context, WalkActivity::class.java)
                .apply { if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                .putExtra(EXTRA_PROPOSAL_ID, proposalId))
        }
    }
}
