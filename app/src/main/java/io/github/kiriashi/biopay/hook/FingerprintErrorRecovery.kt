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
package io.github.kiriashi.biopay.hook

import java.util.WeakHashMap

/** Suppresses known WeChat biometric error tips and tries to continue the active page once. */
internal class FingerprintErrorRecovery(private val onFailure: (Throwable) -> Unit) {
    private val continuedActivities = WeakHashMap<Any, Boolean>()

    fun suppressIfMatched(
        enabled: Boolean,
        message: Any?,
        currentActivity: () -> Any?,
        resolveAction: (Any) -> (() -> Unit)?
    ): Boolean {
        if (!enabled || message !is String || message !in MESSAGES) return false
        var owner: Any? = null
        var claimed = false
        try {
            owner = currentActivity() ?: return true
            synchronized(continuedActivities) {
                if (continuedActivities.containsKey(owner)) return true
            }
            val action = resolveAction(owner) ?: return true
            // Mark before invoking: the callback can synchronously show the same tip again.
            synchronized(continuedActivities) {
                if (continuedActivities.put(owner, true) != null) return true
                claimed = true
            }
            action()
        } catch (e: Throwable) {
            if (claimed) {
                synchronized(continuedActivities) { continuedActivities.remove(owner) }
            }
            onFailure(e)
        }
        // The tip is known; suppress it even when the page has no continuation action.
        return true
    }

    companion object {
        private val MESSAGES = setOf(
            "系统错误，可删除系统指纹，重新录入后再试。如未解决，请咨询手机厂商。",
            "系統錯誤，可以刪除裝置上的指紋，重新加入後再度嘗試。如仍無法解決，請洽詢手機製造商。",
            "系統錯誤，可以刪除裝置的指紋資訊，重新加入後再嘗試。如仍無法解決，請洽手機製造商。",
            "System error. Try deleting the fingerprint data in your device settings and then re-add your fingerprint. If problems persist, contact your device manufacturer."
        )
    }
}
