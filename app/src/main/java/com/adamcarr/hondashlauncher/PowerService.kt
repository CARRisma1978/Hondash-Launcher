package com.adamcarr.hondashlauncher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/**
 * Stays running so Android delivers power on/off changes to the app
 * (since Android 8 these aren't delivered to apps that aren't running).
 * It does no work between changes.
 */
class PowerService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var logic: PowerLogic
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastPlugged = 0

    private val offRunnable = Runnable {
        if (logic.timerFired() == PowerLogic.Action.RUN_OFF) Routines.runOff(this, "power off for 10 s")
        releaseWake()
    }

    // ACTION_BATTERY_CHANGED carries the power source, so it covers both connect and disconnect.
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = handle(i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0))
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        startInForeground()
        logic = PowerLogic(Store.source(this))
        lastPlugged = currentPlugged()
        logic.reset(lastPlugged)
        registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        Store.log(this, "Watching USB power (${logic.source.label}); power is ${if (logic.inCar) "on" else "off"} now")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SOURCE_CHANGED -> {
                logic.source = Store.source(this)
                logic.reset(currentPlugged())
            }
            // Phone switched on while already powered: run On, as Tasker would.
            ACTION_BOOT -> if (logic.inCar) Routines.runOn(this, "phone started with power on")
        }
        return START_STICKY
    }

    private fun handle(plugged: Int) {
        if (plugged == lastPlugged) return
        lastPlugged = plugged
        when (logic.update(plugged)) {
            PowerLogic.Action.RUN_ON -> Routines.runOn(this, "power on")
            PowerLogic.Action.SCHEDULE_OFF -> {
                Store.log(this, "Power off, waiting 10 s")
                acquireWake()
                handler.removeCallbacks(offRunnable)
                handler.postDelayed(offRunnable, PowerLogic.OFF_DELAY_MS)
            }
            PowerLogic.Action.CANCEL_OFF -> {
                handler.removeCallbacks(offRunnable)
                releaseWake()
                Store.log(this, "Power back within 10 s, nothing to do")
            }
            else -> Unit
        }
    }

    private fun currentPlugged(): Int =
        registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0

    // Keeps the CPU awake through the 10 s wait so the Off steps aren't delayed by sleep.
    private fun acquireWake() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HondashLauncher:offDelay")
            .apply { acquire(PowerLogic.OFF_DELAY_MS + 5_000) }
    }

    private fun releaseWake() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun startInForeground() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Background watcher", NotificationManager.IMPORTANCE_MIN)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Waiting for ignition power")
            .setContentIntent(open)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    override fun onDestroy() {
        running = false
        unregisterReceiver(receiver)
        handler.removeCallbacks(offRunnable)
        releaseWake()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "watcher"
        const val ACTION_SOURCE_CHANGED = "source_changed"
        const val ACTION_BOOT = "boot"
        @Volatile var running = false
            private set

        fun start(ctx: Context, action: String? = null) {
            ctx.startForegroundService(Intent(ctx, PowerService::class.java).setAction(action))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, PowerService::class.java))
        }
    }
}

/** Starts the watcher after a reboot (and runs On if the power is already on) or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (!Store.enabled(ctx)) return
        val boot = intent.action == Intent.ACTION_BOOT_COMPLETED
        PowerService.start(ctx, if (boot) PowerService.ACTION_BOOT else null)
    }
}
