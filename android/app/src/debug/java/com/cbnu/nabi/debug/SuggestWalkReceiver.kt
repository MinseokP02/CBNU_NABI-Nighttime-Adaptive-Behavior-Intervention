package com.cbnu.nabi.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cbnu.nabi.pet.PetService

/**
 * DEBUG ONLY. Makes the overlay cat suggest a walk right now (the real trigger is the server's
 * daytime-activity recommendation, checked by PetService):
 *
 *     adb shell am broadcast -n com.cbnu.nabi/.debug.SuggestWalkReceiver
 */
class SuggestWalkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = PetService.suggestWalk(context)
}
