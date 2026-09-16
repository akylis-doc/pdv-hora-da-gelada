package br.com.bebidaspdv.ui

import android.content.Context

/** Favorites are a convenience of this operator on this phone, with no remote reads. */
internal class LocalFavorites(context: Context, operatorId: String) {
    private val preferences = context.applicationContext.getSharedPreferences("cashier_favorites", Context.MODE_PRIVATE)
    private val key = "products:$operatorId"

    fun read(): Set<String> = preferences.getStringSet(key, emptySet()).orEmpty().toSet()

    fun toggle(productId: String): Set<String> {
        val next = read().toMutableSet()
        if (!next.add(productId)) next.remove(productId)
        preferences.edit().putStringSet(key, next).apply()
        return next.toSet()
    }
}
