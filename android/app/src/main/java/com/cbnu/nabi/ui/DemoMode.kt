package com.cbnu.nabi.ui


import com.cbnu.nabi.R
import com.cbnu.nabi.tr
import android.content.Context
import android.content.Intent
import android.widget.EditText
import com.cbnu.nabi.BuildConfig
import com.cbnu.nabi.data.ApiClient
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Scripted demo for recording the promo video. DEBUG BUILDS ONLY, and only after it is switched on with
 *
 *     adb shell am start -n com.cbnu.nabi/.ui.MainActivity --ez demo_video true
 *
 * (`--ez demo_video false` turns it off). While on:
 * - the login form is pre-filled with a fresh demo account,
 * - signing up stores 14 demo nights (sleep + habits) in the DB through the normal API,
 * - the server's automatic recommendation popup is held back; instead, coming back to 홈 after the
 *   diary shows a fixed "조명 낮추기" proposal,
 * - after completing that mission, leaving the app (e.g. to watch YouTube) triggers the bedtime
 *   wind-down a few seconds later: a 나비 notification, then the screen dims over the other app.
 * Release builds ignore all of this.
 */
object DemoMode {
    private const val PREFS = "nabi_demo"
    private const val K_ON = "on"
    const val EXTRA = "demo_video"
    const val PROPOSAL_ID = "demo-dim-lights"
    const val PASSWORD = "nabi-demo-2026"

    /** Read once per process (see [init]); never true in release builds. */
    var active = false
        private set

    // Per-run state of the scripted flow.
    var diaryVisited = false
    var proposalShown = false
    /** Set when the demo mission is completed: leaving the app then starts the [WindDown]. */
    var windDownArmed = false

    /** Seconds after leaving the app before the wind-down notification (time to start a video). */
    const val WIND_DOWN_DELAY_MS = 2_500L

    fun init(context: Context, intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (intent?.hasExtra(EXTRA) == true) sp.edit().putBoolean(K_ON, intent.getBooleanExtra(EXTRA, false)).commit()
        active = sp.getBoolean(K_ON, false)
        diaryVisited = false; proposalShown = false; windDownArmed = false
        // Switching the demo on also lets 나비 out onto the phone's home screen (overlay cat), so the
        // video can open on the cat walking over the launcher before the app is opened.
        if (active && intent?.getBooleanExtra(EXTRA, false) == true &&
            android.provider.Settings.canDrawOverlays(context) && !com.cbnu.nabi.pet.PetService.isRunning) {
            context.startForegroundService(Intent(context, com.cbnu.nabi.pet.PetService::class.java))
        }
    }

    /** After sign-up: the overlay cat follows the user into the app and appears in the room. */
    fun bringCatIntoRoom(activity: MainActivity) {
        if (!active) return
        activity.window.decorView.postDelayed({ CatPresence.enterRoom() }, 600)
    }

    fun prefillLogin(context: Context, email: EditText, password: EditText) {
        if (!active) return
        val stamp = java.time.format.DateTimeFormatter.ofPattern("MMddHHmmss").format(java.time.LocalDateTime.now())
        email.setText("nabi$stamp@demo.app")
        password.setText(PASSWORD)
    }

    /** Blocking (background thread). Stores the demo nights for the account that just signed up. */
    fun seedAfterRegister(api: ApiClient) {
        if (!active) return
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        // Insomnia-like but plausible: late, varying bedtimes, long sleep onset, some waking at night.
        val bedMinutes = intArrayOf(5, 40, -10, 55, 20, 70, 30, 15, 60, -5, 45, 25, 80, 35) // after 23:00
        val latency = intArrayOf(35, 50, 25, 60, 40, 45, 30, 55, 40, 20, 50, 35, 65, 45)
        val waso = intArrayOf(20, 35, 15, 40, 25, 30, 20, 45, 30, 10, 35, 25, 40, 30)
        val wakeAt = intArrayOf(420, 435, 410, 450, 425, 440, 420, 430, 445, 415, 430, 425, 455, 435) // minutes after 00:00
        val quality = intArrayOf(3, 2, 3, 2, 3, 2, 3, 2, 2, 4, 2, 3, 1, 2)
        for (i in 0 until 14) {
            val night = today.minusDays((14 - i).toLong()) // evening the night started
            val bed = ZonedDateTime.of(night, LocalTime.of(23, 0), zone).plusMinutes(bedMinutes[i].toLong())
            val wake = ZonedDateTime.of(night.plusDays(1), LocalTime.MIDNIGHT, zone).plusMinutes(wakeAt[i].toLong())
            api.createSleepRecord(bed, latency[i], waso[i], wake, wake.plusMinutes(10), quality[i])
            api.createBehaviorRecord(night,
                caffeineAfterCutoff = i % 3 != 0,
                screenBeforeBedMin = 40 + (i % 4) * 20,
                napDurationMin = if (i % 4 == 1) 40 else 0,
                exerciseDurationMin = if (i % 3 == 2) 30 else 0,
                stressLevel = 3 + i % 2)
        }
    }

    fun proposal() = MissionProposal(PROPOSAL_ID, ProposalKind.DIM_LIGHTS,
        description = tr(R.string.it_s_been_taking_you_while))
}
