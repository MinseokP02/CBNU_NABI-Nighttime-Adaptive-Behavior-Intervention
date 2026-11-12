package com.cbnu.nabi.ui

import android.content.Context

/**
 * TEMPORARY local coin / shop state. The backend has no coin, product or purchase API yet
 * (spec §8), so everything here is demo data kept on the device and must be labeled as such.
 * Replace with the real API once the backend provides it.
 */
object ShopStore {
    private const val NAME = "nabi_shop_demo"
    private const val K_COINS = "coins"
    const val DEMO_START_COINS = 120

    fun coins(c: Context): Int =
        c.getSharedPreferences(NAME, Context.MODE_PRIVATE).getInt(K_COINS, DEMO_START_COINS)
}
