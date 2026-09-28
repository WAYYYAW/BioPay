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

import android.hardware.biometrics.BiometricPrompt
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import io.github.kiriashi.biopay.core.log.LOG_TAG
import io.github.kiriashi.biopay.core.log.LogCapture
import io.github.kiriashi.biopay.core.util.isValidActivity
import io.github.kiriashi.biopay.data.crypto.KeystoreHelper
import io.github.kiriashi.biopay.lifecycle.AppState

object BiometricGate {
    private const val FACE_INPUT_DELAY_MS = 1_000L
    private val handler = Handler(Looper.getMainLooper())
    private var pendingFaceInput: PendingFaceInput? = null

    fun isFaceInputPending(sessionId: Long): Boolean = pendingFaceInput?.sessionId == sessionId

    fun cancelPendingFaceInput() {
        val pending = pendingFaceInput ?: return
        pendingFaceInput = null
        handler.removeCallbacks(pending)
        pending.operation.ciphertext.fill(0)
    }

    private class PendingFaceInput(
        val sessionId: Long,
        val operation: KeystoreHelper.DecryptOperation,
        val continueInput: () -> Unit
    ) : Runnable {
        override fun run() {
            if (pendingFaceInput !== this) return
            pendingFaceInput = null
            try {
                continueInput()
            } catch (e: Throwable) {
                operation.ciphertext.fill(0)
                Log.w(LOG_TAG, "delayed face input failed", e)
            }
        }
    }

    fun triggerBiometricAuth(
        keyboardView: ViewGroup,
        encodedPassword: String,
        state: AppState,
        sessionId: Long
    ): Boolean {
        if (!state.session.isCurrentSession(sessionId) || !keyboardView.context.isValidActivity()) return false
        if (PasswordAutoInput.isInProgress(sessionId) || isFaceInputPending(sessionId)) return false
        val biometricType = state.prefs.getBiometricType()
        if (biometricType !in BiometricType.BOTH..BiometricType.FACE) return false
        val attempt = state.session.beginAuthentication() ?: return false
        val operation = KeystoreHelper.createDecryptOperation(encodedPassword)
        if (operation == null) {
            state.session.finishAuthentication(attempt.id)
            restoreKeyboard(keyboardView, state, sessionId)
            return false
        }
        try {
            val executor = keyboardView.context.mainExecutor
            val builder = BiometricPrompt.Builder(keyboardView.context)
                .setTitle("身份验证")
                .setNegativeButton("取消", executor) { _, _ ->
                    operation.ciphertext.fill(0)
                    if (state.session.isCurrentSession(sessionId) && state.session.finishAuthentication(attempt.id)) {
                        restoreKeyboard(keyboardView, state, sessionId)
                    }
                }
            val callback = BiometricAuthCallback(keyboardView, operation, state, sessionId, attempt.id, biometricType)
            BiometricPromptPolicy.configure(builder, biometricType).build()
                .authenticate(attempt.signal, executor, callback)
            if (!state.session.isCurrentAuthentication(attempt.id)) return false
            // INVISIBLE preserves external payment keyboard attachment and the active prompt.
            keyboardView.visibility = View.INVISIBLE
            LogCapture.log("trigger: type=$biometricType, session=$sessionId, attempt=${attempt.id}")
            return true
        } catch (e: Throwable) {
            operation.ciphertext.fill(0)
            if (state.session.isCurrentSession(sessionId) && state.session.finishAuthentication(attempt.id)) {
                restoreKeyboard(keyboardView, state, sessionId)
            }
            Log.w(LOG_TAG, "biometric auth failed", e)
            LogCapture.log("trigger: failed: ${e.message}")
            return false
        }
    }

    fun cancelAuthentication(state: AppState) {
        cancelPendingFaceInput()
        state.session.cancelCurrentSignal()
        val view = state.session.getCurrentKeyboardView() ?: return
        restoreKeyboard(view, state, state.session.currentSessionId())
    }

