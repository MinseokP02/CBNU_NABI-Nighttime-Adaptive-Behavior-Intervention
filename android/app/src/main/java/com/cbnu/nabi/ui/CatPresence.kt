package com.cbnu.nabi.ui

import android.app.Activity
import android.app.Application
import android.graphics.Rect
import android.os.Bundle
import android.view.ViewTreeObserver
import com.cbnu.nabi.pet.PetService

/**
 * Decides where the one cat Nabi is while the app is open (FR-05-07: never two cats at once).
 *
 * - Overlay off: the cat lives in the home room.
 * - Overlay on: the overlay cat keeps roaming freely, even over the app, until the user calls it
 *   (or it happens to stand on the room floor when the app opens). Then it is "in the room": the
 *   overlay is hidden and the room shows it. Leaving the app sends it back to the overlay.
 * - The overlay is also hidden while our keyboard or a bottom sheet / dialog is open.
 */
object CatPresence : Application.ActivityLifecycleCallbacks {

    private var installed = false
    private var started = 0
    private var sheets = 0
    private val imeOpen = mutableMapOf<Activity, Boolean>()
    private val imeListeners = mutableMapOf<Activity, ViewTreeObserver.OnGlobalLayoutListener>()

    /** True while the cat has come into the in-app room. Reset when the app goes to the background. */
    var inRoom = false
        private set

    /** Increases every time the app comes back to the foreground. */
    var visit = 0
        private set

    /** Called when [inRoom] changes so the home screen can show or remove the room cat. */
    var onChanged: (() -> Unit)? = null

    fun install(app: Application) {
        if (installed) return
        installed = true
        app.registerActivityLifecycleCallbacks(this)
    }

    /** Whether the home room should draw the cat right now. */
    val showInRoom: Boolean get() = inRoom || !PetService.isRunning

    fun enterRoom() {
        if (inRoom) return
        inRoom = true
        push()
        onChanged?.invoke()
    }

    fun sheetOpened() { sheets++; push() }
    fun sheetClosed() { sheets = (sheets - 1).coerceAtLeast(0); push() }

    private fun push() {
        val hide = started > 0 && (inRoom || sheets > 0 || imeOpen.values.any { it })
        PetService.setHiddenInApp(hide)
    }

    // ---- activity tracking ----
    override fun onActivityStarted(activity: Activity) {
        if (started == 0) visit++
        started++
        watchKeyboard(activity)
        push()
    }

    override fun onActivityStopped(activity: Activity) {
        started = (started - 1).coerceAtLeast(0)
        unwatchKeyboard(activity)
        if (started == 0) {
            // The app went to the background: the cat walks back out onto the home screen.
            inRoom = false
            sheets = 0
            onChanged?.invoke()
        }
        push()
    }

    /** Keyboard detection that works on every API level: the visible frame shrinks when the IME is up. */
    private fun watchKeyboard(activity: Activity) {
        val decor = activity.window?.decorView ?: return
        val r = Rect()
        val l = ViewTreeObserver.OnGlobalLayoutListener {
            decor.getWindowVisibleDisplayFrame(r)
            val full = decor.rootView.height
            val open = full > 0 && full - r.bottom > full * 0.15f
            if (imeOpen[activity] != open) {
                imeOpen[activity] = open
                push()
            }
        }
        decor.viewTreeObserver.addOnGlobalLayoutListener(l)
        imeListeners[activity] = l
    }

    private fun unwatchKeyboard(activity: Activity) {
        imeListeners.remove(activity)?.let { activity.window?.decorView?.viewTreeObserver?.removeOnGlobalLayoutListener(it) }
        imeOpen.remove(activity)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
