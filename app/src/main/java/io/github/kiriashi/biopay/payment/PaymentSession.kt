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

import android.app.Activity
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.EditText
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import io.github.kiriashi.biopay.core.util.findActivity

class PaymentSession(private val onDestroy: () -> Unit = {}) {

    private val isInPaymentMode = AtomicBoolean(false)
    private val sessionToken = SessionToken()
    private val authenticationToken = SessionToken()

    data class AuthenticationAttempt(val id: Long, val signal: CancellationSignal)

    @Volatile
    private var cancelSignal: CancellationSignal? = null
    private val signalLock = Any()

    @Volatile
    private var currentKeyboardViewRef: WeakReference<ViewGroup>? = null
    @Volatile
    private var currentEncodedPassword: String? = null
    @Volatile
    private var inputEditTextRef: WeakReference<EditText>? = null

    @Volatile
    private var lastKeyboardAccessTime: Long = 0

    private val cleanupHandler = Handler(Looper.getMainLooper())
    private val cleanupRunning = AtomicBoolean(false)
    private val cleanupRunnable = object : Runnable {
        override fun run() {
            if (!cleanupRunning.get()) return
            cleanupExpiredReferences()
            if (cleanupRunning.get()) cleanupHandler.postDelayed(this, 60_000L)
        }
    }

    fun startCleanup() {
        if (cleanupRunning.compareAndSet(false, true)) {
            cleanupHandler.postDelayed(cleanupRunnable, 60_000L)
        }
    }

    fun stopCleanup() {
        cleanupRunning.set(false)
        cleanupHandler.removeCallbacks(cleanupRunnable)
    }

    fun isInPaymentMode(): Boolean = isInPaymentMode.get()

    fun beginSession(): Long {
        sessionToken.invalidate()
        onDestroy()
        cancelCurrentSignal()
        startCleanup()
        return sessionToken.begin()
    }

    fun isCurrentSession(id: Long): Boolean = sessionToken.isCurrent(id)

    fun currentSessionId(): Long = sessionToken.current()

    fun endSession(id: Long) {
        if (isCurrentSession(id)) destroy()
    }

    fun endSessionForActivity(activity: Activity) {
        val view = currentKeyboardViewRef?.get()
        if (view?.context?.findActivity() === activity) destroy()
    }

    fun setInPaymentMode(value: Boolean) {
        isInPaymentMode.set(value)
    }

    fun setCurrentKeyboardView(view: ViewGroup?) {
        currentKeyboardViewRef = if (view != null) WeakReference(view) else null
        lastKeyboardAccessTime = SystemClock.uptimeMillis()
    }

    fun getCurrentKeyboardView(): ViewGroup? {
        lastKeyboardAccessTime = SystemClock.uptimeMillis()
        return currentKeyboardViewRef?.get()
    }

    fun setCurrentEncodedPassword(password: String?) {
        currentEncodedPassword = password
    }

    fun getCurrentEncodedPassword(): String? {
        return currentEncodedPassword
    }

    fun setInputEditText(editText: EditText?) {
        inputEditTextRef = if (editText != null) WeakReference(editText) else null
    }

    fun getInputEditText(): EditText? {
        return inputEditTextRef?.get()
    }

    fun isCurrentAuthentication(id: Long): Boolean = authenticationToken.isCurrent(id)

    fun isAuthenticationInProgress(): Boolean = authenticationToken.current() != 0L

    fun beginAuthentication(): AuthenticationAttempt? {
        synchronized(signalLock) {
            if (isAuthenticationInProgress()) return null
            val signal = CancellationSignal()
            cancelSignal = signal
            return AuthenticationAttempt(authenticationToken.begin(), signal)
        }
    }

    fun finishAuthentication(id: Long): Boolean = synchronized(signalLock) {
        if (!authenticationToken.finish(id)) return false
        cancelSignal = null
        true
    }

    fun cancelCurrentSignal() {
        val signal = synchronized(signalLock) {
            authenticationToken.invalidate()
            cancelSignal.also { cancelSignal = null }
        }
        signal?.cancel()
    }

    fun cleanupExpiredReferences() {
        val now = SystemClock.uptimeMillis()

        if (!isAuthenticationInProgress() &&
            (currentKeyboardViewRef?.get() == null || now - lastKeyboardAccessTime > 30_000)) {
            destroy()
        }

        if (inputEditTextRef?.get() == null) {
            inputEditTextRef = null
        }
    }

    fun destroy() {
        onDestroy()
        stopCleanup()
        sessionToken.invalidate()
        isInPaymentMode.set(false)
        currentKeyboardViewRef = null
        inputEditTextRef = null
        currentEncodedPassword = null
        cancelCurrentSignal()
    }
}
