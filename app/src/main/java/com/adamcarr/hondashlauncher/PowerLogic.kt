package com.adamcarr.hondashlauncher

import android.os.BatteryManager

/**
 * The trigger logic of the Tasker profile, kept free of Android so it can be unit-tested.
 *
 * Tasker behaviour being copied:
 *  - Plugged in  -> On Task, unless Off Task is still in its 10 s wait (then On does nothing).
 *  - Unplugged   -> Off Task waits 10 s; if power is back by then it stops without doing anything.
 */
class PowerLogic(var source: Store.Source) {
    enum class Action { NONE, RUN_ON, SCHEDULE_OFF, CANCEL_OFF, RUN_OFF }

    var inCar = false
        private set
    var offPending = false
        private set

    fun matches(plugged: Int): Boolean = when (source) {
        Store.Source.ANY -> plugged != 0
        Store.Source.WIRED -> plugged and (BatteryManager.BATTERY_PLUGGED_AC or BatteryManager.BATTERY_PLUGGED_USB or BatteryManager.BATTERY_PLUGGED_DOCK) != 0
        Store.Source.WIRELESS -> plugged and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0
    }

    /** Called when the service starts: remember the current state without running anything. */
    fun reset(plugged: Int) { inCar = matches(plugged); offPending = false }

    /** Called whenever the charger state may have changed. */
    fun update(plugged: Int): Action {
        val now = matches(plugged)
        return when {
            now && offPending -> { offPending = false; inCar = true; Action.CANCEL_OFF }
            now && !inCar -> { inCar = true; Action.RUN_ON }
            !now && inCar -> { inCar = false; offPending = true; Action.SCHEDULE_OFF }
            else -> Action.NONE
        }
    }

    /** Called when the 10 s wait is over. */
    fun timerFired(): Action = if (offPending) { offPending = false; Action.RUN_OFF } else Action.NONE

    companion object {
        const val OFF_DELAY_MS = 10_000L
    }
}
