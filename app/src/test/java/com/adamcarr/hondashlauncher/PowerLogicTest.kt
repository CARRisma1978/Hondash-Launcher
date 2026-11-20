package com.adamcarr.hondashlauncher

import com.adamcarr.hondashlauncher.PowerLogic.Action.*
import org.junit.Assert.assertEquals
import org.junit.Test

class PowerLogicTest {
    private val USB = 2; private val AC = 1; private val WIRELESS = 4

    @Test fun plugAndUnplug() {
        val l = PowerLogic(Store.Source.ANY).apply { reset(0) }
        assertEquals(RUN_ON, l.update(USB))
        assertEquals(NONE, l.update(USB))
        assertEquals(SCHEDULE_OFF, l.update(0))
        assertEquals(RUN_OFF, l.timerFired())
        assertEquals(NONE, l.timerFired())
    }

    @Test fun replugWithinTenSecondsDoesNothing() {
        val l = PowerLogic(Store.Source.ANY).apply { reset(USB) }
        assertEquals(SCHEDULE_OFF, l.update(0))
        assertEquals(CANCEL_OFF, l.update(USB))
        assertEquals(NONE, l.timerFired())
        assertEquals(SCHEDULE_OFF, l.update(0))
        assertEquals(RUN_OFF, l.timerFired())
    }

    @Test fun alreadyPluggedAtStartDoesNotRunOn() {
        val l = PowerLogic(Store.Source.ANY).apply { reset(AC) }
        assertEquals(NONE, l.update(AC))
    }

    @Test fun wiredOnlyIgnoresWireless() {
        val l = PowerLogic(Store.Source.WIRED).apply { reset(0) }
        assertEquals(NONE, l.update(WIRELESS))
        assertEquals(NONE, l.update(0))
        assertEquals(RUN_ON, l.update(AC))
    }

    @Test fun wirelessOnly() {
        val l = PowerLogic(Store.Source.WIRELESS).apply { reset(0) }
        assertEquals(NONE, l.update(USB))
        assertEquals(RUN_ON, l.update(WIRELESS))
        assertEquals(SCHEDULE_OFF, l.update(0))
    }
}
