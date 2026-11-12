package com.cbnu.nabi.ui

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.cbnu.nabi.pet.PetService

/**
 * Decides where the one cat Nabi is while the app is open (FR-05-07: never two cats at once).
 *
 * - Overlay off: the cat lives in the home room.
 * - Overlay on: Nabi is "out playing" on the home screen. While the user is in the app the overlay
 *   cat is hidden and the room is empty, until the user calls it with the 부르기 button. Then it
 *   stays in the room until the user leaves the app, and goes back out onto the home screen.
 */
object CatPresence : Application.ActivityLifecycleCallbacks {

    private var installed = false
    private var started = 0

    /** True while the cat has come into the in-app room. Reset when the app goes to the background. */
    var inRoom = false
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
        onChanged?.invoke()
    }

    /** The overlay cat never shows over our own screens (it is outside or in the room). */
    private fun push() = PetService.setHiddenInApp(started > 0)

    override fun onActivityStarted(activity: Activity) {
        started++
        push()
    }

    override fun onActivityStopped(activity: Activity) {
        started = (started - 1).coerceAtLeast(0)
        if (started == 0) {
            // The app went to the background: the cat walks back out onto the home screen.
            inRoom = false
            onChanged?.invoke()
        }
        push()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
