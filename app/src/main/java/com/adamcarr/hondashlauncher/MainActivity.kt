package com.adamcarr.hondashlauncher

import android.app.Activity
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.RingtoneManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import kotlin.concurrent.thread

/** One screen: switch, charger type, test buttons and a log. Built in code, no layout files. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var enable: Switch
    @Volatile private var rootOk: Boolean? = null
    private val soundLabels = mutableMapOf<Sound, TextView>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(16)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun header(t: String) = TextView(this).apply {
            text = t; textSize = 13f; setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(18), 0, dp(6)); alpha = 0.7f
        }
        fun button(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t; isAllCaps = false; setOnClickListener { onClick() }
        }

        col.addView(TextView(this).apply { text = getString(R.string.app_name); textSize = 24f })
        col.addView(TextView(this).apply {
            text = "Power on (ignition on): Bluetooth on, sound, Battery Saver off, then Hondash.\n" +
                "Power off for 10 s: screen off, Hondash closed, Bluetooth off, sound, Battery Saver on."
            alpha = 0.75f; setPadding(0, dp(4), 0, 0)
        })

        status = TextView(this).apply { setPadding(0, dp(12), 0, 0); setLineSpacing(0f, 1.2f) }
        col.addView(status)

        enable = Switch(this).apply {
            text = "Run when USB power comes on / goes off"
            textSize = 16f
            setPadding(0, dp(16), 0, dp(4))
            isChecked = Store.enabled(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                Store.setEnabled(this@MainActivity, on)
                if (on) PowerService.start(this@MainActivity) else {
                    PowerService.stop(this@MainActivity)
                    Store.log(this@MainActivity, "Stopped watching USB power")
                }
                status.postDelayed({ refresh() }, 300)
            }
        }
        col.addView(enable)

        col.addView(header("WHICH POWER COUNTS"))
        val group = RadioGroup(this)
        Store.Source.values().forEachIndexed { i, s ->
            group.addView(RadioButton(this).apply {
                id = 100 + i; text = s.label; isChecked = Store.source(this@MainActivity) == s
            })
        }
        group.setOnCheckedChangeListener { _, id ->
            Store.setSource(this, Store.Source.values()[id - 100])
            if (PowerService.running) PowerService.start(this, PowerService.ACTION_SOURCE_CHANGED)
        }
        col.addView(group)

        col.addView(header("SOUNDS"))
        for (sound in Sound.values()) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val label = TextView(this).apply { textSize = 15f }
            soundLabels[sound] = label
            r.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(button("Test") { thread { Sounds.play(this, sound) } })
            r.addView(button("Change") { pickSound(sound) })
            col.addView(r)
        }

        col.addView(header("TEST"))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) }
        row.addView(button("Run On now") { Routines.runOn(this, "test") }, lp)
        row.addView(button("Run Off now") { Routines.runOff(this, "test, no 10 s wait") }, lp)
        col.addView(row)
        col.addView(button("Grant / check root") { checkRoot(true) })

        val logHead = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        logHead.addView(header("LOG"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        logHead.addView(button("Clear") { Store.clearLog(this) })
        col.addView(logHead)
        logView = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true) }
        col.addView(logView)

        setContentView(ScrollView(this).apply { addView(col); isFillViewport = true; fitsSystemWindows = true })
        checkRoot(false)
    }

    override fun onResume() {
        super.onResume()
        Store.onLog = { runOnUiThread { showLog() } }
        if (Store.enabled(this) && !PowerService.running) PowerService.start(this)
        showLog()
        refresh()
    }

    override fun onPause() {
        Store.onLog = null
        super.onPause()
    }

    private fun checkRoot(userAsked: Boolean) = thread {
        val ok = Root.available()
        rootOk = ok
        if (ok) {
            // Keep Android's battery optimisation from putting the watcher to sleep.
            Root.run("dumpsys deviceidle whitelist +$packageName")
            if (userAsked) Store.log(this, "Root OK")
        } else if (userAsked) {
            Store.log(this, "Root not granted. Allow it in Magisk > Superuser.")
        }
        runOnUiThread { refresh() }
    }

    private fun refresh() {
        val plugged = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val charger = when {
            plugged == 0 -> "off"
            plugged and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 -> "wireless"
            plugged and BatteryManager.BATTERY_PLUGGED_AC != 0 -> "AC"
            plugged and BatteryManager.BATTERY_PLUGGED_USB != 0 -> "USB"
            else -> "plugged in"
        }
        val hondash = try {
            packageManager.getPackageInfo(Routines.HONDASH_PKG, 0); "installed"
        } catch (e: PackageManager.NameNotFoundException) { "NOT installed" }
        val root = when (rootOk) { null -> "checking…"; true -> "OK"; false -> "not granted" }
        status.text = "Root: $root\nWatcher: ${if (PowerService.running) "running" else "off"}\n" +
            "USB power: $charger\nHondash: $hondash"
        enable.isChecked = Store.enabled(this)
        refreshSound()
    }

    companion object {
        private const val REQ_SOUND = 100 // + Sound.ordinal
    }

    private fun pickSound(sound: Sound) {
        val i = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, sound.label)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Sounds.uri(this, sound))
        startActivityForResult(i, REQ_SOUND + sound.ordinal)
    }

    @Deprecated("Fine for a one-screen app")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val sound = Sound.values().getOrNull(requestCode - REQ_SOUND)
        if (sound == null || resultCode != RESULT_OK || data == null) return
        Sounds.pick(this, sound, data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java))
        refreshSound()
    }

    private fun refreshSound() = thread {
        val titles = Sound.values().associateWith { "${it.label}: ${Sounds.title(this, it)}" }
        runOnUiThread { titles.forEach { (s, t) -> soundLabels[s]?.text = t } }
    }

    private fun showLog() {
        val lines = Store.readLog(this)
        logView.text = if (lines.isEmpty()) "Nothing yet." else lines.joinToString("\n")
    }
}
