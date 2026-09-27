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

import io.github.kiriashi.biopay.lifecycle.AppState
import io.github.kiriashi.biopay.core.util.findActivity
import android.view.View
import android.view.ViewGroup
import java.util.WeakHashMap

object KeyboardCloak {

    private val cloakedLock = Any()
    private var cloakedStates: WeakHashMap<View, Float>? = null
    private val concealedWindowLock = Any()
    private val concealedWindowStates = WeakHashMap<View, Float>()
    fun reset() {
        restoreCloakedViews()
        restoreConcealedInputViews()
    }
    fun cloakKeyboardViews(keyboardView: ViewGroup) {
        uncloakKeyboardViews(keyboardView)
        val states = WeakHashMap<View, Float>(64)
        saveAndCloakRecursive(keyboardView, states)
        synchronized(cloakedLock) {
            cloakedStates = states
        }
    }
    fun uncloakKeyboardViews(@Suppress("UNUSED_PARAMETER") keyboardView: ViewGroup) = restoreCloakedViews()

    private fun restoreCloakedViews() {
        val states = synchronized(cloakedLock) {
            val s = cloakedStates
            cloakedStates = null
            s
        } ?: return
        for ((view, alpha) in states) {
            view.animate().cancel()
            view.alpha = alpha
        }
    }
    fun concealActivityWindow(state: AppState) {
        val editText = state.session.getInputEditText() ?: return
        val currentKeyboard = state.session.getCurrentKeyboardView()
        synchronized(concealedWindowLock) {
            val activity = currentKeyboard?.context?.findActivity()
                ?: editText.context.findActivity()
            activity?.window?.decorView?.let { decorView ->
                if (!concealedWindowStates.containsKey(decorView)) {
                    concealedWindowStates[decorView] = decorView.alpha
                }
                decorView.animate().cancel()
                decorView.alpha = 0f
            }
        }
    }
    fun restoreConcealedInputViews(animated: Boolean = false) {
        val states: Map<View, Float>
        synchronized(concealedWindowLock) {
            states = concealedWindowStates.toMap()
            concealedWindowStates.clear()
        }
        for ((view, alpha) in states) {
            view.animate().cancel()
            if (animated && view.isAttachedToWindow) {
                view.animate()
                    .alpha(alpha)
                    .setDuration(220L)
                    .start()
            } else {
                view.alpha = alpha
            }
        }
    }
    private fun saveAndCloakRecursive(view: View, states: MutableMap<View, Float>) {
        states[view] = view.alpha
        view.animate().cancel()
        view.alpha = 0f
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                saveAndCloakRecursive(view.getChildAt(i), states)
            }
        }
    }
}
