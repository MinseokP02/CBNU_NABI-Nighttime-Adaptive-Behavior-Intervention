package com.cbnu.nabi

import android.app.Application
import android.content.Context
import androidx.annotation.StringRes

/**
 * Holds the application context so UI text can be looked up anywhere with [tr], including code
 * without a Context at hand (enums, data defaults, services).
 */
class NabiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = this
    }

    companion object {
        lateinit var appContext: Context
            private set
    }
}

/**
 * Localized UI text: res/values/strings.xml (Korean, default) or res/values-en (English), chosen by
 * the device language. [args] fill %1$s, %2$s… in order.
 */
fun tr(@StringRes id: Int, vararg args: Any?): String =
    if (args.isEmpty()) NabiApp.appContext.getString(id) else NabiApp.appContext.getString(id, *args)
