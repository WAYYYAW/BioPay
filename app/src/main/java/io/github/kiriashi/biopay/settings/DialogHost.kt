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
package io.github.kiriashi.biopay.settings

import android.app.AlertDialog
import android.content.Context
import io.github.kiriashi.biopay.core.util.isValidActivity
import android.view.View
import android.os.CancellationSignal
import io.github.kiriashi.biopay.payment.SessionToken

class DialogHost(context: Context) {
    private val contextRef = java.lang.ref.WeakReference(context)
    private var dialog: AlertDialog? = null
    private val authentication = SessionToken()
    private var signal: CancellationSignal? = null
    fun beginAuthentication(newSignal: CancellationSignal): Long {
        cancelAuthentication()
        signal = newSignal
        return authentication.begin()
    }
    fun finishAuthentication(id: Long): Boolean {
        if (!authentication.finish(id)) return false
        signal = null
        return true
    }
    private fun cancelAuthentication() {
        authentication.invalidate()
        val previous = signal
        signal = null
        previous?.cancel()
    }
    var onDismiss: (() -> Unit)? = null
    private val ctx get() = contextRef.get()
    fun show(content: View) {
        val c = ctx ?: return
        if (!c.isValidActivity()) return
        dialog = AlertDialog.Builder(c).setView(content).setCancelable(false).create()
        dialog?.setOnDismissListener { cancelAuthentication(); dialog = null; onDismiss?.invoke() }
        dialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog?.show()
    }
    fun dismiss() {
        cancelAuthentication()
        dialog?.let { if (it.isShowing) it.dismiss() }
        dialog = null
    }
}
