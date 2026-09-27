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

import android.util.Log
import io.github.kiriashi.biopay.core.log.LOG_TAG
import io.github.kiriashi.biopay.core.log.LogCapture
import io.github.kiriashi.biopay.lifecycle.AppState
import io.github.libxposed.api.XposedInterface

object FingerprintErrorHook {
    const val HOOK_ID = "bp_fingerprint_error"

    fun register(cl: ClassLoader, xposed: XposedInterface, state: AppState) {
        try {
            val dialog = cl.loadClass(HookTargets.AlertDialogImpl)
            val callback = cl.loadClass(HookTargets.VoidCallback)
            val method = dialog.getDeclaredMethod(
                "showTipsImpl", String::class.java, String::class.java, String::class.java, callback
            )
            TopActivityProvider.resolve(cl)
            xposed.hook(method).setId(HOOK_ID).intercept(makeInterceptor(xposed, state))
            xposed.log(Log.INFO, LOG_TAG, "Fingerprint error: WeChat tip hook installed")
        } catch (e: Throwable) {
            xposed.log(Log.ERROR, LOG_TAG, "Fingerprint error: tip hook unavailable", e)
        }
    }

    fun makeInterceptor(xposed: XposedInterface, state: AppState): XposedInterface.Hooker {
        val recovery = FingerprintErrorRecovery { failure ->
            xposed.log(Log.WARN, LOG_TAG, "Fingerprint error: page continuation failed", failure)
        }
        return XposedInterface.Hooker { chain ->
            val suppress = recovery.suppressIfMatched(
                enabled = state.prefs.isBioPayEnabled(),
                message = chain.args.getOrNull(0),
                currentActivity = {
                    TopActivityProvider.getTopActivity()?.takeUnless { it.isFinishing || it.isDestroyed }
                },
                resolveAction = { activity ->
                    WeChatPaymentContinuation.resolve(activity)?.let { action ->
                        {
                            action()
                            xposed.log(Log.INFO, LOG_TAG, "Fingerprint error: continued WeChat page")
                        }
                    }
                }
            )
            if (suppress) {
                LogCapture.log("Fingerprint error: suppressed known WeChat tip")
                null
            } else {
                chain.proceed()
            }
        }
    }
}