    private fun restoreKeyboard(callbackView: ViewGroup, state: AppState, sessionId: Long) {
        if (!state.session.isCurrentSession(sessionId)) return
        val keyboardView = state.session.getCurrentKeyboardView() ?: callbackView
        KeyboardCloak.reset()
        keyboardView.visibility = View.VISIBLE
        val input = state.session.getInputEditText()
        if (input?.isAttachedToWindow == true) {
            input.requestFocus()
            input.post {
                if (state.session.isCurrentSession(sessionId) && input.isAttachedToWindow) {
                    val manager = input.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                        as? InputMethodManager
                    manager?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }
        LogCapture.log("auth: keyboard restored, session=$sessionId")
    }

    private class BiometricAuthCallback(
        private val keyboardView: ViewGroup,
        private val operation: KeystoreHelper.DecryptOperation,
        private val state: AppState,
        private val sessionId: Long,
        private val attemptId: Long,
        private val biometricType: Int
    ) : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
            operation.ciphertext.fill(0)
            if (!state.session.isCurrentSession(sessionId) || !state.session.finishAuthentication(attemptId)) return
            LogCapture.log("onAuthError: code=$errorCode, attempt=$attemptId")
            restoreKeyboard(keyboardView, state, sessionId)
        }

        override fun onAuthenticationFailed() {
            if (state.session.isCurrentSession(sessionId) && state.session.isCurrentAuthentication(attemptId)) {
                LogCapture.log("onAuthFailed: attempt=$attemptId")
            }
        }

        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
            if (!state.session.isCurrentSession(sessionId) || !state.session.finishAuthentication(attemptId)) {
                operation.ciphertext.fill(0)
                return
            }
            LogCapture.log("onAuthSucceeded: attempt=$attemptId")
            if (biometricType == BiometricType.FACE) {
                cancelPendingFaceInput()
                val pending = PendingFaceInput(sessionId, operation) {
                    val currentView = state.session.getCurrentKeyboardView()
                    if (!state.session.isCurrentSession(sessionId) ||
                        !state.session.isInPaymentMode() ||
                        state.prefs.getBiometricType() != BiometricType.FACE ||
                        currentView?.isAttachedToWindow != true ||
                        !currentView.context.isValidActivity()
                    ) {
                        operation.ciphertext.fill(0)
                        if (state.session.isCurrentSession(sessionId)) {
                            if (currentView?.isAttachedToWindow == true) {
                                restoreKeyboard(currentView, state, sessionId)
                            } else {
                                state.session.endSession(sessionId)
                            }
                        }
                        return@PendingFaceInput
                    }
                    finishInput()
                }
                pendingFaceInput = pending
                if (!handler.postDelayed(pending, FACE_INPUT_DELAY_MS)) {
                    cancelPendingFaceInput()
                    restoreKeyboard(keyboardView, state, sessionId)
                }
                return
            }
            finishInput()
        }

        private fun finishInput() {
            var password: CharArray? = null
            try {
                password = KeystoreHelper.decryptToCharArray(operation)
                if (password == null) {
                    restoreKeyboard(keyboardView, state, sessionId)
                    return
                }
                val currentView = state.session.getCurrentKeyboardView() ?: keyboardView
                currentView.visibility = View.VISIBLE
                PasswordAutoInput.cancelPendingRunnables()
                KeyboardCloak.concealActivityWindow(state)
                KeyboardCloak.cloakKeyboardViews(currentView)
                if (!PasswordAutoInput.autoInputPassword(currentView, password, state, sessionId)) {
                    restoreKeyboard(currentView, state, sessionId)
                }
            } catch (e: Throwable) {
                Log.w(LOG_TAG, "post-authentication input failed", e)
                restoreKeyboard(keyboardView, state, sessionId)
            } finally {
                password?.fill('\u0000')
                operation.ciphertext.fill(0)
            }
        }
    }
}
