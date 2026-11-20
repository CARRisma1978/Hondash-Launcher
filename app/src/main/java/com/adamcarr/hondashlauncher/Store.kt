package com.adamcarr.hondashlauncher

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Settings and a short on-screen log, kept in SharedPreferences. */
object Store {
    private const val FILE = "settings"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SOURCE = "source"
    private const val KEY_LOG = "log"
    private const val MAX_LOG = 60

    /** Which chargers count as "in the car". */
    enum class Source(val label: String) { ANY("Any (wired or wireless)"), WIRED("Wired only (USB/AC)"), WIRELESS("Wireless only") }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun enabled(ctx: Context) = prefs(ctx).getBoolean(KEY_ENABLED, false)
    fun setEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(KEY_ENABLED, on).apply()

    fun source(ctx: Context): Source =
        runCatching { Source.valueOf(prefs(ctx).getString(KEY_SOURCE, null) ?: "ANY") }.getOrDefault(Source.ANY)
    fun setSource(ctx: Context, s: Source) = prefs(ctx).edit().putString(KEY_SOURCE, s.name).apply()

    /** Picked sound: null = not picked yet (use the Tasker sound by name), "" = silent. */
    fun sound(ctx: Context, key: String): String? = prefs(ctx).getString("sound_$key", null)
    fun setSound(ctx: Context, key: String, uri: String?) = prefs(ctx).edit().putString("sound_$key", uri ?: "").apply()

    /** Listeners (the open screen) refresh when the log changes. */
    @Volatile var onLog: (() -> Unit)? = null

    @Synchronized
    fun log(ctx: Context, msg: String) {
        android.util.Log.i("HondashLauncher", msg)
        val stamp = SimpleDateFormat("dd MMM HH:mm:ss", Locale.UK).format(Date())
        val lines = (listOf("$stamp  $msg") + readLog(ctx)).take(MAX_LOG)
        prefs(ctx).edit().putString(KEY_LOG, lines.joinToString("\n")).apply()
        onLog?.invoke()
    }

    fun readLog(ctx: Context): List<String> =
        prefs(ctx).getString(KEY_LOG, "")!!.split("\n").filter { it.isNotBlank() }

    fun clearLog(ctx: Context) { prefs(ctx).edit().remove(KEY_LOG).apply(); onLog?.invoke() }
}
