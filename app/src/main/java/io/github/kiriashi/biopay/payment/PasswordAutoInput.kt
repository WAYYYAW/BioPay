/*
 * BioPay - biometric payment assistance for WeChat Tenpay keyboard.
 *
 * Copyright (C) 2026 kiriashi
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package io.github.kiriashi.biopay.payment

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import io.github.kiriashi.biopay.core.log.LOG_TAG
import io.github.kiriashi.biopay.hook.HookTargets
import io.github.kiriashi.biopay.lifecycle.AppState
import java.lang.ref.WeakReference
import java.util.concurrent.ThreadLocalRandom

object PasswordAutoInput {
    private val handler = Handler(Looper.getMainLooper())
    private var activeRun: InputRun? = null
    private var cachedPackage: String? = null
    private var digitIds: IntArray? = null

    fun isInProgress(sessionId: Long): Boolean = activeRun?.sessionId == sessionId

    fun autoInputPassword(keyboardView: ViewGroup, passwordChars: CharArray,
                          state: AppState, sessionId: Long): Boolean {
        try {
            if (passwordChars.size != 6 || passwordChars.any { it !in '0'..'9' } ||
                !state.session.isCurrentSession(sessionId) || !keyboardView.isAttachedToWindow) return false
            val app = state.app ?: return false
            if (cachedPackage != app.packageName || digitIds == null) {
                digitIds = IntArray(10) { app.resources.getIdentifier(HookTargets.tenpayKeyboard + it, "id", app.packageName) }
                cachedPackage = app.packageName
            }
            val ids = digitIds ?: return false
            val keys = passwordChars.map { digit ->
                val id = ids[digit - '0']
                if (id == 0) return false
                WeakReference(keyboardView.findViewById<View>(id) ?: return false)
            }
            cancelPendingRunnables()
            val run = InputRun(WeakReference(keyboardView), keys, state, sessionId)
            activeRun = run
            run.scheduleNext()
            return true
        } finally {
            passwordChars.fill('\u0000')
        }
    }

    fun cancelPendingRunnables() {
        val run = activeRun ?: return
        activeRun = null
        handler.removeCallbacksAndMessages(run)
        KeyboardCloak.reset()
    }

    private class InputRun(val keyboard: WeakReference<ViewGroup>, val keys: List<WeakReference<View>>,
                           val state: AppState, val sessionId: Long) : Runnable {
        private var index = 0
        fun scheduleNext() {
            val delay = if (index == 0) 0L else if (index == keys.size) 250L else
                AutoInputTiming.gaussianDelay(ThreadLocalRandom.current().nextGaussian())
            handler.postAtTime(this, this, SystemClock.uptimeMillis() + delay)
        }
        override fun run() {
            if (activeRun !== this) return
            val view = keyboard.get()
            if (!state.session.isCurrentSession(sessionId) || view == null ||
                state.session.getCurrentKeyboardView() !== view || !view.isAttachedToWindow) {
                cancelPendingRunnables()
                return
            }
            try {
                if (index == keys.size) {
                    activeRun = null
                    KeyboardCloak.uncloakKeyboardViews(view)
                    KeyboardCloak.restoreConcealedInputViews(animated = true)
                    state.session.setInputEditText(null)
                    return
                }
                val key = keys[index].get()
                if (key == null || !key.isAttachedToWindow) {
                    cancelPendingRunnables()
                    return
                }
                dispatchFakeTouch(key)
                index++
                if (activeRun === this) scheduleNext()
            } catch (e: Throwable) {
                cancelPendingRunnables()
                Log.w(LOG_TAG, "autoInput failed", e)
            }
        }
    }

    private fun dispatchFakeTouch(view: View) {
        val random = ThreadLocalRandom.current()
        val x = random.nextInt(view.width.coerceAtLeast(1)).toFloat()
        val y = random.nextInt(view.height.coerceAtLeast(1)).toFloat()
        val time = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0)
        try { view.dispatchTouchEvent(down) } finally { down.recycle() }
        val up = MotionEvent.obtain(time, time + random.nextLong(2, 6), MotionEvent.ACTION_UP, x, y, 0)
        try { view.dispatchTouchEvent(up) } finally { up.recycle() }
    }
}
