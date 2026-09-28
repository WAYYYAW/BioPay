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

/** Allows Class 1 face sensors in WeChat's Class 2 biometric preflight. */
internal class ConvenienceFaceHook {
    private val installed = mutableSetOf<String>()
    private val inWeChatFacePreflight = ThreadLocal<Boolean>()

    @Synchronized
    fun register(classLoader: ClassLoader, xposed: XposedInterface) {
        val preAuthClass = try {
            classLoader.loadClass("com.android.server.biometrics.PreAuthInfo")
        } catch (e: Throwable) {
            xposed.log(Log.WARN, LOG_TAG, "Class 1 face: PreAuthInfo unavailable", e)
            return
        }
        val sensorClass = try {
            classLoader.loadClass("com.android.server.biometrics.BiometricSensor")
        } catch (e: Throwable) {
            xposed.log(Log.WARN, LOG_TAG, "Class 1 face: BiometricSensor unavailable", e)
            return
        }
        val preflightMethod = preAuthClass.declaredMethods.singleOrNull { method ->
            method.name == "getStatusForBiometricAuthenticator" &&
                method.parameterTypes.count { it == String::class.java } == 1 &&
                method.parameterTypes.count { it == sensorClass } == 1
        }
        if (preflightMethod == null) {
            xposed.log(Log.WARN, LOG_TAG, "Class 1 face: preflight signature unavailable")
            return
        }
        val packageIndex = preflightMethod.parameterTypes.indexOf(String::class.java)
        val sensorIndex = preflightMethod.parameterTypes.indexOf(sensorClass)
        val modalityField = try {
            sensorClass.getDeclaredField("modality").apply { isAccessible = true }
        } catch (e: Throwable) {
            xposed.log(Log.WARN, LOG_TAG, "Class 1 face: modality field unavailable", e)
            return
        }
        try {
            val contextId = "bp_face_preflight_context"
            if (contextId !in installed) {
                preflightMethod.isAccessible = true
                xposed.hook(preflightMethod).setId(contextId).intercept(XposedInterface.Hooker { chain ->
                    // Keep the package and sensor context on this thread only during the preflight call.
                    val previous = inWeChatFacePreflight.get()
                    val sensor = chain.args.getOrNull(sensorIndex)
                    val isFace = sensor != null &&
                        runCatching { modalityField.getInt(sensor) == FACE_MODALITY }.getOrDefault(false)
                    inWeChatFacePreflight.set(
                        chain.args.getOrNull(packageIndex) == WECHAT_PACKAGE && isFace
                    )
                    try {
                        chain.proceed()
                    } finally {
                        if (previous == null) inWeChatFacePreflight.remove()
                        else inWeChatFacePreflight.set(previous)
                    }
                })
                installed.add(contextId)
            }
            // The preflight method may inline Utils.isAtLeastStrength on some ROMs.
            if (!xposed.deoptimize(preflightMethod)) {
                xposed.log(Log.WARN, LOG_TAG, "Class 1 face: preflight deoptimization unavailable")
            }
        } catch (e: Throwable) {
            xposed.log(Log.ERROR, LOG_TAG, "Class 1 face: preflight hook failed", e)
            return
        }

        val comparisonId = "bp_face_strength_comparison"
        if (comparisonId in installed) return
        try {
            val method = classLoader.loadClass("com.android.server.biometrics.Utils")
                .getDeclaredMethod("isAtLeastStrength", Integer.TYPE, Integer.TYPE)
                .apply { isAccessible = true }
            val hit = AtomicBoolean()
            xposed.hook(method).setId(comparisonId).intercept(XposedInterface.Hooker { chain ->
                val sensor = chain.args.getOrNull(0) as? Int
                val requested = chain.args.getOrNull(1) as? Int
                if (inWeChatFacePreflight.get() == true && sensor != null && requested != null &&
                    ConvenienceFacePolicy.allowsWeakRequest(sensor, requested)
                ) {
                    if (hit.compareAndSet(false, true)) {
                        xposed.log(Log.INFO, LOG_TAG, "Class 1 face: WeChat face preflight accepted as Class 2")
                    }
                    true
                } else {
                    chain.proceed()
                }
            })
            installed.add(comparisonId)
            xposed.log(Log.INFO, LOG_TAG, "Class 1 face: WeChat-only compatibility installed")
        } catch (e: Throwable) {
            xposed.log(Log.ERROR, LOG_TAG, "Class 1 face: strength comparison hook failed", e)
        }
    }

    private companion object {
        const val WECHAT_PACKAGE = "com.tencent.mm"
        const val FACE_MODALITY = 8 // BiometricAuthenticator.TYPE_FACE
    }
}
