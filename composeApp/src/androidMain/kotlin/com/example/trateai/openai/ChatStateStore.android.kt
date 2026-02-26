package com.example.trateai.openai

import android.content.Context
import android.content.SharedPreferences

private const val PREFS_NAME = "trateai_prefs"

actual fun createKvStore(): KvStore = AndroidKvStore(AndroidContextProvider.context)

private class AndroidKvStore(
    context: Context
) : KvStore {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}

/**
 * Нужно один раз проинициализировать в Android Application/Activity.
 */
object AndroidContextProvider {
    lateinit var context: Context
}