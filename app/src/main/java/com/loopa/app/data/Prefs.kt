package com.loopa.app.data

import android.content.Context

/**
 * Garstka ustawien, ktore nie maja po co siedziec w bazie.
 *
 * Adres aktualizacji jest edytowalny celowo: apka nie jest w sklepie, wiec nie ma
 * zadnego z gory znanego miejsca, pod ktorym zawsze lezy najnowsze wydanie.
 * Wklejasz adres raz, a potem aktualizujesz jednym przyciskiem.
 */
class Prefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("loopa", Context.MODE_PRIVATE)

    var updateUrl: String
        get() = prefs.getString(KEY_UPDATE_URL, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_UPDATE_URL, value.trim()).apply()

    private companion object {
        const val KEY_UPDATE_URL = "update_url"
    }
}
