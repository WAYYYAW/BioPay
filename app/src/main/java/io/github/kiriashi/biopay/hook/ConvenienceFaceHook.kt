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
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.atomic.AtomicBoolean

/** Implements the three system strength hooks used by the verified FaceBiometricFix module. */
internal class ConvenienceFaceHook {
    private val installed = mutableSetOf<String>()

    @Synchronized
    fun register(classLoader: ClassLoader, xposed: XposedInterface) {
        fun install(
            id: String,
            className: String,
            methodName: String,
            parameterTypes: Array<Class<*>>,
            hooker: XposedInterface.Hooker
        ) {
            if (id in installed) return
            try {
                val method = classLoader.loadClass(className)
                    .getDeclaredMethod(methodName, *parameterTypes)
                    .apply { isAccessible = true }
                xposed.hook(method).setId(id).intercept(hooker)
                installed.add(id)
                xposed.log(Log.INFO, LOG_TAG, "Class 1 face: installed $className.$methodName")
            } catch (e: Throwable) {
                // A missing OEM method must not prevent installing the other two hooks.
                xposed.log(Log.ERROR, LOG_TAG, "Class 1 face: failed $className.$methodName", e)
            }
        }

        val currentStrengthHit = AtomicBoolean()
        install(
            "bp_face_current_strength",
            "com.android.server.biometrics.BiometricSensor",
            "getCurrentStrength",
            emptyArray(),
            XposedInterface.Hooker { chain ->
                val original = chain.proceed()
                if (original !is Int) return@Hooker original
                val adjusted = ConvenienceFacePolicy.currentStrength(original)
                if (adjusted != original && currentStrengthHit.compareAndSet(false, true)) {
                    xposed.log(Log.INFO, LOG_TAG, "Class 1 face: getCurrentStrength $original -> $adjusted")
                }
                adjusted
            }
        )

        val comparisonHit = AtomicBoolean()
        install(
            "bp_face_strength_comparison",
            "com.android.server.biometrics.Utils",
            "isAtLeastStrength",
            arrayOf(Integer.TYPE, Integer.TYPE),
            XposedInterface.Hooker { chain ->
                if (comparisonHit.compareAndSet(false, true)) {
                    xposed.log(Log.INFO, LOG_TAG, "Class 1 face: isAtLeastStrength intercepted")
                }
                // Match the working module: OEM and current-strength checks both pass.
                true
            }
        )

        val propertyHit = AtomicBoolean()
        install(
            "bp_face_property_strength",
            "com.android.server.biometrics.Utils",
            "authenticatorStrengthToPropertyStrength",
            arrayOf(Integer.TYPE),
            XposedInterface.Hooker { chain ->
                if (propertyHit.compareAndSet(false, true)) {
                    xposed.log(Log.INFO, LOG_TAG, "Class 1 face: property strength -> Strong (2)")
                }
                ConvenienceFacePolicy.PROPERTY_STRONG
            }
        )

        if (installed.size == 3) {
            xposed.log(Log.INFO, LOG_TAG, "Class 1 face: all 3 compatibility hooks installed")
        } else {
            xposed.log(Log.ERROR, LOG_TAG, "Class 1 face: only ${installed.size}/3 compatibility hooks installed")
        }
        deoptimizeCallers(classLoader, xposed)
    }

    private fun deoptimizeCallers(classLoader: ClassLoader, xposed: XposedInterface) {
        // Optional AOSP callers; their absence on an OEM ROM must not disable compatibility.
        val callers = mapOf(
            "com.android.server.biometrics.PreAuthInfo" to setOf(
                "create", "getStatusForBiometricAuthenticator"
            ),
            "com.android.server.biometrics.BiometricSensor" to setOf("isStrongBiometric"),
            "com.android.server.biometrics.AuthSession" to setOf("onAuthenticationSucceeded")
        )
        for ((className, names) in callers) {
            val clazz = try {
                classLoader.loadClass(className)
            } catch (_: Throwable) {
                continue
            }
            try {
                clazz.declaredMethods.filter { it.name in names }.forEach {
                    if (!xposed.deoptimize(it)) {
                        xposed.log(Log.WARN, LOG_TAG, "Class 1 face: could not deoptimize $className.${it.name}")
                    }
                }
            } catch (e: Throwable) {
                xposed.log(Log.WARN, LOG_TAG, "Class 1 face: optional caller deoptimization failed for $className", e)
            }
        }
    }
}
