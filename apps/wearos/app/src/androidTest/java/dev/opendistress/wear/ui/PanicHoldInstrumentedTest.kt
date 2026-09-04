// SPDX-License-Identifier: MIT
package dev.opendistress.wear.ui

import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PanicHoldInstrumentedTest {
    @Test
    fun shortTapDoesNotConfirmButContinuousHoldDoes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var view: PanicHoldView
        val confirmed = AtomicBoolean(false)
        instrumentation.runOnMainSync {
            view = PanicHoldView(instrumentation.targetContext).apply {
                layout(0, 0, 384, 384)
                listener = object : PanicHoldView.Listener {
                    override fun onHapticCue(cue: PanicHoldView.HapticCue) = Unit
                    override fun onTriggerConfirmed() {
                        confirmed.set(true)
                    }
                }
            }
            view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN))
            view.dispatchTouchEvent(event(MotionEvent.ACTION_UP))
        }
        assertFalse(confirmed.get())

        instrumentation.runOnMainSync {
            view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN))
        }
        Thread.sleep(2_650)
        instrumentation.runOnMainSync {
            view.dispatchTouchEvent(event(MotionEvent.ACTION_UP))
        }
        assertTrue(confirmed.get())
    }

    private fun event(action: Int): MotionEvent {
        val now = SystemClock.uptimeMillis()
        return MotionEvent.obtain(now, now, action, 192f, 192f, 0)
    }
}
