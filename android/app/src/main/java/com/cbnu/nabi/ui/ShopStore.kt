package com.cbnu.nabi.ui

import android.content.Context
import com.cbnu.nabi.R

/**
 * TEMPORARY local coin / shop state. The backend has no coin, product or purchase API yet
 * (spec §8), so everything here is demo data kept on the device and is labeled as such in the UI.
 * Replace with the real API once the backend provides it.
 */
object ShopStore {
    private const val NAME = "nabi_shop_demo"
    private const val K_COINS = "coins"
    private const val K_OWNED = "owned"
    private const val K_EQUIP = "equip_"
    const val DEMO_START_COINS = 300

    enum class Category(val label: String) { INTERIOR("인테리어"), TOY("장난감"), SNACK("간식") }

    /** Where an interior item goes in the home room (one item per slot). */
    enum class Slot { RUG, TOWER, PLANT }

    data class Product(val id: String, val name: String, val category: Category, val price: Int, val image: Int, val slot: Slot? = null)

    const val DEFAULT_RUG = "default-ivory-rug"
    val defaultRug = Product(DEFAULT_RUG, "기본 아이보리 카펫", Category.INTERIOR, 0, R.drawable.rug_ivory, Slot.RUG)

    /** Same catalog as the Figma design. */
    val products = listOf(
        Product("lavender-rug", "라벤더 카펫", Category.INTERIOR, 80, R.drawable.shop_lavender_rug, Slot.RUG),
        Product("wood-cat-tower", "원목 캣타워", Category.INTERIOR, 150, R.drawable.shop_wood_cat_tower, Slot.TOWER),
        Product("green-plant", "초록 화분", Category.INTERIOR, 60, R.drawable.shop_green_plant, Slot.PLANT),
        Product("feather-wand", "깃털 낚싯대", Category.TOY, 50, R.drawable.shop_feather_wand),
        Product("tuna-treat", "참치 츄르", Category.SNACK, 30, R.drawable.shop_tuna_treat),
    )

    fun find(id: String): Product? = if (id == DEFAULT_RUG) defaultRug else products.firstOrNull { it.id == id }

    private fun sp(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun coins(c: Context): Int = sp(c).getInt(K_COINS, DEMO_START_COINS)

    fun owned(c: Context): Set<String> = sp(c).getStringSet(K_OWNED, emptySet())!!.toSet()

    fun isOwned(c: Context, id: String) = id == DEFAULT_RUG || id in owned(c)

    /** Item equipped in [slot]; the ivory rug is the default rug. */
    /** "" = the slot was emptied on purpose (e.g. the rug was put away), so it doesn't fall back to the default. */
    fun equipped(c: Context, slot: Slot): String? =
        sp(c).getString(K_EQUIP + slot.name, if (slot == Slot.RUG) DEFAULT_RUG else null)?.takeIf { it.isNotEmpty() }

    enum class Result { SUCCESS, INSUFFICIENT, OWNED, ERROR }

    /** FR-06-03/04: one-time purchase; deducts coins and marks the item owned. */
    fun purchase(c: Context, p: Product): Result {
        if (isOwned(c, p.id)) return Result.OWNED
        val coins = coins(c)
        if (coins < p.price) return Result.INSUFFICIENT
        val ok = sp(c).edit().putInt(K_COINS, coins - p.price).putStringSet(K_OWNED, owned(c) + p.id).commit()
        return if (ok) Result.SUCCESS else Result.ERROR
    }

    /** FR-06-05: place an owned interior item in its room slot. */
    fun equip(c: Context, p: Product): Boolean {
        val slot = p.slot ?: return false
        if (!isOwned(c, p.id)) return false
        return sp(c).edit().putString(K_EQUIP + slot.name, p.id).commit()
    }

    /** 방 꾸미기 › 보관: take the item out of the room; it shows as "보관 중" in 내 아이템. */
    fun unequip(c: Context, slot: Slot): Boolean =
        sp(c).edit().putString(K_EQUIP + slot.name, "").commit()

    // ---------------- 방 꾸미기 (user-arranged placement) ----------------
    /**
     * Where an item sits, as fractions of a reference box: [cx] = horizontal centre, [bottom] =
     * bottom edge, [width] = item width. Saved placements are relative to the ROOM BACKGROUND IMAGE
     * (not the screen), so furniture stays on the same spot of the drawn floor on phones and tablets.
     */
    data class Placement(val cx: Float, val bottom: Float, val width: Float)

    // v2 = background-image relative. "place_" (screen relative, first version) is ignored.
    private const val K_PLACE = "placeimg_"
    private const val K_PLACE_OLD = "place_"

    /** Null → the Figma default position. */
    fun placement(c: Context, slot: Slot): Placement? =
        sp(c).getString(K_PLACE + slot.name, null)?.split(",")?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.size == 3 }?.let { Placement(it[0], it[1], it[2]) }

    fun savePlacements(c: Context, all: Map<Slot, Placement>): Boolean {
        val e = sp(c).edit()
        all.forEach { (slot, p) -> e.putString(K_PLACE + slot.name, "${p.cx},${p.bottom},${p.width}") }
        return e.commit()
    }

    fun clearPlacements(c: Context): Boolean {
        val e = sp(c).edit()
        Slot.values().forEach { e.remove(K_PLACE + it.name); e.remove(K_PLACE_OLD + it.name) }
        return e.commit()
    }
}
