package com.adamcarr.hondashlauncher

import android.bluetooth.BluetoothManager
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.PowerManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The two Tasker tasks, step for step.
 * Everything runs on one background thread, so an On and an Off never overlap.
 */
object Routines {
    const val HONDASH_PKG = "net.hondash.hondash"
    private const val HONDASH_ACTIVITY = "net.hondash.hondash/.activity.MainActivity"
    private const val BT_WAIT_MS = 30_000L

    private val worker = Executors.newSingleThreadExecutor()

    fun runOn(ctx: Context, reason: String) = run(ctx) { onTask(it, reason) }
    fun runOff(ctx: Context, reason: String) = run(ctx) { offTask(it, reason) }

    /** Keeps the phone awake until the routine has finished (the screen goes off part-way through Off). */
    private fun run(ctx: Context, routine: (Context) -> Unit) {
        val app = ctx.applicationContext
        val wake = app.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HondashLauncher:routine")
        wake.acquire(BT_WAIT_MS + 30_000)
        worker.execute {
            try { routine(app) } finally { if (wake.isHeld) wake.release() }
        }
    }

    // ------------------------------------------------------------------ On Task

    private fun onTask(ctx: Context, reason: String) {
        Store.log(ctx, "ON ($reason)")
        // Bluetooth on
        step(ctx, "Bluetooth on", "cmd bluetooth_manager enable || svc bluetooth enable")
        // Play Ringtone: Leaf, notification stream
        Sounds.play(ctx, Sound.ON)
        // Power Mode: Normal (Battery Saver off).
        // Also clear Android's "sticky" flag. Without that, Android turns Battery Saver back on by
        // itself the moment the power goes off, and Extreme Battery Saver then pauses this app,
        // which silences the power-off sound.
        step(ctx, "Battery Saver off",
            "(cmd power set-mode 0 || settings put global low_power 0) && settings put global low_power_sticky 0")
        // Wait Until %BLUE ~ on
        val waited = waitForBluetooth(ctx)
        if (waited >= 0) Store.log(ctx, "  Bluetooth is on (${waited} ms)")
        else Store.log(ctx, "  Bluetooth didn't come on within ${BT_WAIT_MS / 1000} s, launching Hondash anyway")
        // Launch App: Hondash (via root, so Android's background-launch block doesn't apply)
        step(ctx, "Launch Hondash", "am start -n $HONDASH_ACTIVITY")
    }

    // ----------------------------------------------------------------- Off Task
    // The 10-second wait and the "power came back" check happen in PowerService before this runs.

    private fun offTask(ctx: Context, reason: String) {
        Store.log(ctx, "OFF ($reason)")
        // Turn Off (screen)
        step(ctx, "Screen off", "input keyevent KEYCODE_SLEEP")
        // Kill App: Hondash (root)
        step(ctx, "Close Hondash", "am force-stop $HONDASH_PKG")
        // Bluetooth off
        step(ctx, "Bluetooth off", "cmd bluetooth_manager disable || svc bluetooth disable")
        // Play Ringtone: Chime. Let it finish: Battery Saver (next) brings in Extreme Battery Saver,
        // which pauses this app and mutes it.
        Sounds.play(ctx, Sound.OFF, wait = true)
        // Power Mode: Battery Saver
        step(ctx, "Battery Saver on", "cmd power set-mode 1 || settings put global low_power 1")
    }

    // ------------------------------------------------------------------ helpers

    private fun step(ctx: Context, label: String, cmd: String) {
        val r = Root.run(cmd)
        Store.log(ctx, if (r.ok) "  $label" else "  $label FAILED (${r.code}) ${r.out.take(120)}")
    }

    /** Waits until Bluetooth is on. Milliseconds it took, or -1 on timeout. */
    private fun waitForBluetooth(ctx: Context): Long {
        val adapter = ctx.getSystemService(BluetoothManager::class.java).adapter
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < BT_WAIT_MS) {
            if (runCatching { adapter.isEnabled }.getOrDefault(false)) return System.currentTimeMillis() - start
            Thread.sleep(250)
        }
        return -1
    }
}

/** The two routine sounds, and the Tasker sound each one uses until another is picked in the app. */
enum class Sound(val key: String, val defaultName: String, val label: String) {
    ON("on", "Leaf", "Power-on sound"),
    OFF("off", "Chime", "Power-off sound"),
}

object Sounds {
    /** Null means silent. */
    fun uri(ctx: Context, s: Sound): Uri? {
        val picked = Store.sound(ctx, s.key) ?: return find(ctx, s.defaultName)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        return if (picked.isEmpty()) null else Uri.parse(picked)
    }

    fun pick(ctx: Context, s: Sound, uri: Uri?) = Store.setSound(ctx, s.key, uri?.toString())

    /** Name to show in the app and the log. */
    fun title(ctx: Context, s: Sound): String {
        val uri = uri(ctx, s) ?: return "Silent"
        return runCatching { RingtoneManager.getRingtone(ctx, uri).getTitle(ctx) }.getOrNull() ?: "Custom sound"
    }

    private var player: MediaPlayer? = null

    /** Plays a routine sound. With [wait], returns once it has finished. */
    fun play(ctx: Context, s: Sound, wait: Boolean = false) {
        val target = uri(ctx, s) ?: return Store.log(ctx, "  Sound: silent")
        play(ctx, target, title(ctx, s), wait)
    }

    /** Plays on the notification stream, like Tasker's Play Ringtone. */
    private fun play(ctx: Context, target: Uri, name: String, wait: Boolean) {
        val done = CountDownLatch(1)
        try {
            player?.release()
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(ctx, target)
                setOnCompletionListener { it.release(); if (player === it) player = null; done.countDown() }
                setOnErrorListener { _, _, _ -> done.countDown(); false }
                prepare()
                start()
            }
            Store.log(ctx, "  Played '$name'")
            if (wait) done.await(15, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Store.log(ctx, "  Sound '$name' failed: ${e.message}")
        }
    }

    /** Finds a notification sound by the name shown in Settings > Sound. */
    private fun find(ctx: Context, name: String): Uri? = runCatching {
        val rm = RingtoneManager(ctx).apply { setType(RingtoneManager.TYPE_NOTIFICATION) }
        val c = rm.cursor
        while (c.moveToNext()) {
            if (c.getString(RingtoneManager.TITLE_COLUMN_INDEX).equals(name, ignoreCase = true)) {
                return@runCatching rm.getRingtoneUri(c.position)
            }
        }
        null
    }.getOrNull()
}
